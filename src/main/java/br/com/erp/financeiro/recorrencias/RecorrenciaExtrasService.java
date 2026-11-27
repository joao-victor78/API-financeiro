package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.configuracoes.ConfiguracaoService;
import br.com.erp.financeiro.eventos.Outbox;
import br.com.erp.financeiro.eventos.PedidosPlataforma;
import br.com.erp.financeiro.eventos.TiposDeEvento;
import br.com.erp.financeiro.pagamentos.provedor.ProvedorPagamento;
import br.com.erp.financeiro.pagamentos.provedor.ProvedorSimulado;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.AdicionalEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.AdicionalSaida;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.CartaoEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ConsumoEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ConsumoIntegracao;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ConsumoSaida;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ReajusteEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ReajusteSaida;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.RegraSaida;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O que a V2 acrescenta à recorrência: consumo e excedentes (§41), adicionais (§26), reajuste por índice
 * (§69) e cartão recorrente (§27). Nenhum desses recursos altera cobrança já emitida: tudo vale a partir
 * da próxima geração, como o aditivo (RN-17).
 */
@Service
public class RecorrenciaExtrasService {

  private static final String ROTA = "/modulos/financeiro/recorrencias";
  private static final BigDecimal CEM = BigDecimal.valueOf(100);

  private final RecorrenciaRepository regras;
  private final RecorrenciaItemRepository itens;
  private final ConsumoRepository consumos;
  private final AdicionalRepository adicionais;
  private final ReajusteRepository reajustes;
  private final ConfiguracaoService configuracoes;
  private final AuditoriaService auditoria;
  private final Outbox outbox;
  private final PedidosPlataforma plataforma;
  private final ProvedorPagamento provedor;
  private final Relogio relogio;

  public RecorrenciaExtrasService(RecorrenciaRepository regras, RecorrenciaItemRepository itens, ConsumoRepository consumos,
      AdicionalRepository adicionais, ReajusteRepository reajustes, ConfiguracaoService configuracoes,
      AuditoriaService auditoria, Outbox outbox, PedidosPlataforma plataforma, ProvedorPagamento provedor, Relogio relogio) {
    this.regras = regras;
    this.itens = itens;
    this.consumos = consumos;
    this.adicionais = adicionais;
    this.reajustes = reajustes;
    this.configuracoes = configuracoes;
    this.auditoria = auditoria;
    this.outbox = outbox;
    this.plataforma = plataforma;
    this.provedor = provedor;
    this.relogio = relogio;
  }

  private Recorrencia regra(UUID id) {
    return Tenant.ativoDoTenant(regras.findById(id), "Regra de recorrência");
  }

  private static RecorrenciaItem itemDoServico(List<RecorrenciaItem> todos, UUID servico, String competencia) {
    return todos.stream().filter(i -> i.getServicoId().equals(servico) && i.vigenteEm(competencia)).findFirst().orElse(null);
  }

  // ------------------------------------------------------------------ consumo (§41)

  @Transactional(readOnly = true)
  public List<ConsumoSaida> consumos(UUID regraId) {
    Recorrencia r = regra(regraId);
    List<RecorrenciaItem> todos = itens.daRegra(r.getId());
    return consumos.daRegra(r.getId()).stream().map(c -> saida(c, itemDoServico(todos, c.getServicoId(), c.getCompetencia()))).toList();
  }

  /** Lança ou corrige o consumo do serviço na competência; já faturado não muda (409). */
  @Transactional
  public ConsumoSaida registrarConsumo(UUID regraId, ConsumoEntrada e, RecorrenciaConsumo.Origem origem) {
    Recorrencia r = regra(regraId);
    String competencia = Competencia.de(e.competencia());
    RecorrenciaItem item = itemDoServico(itens.daRegra(r.getId()), e.servicoId(), competencia);
    if (item == null) {
      throw ApiException.regra("SERVICO_FORA_DA_REGRA", "O serviço não está entre os itens vigentes da regra em "
          + Competencia.mes(competencia).getMonthValue() + "/" + Competencia.mes(competencia).getYear() + ".");
    }
    RecorrenciaConsumo c = consumos.doServico(r.getId(), e.servicoId(), competencia)
        .orElseGet(() -> new RecorrenciaConsumo(r.getId(), e.servicoId(), competencia));
    if (c.getCobrancaId() != null) {
      throw ApiException.conflito("CONSUMO_FATURADO", "O consumo desta competência já foi faturado na cobrança "
          + c.getCobrancaId().toString().substring(0, 8) + ".");
    }
    Map<String, Object> antes = c.isNew() ? null : c.instantaneo();
    c.registrar(e.quantidade(), origem, e.observacao() == null || e.observacao().isBlank() ? null : e.observacao().trim());
    consumos.save(c);
    auditoria.registrar(antes == null ? "REGISTRAR_CONSUMO" : "CORRIGIR_CONSUMO", "recorrencia_consumo", c.getId(), antes, c.instantaneo());
    alertarSeExcedeu(r, item, c);
    return saida(c, item);
  }

  /** Integração (RMM, painel do fabricante, script): mesmo efeito, por regra ou por contrato. */
  @Transactional
  public List<ConsumoSaida> registrarPorIntegracao(List<ConsumoIntegracao> lote) {
    if (lote.size() > 500) throw ApiException.campo("lote", "LOTE_GRANDE", "No máximo 500 consumos por envio.");
    List<ConsumoSaida> saida = new ArrayList<>();
    for (int i = 0; i < lote.size(); i++) {
      ConsumoIntegracao x = lote.get(i);
      UUID regraId = x.recorrenciaId();
      if (regraId == null && x.contratoId() != null) regraId = regras.doContrato(x.contratoId()).map(Recorrencia::getId).orElse(null);
      if (regraId == null) throw ApiException.campo("[" + i + "]", "REGRA_NAO_ENCONTRADA", "Informe recorrenciaId ou um contratoId com regra.");
      saida.add(registrarConsumo(regraId, new ConsumoEntrada(x.servicoId(), x.competencia(), x.quantidade(), "integração"),
          RecorrenciaConsumo.Origem.INTEGRACAO));
    }
    return saida;
  }

  /**
   * §41 e §105 "QUANDO consumo ultrapassar franquia ENTÃO alertar vendedor": uma vez por consumo, ao
   * chegar no percentual configurado do permitido. O evento financeiro.consumo.excedido leva a sugestão
   * de upgrade para o CRM abrir a oportunidade de expansão (§70).
   */
  private void alertarSeExcedeu(Recorrencia r, RecorrenciaItem item, RecorrenciaConsumo c) {
    if (c.getAlertadoEm() != null || item.permitido().signum() <= 0) return;
    BigDecimal uso = c.getQuantidade().multiply(CEM).divide(item.permitido(), 2, RoundingMode.HALF_EVEN);
    if (uso.compareTo(configuracoes.atual().alertaConsumoPercentual()) < 0) return;
    BigDecimal excesso = c.getQuantidade().subtract(item.permitido()).max(BigDecimal.ZERO);
    BigDecimal valorExcesso = excesso.multiply(item.precoExcedente()).setScale(2, RoundingMode.HALF_EVEN);
    String sugestao = sugestao(item, c.getQuantidade());
    Map<String, Object> dados = new LinkedHashMap<>();
    dados.put("recorrenciaId", r.getId());
    dados.put("empresaId", r.getEmpresaId());
    dados.put("clienteId", r.getEmpresaId());
    dados.put("contratoId", r.getContratoId());
    dados.put("servicoId", item.getServicoId());
    dados.put("descricao", item.getDescricao());
    dados.put("unidade", item.getUnidade());
    dados.put("competencia", Competencia.primeiroDia(c.getCompetencia()));
    dados.put("contratado", item.permitido());
    dados.put("consumo", c.getQuantidade());
    dados.put("excedente", excesso);
    dados.put("percentualUso", uso);
    dados.put("valorExcedenteEstimado", valorExcesso);
    dados.put("vendedorId", r.getVendedorId());
    dados.put("sugestao", sugestao);
    dados.put("tipoOportunidade", "UPSELL");
    outbox.gravar(TiposDeEvento.CONSUMO_EXCEDIDO, dados);
    String texto = item.getDescricao() + ": consumo " + c.getQuantidade().stripTrailingZeros().toPlainString() + " " + item.getUnidade()
        + " de " + item.permitido().stripTrailingZeros().toPlainString() + " contratados (" + uso.stripTrailingZeros().toPlainString()
        + "%)" + (valorExcesso.signum() > 0 ? ", excedente estimado " + Dinheiro.brl(valorExcesso) : "") + ". " + sugestao;
    plataforma.timeline(r.getEmpresaId(), TiposDeEvento.CONSUMO_EXCEDIDO, texto, ROTA);
    UUID destino = r.getVendedorId() != null ? r.getVendedorId() : r.getCreatedBy();
    if (destino != null) plataforma.notificacao(destino, "CONSUMO_EXCEDIDO", "Consumo acima do contratado", texto, ROTA);
    c.marcarAlertado(relogio.agoraUtc());
  }

  /** Comparação simples: quanto ficaria o fixo com a quantidade consumida, e o excedente avulso. */
  static String sugestao(RecorrenciaItem item, BigDecimal consumo) {
    BigDecimal novaQtd = consumo.setScale(0, RoundingMode.CEILING);
    if (novaQtd.compareTo(item.getQuantidade()) <= 0) return "Uso dentro do contratado.";
    BigDecimal fixoNovo = novaQtd.multiply(item.getValorUnitario()).setScale(2, RoundingMode.HALF_EVEN);
    return "Sugerir upgrade de " + item.getQuantidade().stripTrailingZeros().toPlainString() + " para "
        + novaQtd.toPlainString() + " " + item.getUnidade() + " (novo fixo do item " + Dinheiro.brl(fixoNovo) + ").";
  }

  private ConsumoSaida saida(RecorrenciaConsumo c, RecorrenciaItem item) {
    BigDecimal permitido = item == null ? null : item.permitido();
    BigDecimal excesso = permitido == null ? null : c.getQuantidade().subtract(permitido).max(BigDecimal.ZERO);
    BigDecimal valor = excesso == null ? null : excesso.multiply(item.precoExcedente()).setScale(2, RoundingMode.HALF_EVEN);
    BigDecimal uso = permitido == null || permitido.signum() == 0 ? null
        : c.getQuantidade().multiply(CEM).divide(permitido, 2, RoundingMode.HALF_EVEN);
    return new ConsumoSaida(c.getId(), c.getRecorrenciaId(), c.getServicoId(), item == null ? null : item.getDescricao(),
        item == null ? null : item.getUnidade(), Competencia.primeiroDia(c.getCompetencia()), c.getQuantidade(), permitido,
        excesso, valor, uso, c.getOrigem().name(), c.getObservacao(), c.getCobrancaId(), c.getAlertadoEm(),
        item == null ? null : sugestao(item, c.getQuantidade()));
  }

  // ------------------------------------------------------------------ adicionais (§26)

  @Transactional(readOnly = true)
  public List<AdicionalSaida> adicionais(UUID regraId) {
    Recorrencia r = regra(regraId);
    return adicionais.daRegra(r.getId()).stream().map(RecorrenciaExtrasService::saida).toList();
  }

  @Transactional
  public AdicionalSaida criarAdicional(UUID regraId, AdicionalEntrada e) {
    Recorrencia r = regra(regraId);
    if (!r.isAtiva()) throw ApiException.conflito("REGRA_INATIVA", "Regra encerrada não recebe adicional.");
    RecorrenciaAdicional a = adicionais.save(new RecorrenciaAdicional(r.getId(), Competencia.de(e.competencia()), e.descricao().trim(), e.valor()));
    auditoria.registrar("CRIAR_ADICIONAL", "recorrencia_adicional", a.getId(), null, a.instantaneo());
    return saida(a);
  }

  @Transactional
  public void excluirAdicional(UUID regraId, UUID adicionalId) {
    Recorrencia r = regra(regraId);
    RecorrenciaAdicional a = Tenant.ativoDoTenant(adicionais.findById(adicionalId), "Adicional");
    if (!a.getRecorrenciaId().equals(r.getId())) throw ApiException.naoEncontrado("Adicional");
    if (a.getCobrancaId() != null) throw ApiException.conflito("ADICIONAL_FATURADO", "O adicional já foi faturado; cancele ou edite a cobrança.");
    Map<String, Object> antes = a.instantaneo();
    a.excluir();
    auditoria.registrar("EXCLUIR_ADICIONAL", "recorrencia_adicional", a.getId(), antes, null);
  }

  private static AdicionalSaida saida(RecorrenciaAdicional a) {
    return new AdicionalSaida(a.getId(), a.getRecorrenciaId(), Competencia.primeiroDia(a.getCompetencia()), a.getDescricao(),
        a.getValor(), a.getCobrancaId(), a.getCreatedAt());
  }

  // ------------------------------------------------------------------ reajuste (§69)

  @Transactional(readOnly = true)
  public List<ReajusteSaida> reajustes(UUID regraId) {
    Recorrencia r = regra(regraId);
    return reajustes.daRegra(r.getId()).stream().map(RecorrenciaExtrasService::saida).toList();
  }

  /**
   * Aplica o percentual do índice em todos os itens a partir da competência (padrão: próxima geração).
   * Como o aditivo (RN-17): a versão atual dos itens termina na competência anterior e a reajustada
   * começa na de aplicação; cobranças já emitidas não mudam. Preço de excedente reajusta junto; custo não.
   */
  @Transactional
  public ReajusteSaida aplicarReajuste(UUID regraId, ReajusteEntrada e) {
    Recorrencia r = regra(regraId);
    if (!r.isAtiva()) throw ApiException.conflito("REGRA_INATIVA", "Regra encerrada não recebe reajuste.");
    String desde = e.competenciaAplicacao() == null ? r.getProximaCompetencia() : Competencia.de(e.competenciaAplicacao());
    if (desde.compareTo(r.getProximaCompetencia()) < 0) {
      throw ApiException.regra("COMPETENCIA_JA_GERADA", "O reajuste vale a partir da próxima geração ("
          + Competencia.mes(r.getProximaCompetencia()).getMonthValue() + "/" + Competencia.mes(r.getProximaCompetencia()).getYear() + ") ou depois.");
    }
    List<RecorrenciaItem> todos = itens.daRegra(r.getId());
    List<RecorrenciaItem> afetados = todos.stream()
        .filter(i -> i.getDeletedAt() == null && (i.vigenteEm(desde) || i.getVigenteDesde().compareTo(desde) > 0)).toList();
    if (afetados.stream().noneMatch(i -> i.vigenteEm(desde))) {
      throw ApiException.regra("SEM_ITENS_VIGENTES", "A regra não tem itens vigentes na competência do reajuste.");
    }
    BigDecimal fator = BigDecimal.ONE.add(e.percentual().divide(CEM, 8, RoundingMode.HALF_EVEN));
    BigDecimal anterior = afetados.stream().filter(i -> i.vigenteEm(desde)).map(RecorrenciaItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal novo = BigDecimal.ZERO;
    String vespera = Competencia.somarMeses(desde, -1);
    for (RecorrenciaItem i : afetados) {
      String inicio = i.getVigenteDesde().compareTo(desde) >= 0 ? i.getVigenteDesde() : desde;
      if (i.getVigenteDesde().compareTo(desde) >= 0) i.excluir();
      else i.encerrarEm(vespera);
      RecorrenciaItem n = new RecorrenciaItem(r.getId(), i.getServicoId(), i.getDescricao(), i.getQuantidade(), i.getUnidade(),
          i.getValorUnitario().multiply(fator).setScale(2, RoundingMode.HALF_EVEN), i.getFranquia(), inicio,
          i.getValorExcedente() == null ? null : i.getValorExcedente().multiply(fator).setScale(2, RoundingMode.HALF_EVEN),
          i.getCustoUnitario(), i.getFabricante());
      if (i.getVigenteAte() != null && i.getVigenteAte().compareTo(desde) >= 0) n.encerrarEm(i.getVigenteAte());
      itens.save(n);
      if (n.vigenteEm(desde)) novo = novo.add(n.total());
    }
    String obs = e.observacao() == null || e.observacao().isBlank() ? null : e.observacao().trim();
    Reajuste reajuste = reajustes.save(new Reajuste(r.getId(), e.indice(), e.percentual(), desde, anterior, novo, obs));
    Map<String, Object> depois = new LinkedHashMap<>();
    depois.put("indice", e.indice());
    depois.put("percentual", e.percentual());
    depois.put("competencia", desde);
    depois.put("valorAnterior", Dinheiro.de(anterior));
    depois.put("valorNovo", Dinheiro.de(novo));
    auditoria.registrar("REAJUSTAR", RecorrenciaService.ENTIDADE, r.getId(), Map.of("valor", Dinheiro.de(anterior)), depois);
    Map<String, Object> dados = new LinkedHashMap<>(depois);
    dados.put("recorrenciaId", r.getId());
    dados.put("empresaId", r.getEmpresaId());
    dados.put("contratoId", r.getContratoId());
    outbox.gravar(TiposDeEvento.REAJUSTE_APLICADO, dados);
    plataforma.timeline(r.getEmpresaId(), TiposDeEvento.REAJUSTE_APLICADO, "Reajuste " + e.indice() + " de "
        + e.percentual().stripTrailingZeros().toPlainString() + "% a partir de " + Competencia.mes(desde).getMonthValue() + "/"
        + Competencia.mes(desde).getYear() + ": " + Dinheiro.brl(anterior) + " → " + Dinheiro.brl(novo) + ".", ROTA);
    return saida(reajuste);
  }

  /** Regras com mês de reajuste no mês atual e sem reajuste aplicado nos últimos 10 meses. */
  @Transactional(readOnly = true)
  public List<RegraSaida> reajustesPendentes() {
    int mes = relogio.hoje().getMonthValue();
    var desde = relogio.agoraUtc().minusMonths(10);
    List<RegraSaida> lista = new ArrayList<>();
    for (Recorrencia r : regras.todas()) {
      if (!r.isAtiva() || r.getMesReajuste() == null || r.getMesReajuste() != mes) continue;
      if (reajustes.desde(r.getId(), desde) > 0) continue;
      lista.add(RegraSaida.de(r, itens.daRegra(r.getId())));
    }
    return lista;
  }

  private static ReajusteSaida saida(Reajuste r) {
    return new ReajusteSaida(r.getId(), r.getRecorrenciaId(), r.getIndice(), r.getPercentual(),
        Competencia.primeiroDia(r.getCompetenciaAplicacao()), r.getValorAnterior(), r.getValorNovo(), r.getObservacao(),
        r.getCreatedBy(), r.getCreatedAt());
  }

  // ------------------------------------------------------------------ cartão recorrente (§27)

  @Transactional
  public RegraSaida registrarCartao(UUID regraId, CartaoEntrada e) {
    Recorrencia r = regra(regraId);
    Map<String, Object> antes = r.instantaneo();
    r.registrarCartao(e.tokenRef().trim(), e.finalCartao(), e.bandeira() == null ? null : e.bandeira().trim().toUpperCase(),
        e.cobrancaAutomatica() == null || e.cobrancaAutomatica());
    auditoria.registrar("REGISTRAR_CARTAO", RecorrenciaService.ENTIDADE, r.getId(), antes, r.instantaneo());
    return RegraSaida.de(r, itens.daRegra(r.getId()));
  }

  @Transactional
  public RegraSaida removerCartao(UUID regraId) {
    Recorrencia r = regra(regraId);
    Map<String, Object> antes = r.instantaneo();
    r.removerCartao();
    auditoria.registrar("REMOVER_CARTAO", RecorrenciaService.ENTIDADE, r.getId(), antes, r.instantaneo());
    return RegraSaida.de(r, itens.daRegra(r.getId()));
  }

  /** Link para o cliente cadastrar o cartão na página do provedor (o número nunca passa pelo módulo). */
  @Transactional(readOnly = true)
  public String linkCadastroCartao(UUID regraId) {
    Recorrencia r = regra(regraId);
    try {
      return provedor.linkCadastroCartao(r.getId(), r.getEmpresaId());
    } catch (UnsupportedOperationException ex) {
      throw ApiException.regra("PROVEDOR_SEM_CARTAO", ex.getMessage());
    }
  }

  /** Só com o provedor simulado: grava um token de teste, para demonstrar a cobrança automática. */
  @Transactional
  public RegraSaida cartaoDeTeste(UUID regraId) {
    if (!(provedor instanceof ProvedorSimulado)) {
      throw ApiException.regra("PROVEDOR_REAL", "Cartão de teste disponível só com o provedor simulado.");
    }
    return registrarCartao(regraId, new CartaoEntrada("tok_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        "4242", "VISA", true));
  }
}
