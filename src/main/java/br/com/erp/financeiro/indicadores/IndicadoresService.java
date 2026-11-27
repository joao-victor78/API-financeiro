package br.com.erp.financeiro.indicadores;

import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaRepository;
import br.com.erp.financeiro.cobrancas.StatusCobranca;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.contaspagar.ContaPagarRepository;
import br.com.erp.financeiro.pagamentos.PagamentoRepository;
import br.com.erp.financeiro.recorrencias.Recorrencia;
import br.com.erp.financeiro.recorrencias.RecorrenciaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MRR decomposto e home do financeiro (RF-08). O Financeiro é o dono do cálculo (RN-45, PD-3).
 *
 * MRR de uma empresa no mês M: soma, das cobranças geradas por recorrência que cobrem M, do valor
 * (menos desconto) dividido pelos meses da periodicidade. Regra encerrada (contrato cancelado) até M
 * não conta em M — por isso o cancelamento entra como Churned no próprio mês (RF-08, critério 2).
 */
@Service
public class IndicadoresService {

  private final CobrancaRepository cobrancas;
  private final RecorrenciaRepository regras;
  private final PagamentoRepository pagamentos;
  private final ContaPagarRepository contasPagar;
  private final Relogio relogio;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final br.com.erp.financeiro.gerencial.FluxoCaixaService fluxo;

  public IndicadoresService(CobrancaRepository cobrancas, RecorrenciaRepository regras, PagamentoRepository pagamentos,
      ContaPagarRepository contasPagar, Relogio relogio, org.springframework.jdbc.core.JdbcTemplate jdbc,
      br.com.erp.financeiro.gerencial.FluxoCaixaService fluxo) {
    this.cobrancas = cobrancas;
    this.regras = regras;
    this.pagamentos = pagamentos;
    this.contasPagar = contasPagar;
    this.relogio = relogio;
    this.jdbc = jdbc;
    this.fluxo = fluxo;
  }

  /** contratos/financeiro.yaml MrrResponse, com ARR e churn (RN-47, RN-48) acrescentados. Taxas em pontos (12.5 = 12,5%). */
  public record Mrr(String periodo, BigDecimal mrrNovo, BigDecimal mrrExpansion, BigDecimal mrrContraction,
      BigDecimal mrrChurned, BigDecimal mrrNetNew, BigDecimal mrrTotal, BigDecimal mrrInicial, BigDecimal arr,
      int empresasAtivas, int empresasNoInicio, int empresasCanceladas, BigDecimal logoChurn, BigDecimal mrrChurn,
      BigDecimal revenueChurn) {}

  @Transactional(readOnly = true)
  public Mrr mrr(YearMonth periodo) {
    Base base = carregar();
    return calcular(base, periodo);
  }

  @Transactional(readOnly = true)
  public List<Mrr> historico(YearMonth ate, int meses) {
    Base base = carregar();
    List<Mrr> lista = new ArrayList<>();
    for (int i = meses - 1; i >= 0; i--) lista.add(calcular(base, ate.minusMonths(i)));
    return lista;
  }

  private record Base(List<Cobranca> recorrentes, Map<UUID, Recorrencia> regras) {}

  private Base carregar() {
    Map<UUID, Recorrencia> porId = regras.todas().stream().collect(Collectors.toMap(Recorrencia::getId, Function.identity()));
    return new Base(cobrancas.recorrentes(), porId);
  }

  private Mrr calcular(Base b, YearMonth m) {
    Map<UUID, BigDecimal> atual = porEmpresa(b, m);
    Map<UUID, BigDecimal> anterior = porEmpresa(b, m.minusMonths(1));
    BigDecimal novo = Dinheiro.ZERO, expansion = Dinheiro.ZERO, contraction = Dinheiro.ZERO, churned = Dinheiro.ZERO;
    Set<UUID> empresas = new HashSet<>(atual.keySet());
    empresas.addAll(anterior.keySet());
    int canceladas = 0;
    for (UUID e : empresas) {
      BigDecimal antes = anterior.getOrDefault(e, BigDecimal.ZERO);
      BigDecimal agora = atual.getOrDefault(e, BigDecimal.ZERO);
      if (antes.signum() == 0 && agora.signum() > 0) novo = novo.add(agora);                       // RN-43
      else if (antes.signum() > 0 && agora.signum() == 0) {
        churned = churned.add(antes);
        canceladas++;
      } else if (agora.compareTo(antes) > 0) expansion = expansion.add(agora.subtract(antes));
      else if (agora.compareTo(antes) < 0) contraction = contraction.add(antes.subtract(agora));
    }
    BigDecimal total = soma(atual.values());
    BigDecimal inicial = soma(anterior.values());
    BigDecimal netNew = novo.add(expansion).subtract(contraction).subtract(churned);                // RN-44
    int noInicio = (int) anterior.values().stream().filter(v -> v.signum() > 0).count();
    return new Mrr(m.toString(), dinheiro(novo), dinheiro(expansion), dinheiro(contraction), dinheiro(churned),
        dinheiro(netNew), dinheiro(total), dinheiro(inicial), dinheiro(total.multiply(BigDecimal.valueOf(12))),  // RN-47
        (int) atual.values().stream().filter(v -> v.signum() > 0).count(), noInicio, canceladas,
        taxa(BigDecimal.valueOf(canceladas), BigDecimal.valueOf(noInicio)),                                    // RN-48
        taxa(churned, inicial), taxa(churned.add(contraction), inicial));
  }

  private static Map<UUID, BigDecimal> porEmpresa(Base b, YearMonth m) {
    Map<UUID, BigDecimal> mapa = new HashMap<>();
    for (Cobranca c : b.recorrentes()) {
      Recorrencia r = b.regras().get(c.getRecorrenciaId());
      if (r == null) continue;
      YearMonth inicio = Competencia.mes(c.getCompetencia());
      int meses = r.getPeriodicidade().meses();
      if (m.isBefore(inicio) || !m.isBefore(inicio.plusMonths(meses))) continue;
      if (r.getVigenciaFim() != null && !YearMonth.from(r.getVigenciaFim()).isAfter(m)) continue;
      // V2: excedente de consumo e adicionais (valor_variavel) são receita não recorrente: fora do MRR (§75).
      BigDecimal mensal = c.getValor().subtract(c.getDesconto()).subtract(c.getValorVariavel())
          .divide(BigDecimal.valueOf(meses), 6, RoundingMode.HALF_EVEN);
      mapa.merge(c.getEmpresaId(), mensal, BigDecimal::add);
    }
    return mapa;
  }

  private static BigDecimal soma(Iterable<BigDecimal> valores) {
    BigDecimal s = BigDecimal.ZERO;
    for (BigDecimal v : valores) s = s.add(v);
    return s;
  }

  private static BigDecimal dinheiro(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_EVEN);
  }

  private static BigDecimal taxa(BigDecimal parte, BigDecimal todo) {
    return todo.signum() == 0 ? BigDecimal.ZERO.setScale(2)
        : parte.multiply(BigDecimal.valueOf(100)).divide(todo, 2, RoundingMode.HALF_EVEN);
  }

  /**
   * contratos/financeiro.yaml DashboardFinanceiro (RN-46), com contagens e MRR do mês. Os campos depois de
   * arr são da V2 (§74 e §111): receita, despesas, margem, fluxo dos próximos 30 dias, saldo bancário,
   * conciliações e comissões. custoServicosMes e margemBrutaMes só vêm para quem tem financeiro.margem.ver.
   */
  public record Dashboard(BigDecimal aReceberHoje, BigDecimal atrasados, BigDecimal pagamentosRecebidosMes,
      BigDecimal contasAPagarMes, BigDecimal inadimplenciaTotal, int cobrancasEmAberto, int cobrancasAtrasadas,
      BigDecimal mrrAtual, BigDecimal arr,
      BigDecimal receitaMes, BigDecimal despesasMes, BigDecimal resultadoMes, BigDecimal margemMes, BigDecimal pagoMes,
      BigDecimal entradasPrevistas30d, BigDecimal saidasPrevistas30d, BigDecimal saldoProjetado30d, BigDecimal saldoBancario,
      long conciliacoesPendentes, BigDecimal comissoesAPagar, long reajustesDoMes, BigDecimal custoServicosMes,
      BigDecimal margemBrutaMes) {}

  @Transactional(readOnly = true)
  public Dashboard dashboard() {
    LocalDate hoje = relogio.hoje();
    YearMonth mes = YearMonth.from(hoje);
    List<Cobranca> abertas = cobrancas.comStatus(StatusCobranca.EM_ABERTO);
    BigDecimal aReceberHoje = Dinheiro.ZERO, atrasados = Dinheiro.ZERO, inadimplencia = Dinheiro.ZERO;
    int qtdAtrasadas = 0;
    for (Cobranca c : abertas) {
      if (c.getDataVencimento().equals(hoje)) aReceberHoje = aReceberHoje.add(c.saldo());
      if (c.getStatus() == StatusCobranca.ATRASADO) {
        atrasados = atrasados.add(c.saldo());
        qtdAtrasadas++;
      }
      if (c.getDataVencimento().isBefore(hoje)) inadimplencia = inadimplencia.add(c.saldo());
    }
    OffsetDateTime de = mes.atDay(1).atStartOfDay(Relogio.BRASILIA).toOffsetDateTime();
    OffsetDateTime ate = mes.plusMonths(1).atDay(1).atStartOfDay(Relogio.BRASILIA).toOffsetDateTime();
    Mrr mrr = mrr(mes);

    // V2 (§74, §111)
    UUID tenant = br.com.erp.financeiro.tenant.TenantContexto.exigir();
    String comp = mes.toString();
    java.sql.Date hojeSql = java.sql.Date.valueOf(hoje);
    java.sql.Date em30 = java.sql.Date.valueOf(hoje.plusDays(30));
    BigDecimal receita = soma("""
        SELECT coalesce(sum(valor - desconto + juros + multa), 0) FROM financeiro.cobrancas
         WHERE tenant_id = ? AND deleted_at IS NULL AND status <> 'ESTORNADO' AND competencia = ?""", tenant, comp);
    BigDecimal despesas = soma("""
        SELECT coalesce(sum(valor), 0) FROM financeiro.contas_pagar
         WHERE tenant_id = ? AND deleted_at IS NULL AND status <> 'CANCELADA' AND competencia = ?""", tenant, comp);
    BigDecimal pago = soma("""
        SELECT coalesce(sum(valor_pago), 0) FROM financeiro.contas_pagar
         WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'PAGA' AND data_pagamento BETWEEN ? AND ?""",
        tenant, java.sql.Date.valueOf(mes.atDay(1)), java.sql.Date.valueOf(mes.atEndOfMonth()));
    BigDecimal entradas30 = soma("""
        SELECT coalesce(sum(valor - desconto + juros + multa - valor_pago), 0) FROM financeiro.cobrancas
         WHERE tenant_id = ? AND deleted_at IS NULL AND status IN ('ABERTO','AGENDADO','ATRASADO','PARCIAL')
           AND coalesce(CASE WHEN status = 'AGENDADO' THEN agendada_para END, data_vencimento) BETWEEN ? AND ?""", tenant, hojeSql, em30);
    BigDecimal saidas30 = soma("""
        SELECT coalesce(sum(valor), 0) FROM financeiro.contas_pagar
         WHERE tenant_id = ? AND deleted_at IS NULL AND status IN ('PENDENTE','APROVADA') AND data_vencimento BETWEEN ? AND ?""",
        tenant, hojeSql, em30);
    BigDecimal saldoBancario = null;
    Long contasAtivas = jdbc.queryForObject("SELECT count(*) FROM financeiro.contas_bancarias WHERE tenant_id = ? AND deleted_at IS NULL AND ativa",
        Long.class, tenant);
    if (contasAtivas != null && contasAtivas > 0) {
      var f = fluxo.calcular(hoje, hoje, br.com.erp.financeiro.gerencial.FluxoCaixaService.Agrupamento.DIA, null);
      saldoBancario = f.saldoInicial().add(f.totalEntradasRealizadas()).subtract(f.totalSaidasRealizadas());
    }
    Long pendentes = jdbc.queryForObject("""
        SELECT count(*) FROM financeiro.transacoes_bancarias WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'PENDENTE'""",
        Long.class, tenant);
    BigDecimal comissoes = soma("""
        SELECT coalesce(sum(valor), 0) FROM financeiro.comissoes WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'A_PAGAR'""", tenant);
    Long reajustes = jdbc.queryForObject("""
        SELECT count(*) FROM financeiro.recorrencias WHERE tenant_id = ? AND deleted_at IS NULL AND ativa AND mes_reajuste = ?""",
        Long.class, tenant, hoje.getMonthValue());
    BigDecimal custo = null;
    BigDecimal margemBruta = null;
    if (br.com.erp.financeiro.seguranca.Usuarios.tem("financeiro.margem.ver")) {
      custo = soma("""
          SELECT coalesce(sum(i.custo_total), 0) FROM financeiro.cobranca_itens i JOIN financeiro.cobrancas c ON c.id = i.cobranca_id
           WHERE c.tenant_id = ? AND c.deleted_at IS NULL AND c.status <> 'ESTORNADO' AND c.competencia = ?""", tenant, comp);
      BigDecimal recorrente = soma("""
          SELECT coalesce(sum(valor - desconto), 0) FROM financeiro.cobrancas
           WHERE tenant_id = ? AND deleted_at IS NULL AND status <> 'ESTORNADO' AND competencia = ? AND recorrencia_id IS NOT NULL""",
          tenant, comp);
      margemBruta = recorrente.signum() == 0 ? null : taxa(recorrente.subtract(custo), recorrente);
    }
    BigDecimal resultado = receita.subtract(despesas);
    return new Dashboard(dinheiro(aReceberHoje), dinheiro(atrasados), dinheiro(pagamentos.totalConfirmado(de, ate)),
        dinheiro(contasPagar.totalAPagar(mes.atDay(1), mes.atEndOfMonth())), dinheiro(inadimplencia),
        abertas.size(), qtdAtrasadas, mrr.mrrTotal(), mrr.arr(),
        dinheiro(receita), dinheiro(despesas), dinheiro(resultado), receita.signum() == 0 ? null : taxa(resultado, receita),
        dinheiro(pago), dinheiro(entradas30), dinheiro(saidas30), dinheiro(entradas30.subtract(saidas30)),
        saldoBancario == null ? null : dinheiro(saldoBancario), pendentes == null ? 0 : pendentes, dinheiro(comissoes),
        reajustes == null ? 0 : reajustes, custo == null ? null : dinheiro(custo), margemBruta);
  }

  private BigDecimal soma(String sql, Object... args) {
    BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
    return v == null ? BigDecimal.ZERO : v;
  }
}
