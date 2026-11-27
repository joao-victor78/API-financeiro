package br.com.erp.financeiro.comissoes;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cadastros.CategoriaFinanceira;
import br.com.erp.financeiro.cadastros.CategoriaRepository;
import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaItem;
import br.com.erp.financeiro.cobrancas.CobrancaItemRepository;
import br.com.erp.financeiro.cobrancas.EventosInternos;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.contaspagar.ContaPagar;
import br.com.erp.financeiro.contaspagar.ContaPagarDtos.ContaPagarEntrada;
import br.com.erp.financeiro.contaspagar.ContaPagarService;
import br.com.erp.financeiro.eventos.PedidosPlataforma;
import br.com.erp.financeiro.pagamentos.Pagamento;
import br.com.erp.financeiro.recorrencias.Recorrencia;
import br.com.erp.financeiro.recorrencias.RecorrenciaRepository;
import br.com.erp.financeiro.tenant.TenantContexto;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comissões (§71). Apuradas quando o pagamento é confirmado (webhook ou baixa manual), na mesma
 * transação; canceladas no estorno. Para cada beneficiário da cobrança (vendedor, SDR, parceiro) vale
 * a regra mais específica (beneficiário > serviço > fabricante), desempate pela prioridade (menor
 * primeiro). Regra com serviço/fabricante só incide sobre a parte da cobrança daqueles itens.
 */
@Service
public class ComissaoService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComissaoService.class);
  private static final BigDecimal CEM = BigDecimal.valueOf(100);

  private final RegraComissaoRepository regras;
  private final ComissaoRepository comissoes;
  private final CobrancaItemRepository itens;
  private final RecorrenciaRepository recorrencias;
  private final CategoriaRepository categorias;
  private final ContaPagarService contasPagar;
  private final AuditoriaService auditoria;
  private final PedidosPlataforma plataforma;
  private final JdbcTemplate jdbc;
  private final Relogio relogio;

  public ComissaoService(RegraComissaoRepository regras, ComissaoRepository comissoes, CobrancaItemRepository itens,
      RecorrenciaRepository recorrencias, CategoriaRepository categorias, ContaPagarService contasPagar,
      AuditoriaService auditoria, PedidosPlataforma plataforma, JdbcTemplate jdbc, Relogio relogio) {
    this.regras = regras;
    this.comissoes = comissoes;
    this.itens = itens;
    this.recorrencias = recorrencias;
    this.categorias = categorias;
    this.contasPagar = contasPagar;
    this.auditoria = auditoria;
    this.plataforma = plataforma;
    this.jdbc = jdbc;
    this.relogio = relogio;
  }

  public record RegraEntrada(
      @NotBlank(message = "obrigatório") @Size(max = 120, message = "até 120 caracteres") String nome,
      @NotNull(message = "obrigatório") RegraComissao.Beneficiario beneficiarioTipo,
      UUID beneficiarioId,
      UUID servicoId,
      @Size(max = 80, message = "até 80 caracteres") String fabricante,
      @NotNull(message = "obrigatória") RegraComissao.Base base,
      @NotNull(message = "obrigatório") @Positive(message = "deve ser maior que zero")
      @Digits(integer = 11, fraction = 4, message = "no máximo 4 casas decimais") BigDecimal valor,
      @Min(value = 1, message = "entre 1 e 120") @Max(value = 120, message = "entre 1 e 120") Integer mesesRecorrencia,
      @Min(value = 0, message = "0 a 9999") @Max(value = 9999, message = "0 a 9999") Integer prioridade,
      Boolean ativa,
      LocalDate vigenciaInicio,
      LocalDate vigenciaFim) {}

  public record RegraSaida(UUID id, String nome, RegraComissao.Beneficiario beneficiarioTipo, UUID beneficiarioId, UUID servicoId,
      String fabricante, RegraComissao.Base base, BigDecimal valor, Integer mesesRecorrencia, int prioridade, boolean ativa,
      LocalDate vigenciaInicio, LocalDate vigenciaFim) {
    static RegraSaida de(RegraComissao r) {
      return new RegraSaida(r.getId(), r.getNome(), r.getBeneficiarioTipo(), r.getBeneficiarioId(), r.getServicoId(),
          r.getFabricante(), r.getBase(), r.getValor(), r.getMesesRecorrencia(), r.getPrioridade(), r.isAtiva(),
          r.getVigenciaInicio(), r.getVigenciaFim());
    }
  }

  public record ComissaoSaida(UUID id, UUID regraId, String regra, RegraComissao.Beneficiario beneficiarioTipo, UUID beneficiarioId,
      UUID cobrancaId, UUID pagamentoId, UUID empresaId, LocalDate competencia, BigDecimal baseCalculo, BigDecimal percentual,
      BigDecimal valor, Comissao.Status status, UUID contaPagarId, OffsetDateTime createdAt) {}

  public record Fechamento(LocalDate competencia, int beneficiarios, int comissoes, BigDecimal total, List<UUID> contasPagar) {}

  // ------------------------------------------------------------------ regras

  @Transactional(readOnly = true)
  public List<RegraSaida> regras() {
    return regras.ordenadas().stream().map(RegraSaida::de).toList();
  }

  @Transactional
  public RegraSaida salvarRegra(UUID id, RegraEntrada e) {
    if (e.base() != RegraComissao.Base.FIXA && e.valor().compareTo(CEM) > 0) {
      throw ApiException.campo("valor", "PERCENTUAL_INVALIDO", "Nas bases percentuais o valor é um % de até 100.");
    }
    if (e.vigenciaInicio() != null && e.vigenciaFim() != null && e.vigenciaFim().isBefore(e.vigenciaInicio())) {
      throw ApiException.campo("vigenciaFim", "PERIODO_INVALIDO", "O fim da vigência é anterior ao início.");
    }
    RegraComissao r = id == null ? new RegraComissao(e.nome()) : Tenant.ativoDoTenant(regras.findById(id), "Regra de comissão");
    Map<String, Object> antes = id == null ? null : r.instantaneo();
    r.configurar(e.nome().trim(), e.beneficiarioTipo(), e.beneficiarioId(), e.servicoId(),
        e.fabricante() == null || e.fabricante().isBlank() ? null : e.fabricante().trim(), e.base(), e.valor(), e.mesesRecorrencia(),
        e.prioridade() == null ? 100 : e.prioridade(), e.ativa() == null || e.ativa(), e.vigenciaInicio(), e.vigenciaFim());
    regras.save(r);
    auditoria.registrar(id == null ? "CRIAR" : "EDITAR", "regra_comissao", r.getId(), antes, r.instantaneo());
    return RegraSaida.de(r);
  }

  @Transactional
  public void excluirRegra(UUID id) {
    RegraComissao r = Tenant.ativoDoTenant(regras.findById(id), "Regra de comissão");
    Map<String, Object> antes = r.instantaneo();
    r.excluir();
    auditoria.registrar("EXCLUIR", "regra_comissao", r.getId(), antes, null);
  }

  // ------------------------------------------------------------------ apuração

  @EventListener
  public void aoConfirmarPagamento(EventosInternos.PagamentoConfirmado e) {
    Cobranca c = e.cobranca();
    Pagamento p = e.pagamento();
    LocalDate dia = p.getConfirmadoEm() == null ? relogio.hoje() : p.getConfirmadoEm().atZoneSameInstant(Relogio.BRASILIA).toLocalDate();
    Map<RegraComissao.Beneficiario, UUID> beneficiarios = new LinkedHashMap<>();
    if (c.getVendedorId() != null) beneficiarios.put(RegraComissao.Beneficiario.VENDEDOR, c.getVendedorId());
    if (c.getSdrId() != null) beneficiarios.put(RegraComissao.Beneficiario.SDR, c.getSdrId());
    if (c.getParceiroId() != null) beneficiarios.put(RegraComissao.Beneficiario.PARCEIRO, c.getParceiroId());
    if (beneficiarios.isEmpty()) return;
    List<CobrancaItem> composicao = itens.daCobranca(c.getId());
    for (var b : beneficiarios.entrySet()) {
      Optional<RegraComissao> regra = regras.ativasDoTipo(b.getKey()).stream()
          .filter(r -> r.vigenteEm(dia))
          .filter(r -> r.getBeneficiarioId() == null || r.getBeneficiarioId().equals(b.getValue()))
          .filter(r -> !r.filtraItens() || composicao.stream().anyMatch(i -> casa(r, i)))
          .min(Comparator.comparingInt(RegraComissao::especificidade).reversed().thenComparingInt(RegraComissao::getPrioridade));
      // Erro de regra de comissão não pode desfazer o recebimento do cliente.
      regra.ifPresent(r -> {
        try {
          apurar(r, b.getKey(), b.getValue(), c, p, composicao, dia);
        } catch (RuntimeException ex) {
          log.error("Comissão da regra {} não apurada para o pagamento {}: {}", r.getId(), p.getId(), ex.getMessage(), ex);
        }
      });
    }
  }

  private void apurar(RegraComissao r, RegraComissao.Beneficiario tipo, UUID beneficiario, Cobranca c, Pagamento p,
      List<CobrancaItem> composicao, LocalDate dia) {
    BigDecimal devido = c.totalDevido();
    if (devido.signum() <= 0) return;
    BigDecimal proporcao = p.getValor().divide(devido, 8, RoundingMode.HALF_EVEN).min(BigDecimal.ONE);
    BigDecimal totalItens = composicao.stream().map(CobrancaItem::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal parte = BigDecimal.ONE;
    if (r.filtraItens() && totalItens.signum() > 0) {
      BigDecimal casados = composicao.stream().filter(i -> casa(r, i)).map(CobrancaItem::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
      parte = casados.divide(totalItens, 8, RoundingMode.HALF_EVEN);
    }
    boolean recorrente = c.getRecorrenciaId() != null;
    List<UUID> cobrancasDaVenda = recorrente ? cobrancasDaRecorrencia(c.getRecorrenciaId()) : List.of(c.getId());
    boolean primeira = recorrente && !cobrancasDaVenda.isEmpty() && cobrancasDaVenda.get(0).equals(c.getId());
    long jaApuradas = comissoes.jaPagasNasCobrancas(r.getId(), beneficiario, cobrancasDaVenda);

    BigDecimal base;
    switch (r.getBase()) {
      case FIXA -> {
        if (jaApuradas > 0) return; // uma vez por venda
        base = devido;
      }
      case PERCENTUAL -> {
        if (recorrente) return;
        base = p.getValor().multiply(parte);
      }
      case SOBRE_PRIMEIRA_MENSALIDADE -> {
        if (!primeira) return;
        base = p.getValor().multiply(parte);
      }
      case SOBRE_MRR -> {
        if (!recorrente || jaApuradas > 0) return;
        int meses = recorrencias.findById(c.getRecorrenciaId()).map(Recorrencia::mesesDoCiclo).orElse(1);
        base = c.getValor().subtract(c.getDesconto()).subtract(c.getValorVariavel())
            .divide(BigDecimal.valueOf(meses), 8, RoundingMode.HALF_EVEN).multiply(parte);
      }
      case SOBRE_MARGEM -> {
        BigDecimal custo = composicao.stream().filter(i -> !r.filtraItens() || casa(r, i)).map(CobrancaItem::getCustoTotal)
            .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(proporcao);
        base = p.getValor().multiply(parte).subtract(custo);
        if (base.signum() <= 0) return;
      }
      case RECORRENTE -> {
        if (!recorrente) return;
        if (r.getMesesRecorrencia() != null) {
          String primeiraComp = jdbc.queryForObject("SELECT min(competencia) FROM financeiro.recorrencia_geracoes WHERE recorrencia_id = ?",
              String.class, c.getRecorrenciaId());
          if (primeiraComp != null) {
            long meses = YearMonth.parse(primeiraComp).until(YearMonth.parse(c.getCompetencia()), java.time.temporal.ChronoUnit.MONTHS);
            if (meses >= r.getMesesRecorrencia()) return;
          }
        }
        base = p.getValor().multiply(parte);
      }
      default -> {
        return;
      }
    }
    BigDecimal pct = r.getBase() == RegraComissao.Base.FIXA ? null : r.getValor();
    BigDecimal valor = r.getBase() == RegraComissao.Base.FIXA ? r.getValor()
        : base.multiply(r.getValor()).divide(CEM, 2, RoundingMode.HALF_EVEN);
    if (valor.signum() <= 0) return;
    Comissao k = comissoes.save(new Comissao(r.getId(), tipo, beneficiario, c.getId(), p.getId(), c.getEmpresaId(),
        Competencia.de(dia), base.setScale(2, RoundingMode.HALF_EVEN), pct, valor));
    auditoria.registrar("APURAR_COMISSAO", "comissao", k.getId(), null, k.instantaneo());
    plataforma.notificacao(beneficiario, "COMISSAO", "Comissão apurada", Dinheiro.brl(valor) + " (" + r.getNome()
        + ") sobre o pagamento da cobrança " + c.getId().toString().substring(0, 8) + ".", "/modulos/financeiro/comissoes");
  }

  /** Estorno: a comissão ainda não lançada cai; a já lançada em contas a pagar fica, com aviso na timeline. */
  @EventListener
  public void aoEstornarPagamento(EventosInternos.PagamentoEstornado e) {
    for (Comissao k : comissoes.doPagamento(e.pagamento().getId())) {
      if (k.getStatus() == Comissao.Status.A_PAGAR) {
        Map<String, Object> antes = k.instantaneo();
        k.cancelar();
        auditoria.registrar("CANCELAR_COMISSAO", "comissao", k.getId(), antes, k.instantaneo());
      } else if (k.getStatus() == Comissao.Status.LANCADA) {
        plataforma.timeline(e.cobranca().getEmpresaId(), "financeiro.comissao.estorno",
            "Pagamento estornado com comissão já lançada em contas a pagar (" + Dinheiro.brl(k.getValor())
                + "): revise o desconto na próxima comissão.", "/modulos/financeiro/comissoes");
      }
    }
  }

  private static boolean casa(RegraComissao r, CobrancaItem i) {
    if (r.getServicoId() != null && !r.getServicoId().equals(i.getServicoId())) return false;
    return r.getFabricante() == null || (i.getFabricante() != null && r.getFabricante().equalsIgnoreCase(i.getFabricante()));
  }

  /** Cobranças da recorrência em ordem de competência (a primeira é a "primeira mensalidade"). */
  private List<UUID> cobrancasDaRecorrencia(UUID recorrencia) {
    return jdbc.queryForList("""
        SELECT cobranca_id FROM financeiro.recorrencia_geracoes WHERE recorrencia_id = ? ORDER BY competencia""", UUID.class, recorrencia);
  }

  // ------------------------------------------------------------------ consulta e fechamento

  @Transactional(readOnly = true)
  public Pagina<ComissaoSaida> listar(LocalDate competencia, RegraComissao.Beneficiario tipo, UUID beneficiario, Comissao.Status status,
      Pageable pagina) {
    Specification<Comissao> s = (r, q, cb) -> cb.isNull(r.get("deletedAt"));
    if (competencia != null) s = s.and((r, q, cb) -> cb.equal(r.get("competencia"), Competencia.de(competencia)));
    if (tipo != null) s = s.and((r, q, cb) -> cb.equal(r.get("beneficiarioTipo"), tipo));
    if (beneficiario != null) s = s.and((r, q, cb) -> cb.equal(r.get("beneficiarioId"), beneficiario));
    if (status != null) s = s.and((r, q, cb) -> cb.equal(r.get("status"), status));
    Map<UUID, String> nomes = new LinkedHashMap<>();
    regras.ordenadas().forEach(r -> nomes.put(r.getId(), r.getNome()));
    return Pagina.de(comissoes.findAll(s, pagina), k -> new ComissaoSaida(k.getId(), k.getRegraId(), nomes.get(k.getRegraId()),
        k.getBeneficiarioTipo(), k.getBeneficiarioId(), k.getCobrancaId(), k.getPagamentoId(), k.getEmpresaId(),
        Competencia.primeiroDia(k.getCompetencia()), k.getBaseCalculo(), k.getPercentual(), k.getValor(), k.getStatus(),
        k.getContaPagarId(), k.getCreatedAt()));
  }

  @Transactional(readOnly = true)
  public BigDecimal totalAPagar() {
    return jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM financeiro.comissoes WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'A_PAGAR'",
        BigDecimal.class, TenantContexto.exigir());
  }

  /**
   * Fecha as comissões A_PAGAR da competência: uma conta a pagar por beneficiário (PENDENTE, passa pela
   * aprovação como qualquer despesa, RN-11), na categoria "Comissões" quando ela existe.
   */
  @Transactional
  public Fechamento fechar(LocalDate competencia) {
    String comp = Competencia.de(competencia);
    List<Comissao> lista = comissoes.aPagarNaCompetencia(comp);
    if (lista.isEmpty()) throw ApiException.regra("SEM_COMISSOES", "Não há comissões a pagar na competência.");
    UUID categoria = categorias.porNome(CategoriaFinanceira.Tipo.DESPESA, "Comissões").map(CategoriaFinanceira::getId).orElse(null);
    Map<String, List<Comissao>> porBeneficiario = new LinkedHashMap<>();
    for (Comissao k : lista) porBeneficiario.computeIfAbsent(k.getBeneficiarioTipo() + ":" + k.getBeneficiarioId(), x -> new ArrayList<>()).add(k);
    List<UUID> contas = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    LocalDate vencimento = YearMonth.parse(comp).plusMonths(1).atDay(10);
    for (List<Comissao> grupo : porBeneficiario.values()) {
      Comissao primeira = grupo.get(0);
      BigDecimal soma = grupo.stream().map(Comissao::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
      String nome = "Comissão " + primeira.getBeneficiarioTipo().name().toLowerCase() + " " + primeira.getBeneficiarioId().toString().substring(0, 8);
      ContaPagar cp = contasPagar.lancar(new ContaPagarEntrada(nome, null, null, null, competencia.withDayOfMonth(1), vencimento,
          Dinheiro.de(soma), "COM-" + comp, grupo.size() + " comissão(ões) de " + comp + "; beneficiário " + primeira.getBeneficiarioId(),
          categoria, null, null, null));
      for (Comissao k : grupo) {
        Map<String, Object> antes = k.instantaneo();
        k.lancar(cp.getId());
        auditoria.registrar("LANCAR_COMISSAO", "comissao", k.getId(), antes, k.instantaneo());
      }
      contas.add(cp.getId());
      total = total.add(soma);
    }
    return new Fechamento(competencia.withDayOfMonth(1), porBeneficiario.size(), lista.size(), Dinheiro.de(total), contas);
  }

  @Transactional
  public void cancelar(UUID id) {
    Comissao k = Tenant.ativoDoTenant(comissoes.findById(id), "Comissão");
    if (k.getStatus() != Comissao.Status.A_PAGAR) throw ApiException.conflito("COMISSAO_NAO_CANCELAVEL", "Só comissão a pagar pode ser cancelada.");
    Map<String, Object> antes = k.instantaneo();
    k.cancelar();
    auditoria.registrar("CANCELAR_COMISSAO", "comissao", k.getId(), antes, k.instantaneo());
  }
}
