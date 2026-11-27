package br.com.erp.financeiro.contabilidade;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.contabilidade.FechamentoContabil.Item;
import br.com.erp.financeiro.contabilidade.FechamentoContabil.Status;
import br.com.erp.financeiro.documentos.Armazenamento;
import br.com.erp.financeiro.documentos.Documento;
import br.com.erp.financeiro.documentos.DocumentoService;
import br.com.erp.financeiro.eventos.Outbox;
import br.com.erp.financeiro.eventos.PedidosPlataforma;
import br.com.erp.financeiro.eventos.TiposDeEvento;
import br.com.erp.financeiro.relatorios.Planilhas;
import br.com.erp.financeiro.seguranca.Usuarios;
import br.com.erp.financeiro.tenant.TenantContexto;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contabilidade (§33 a §37): fechamento mensal com checklist e os oito status, portal do escritório
 * (só leitura do que é financeiro e contábil) e o envio do pacote pelo conector configurado.
 *
 * <p>Quem faz o quê: o financeiro (financeiro.contabilidade.fechar) confere, marca o checklist e envia;
 * a contabilidade (financeiro.contabilidade.analisar) coloca em análise, devolve com pendência ou
 * finaliza. Enviado, em análise ou finalizado trava os lançamentos da competência (PeriodosContabeis).
 */
@Service
public class ContabilidadeService {

  public static final String FECHAR = "financeiro.contabilidade.fechar";
  public static final String ANALISAR = "financeiro.contabilidade.analisar";

  /** Transições permitidas e a permissão de quem pode fazê-las. */
  private static final Map<Status, Set<Status>> DO_FINANCEIRO = Map.of(
      Status.NAO_INICIADO, EnumSet.of(Status.EM_CONFERENCIA),
      Status.EM_CONFERENCIA, EnumSet.of(Status.COM_PENDENCIA, Status.AGUARDANDO_DOCUMENTO, Status.PRONTO),
      Status.COM_PENDENCIA, EnumSet.of(Status.EM_CONFERENCIA, Status.AGUARDANDO_DOCUMENTO, Status.PRONTO),
      Status.AGUARDANDO_DOCUMENTO, EnumSet.of(Status.EM_CONFERENCIA, Status.COM_PENDENCIA, Status.PRONTO),
      Status.PRONTO, EnumSet.of(Status.EM_CONFERENCIA, Status.ENVIADO_PARA_CONTABILIDADE));
  private static final Map<Status, Set<Status>> DA_CONTABILIDADE = Map.of(
      Status.ENVIADO_PARA_CONTABILIDADE, EnumSet.of(Status.EM_ANALISE, Status.COM_PENDENCIA),
      Status.EM_ANALISE, EnumSet.of(Status.FINALIZADO, Status.COM_PENDENCIA));

  private final FechamentoRepository fechamentos;
  private final FechamentoItemRepository itens;
  private final LancamentosContabeis lancamentos;
  private final List<ConectorContabil> conectores;
  private final DocumentoService documentos;
  private final Armazenamento armazenamento;
  private final AuditoriaService auditoria;
  private final Outbox outbox;
  private final PedidosPlataforma plataforma;
  private final JdbcTemplate jdbc;
  private final Relogio relogio;

  public ContabilidadeService(FechamentoRepository fechamentos, FechamentoItemRepository itens, LancamentosContabeis lancamentos,
      List<ConectorContabil> conectores, DocumentoService documentos, Armazenamento armazenamento, AuditoriaService auditoria,
      Outbox outbox, PedidosPlataforma plataforma, JdbcTemplate jdbc, Relogio relogio) {
    this.fechamentos = fechamentos;
    this.itens = itens;
    this.lancamentos = lancamentos;
    this.conectores = conectores;
    this.documentos = documentos;
    this.armazenamento = armazenamento;
    this.auditoria = auditoria;
    this.outbox = outbox;
    this.plataforma = plataforma;
    this.jdbc = jdbc;
    this.relogio = relogio;
  }

  public record ItemSaida(Item item, int ordem, boolean concluido, UUID concluidoPor, OffsetDateTime concluidoEm, String observacao,
      String verificacao) {}

  public record FechamentoSaida(UUID id, LocalDate competencia, Status status, String pendencias, int concluidos, int total,
      List<ItemSaida> itens, Map<String, Object> diagnostico, OffsetDateTime enviadoEm, UUID enviadoPor, OffsetDateTime finalizadoEm,
      UUID finalizadoPor, UUID pacoteDocumentoId) {}

  public record Integracao(String codigo, String nome, boolean disponivel, boolean configurado) {}

  // ------------------------------------------------------------------ fechamento

  @Transactional(readOnly = true)
  public List<FechamentoSaida> fechamentos() {
    List<FechamentoSaida> l = new ArrayList<>();
    for (FechamentoContabil f : fechamentos.todos()) l.add(saida(f, YearMonth.parse(f.getCompetencia()), false));
    return l;
  }

  /** O fechamento da competência; sem registro, devolve NAO_INICIADO com o diagnóstico. */
  @Transactional(readOnly = true)
  public FechamentoSaida fechamento(YearMonth mes) {
    return fechamentos.daCompetencia(mes.toString()).map(f -> saida(f, mes, true))
        .orElseGet(() -> new FechamentoSaida(null, mes.atDay(1), Status.NAO_INICIADO, null, 0, Item.values().length,
            itensVirtuais(diagnostico(mes)), diagnostico(mes), null, null, null, null, null));
  }

  @Transactional
  public FechamentoSaida iniciar(YearMonth mes) {
    exigir(FECHAR);
    if (mes.isAfter(relogio.mesAtual())) throw ApiException.regra("COMPETENCIA_FUTURA", "Não é possível fechar uma competência futura.");
    FechamentoContabil f = fechamentos.daCompetencia(mes.toString()).orElse(null);
    if (f != null && f.getStatus() != Status.NAO_INICIADO) {
      throw ApiException.conflito("FECHAMENTO_INICIADO", "O fechamento de " + rotulo(mes) + " já está " + f.getStatus() + ".");
    }
    if (f == null) {
      f = fechamentos.save(new FechamentoContabil(mes.toString()));
      fechamentos.flush();
      for (Item i : Item.values()) itens.save(new FechamentoItem(f.getId(), i));
    }
    Map<String, Object> antes = f.instantaneo();
    f.mudar(Status.EM_CONFERENCIA, null, Usuarios.atual(), relogio.agoraUtc());
    auditoria.registrar("INICIAR_FECHAMENTO", "fechamento", f.getId(), antes, f.instantaneo());
    return saida(f, mes, true);
  }

  @Transactional
  public FechamentoSaida marcar(YearMonth mes, Item item, boolean concluido, String observacao) {
    exigir(FECHAR);
    FechamentoContabil f = existente(mes);
    if (!EnumSet.of(Status.EM_CONFERENCIA, Status.COM_PENDENCIA, Status.AGUARDANDO_DOCUMENTO).contains(f.getStatus())) {
      throw ApiException.conflito("FECHAMENTO_FECHADO", "O checklist só muda em conferência, com pendência ou aguardando documento.");
    }
    FechamentoItem i = itens.doFechamento(f.getId()).stream().filter(x -> x.getItem() == item).findFirst()
        .orElseThrow(() -> ApiException.naoEncontrado("Item do checklist"));
    Map<String, Object> antes = Map.of("item", item, "concluido", i.isConcluido());
    i.marcar(concluido, observacao == null || observacao.isBlank() ? null : observacao.trim(), Usuarios.atual(), relogio.agoraUtc());
    auditoria.registrar("MARCAR_CHECKLIST", "fechamento", f.getId(), antes, Map.of("item", item, "concluido", concluido));
    return saida(f, mes, true);
  }

  /**
   * Muda o status pela matriz de transições. PRONTO exige o checklist inteiro; COM_PENDENCIA e
   * AGUARDANDO_DOCUMENTO exigem o texto da pendência; ENVIADO gera o pacote e chama o conector.
   */
  @Transactional
  public FechamentoSaida mudarStatus(YearMonth mes, Status novo, String pendencias) {
    FechamentoContabil f = existente(mes);
    Status atual = f.getStatus();
    boolean doFinanceiro = DO_FINANCEIRO.getOrDefault(atual, Set.of()).contains(novo);
    boolean daContabilidade = DA_CONTABILIDADE.getOrDefault(atual, Set.of()).contains(novo);
    if (!doFinanceiro && !daContabilidade) {
      throw ApiException.conflito("TRANSICAO_INVALIDA", "De " + atual + " não é possível ir para " + novo + ".");
    }
    exigir(daContabilidade ? ANALISAR : FECHAR);
    String texto = pendencias == null || pendencias.isBlank() ? null : pendencias.trim();
    if ((novo == Status.COM_PENDENCIA || novo == Status.AGUARDANDO_DOCUMENTO) && texto == null) {
      throw ApiException.campo("pendencias", "OBRIGATORIO", "Descreva a pendência ou o documento aguardado.");
    }
    if (novo == Status.PRONTO) {
      long faltam = itens.doFechamento(f.getId()).stream().filter(i -> !i.isConcluido()).count();
      if (faltam > 0) throw ApiException.regra("CHECKLIST_INCOMPLETO", "Faltam " + faltam + " item(ns) do checklist.");
    }
    Map<String, Object> antes = f.instantaneo();
    f.mudar(novo, texto != null && texto.length() > 1000 ? texto.substring(0, 1000) : texto, Usuarios.atual(), relogio.agoraUtc());
    if (novo == Status.ENVIADO_PARA_CONTABILIDADE) {
      enviar(f, mes);
      fechamentos.flush(); // o documento do pacote precisa estar no banco para a consulta da resposta
    }
    auditoria.registrar("MUDAR_STATUS_FECHAMENTO", "fechamento", f.getId(), antes, f.instantaneo());
    if (atual == Status.ENVIADO_PARA_CONTABILIDADE || atual == Status.EM_ANALISE) {
      if (f.getEnviadoPor() != null) {
        plataforma.notificacao(f.getEnviadoPor(), "FECHAMENTO_CONTABIL", "Fechamento " + rotulo(mes) + ": " + novo,
            texto != null ? texto : "A contabilidade atualizou o fechamento.", "/modulos/financeiro/contabilidade");
      }
    }
    return saida(f, mes, true);
  }

  private void enviar(FechamentoContabil f, YearMonth mes) {
    List<LancamentosContabeis.Lancamento> l = lancamentos.daCompetencia(mes);
    byte[] pacote = pacote(mes, l);
    Documento d = documentos.enviar("fechamento-" + mes + ".zip", pacote, Documento.Categoria.RELATORIO, "fechamento", f.getId(),
        "Pacote do fechamento contábil " + rotulo(mes));
    List<String> resultados = new ArrayList<>();
    for (ConectorContabil c : conectores) {
      if (c.configurado()) resultados.add(c.codigo() + ": " + c.enviar(mes, l, pacote));
    }
    Map<String, Object> dados = new LinkedHashMap<>();
    dados.put("competencia", mes.atDay(1));
    dados.put("fechamentoId", f.getId());
    dados.put("pacoteDocumentoId", d.getId());
    dados.put("lancamentos", l.size());
    dados.put("conectores", resultados);
    outbox.gravar(TiposDeEvento.FECHAMENTO_ENVIADO, dados);
  }

  /** Pacote ZIP: lançamentos em quatro formatos, notas, lista de documentos e até 200 anexos (100 MB). */
  @Transactional(readOnly = true)
  public byte[] pacote(YearMonth mes, List<LancamentosContabeis.Lancamento> l) {
    List<List<Object>> notas = new ArrayList<>();
    jdbc.query("""
        SELECT tipo, modelo, numero, serie, chave_acesso, data_emissao, valor, valor_impostos, coalesce(participante_nome, empresa_id::text), status
          FROM financeiro.notas_fiscais WHERE tenant_id = ? AND deleted_at IS NULL AND competencia = ? ORDER BY data_emissao, numero""",
        rs -> {
          notas.add(new ArrayList<>(java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
              ExportacaoContabil.dataBr(rs.getDate(6).toLocalDate()), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9), rs.getString(10))));
        }, TenantContexto.exigir(), mes.toString());
    byte[] notasCsv = Planilhas.csv(List.of("Tipo", "Modelo", "Número", "Série", "Chave", "Emissão", "Valor", "Impostos", "Participante", "Status"), notas);
    List<Object[]> docs = documentosDoMes(mes);
    List<List<Object>> linhasDocs = new ArrayList<>();
    Map<String, byte[]> anexos = new LinkedHashMap<>();
    long tamanho = 0;
    for (Object[] d : docs) {
      linhasDocs.add(new ArrayList<>(java.util.Arrays.asList(d[0].toString(), d[1], d[2], d[3], d[4], d[5] == null ? null : d[5].toString(), d[6])));
      long t = (Long) d[7];
      if (anexos.size() < 200 && tamanho + t <= 100L * 1024 * 1024) {
        try {
          anexos.put(d[0].toString().substring(0, 8) + "-" + d[1], armazenamento.ler((String) d[8]));
          tamanho += t;
        } catch (RuntimeException e) {
          // arquivo indisponível no armazenamento: fica só na lista
        }
      }
    }
    byte[] docsCsv = Planilhas.csv(List.of("Id", "Arquivo", "Categoria", "Entidade", "Descrição", "Registro", "SHA-256"), linhasDocs);
    return ExportacaoContabil.pacote(mes, l, notasCsv, docsCsv, anexos);
  }

  /** Documentos ligados a lançamentos da competência (cobranças, contas, notas, pagamentos do mês) e extratos. */
  private List<Object[]> documentosDoMes(YearMonth mes) {
    return jdbc.query("""
        SELECT d.id, d.nome_arquivo, d.categoria, d.entidade, d.descricao, d.registro_id, d.sha256, d.tamanho_bytes, d.chave_armazenamento
          FROM financeiro.documentos d
         WHERE d.tenant_id = ? AND d.deleted_at IS NULL AND d.categoria <> 'RELATORIO' AND (
               (d.entidade = 'cobranca' AND d.registro_id IN (SELECT id FROM financeiro.cobrancas WHERE competencia = ?))
            OR (d.entidade = 'conta_pagar' AND d.registro_id IN (SELECT id FROM financeiro.contas_pagar WHERE competencia = ?))
            OR (d.entidade = 'nota_fiscal' AND d.registro_id IN (SELECT id FROM financeiro.notas_fiscais WHERE competencia = ?))
            OR (d.entidade = 'pagamento' AND d.registro_id IN (SELECT id FROM financeiro.pagamentos
                  WHERE to_char(confirmado_em AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') = ?))
            OR (d.entidade = 'extrato' AND d.registro_id IN (SELECT id FROM financeiro.extratos_importados
                  WHERE coalesce(periodo_inicio, created_at::date) <= ? AND coalesce(periodo_fim, created_at::date) >= ?)))
         ORDER BY d.categoria, d.created_at""",
        (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getObject(6, UUID.class), rs.getString(7), rs.getLong(8), rs.getString(9)},
        TenantContexto.exigir(), mes.toString(), mes.toString(), mes.toString(), mes.toString(), Date.valueOf(mes.atEndOfMonth()),
        Date.valueOf(mes.atDay(1)));
  }

  // ------------------------------------------------------------------ diagnóstico (ajuda a marcar o checklist)

  /** Contagens que indicam se cada item do checklist está em ordem. */
  @Transactional(readOnly = true)
  public Map<String, Object> diagnostico(YearMonth mes) {
    UUID t = TenantContexto.exigir();
    String c = mes.toString();
    Date d1 = Date.valueOf(mes.atDay(1));
    Date d2 = Date.valueOf(mes.atEndOfMonth());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("receitas", n("SELECT count(*) FROM financeiro.cobrancas WHERE tenant_id = ? AND deleted_at IS NULL AND competencia = ?", t, c));
    m.put("receitasSemCategoria", n("SELECT count(*) FROM financeiro.cobrancas WHERE tenant_id = ? AND deleted_at IS NULL AND competencia = ? AND categoria_id IS NULL", t, c));
    m.put("despesas", n("SELECT count(*) FROM financeiro.contas_pagar WHERE tenant_id = ? AND deleted_at IS NULL AND status <> 'CANCELADA' AND competencia = ?", t, c));
    m.put("despesasSemCategoria", n("SELECT count(*) FROM financeiro.contas_pagar WHERE tenant_id = ? AND deleted_at IS NULL AND status <> 'CANCELADA' AND competencia = ? AND categoria_id IS NULL", t, c));
    m.put("despesasPendentesDeAprovacao", n("SELECT count(*) FROM financeiro.contas_pagar WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'PENDENTE' AND competencia = ?", t, c));
    m.put("despesasSemDocumento", n("SELECT count(*) FROM financeiro.contas_pagar WHERE tenant_id = ? AND deleted_at IS NULL AND status <> 'CANCELADA' AND competencia = ? AND documento_id IS NULL", t, c));
    m.put("transacoesPendentes", n("SELECT count(*) FROM financeiro.transacoes_bancarias WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'PENDENTE' AND data BETWEEN ? AND ?", t, d1, d2));
    m.put("extratosImportados", n("SELECT count(*) FROM financeiro.extratos_importados WHERE tenant_id = ? AND deleted_at IS NULL AND coalesce(periodo_fim, created_at::date) >= ? AND coalesce(periodo_inicio, created_at::date) <= ?", t, d1, d2));
    m.put("cobrancasPagasSemNota", n("""
        SELECT count(*) FROM financeiro.cobrancas c WHERE c.tenant_id = ? AND c.deleted_at IS NULL AND c.competencia = ? AND c.status = 'PAGO'
           AND NOT EXISTS (SELECT 1 FROM financeiro.notas_fiscais n WHERE n.cobranca_id = c.id AND n.deleted_at IS NULL AND n.status = 'AUTORIZADA')""", t, c));
    m.put("notasEmitidas", n("SELECT count(*) FROM financeiro.notas_fiscais WHERE tenant_id = ? AND deleted_at IS NULL AND tipo = 'EMITIDA' AND status = 'AUTORIZADA' AND competencia = ?", t, c));
    m.put("notasRecebidas", n("SELECT count(*) FROM financeiro.notas_fiscais WHERE tenant_id = ? AND deleted_at IS NULL AND tipo = 'RECEBIDA' AND status = 'AUTORIZADA' AND competencia = ?", t, c));
    m.put("pagamentosSemComprovante", n("""
        SELECT count(*) FROM financeiro.contas_pagar cp WHERE cp.tenant_id = ? AND cp.deleted_at IS NULL AND cp.status = 'PAGA' AND cp.data_pagamento BETWEEN ? AND ?
           AND NOT EXISTS (SELECT 1 FROM financeiro.documentos d WHERE d.entidade = 'conta_pagar' AND d.registro_id = cp.id AND d.deleted_at IS NULL)""", t, d1, d2));
    m.put("comissoesNaoLancadas", n("SELECT count(*) FROM financeiro.comissoes WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'A_PAGAR' AND competencia = ?", t, c));
    m.put("impostosLancados", n("""
        SELECT count(*) FROM financeiro.contas_pagar cp JOIN financeiro.categorias_financeiras cat ON cat.id = cp.categoria_id
         WHERE cp.tenant_id = ? AND cp.deleted_at IS NULL AND cp.status <> 'CANCELADA' AND cp.competencia = ?
           AND cat.grupo_dre IN ('DEDUCOES','IMPOSTOS_SOBRE_LUCRO')""", t, c));
    return m;
  }

  private long n(String sql, Object... args) {
    Long v = jdbc.queryForObject(sql, Long.class, args);
    return v == null ? 0 : v;
  }

  /** Texto de apoio de cada item, a partir do diagnóstico. */
  static String verificacao(Item i, Map<String, Object> d) {
    return switch (i) {
      case RECEITAS_CONFERIDAS -> d.get("receitas") + " receita(s); " + d.get("receitasSemCategoria") + " sem categoria";
      case DESPESAS_CONFERIDAS -> d.get("despesas") + " despesa(s); " + d.get("despesasSemCategoria") + " sem categoria; "
          + d.get("despesasPendentesDeAprovacao") + " pendente(s) de aprovação";
      case EXTRATOS_CONFERIDOS -> d.get("extratosImportados") + " extrato(s); " + d.get("transacoesPendentes") + " transação(ões) pendente(s)";
      case NOTAS_EMITIDAS -> d.get("notasEmitidas") + " nota(s) emitida(s); " + d.get("cobrancasPagasSemNota") + " cobrança(s) paga(s) sem nota";
      case NOTAS_RECEBIDAS -> d.get("notasRecebidas") + " nota(s) recebida(s)";
      case COMPROVANTES -> d.get("pagamentosSemComprovante") + " pagamento(s) sem comprovante anexo";
      case COMISSOES -> d.get("comissoesNaoLancadas") + " comissão(ões) ainda não lançada(s)";
      case IMPOSTOS -> d.get("impostosLancados") + " lançamento(s) de imposto";
      case LANCAMENTOS_CLASSIFICADOS -> (((Number) d.get("receitasSemCategoria")).longValue() + ((Number) d.get("despesasSemCategoria")).longValue())
          + " lançamento(s) sem categoria";
      case DOCUMENTACAO_COMPLETA -> d.get("despesasSemDocumento") + " despesa(s) sem documento";
    };
  }

  // ------------------------------------------------------------------ integrações (§37)

  public List<Integracao> integracoes() {
    List<Integracao> l = new ArrayList<>();
    for (ConectorContabil c : conectores) l.add(new Integracao(c.codigo(), c.nome(), true, c.configurado()));
    for (String s : ConectorContabil.SISTEMAS_PREVISTOS) {
      if (conectores.stream().noneMatch(c -> c.codigo().equals(s))) l.add(new Integracao(s, s.replace('_', ' '), false, false));
    }
    return l;
  }

  @Transactional(readOnly = true)
  public List<LancamentosContabeis.Lancamento> lancamentos(YearMonth mes) {
    return lancamentos.daCompetencia(mes);
  }

  // ------------------------------------------------------------------ apoio

  private FechamentoContabil existente(YearMonth mes) {
    return fechamentos.daCompetencia(mes.toString())
        .orElseThrow(() -> ApiException.regra("FECHAMENTO_NAO_INICIADO", "Inicie o fechamento de " + rotulo(mes) + " primeiro."));
  }

  private FechamentoSaida saida(FechamentoContabil f, YearMonth mes, boolean comDiagnostico) {
    Map<String, Object> d = comDiagnostico ? diagnostico(mes) : Map.of();
    List<ItemSaida> lista = itens.doFechamento(f.getId()).stream().map(i -> new ItemSaida(i.getItem(), i.getOrdem(), i.isConcluido(),
        i.getConcluidoPor(), i.getConcluidoEm(), i.getObservacao(), comDiagnostico ? verificacao(i.getItem(), d) : null)).toList();
    UUID pacote = jdbc.queryForList("""
        SELECT id FROM financeiro.documentos WHERE tenant_id = ? AND entidade = 'fechamento' AND registro_id = ? AND deleted_at IS NULL
         ORDER BY created_at DESC LIMIT 1""", UUID.class, TenantContexto.exigir(), f.getId()).stream().findFirst().orElse(null);
    return new FechamentoSaida(f.getId(), Competencia.primeiroDia(f.getCompetencia()), f.getStatus(), f.getPendencias(),
        (int) lista.stream().filter(ItemSaida::concluido).count(), lista.size(), lista, d, f.getEnviadoEm(), f.getEnviadoPor(),
        f.getFinalizadoEm(), f.getFinalizadoPor(), pacote);
  }

  private static List<ItemSaida> itensVirtuais(Map<String, Object> d) {
    List<ItemSaida> l = new ArrayList<>();
    for (Item i : Item.values()) l.add(new ItemSaida(i, i.ordinal() + 1, false, null, null, null, verificacao(i, d)));
    return l;
  }

  private static void exigir(String permissao) {
    if (!Usuarios.tem(permissao)) throw new AccessDeniedException("Sem a permissão " + permissao);
  }

  static String rotulo(YearMonth m) {
    return String.format("%02d/%d", m.getMonthValue(), m.getYear());
  }
}
