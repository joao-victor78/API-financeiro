package br.com.erp.financeiro.conciliacao;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cadastros.CadastroService;
import br.com.erp.financeiro.cadastros.ContaBancaria;
import br.com.erp.financeiro.cobrancas.CobrancaDtos.BaixaManualEntrada;
import br.com.erp.financeiro.cobrancas.CobrancaService;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.Candidato;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.ConciliacaoEntrada;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.Importacao;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.ResumoConta;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.Sugestoes;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.TransacaoEntrada;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.TransacaoSaida;
import br.com.erp.financeiro.conciliacao.TransacaoBancaria.Classificacao;
import br.com.erp.financeiro.configuracoes.ConfiguracaoService;
import br.com.erp.financeiro.contaspagar.ContaPagar;
import br.com.erp.financeiro.contaspagar.ContaPagarDtos.PagamentoContaEntrada;
import br.com.erp.financeiro.contaspagar.ContaPagarService;
import br.com.erp.financeiro.documentos.Documento;
import br.com.erp.financeiro.documentos.DocumentoService;
import br.com.erp.financeiro.pagamentos.Pagamento;
import br.com.erp.financeiro.pagamentos.PagamentoRepository;
import br.com.erp.financeiro.pagamentos.StatusPagamento;
import br.com.erp.financeiro.seguranca.Usuarios;
import br.com.erp.financeiro.tenant.TenantContexto;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conciliação financeira (§32): importação OFX, conciliação automática e manual, e a identificação de
 * pagamento, transferência, tarifa, juros e estorno.
 *
 * <p>Automática: crédito ↔ pagamento CONFIRMADO de mesmo valor; débito ↔ conta a pagar PAGA de mesmo
 * valor pago; data dentro da tolerância do tenant (padrão ±3 dias). Só concilia quando há um candidato
 * mais próximo sem empate; o resto fica PENDENTE, com a classificação sugerida pela descrição.
 */
@Service
public class ConciliacaoService {

  private static final Pattern TARIFA = Pattern.compile("TARIFA|TAR\\b|CESTA|MANUT|PACOTE SERV|TAXA", Pattern.CASE_INSENSITIVE);
  private static final Pattern JUROS = Pattern.compile("JUROS|IOF|ENCARGO|MORA|REND", Pattern.CASE_INSENSITIVE);
  private static final Pattern ESTORNO = Pattern.compile("ESTORNO|DEVOL|CHARGEBACK|REEMB", Pattern.CASE_INSENSITIVE);
  private static final Pattern TRANSFERENCIA = Pattern.compile("TED|DOC\\b|TRANSF|PIX|APLICA|RESGATE", Pattern.CASE_INSENSITIVE);

  private final TransacaoRepository transacoes;
  private final ExtratoRepository extratos;
  private final PagamentoRepository pagamentos;
  private final CadastroService cadastros;
  private final DocumentoService documentos;
  private final CobrancaService cobrancas;
  private final ContaPagarService contasPagar;
  private final ConfiguracaoService configuracoes;
  private final AuditoriaService auditoria;
  private final JdbcTemplate jdbc;
  private final Relogio relogio;

  public ConciliacaoService(TransacaoRepository transacoes, ExtratoRepository extratos, PagamentoRepository pagamentos,
      CadastroService cadastros, DocumentoService documentos, CobrancaService cobrancas, ContaPagarService contasPagar,
      ConfiguracaoService configuracoes, AuditoriaService auditoria, JdbcTemplate jdbc, Relogio relogio) {
    this.transacoes = transacoes;
    this.extratos = extratos;
    this.pagamentos = pagamentos;
    this.cadastros = cadastros;
    this.documentos = documentos;
    this.cobrancas = cobrancas;
    this.contasPagar = contasPagar;
    this.configuracoes = configuracoes;
    this.auditoria = auditoria;
    this.jdbc = jdbc;
    this.relogio = relogio;
  }

  // ------------------------------------------------------------------ entrada

  @Transactional
  public Importacao importarOfx(UUID contaId, String nomeArquivo, byte[] conteudo) {
    ContaBancaria conta = cadastros.contaParaUso(contaId, "contaBancariaId");
    if (conta == null) throw ApiException.campo("contaBancariaId", "OBRIGATORIA", "Informe a conta bancária do extrato.");
    String sha = DocumentoService.sha256(conteudo);
    extratos.mesmoArquivo(conta.getId(), sha).ifPresent(e -> {
      throw ApiException.conflito("EXTRATO_JA_IMPORTADO", "Este arquivo já foi importado nesta conta em "
          + e.getCreatedAt().toLocalDate() + ".");
    });
    LeitorOfx.Extrato ofx;
    try {
      ofx = LeitorOfx.ler(conteudo);
    } catch (LeitorOfx.OfxInvalidoException e) {
      throw ApiException.campo("arquivo", "OFX_INVALIDO", e.getMessage());
    }
    String nome = nomeArquivo == null || nomeArquivo.isBlank() ? "extrato.ofx" : nomeArquivo;
    if (!nome.toLowerCase(Locale.ROOT).endsWith(".ofx")) nome = nome + ".ofx";
    Documento doc = documentos.enviar(nome, conteudo, Documento.Categoria.EXTRATO, null, null, "Extrato " + conta.getNome());
    ExtratoImportado extrato = extratos.save(new ExtratoImportado(conta.getId(), doc.getNomeArquivo(), sha, doc.getId(),
        ofx.inicio(), ofx.fim(), ofx.saldoFinal()));
    extratos.flush();
    documentos.vincular(doc, "extrato", extrato.getId());
    int novos = 0;
    int automaticos = 0;
    int tolerancia = configuracoes.atual().conciliacaoToleranciaDias();
    for (LeitorOfx.Lancamento l : ofx.lancamentos()) {
      if (transacoes.doIdentificador(conta.getId(), l.identificador()).isPresent()) continue;
      TransacaoBancaria t = transacoes.save(new TransacaoBancaria(conta.getId(), extrato.getId(), l.identificador(), l.data(),
          l.valor(), l.descricao(), l.documento()));
      novos++;
      if (conciliarAutomatico(t, tolerancia)) automaticos++;
    }
    extrato.totalizar(ofx.lancamentos().size(), novos, automaticos);
    auditoria.registrar("IMPORTAR_EXTRATO", "extrato", extrato.getId(), null, Map.of("conta", conta.getNome(),
        "arquivo", doc.getNomeArquivo(), "lancamentos", ofx.lancamentos().size(), "novos", novos, "conciliadosAuto", automaticos));
    return new Importacao(extrato.getId(), doc.getId(), doc.getNomeArquivo(), ofx.inicio(), ofx.fim(), ofx.saldoFinal(),
        ofx.lancamentos().size(), novos, ofx.lancamentos().size() - novos, automaticos, novos - automaticos);
  }

  /** Integração bancária por API ou movimento de caixa: o mesmo identificador não entra duas vezes. */
  @Transactional
  public TransacaoBancaria registrar(TransacaoEntrada e) {
    ContaBancaria conta = cadastros.contaParaUso(e.contaBancariaId(), "contaBancariaId");
    if (e.valor().signum() == 0) throw ApiException.campo("valor", "VALOR_ZERO", "O valor não pode ser zero.");
    String id = e.identificador() == null || e.identificador().isBlank() ? "manual-" + UUID.randomUUID() : e.identificador().trim();
    if (transacoes.doIdentificador(conta.getId(), id).isPresent()) {
      throw ApiException.conflito("TRANSACAO_DUPLICADA", "Já existe uma transação com o identificador " + id + " nesta conta.");
    }
    TransacaoBancaria t = transacoes.save(new TransacaoBancaria(conta.getId(), null, id, e.data(), e.valor(),
        vazio(e.descricao()), vazio(e.documento())));
    auditoria.registrar("REGISTRAR_TRANSACAO", "transacao_bancaria", t.getId(), null, t.instantaneo());
    conciliarAutomatico(t, configuracoes.atual().conciliacaoToleranciaDias());
    return t;
  }

  // ------------------------------------------------------------------ automática

  /** Roda a conciliação automática de novo nas pendentes (de uma conta ou de todas). */
  @Transactional
  public int conciliarPendentes(UUID contaId) {
    int tolerancia = configuracoes.atual().conciliacaoToleranciaDias();
    int n = 0;
    for (TransacaoBancaria t : contaId == null ? transacoes.pendentes() : transacoes.pendentesDaConta(contaId)) {
      if (conciliarAutomatico(t, tolerancia)) n++;
    }
    return n;
  }

  private boolean conciliarAutomatico(TransacaoBancaria t, int tolerancia) {
    List<Candidato> c = t.credito() ? pagamentosCandidatos(t, tolerancia) : contasPagasCandidatas(t, tolerancia);
    if (!c.isEmpty() && (c.size() == 1 || c.get(0).diasDeDiferenca() < c.get(1).diasDeDiferenca())) {
      Map<String, Object> antes = t.instantaneo();
      if (t.credito()) {
        t.conciliarComPagamento(c.get(0).id(), TransacaoBancaria.Modo.AUTOMATICA, null, relogio.agoraUtc());
        pagamentos.findById(c.get(0).id()).filter(p -> p.getContaBancariaId() == null)
            .ifPresent(p -> p.definirContaBancaria(t.getContaBancariaId()));
      } else {
        t.conciliarComContaPagar(c.get(0).id(), TransacaoBancaria.Modo.AUTOMATICA, null, relogio.agoraUtc());
        contasPagar.obter(c.get(0).id()).definirContaBancaria(t.getContaBancariaId());
      }
      transacoes.flush();
      auditoria.registrar("CONCILIAR_AUTOMATICO", "transacao_bancaria", t.getId(), antes, t.instantaneo());
      return true;
    }
    t.sugerir(classificacaoSugerida(t));
    return false;
  }

  static Classificacao classificacaoSugerida(TransacaoBancaria t) {
    String d = t.getDescricao() == null ? "" : t.getDescricao();
    if (ESTORNO.matcher(d).find()) return Classificacao.ESTORNO;
    if (TARIFA.matcher(d).find() && !t.credito()) return Classificacao.TARIFA;
    if (JUROS.matcher(d).find()) return Classificacao.JUROS;
    if (TRANSFERENCIA.matcher(d).find()) return t.credito() ? Classificacao.PAGAMENTO_RECEBIDO : Classificacao.TRANSFERENCIA;
    return t.credito() ? Classificacao.PAGAMENTO_RECEBIDO : Classificacao.PAGAMENTO_EFETUADO;
  }

  private List<Candidato> pagamentosCandidatos(TransacaoBancaria t, int tolerancia) {
    return jdbc.query("""
        SELECT p.id, p.valor, (p.confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date AS dia, c.descricao, p.meio
          FROM financeiro.pagamentos p JOIN financeiro.cobrancas c ON c.id = p.cobranca_id
         WHERE p.tenant_id = ? AND p.status = 'CONFIRMADO' AND p.valor = ?
           AND (p.conta_bancaria_id IS NULL OR p.conta_bancaria_id = ?)
           AND (p.confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
           AND NOT EXISTS (SELECT 1 FROM financeiro.transacoes_bancarias x WHERE x.pagamento_id = p.id AND x.deleted_at IS NULL)""",
        (rs, i) -> {
          LocalDate dia = rs.getDate("dia").toLocalDate();
          return new Candidato("PAGAMENTO", rs.getObject("id", UUID.class),
              "Pagamento " + rs.getString("meio") + " — " + (rs.getString("descricao") == null ? "cobrança" : rs.getString("descricao")),
              rs.getBigDecimal("valor"), dia, Math.abs(ChronoUnit.DAYS.between(dia, t.getData())));
        },
        TenantContexto.exigir(), t.getValor(), t.getContaBancariaId(), Date.valueOf(t.getData().minusDays(tolerancia)),
        Date.valueOf(t.getData().plusDays(tolerancia))).stream().sorted(Comparator.comparingLong(Candidato::diasDeDiferenca)).toList();
  }

  private List<Candidato> contasPagasCandidatas(TransacaoBancaria t, int tolerancia) {
    return jdbc.query("""
        SELECT cp.id, cp.valor_pago, cp.data_pagamento, cp.fornecedor_nome
          FROM financeiro.contas_pagar cp
         WHERE cp.tenant_id = ? AND cp.deleted_at IS NULL AND cp.status = 'PAGA' AND cp.valor_pago = ?
           AND (cp.conta_bancaria_id IS NULL OR cp.conta_bancaria_id = ?)
           AND cp.data_pagamento BETWEEN ? AND ?
           AND NOT EXISTS (SELECT 1 FROM financeiro.transacoes_bancarias x WHERE x.conta_pagar_id = cp.id AND x.deleted_at IS NULL)""",
        (rs, i) -> {
          LocalDate dia = rs.getDate("data_pagamento").toLocalDate();
          return new Candidato("CONTA_PAGA", rs.getObject("id", UUID.class), "Conta paga — " + rs.getString("fornecedor_nome"),
              rs.getBigDecimal("valor_pago").negate(), dia, Math.abs(ChronoUnit.DAYS.between(dia, t.getData())));
        },
        TenantContexto.exigir(), t.getValor().negate(), t.getContaBancariaId(), Date.valueOf(t.getData().minusDays(tolerancia)),
        Date.valueOf(t.getData().plusDays(tolerancia))).stream().sorted(Comparator.comparingLong(Candidato::diasDeDiferenca)).toList();
  }

  // ------------------------------------------------------------------ manual

  @Transactional(readOnly = true)
  public Sugestoes sugestoes(UUID id) {
    TransacaoBancaria t = obter(id);
    int tolerancia = Math.max(7, configuracoes.atual().conciliacaoToleranciaDias() * 3);
    List<Candidato> lista = new ArrayList<>();
    if (t.credito()) {
      lista.addAll(pagamentosCandidatos(t, tolerancia));
      lista.addAll(jdbc.query("""
          SELECT id, descricao, data_vencimento, valor - desconto + juros + multa - valor_pago AS saldo
            FROM financeiro.cobrancas
           WHERE tenant_id = ? AND deleted_at IS NULL AND status IN ('ABERTO','AGENDADO','ATRASADO','PARCIAL')
             AND valor - desconto + juros + multa - valor_pago >= ?
           ORDER BY abs(data_vencimento - ?::date) LIMIT 10""",
          (rs, i) -> {
            LocalDate venc = rs.getDate("data_vencimento").toLocalDate();
            return new Candidato("COBRANCA_EM_ABERTO", rs.getObject("id", UUID.class),
                "Cobrança em aberto — " + (rs.getString("descricao") == null ? "sem descrição" : rs.getString("descricao"))
                    + " (saldo " + Dinheiro.brl(rs.getBigDecimal("saldo")) + ")",
                rs.getBigDecimal("saldo"), venc, Math.abs(ChronoUnit.DAYS.between(venc, t.getData())));
          }, TenantContexto.exigir(), t.getValor(), Date.valueOf(t.getData())));
    } else {
      lista.addAll(contasPagasCandidatas(t, tolerancia));
      lista.addAll(jdbc.query("""
          SELECT id, fornecedor_nome, data_vencimento, valor FROM financeiro.contas_pagar
           WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'APROVADA' AND valor = ?
           ORDER BY abs(data_vencimento - ?::date) LIMIT 10""",
          (rs, i) -> {
            LocalDate venc = rs.getDate("data_vencimento").toLocalDate();
            return new Candidato("CONTA_APROVADA", rs.getObject("id", UUID.class),
                "Conta aprovada a pagar — " + rs.getString("fornecedor_nome"), rs.getBigDecimal("valor").negate(), venc,
                Math.abs(ChronoUnit.DAYS.between(venc, t.getData())));
          }, TenantContexto.exigir(), t.getValor().negate(), Date.valueOf(t.getData())));
    }
    return new Sugestoes(t.getId(), classificacaoSugerida(t), lista);
  }

  @Transactional
  public TransacaoBancaria conciliar(UUID id, ConciliacaoEntrada e) {
    TransacaoBancaria t = obter(id);
    if (t.getStatus() != TransacaoBancaria.Status.PENDENTE) {
      throw ApiException.conflito("TRANSACAO_NAO_PENDENTE", "A transação já está " + t.getStatus() + "; desfaça antes de conciliar de novo.");
    }
    int destinos = (e.pagamentoId() != null ? 1 : 0) + (e.cobrancaId() != null ? 1 : 0) + (e.contaPagarId() != null ? 1 : 0)
        + (e.classificacao() != null && e.pagamentoId() == null && e.cobrancaId() == null && e.contaPagarId() == null ? 1 : 0);
    if (destinos != 1) {
      throw ApiException.campo(null, "DESTINO_INVALIDO", "Informe exatamente um: pagamentoId, cobrancaId, contaPagarId ou classificação.");
    }
    Map<String, Object> antes = t.instantaneo();
    UUID usuario = Usuarios.atual();
    if (e.pagamentoId() != null) {
      if (!t.credito()) throw ApiException.regra("SENTIDO_INVALIDO", "Débito não concilia com recebimento.");
      Pagamento p = Tenant.doTenant(pagamentos.findById(e.pagamentoId()), "Pagamento");
      if (p.getStatus() != StatusPagamento.CONFIRMADO) throw ApiException.regra("PAGAMENTO_NAO_CONFIRMADO", "O pagamento não está confirmado.");
      if (p.getValor().compareTo(t.getValor()) != 0) {
        throw ApiException.regra("VALOR_DIFERENTE", "O pagamento é de " + Dinheiro.brl(p.getValor()) + " e a transação de " + Dinheiro.brl(t.getValor()) + ".");
      }
      transacoes.doPagamento(p.getId()).ifPresent(x -> {
        throw ApiException.conflito("PAGAMENTO_JA_CONCILIADO", "Este pagamento já está conciliado com outra transação.");
      });
      if (p.getContaBancariaId() == null) p.definirContaBancaria(t.getContaBancariaId());
      t.conciliarComPagamento(p.getId(), TransacaoBancaria.Modo.MANUAL, usuario, relogio.agoraUtc());
    } else if (e.cobrancaId() != null) {
      if (!t.credito()) throw ApiException.regra("SENTIDO_INVALIDO", "Débito não baixa cobrança.");
      Pagamento p = cobrancas.baixaManual(e.cobrancaId(), new BaixaManualEntrada(t.getValor(), t.getData(), null));
      p.definirContaBancaria(t.getContaBancariaId());
      t.conciliarComPagamento(p.getId(), TransacaoBancaria.Modo.MANUAL, usuario, relogio.agoraUtc());
    } else if (e.contaPagarId() != null) {
      if (t.credito()) throw ApiException.regra("SENTIDO_INVALIDO", "Crédito não concilia com conta a pagar.");
      ContaPagar c = contasPagar.obter(e.contaPagarId());
      BigDecimal pago = t.getValor().negate();
      if (c.getStatus() == ContaPagar.Status.APROVADA) {
        contasPagar.registrarPagamento(c.getId(), new PagamentoContaEntrada(pago, t.getData(), t.getContaBancariaId()));
      } else if (c.getStatus() != ContaPagar.Status.PAGA) {
        throw ApiException.regra("CONTA_NAO_PAGAVEL", "A conta está " + c.getStatus() + "; só conta aprovada ou paga concilia.");
      } else if (c.getValorPago().compareTo(pago) != 0) {
        throw ApiException.regra("VALOR_DIFERENTE", "A conta foi paga com " + Dinheiro.brl(c.getValorPago()) + " e a transação é de " + Dinheiro.brl(pago) + ".");
      }
      transacoes.daContaPagar(c.getId()).ifPresent(x -> {
        throw ApiException.conflito("CONTA_JA_CONCILIADA", "Esta conta já está conciliada com outra transação.");
      });
      c.definirContaBancaria(t.getContaBancariaId());
      t.conciliarComContaPagar(c.getId(), TransacaoBancaria.Modo.MANUAL, usuario, relogio.agoraUtc());
    } else {
      if (e.classificacao() == Classificacao.PAGAMENTO_RECEBIDO || e.classificacao() == Classificacao.PAGAMENTO_EFETUADO) {
        throw ApiException.campo("classificacao", "EXIGE_DOCUMENTO", "Recebimento e pagamento precisam do pagamento, da cobrança ou da conta a pagar.");
      }
      if (e.categoriaId() != null) cadastros.categoriaParaUso(e.categoriaId(), null, "categoriaId");
      t.classificar(e.classificacao(), e.categoriaId(), usuario, relogio.agoraUtc());
    }
    transacoes.flush();
    auditoria.registrar("CONCILIAR_MANUAL", "transacao_bancaria", t.getId(), antes, t.instantaneo());
    return t;
  }

  @Transactional
  public TransacaoBancaria ignorar(UUID id) {
    TransacaoBancaria t = obter(id);
    if (t.getStatus() != TransacaoBancaria.Status.PENDENTE) throw ApiException.conflito("TRANSACAO_NAO_PENDENTE", "A transação não está pendente.");
    Map<String, Object> antes = t.instantaneo();
    t.ignorar(Usuarios.atual(), relogio.agoraUtc());
    auditoria.registrar("IGNORAR_TRANSACAO", "transacao_bancaria", t.getId(), antes, t.instantaneo());
    return t;
  }

  /** Desfaz só o vínculo: a baixa ou o pagamento registrados a partir do extrato continuam valendo. */
  @Transactional
  public TransacaoBancaria desfazer(UUID id) {
    TransacaoBancaria t = obter(id);
    if (t.getStatus() == TransacaoBancaria.Status.PENDENTE) throw ApiException.conflito("TRANSACAO_PENDENTE", "A transação já está pendente.");
    Map<String, Object> antes = t.instantaneo();
    t.desfazer();
    t.sugerir(classificacaoSugerida(t));
    auditoria.registrar("DESFAZER_CONCILIACAO", "transacao_bancaria", t.getId(), antes, t.instantaneo());
    return t;
  }

  // ------------------------------------------------------------------ consulta

  public record Filtro(UUID contaBancariaId, TransacaoBancaria.Status status, LocalDate de, LocalDate ate, UUID extratoId) {}

  @Transactional(readOnly = true)
  public Pagina<TransacaoSaida> listar(Filtro f, Pageable pagina) {
    Specification<TransacaoBancaria> s = (r, q, cb) -> cb.isNull(r.get("deletedAt"));
    if (f.contaBancariaId() != null) s = s.and((r, q, cb) -> cb.equal(r.get("contaBancariaId"), f.contaBancariaId()));
    if (f.status() != null) s = s.and((r, q, cb) -> cb.equal(r.get("status"), f.status()));
    if (f.extratoId() != null) s = s.and((r, q, cb) -> cb.equal(r.get("extratoId"), f.extratoId()));
    if (f.de() != null) s = s.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("data"), f.de()));
    if (f.ate() != null) s = s.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("data"), f.ate()));
    return Pagina.de(transacoes.findAll(s, pagina), TransacaoSaida::de);
  }

  @Transactional(readOnly = true)
  public List<ExtratoImportado> extratos() {
    return extratos.recentes(org.springframework.data.domain.PageRequest.of(0, 50));
  }

  @Transactional(readOnly = true)
  public long pendentes() {
    return transacoes.contarPendentes();
  }

  /**
   * Por conta: saldo pelo extrato (saldo inicial + transações importadas) e pelo sistema (saldo inicial +
   * recebimentos e pagamentos registrados na conta + tarifas/juros/transferências classificadas). A
   * diferença mostra o que falta conciliar ou lançar.
   */
  @Transactional(readOnly = true)
  public List<ResumoConta> resumo() {
    UUID tenant = TenantContexto.exigir();
    List<ResumoConta> lista = new ArrayList<>();
    for (ContaBancaria c : cadastros.contas()) {
      if (!c.isAtiva()) continue;
      Map<String, Object> m = jdbc.queryForMap("""
          SELECT count(*) FILTER (WHERE status = 'PENDENTE') AS pendentes,
                 count(*) FILTER (WHERE status = 'CONCILIADA') AS conciliadas,
                 count(*) FILTER (WHERE status = 'IGNORADA') AS ignoradas,
                 coalesce(sum(valor) FILTER (WHERE status <> 'IGNORADA'), 0) AS movimento,
                 coalesce(sum(valor) FILTER (WHERE status = 'CONCILIADA' AND pagamento_id IS NULL AND conta_pagar_id IS NULL), 0) AS classificadas,
                 max(data) AS ultima
            FROM financeiro.transacoes_bancarias
           WHERE tenant_id = ? AND conta_bancaria_id = ? AND deleted_at IS NULL AND data >= ?""",
          tenant, c.getId(), Date.valueOf(c.getDataSaldoInicial()));
      BigDecimal recebido = jdbc.queryForObject("""
          SELECT coalesce(sum(valor), 0) FROM financeiro.pagamentos
           WHERE tenant_id = ? AND conta_bancaria_id = ? AND status = 'CONFIRMADO'
             AND (confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date >= ?""", BigDecimal.class, tenant, c.getId(),
          Date.valueOf(c.getDataSaldoInicial()));
      BigDecimal pago = jdbc.queryForObject("""
          SELECT coalesce(sum(valor_pago), 0) FROM financeiro.contas_pagar
           WHERE tenant_id = ? AND conta_bancaria_id = ? AND status = 'PAGA' AND deleted_at IS NULL AND data_pagamento >= ?""",
          BigDecimal.class, tenant, c.getId(), Date.valueOf(c.getDataSaldoInicial()));
      BigDecimal extrato = c.getSaldoInicial().add((BigDecimal) m.get("movimento"));
      BigDecimal sistema = c.getSaldoInicial().add(recebido).subtract(pago).add((BigDecimal) m.get("classificadas"));
      Date ultima = (Date) m.get("ultima");
      lista.add(new ResumoConta(c.getId(), c.getNome(), ((Number) m.get("pendentes")).longValue(),
          ((Number) m.get("conciliadas")).longValue(), ((Number) m.get("ignoradas")).longValue(), Dinheiro.de(extrato),
          Dinheiro.de(sistema), Dinheiro.de(extrato.subtract(sistema)), ultima == null ? null : ultima.toLocalDate()));
    }
    return lista;
  }

  private TransacaoBancaria obter(UUID id) {
    return Tenant.ativoDoTenant(transacoes.findById(id), "Transação");
  }

  private static String vazio(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
