package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaRepository;
import br.com.erp.financeiro.cobrancas.CobrancaService;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ItemEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.RegraEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.RegraSaida;
import br.com.erp.financeiro.tenant.TenantContexto;
import br.com.erp.financeiro.cadastros.CadastroService;
import br.com.erp.financeiro.cadastros.CategoriaFinanceira;
import br.com.erp.financeiro.cobrancas.CobrancaItem;
import br.com.erp.financeiro.cobrancas.CobrancaItemRepository;
import br.com.erp.financeiro.eventos.PedidosPlataforma;
import br.com.erp.financeiro.pagamentos.PagamentoService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cobrança recorrente (RF-03). O valor do ciclo é sempre a soma dos itens vigentes (RN-14); cada regra
 * gera no máximo uma cobrança por competência (RN-15, UNIQUE em recorrencia_geracoes); um aditivo só
 * vale das gerações seguintes em diante (RN-17).
 */
@Service
public class RecorrenciaService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecorrenciaService.class);
  static final String ENTIDADE = "recorrencia";

  public enum Origem { JOB, MANUAL }

  private final RecorrenciaRepository regras;
  private final RecorrenciaItemRepository itens;
  private final CobrancaRepository cobrancas;
  private final CobrancaService cobrancaService;
  private final AuditoriaService auditoria;
  private final JdbcTemplate jdbc;
  private final Relogio relogio;
  private final ConsumoRepository consumos;
  private final AdicionalRepository adicionais;
  private final CobrancaItemRepository composicao;
  private final CadastroService cadastros;
  private final PagamentoService pagamentos;
  private final PedidosPlataforma plataforma;

  public RecorrenciaService(RecorrenciaRepository regras, RecorrenciaItemRepository itens, CobrancaRepository cobrancas,
      CobrancaService cobrancaService, AuditoriaService auditoria, JdbcTemplate jdbc, Relogio relogio,
      ConsumoRepository consumos, AdicionalRepository adicionais, CobrancaItemRepository composicao,
      CadastroService cadastros, PagamentoService pagamentos, PedidosPlataforma plataforma) {
    this.regras = regras;
    this.itens = itens;
    this.cobrancas = cobrancas;
    this.cobrancaService = cobrancaService;
    this.auditoria = auditoria;
    this.jdbc = jdbc;
    this.relogio = relogio;
    this.consumos = consumos;
    this.adicionais = adicionais;
    this.composicao = composicao;
    this.cadastros = cadastros;
    this.pagamentos = pagamentos;
    this.plataforma = plataforma;
  }

  @Transactional(readOnly = true)
  public Pagina<RegraSaida> listar(UUID clienteId, Boolean ativa, Pageable pagina) {
    Specification<Recorrencia> s = (r, q, cb) -> cb.isNull(r.get("deletedAt"));
    if (clienteId != null) s = s.and((r, q, cb) -> cb.equal(r.get("empresaId"), clienteId));
    if (ativa != null) s = s.and((r, q, cb) -> cb.equal(r.get("ativa"), ativa));
    Page<Recorrencia> p = regras.findAll(s, pagina);
    Map<UUID, List<RecorrenciaItem>> porRegra = p.isEmpty() ? Map.of()
        : itens.dasRegras(p.map(Recorrencia::getId).toList()).stream().collect(Collectors.groupingBy(RecorrenciaItem::getRecorrenciaId));
    return Pagina.de(p, r -> RegraSaida.de(r, porRegra.getOrDefault(r.getId(), List.of())));
  }

  @Transactional(readOnly = true)
  public RegraSaida ver(UUID id) {
    Recorrencia r = obter(id);
    return RegraSaida.de(r, itens.daRegra(r.getId()));
  }

  Recorrencia obter(UUID id) {
    return Tenant.ativoDoTenant(regras.findById(id), "Regra de recorrência");
  }

  /** A primeira competência é a do mês atual: o job gera quando chega o dia de geração. */
  @Transactional
  public RegraSaida criar(RegraEntrada e) {
    String inicio = Competencia.de(relogio.hoje());
    Recorrencia r = new Recorrencia(e.clienteId(), e.contratoId(), inicio, relogio.hoje());
    configurar(r, e);
    if (Boolean.FALSE.equals(e.ativa())) r.encerrar(relogio.hoje());
    regras.save(r);
    List<RecorrenciaItem> novos = salvarItens(r.getId(), e.itens(), inicio);
    auditoria.registrar("CRIAR", ENTIDADE, r.getId(), null, Map.of("regra", r.instantaneo(), "itens", e.itens()));
    return RegraSaida.de(r, novos);
  }

  /**
   * RN-17: os itens novos valem da próxima competência a gerar; os vigentes até ali são encerrados na
   * competência anterior. Cobranças já emitidas não mudam (guardam o próprio valor).
   */
  @Transactional
  public RegraSaida atualizar(UUID id, RegraEntrada e) {
    Recorrencia r = obter(id);
    List<RecorrenciaItem> atuais = itens.daRegra(r.getId());
    Map<String, Object> antes = Map.of("regra", r.instantaneo(), "itens", atuais.stream()
        .filter(i -> i.vigenteEm(r.getProximaCompetencia())).map(RecorrenciaDtos.ItemSaida::de).toList());
    configurar(r, e);
    if (Boolean.FALSE.equals(e.ativa()) && r.isAtiva()) r.encerrar(relogio.hoje());
    else if (Boolean.TRUE.equals(e.ativa()) && !r.isAtiva()) r.ativar();

    String desde = r.getProximaCompetencia();
    String anterior = Competencia.somarMeses(desde, -1);
    // Quem não vê margem (ou o consumidor de contrato) recebe o custo nulo: mantém o custo gravado por serviço
    List<ItemEntrada> entrada = e.itens();
    if (!br.com.erp.financeiro.seguranca.Usuarios.tem(RecorrenciaDtos.PERMISSAO_MARGEM)) {
      Map<UUID, BigDecimal> custos = new java.util.HashMap<>();
      for (RecorrenciaItem i : atuais) {
        if (i.getDeletedAt() == null && i.vigenteEm(desde) && i.getCustoUnitario() != null) custos.put(i.getServicoId(), i.getCustoUnitario());
      }
      entrada = entrada.stream().map(i -> i.custoUnitario() != null || !custos.containsKey(i.servicoId()) ? i
          : new ItemEntrada(i.servicoId(), i.descricao(), i.quantidade(), i.unidade(), i.valorUnitario(), i.franquia(),
              i.valorExcedente(), custos.get(i.servicoId()), i.fabricante())).toList();
    }
    for (RecorrenciaItem i : atuais) {
      if (i.getVigenteDesde().compareTo(desde) >= 0) i.excluir(); // versão futura ainda não usada: substituída
      else if (i.vigenteEm(desde)) i.encerrarEm(anterior);
    }
    List<RecorrenciaItem> novos = salvarItens(r.getId(), entrada, desde);
    auditoria.registrar("EDITAR", ENTIDADE, r.getId(), antes, Map.of("regra", r.instantaneo(), "itens", e.itens()));
    return RegraSaida.de(r, novos);
  }

  /** Botão "gerar cobrança do ciclo" (uso de teste do job): competência do mês atual; repetir dá 409. */
  @Transactional
  public Cobranca gerarManual(UUID id) {
    Recorrencia r = obter(id);
    if (!r.isAtiva()) throw ApiException.conflito("REGRA_INATIVA", "Regra inativa não gera cobrança.");
    return gerarCiclo(r, Competencia.de(relogio.hoje()), Origem.MANUAL);
  }

  /**
   * Gera a cobrança de uma competência. Chamado na transação de quem pede (manual ou job, por regra).
   *
   * <p>Valor = itens fixos vigentes (RN-14) + excedente dos consumos ainda não faturados + adicionais
   * ainda não faturados, até a competência gerada (§26: CONTRATO + SERVIÇOS + QUANTIDADE + CONSUMO +
   * ADICIONAIS). Sem consumo nem adicional, o valor é exatamente o de antes (só os itens). A composição
   * fica em cobranca_itens; a parte variável não entra no MRR.
   */
  @Transactional
  public Cobranca gerarCiclo(Recorrencia r, String competencia, Origem origem) {
    Integer ja = jdbc.queryForObject(
        "SELECT count(*) FROM financeiro.recorrencia_geracoes WHERE recorrencia_id = ? AND competencia = ?",
        Integer.class, r.getId(), competencia);
    if (ja != null && ja > 0) {
      throw ApiException.conflito("CICLO_JA_GERADO", "A competência " + competencia + " já foi gerada para esta regra.");
    }
    List<RecorrenciaItem> vigentes = itens.daRegra(r.getId()).stream().filter(i -> i.vigenteEm(competencia)).toList();
    BigDecimal fixo = vigentes.stream().map(RecorrenciaItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (fixo.signum() <= 0) {
      throw ApiException.regra("SEM_ITENS_VIGENTES", "A regra não tem itens com valor vigentes em " + competencia + ".");
    }

    // Excedentes: consumo acima do permitido (franquia ou quantidade contratada) × preço do excedente.
    Map<UUID, RecorrenciaItem> porServico = new java.util.LinkedHashMap<>();
    vigentes.forEach(i -> porServico.putIfAbsent(i.getServicoId(), i));
    List<RecorrenciaConsumo> consumosDoCiclo = new ArrayList<>();
    List<Object[]> excedentes = new ArrayList<>(); // [item, consumo, excesso, valor]
    BigDecimal variavel = BigDecimal.ZERO;
    for (RecorrenciaConsumo consumo : consumos.aFaturar(r.getId(), competencia)) {
      RecorrenciaItem item = porServico.get(consumo.getServicoId());
      if (item == null) continue; // serviço fora da regra vigente: fica pendente para revisão
      consumosDoCiclo.add(consumo);
      BigDecimal excesso = consumo.getQuantidade().subtract(item.permitido());
      if (excesso.signum() <= 0) continue;
      BigDecimal valorExcesso = excesso.multiply(item.precoExcedente()).setScale(2, RoundingMode.HALF_EVEN);
      if (valorExcesso.signum() <= 0) continue;
      excedentes.add(new Object[] {item, consumo, excesso, valorExcesso});
      variavel = variavel.add(valorExcesso);
    }
    List<RecorrenciaAdicional> adicionaisDoCiclo = adicionais.aFaturar(r.getId(), competencia);
    for (RecorrenciaAdicional a : adicionaisDoCiclo) variavel = variavel.add(a.getValor());
    BigDecimal valor = fixo.add(variavel);

    String descricao = (r.getDescricao() != null ? r.getDescricao() : "Mensalidade")
        + " — " + Competencia.mes(competencia).getMonthValue() + "/" + Competencia.mes(competencia).getYear();
    Cobranca c = Cobranca.nova(r.getEmpresaId(), r.getContratoId(), r.getId(), competencia, descricao,
        r.vencimentoDe(competencia), valor, null, null, null, r.getFormaPagamento());
    c.definirValorVariavel(variavel);
    c.classificar(r.getCategoriaId(), r.getCentroCustoId());
    c.atribuirComercial(r.getVendedorId(), r.getSdrId(), r.getParceiroId());
    if (c.getDataVencimento().isBefore(relogio.hoje())) c.marcarAtrasada();
    cobrancaService.registrarNova(c);
    cobrancas.flush(); // a geração referencia a cobrança por FK
    jdbc.update("""
        INSERT INTO financeiro.recorrencia_geracoes
          (id, tenant_id, recorrencia_id, competencia, cobranca_id, origem, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        UUID.randomUUID(), TenantContexto.exigir(), r.getId(), competencia, c.getId(), origem.name(),
        Timestamp.from(relogio.agora()), Timestamp.from(relogio.agora()));

    // Composição (§26) e vínculo dos consumos e adicionais faturados.
    for (RecorrenciaItem i : vigentes) {
      composicao.save(new CobrancaItem(c.getId(), CobrancaItem.Tipo.FIXO, i.getServicoId(), i.getFabricante(), i.getDescricao(),
          i.getQuantidade(), i.getUnidade(), i.getValorUnitario(), i.total(), i.custoTotal()));
    }
    for (Object[] e : excedentes) {
      RecorrenciaItem i = (RecorrenciaItem) e[0];
      RecorrenciaConsumo consumo = (RecorrenciaConsumo) e[1];
      BigDecimal excesso = (BigDecimal) e[2];
      BigDecimal custo = i.getCustoUnitario() == null ? null : excesso.multiply(i.getCustoUnitario()).setScale(2, RoundingMode.HALF_EVEN);
      composicao.save(new CobrancaItem(c.getId(), CobrancaItem.Tipo.EXCEDENTE, i.getServicoId(), i.getFabricante(),
          "Excedente " + i.getDescricao() + " (" + Competencia.mes(consumo.getCompetencia()).getMonthValue() + "/"
              + Competencia.mes(consumo.getCompetencia()).getYear() + "): " + excesso.stripTrailingZeros().toPlainString()
              + " " + i.getUnidade() + " acima de " + i.permitido().stripTrailingZeros().toPlainString(),
          excesso, i.getUnidade(), i.precoExcedente(), (BigDecimal) e[3], custo));
    }
    for (RecorrenciaAdicional a : adicionaisDoCiclo) {
      composicao.save(new CobrancaItem(c.getId(), CobrancaItem.Tipo.ADICIONAL, null, null, a.getDescricao(), BigDecimal.ONE,
          "UN", a.getValor(), a.getValor(), null));
      a.faturar(c.getId());
    }
    consumosDoCiclo.forEach(x -> x.faturar(c.getId()));

    Map<String, Object> antes = r.instantaneo();
    r.avancarDepoisDe(competencia);
    Map<String, Object> resumo = new java.util.LinkedHashMap<>();
    resumo.put("competencia", competencia);
    resumo.put("cobrancaId", c.getId());
    resumo.put("valor", valor);
    resumo.put("origem", origem);
    if (variavel.signum() > 0) {
      resumo.put("valorFixo", fixo);
      resumo.put("valorVariavel", variavel);
    }
    auditoria.registrar("GERAR_CICLO", ENTIDADE, r.getId(), antes, resumo);
    cobrarCartaoSeAutomatico(r, c);
    return c;
  }

  /** Cartão recorrente (§27): pedido ao provedor e cobrança AGENDADO até o webhook confirmar. */
  private void cobrarCartaoSeAutomatico(Recorrencia r, Cobranca c) {
    if (!r.isCobrancaAutomatica() || r.getCartaoTokenRef() == null || c.getStatus() == br.com.erp.financeiro.cobrancas.StatusCobranca.ATRASADO) {
      return;
    }
    try {
      pagamentos.cobrarCartaoSalvo(c, r.getCartaoTokenRef());
      Map<String, Object> antes = c.instantaneo();
      c.agendar(c.getDataVencimento());
      auditoria.registrar("AGENDAR", CobrancaService.ENTIDADE, c.getId(), antes, c.instantaneo());
    } catch (RuntimeException e) {
      log.warn("Cartão recorrente da regra {} não cobrado: {}", r.getId(), e.getMessage());
      plataforma.timeline(c.getEmpresaId(), "financeiro.cartao.falhou",
          "Não foi possível cobrar o cartão final " + (r.getCartaoFinal() == null ? "?" : r.getCartaoFinal())
              + " na cobrança " + c.getId().toString().substring(0, 8) + "; a cobrança segue para pagamento manual.",
          "/modulos/financeiro/cobrancas/" + c.getId());
    }
  }

  /** Usado pelo job: regras ativas com competência a gerar até o mês atual. */
  @Transactional(readOnly = true)
  public List<UUID> pendentesDeGeracao() {
    return regras.ativasAte(Competencia.de(relogio.hoje())).stream().map(Recorrencia::getId).toList();
  }

  /**
   * Job, uma regra por transação: gera cada competência cujo dia de geração já chegou (recupera ciclos
   * perdidos se o serviço ficou fora). Competência já gerada (por exemplo, manualmente) só avança.
   */
  @Transactional
  public int processarRegraNoJob(UUID id) {
    Recorrencia r = regras.findById(id).orElseThrow();
    int geradas = 0;
    LocalDate hoje = relogio.hoje();
    for (int guarda = 0; guarda < 24 && r.isAtiva() && !r.dataDeGeracao(r.getProximaCompetencia()).isAfter(hoje); guarda++) {
      String competencia = r.getProximaCompetencia();
      if (r.getVigenciaFim() != null && Competencia.primeiroDia(competencia).isAfter(r.getVigenciaFim())) {
        r.encerrar(r.getVigenciaFim());
        break;
      }
      Integer ja = jdbc.queryForObject(
          "SELECT count(*) FROM financeiro.recorrencia_geracoes WHERE recorrencia_id = ? AND competencia = ?",
          Integer.class, r.getId(), competencia);
      if (ja != null && ja > 0) {
        r.avancarDepoisDe(competencia);
        continue;
      }
      gerarCiclo(r, competencia, Origem.JOB);
      geradas++;
    }
    return geradas;
  }

  /** Contrato assinado (consumidor idempotente): cria a regra inicial, se o contrato ainda não tem uma. */
  @Transactional
  public boolean criarDeContrato(UUID contratoId, RegraEntrada e) {
    if (regras.doContrato(contratoId).isPresent()) return false;
    criar(e);
    return true;
  }

  /** Contrato cancelado: a regra para de gerar e o valor entra como churn no MRR do mês (RN-43). */
  @Transactional
  public boolean encerrarDeContrato(UUID contratoId, LocalDate quando) {
    return regras.doContrato(contratoId).map(r -> {
      Map<String, Object> antes = r.instantaneo();
      r.encerrar(quando);
      auditoria.registrar("ENCERRAR", ENTIDADE, r.getId(), antes, r.instantaneo());
      return true;
    }).orElse(false);
  }

  private void configurar(Recorrencia r, RegraEntrada e) {
    r.configurar(e.clienteId(), e.contratoId(), e.descricao(),
        e.periodicidade() != null ? e.periodicidade() : Periodicidade.MENSAL,
        e.diaGeracao() != null ? e.diaGeracao() : 5,
        e.diasParaVencimento() != null ? e.diasParaVencimento() : 10,
        e.formaPagamento());
    cadastros.categoriaParaUso(e.categoriaId(), CategoriaFinanceira.Tipo.RECEITA, "categoriaId");
    cadastros.centroParaUso(e.centroCustoId(), "centroCustoId");
    r.configurarComplementos(e.vendedorId(), e.sdrId(), e.parceiroId(), e.categoriaId(), e.centroCustoId(),
        e.indiceReajuste(), e.mesReajuste());
  }

  List<RecorrenciaItem> salvarItens(UUID regra, List<ItemEntrada> entrada, String desde) {
    return itens.saveAll(entrada.stream().map(i -> new RecorrenciaItem(regra, i.servicoId(), i.descricao().trim(),
        i.quantidade(), i.unidade().trim(), i.valorUnitario(), i.franquia(), desde, i.valorExcedente(), i.custoUnitario(),
        i.fabricante())).toList());
  }
}
