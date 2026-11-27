package br.com.erp.financeiro.gerencial;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.recorrencias.Recorrencia;
import br.com.erp.financeiro.recorrencias.RecorrenciaItem;
import br.com.erp.financeiro.recorrencias.RecorrenciaItemRepository;
import br.com.erp.financeiro.recorrencias.RecorrenciaRepository;
import br.com.erp.financeiro.tenant.TenantContexto;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fluxo de caixa (§23, §74): realizado (o que entrou e saiu) e previsto (o que vai entrar e sair),
 * por dia, semana ou mês, com saldo acumulado a partir do saldo inicial das contas bancárias.
 *
 * <ul>
 *   <li>Entradas realizadas: pagamentos confirmados (data da confirmação); estorno sai na data do estorno.</li>
 *   <li>Saídas realizadas: contas a pagar pagas (data do pagamento).</li>
 *   <li>Outros realizados: tarifas, juros, estornos e outros lançamentos do extrato classificados na
 *       conciliação sem documento no sistema (transferência entre contas fica de fora).</li>
 *   <li>Entradas previstas: saldo das cobranças em aberto (vencimento ou data agendada) e os próximos
 *       ciclos das recorrências ativas ainda não gerados (só na visão sem filtro de conta).</li>
 *   <li>Saídas previstas: contas a pagar pendentes ou aprovadas (vencimento).</li>
 * </ul>
 * O que já venceu e não foi pago aparece à parte (vencidoAReceber, vencidoAPagar), sem entrar no saldo.
 */
@Service
public class FluxoCaixaService {

  public enum Agrupamento { DIA, SEMANA, MES }

  public record Periodo(String periodo, LocalDate inicio, LocalDate fim, BigDecimal entradasRealizadas,
      BigDecimal saidasRealizadas, BigDecimal entradasPrevistas, BigDecimal saidasPrevistas, BigDecimal saldoDoPeriodo,
      BigDecimal saldoAcumulado) {}

  public record Fluxo(LocalDate de, LocalDate ate, Agrupamento agrupamento, UUID contaBancariaId, BigDecimal saldoInicial,
      List<Periodo> periodos, BigDecimal totalEntradasRealizadas, BigDecimal totalSaidasRealizadas,
      BigDecimal totalEntradasPrevistas, BigDecimal totalSaidasPrevistas, BigDecimal saldoFinal,
      BigDecimal vencidoAReceber, BigDecimal vencidoAPagar, BigDecimal recorrenciasProjetadas) {}

  private final JdbcTemplate jdbc;
  private final Relogio relogio;
  private final RecorrenciaRepository regras;
  private final RecorrenciaItemRepository itens;

  public FluxoCaixaService(JdbcTemplate jdbc, Relogio relogio, RecorrenciaRepository regras, RecorrenciaItemRepository itens) {
    this.jdbc = jdbc;
    this.relogio = relogio;
    this.regras = regras;
    this.itens = itens;
  }

  record Movimento(LocalDate data, BigDecimal valor, boolean previsto) {}

  @Transactional(readOnly = true)
  public Fluxo calcular(LocalDate de, LocalDate ate, Agrupamento agrupamento, UUID conta) {
    if (ate.isBefore(de)) throw ApiException.campo("ate", "PERIODO_INVALIDO", "A data final é anterior à inicial.");
    if (ChronoUnit.DAYS.between(de, ate) > 731) throw ApiException.campo("ate", "PERIODO_LONGO", "Até 2 anos por consulta.");
    UUID tenant = TenantContexto.exigir();
    LocalDate hoje = relogio.hoje();

    // saldo inicial: contas bancárias + realizado entre a data do saldo inicial e a véspera do período
    List<Object[]> contas = conta == null
        ? jdbc.query("SELECT saldo_inicial, data_saldo_inicial FROM financeiro.contas_bancarias WHERE tenant_id = ? AND deleted_at IS NULL AND ativa",
            (rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getDate(2).toLocalDate()}, tenant)
        : jdbc.query("SELECT saldo_inicial, data_saldo_inicial FROM financeiro.contas_bancarias WHERE tenant_id = ? AND deleted_at IS NULL AND id = ?",
            (rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getDate(2).toLocalDate()}, tenant, conta);
    BigDecimal saldoInicial = contas.stream().map(o -> (BigDecimal) o[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
    LocalDate inicioHistorico = contas.stream().map(o -> (LocalDate) o[1]).min(LocalDate::compareTo).orElse(de);
    if (inicioHistorico.isBefore(de)) {
      for (Movimento m : realizados(tenant, inicioHistorico, de.minusDays(1), conta)) saldoInicial = saldoInicial.add(m.valor());
    }

    List<Movimento> movimentos = new ArrayList<>(realizados(tenant, de, ate, conta));
    BigDecimal vencidoReceber = BigDecimal.ZERO;
    BigDecimal vencidoPagar = BigDecimal.ZERO;
    BigDecimal projetado = BigDecimal.ZERO;
    if (!ate.isBefore(hoje)) {
      LocalDate inicioPrevisto = de.isAfter(hoje) ? de : hoje;
      if (conta == null) {
        for (Object[] r : jdbc.query("""
            SELECT coalesce(CASE WHEN status = 'AGENDADO' THEN agendada_para END, data_vencimento) AS dia,
                   valor - desconto + juros + multa - valor_pago AS saldo
              FROM financeiro.cobrancas
             WHERE tenant_id = ? AND deleted_at IS NULL AND status IN ('ABERTO','AGENDADO','ATRASADO','PARCIAL')""",
            (rs, i) -> new Object[] {rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)}, tenant)) {
          LocalDate dia = (LocalDate) r[0];
          BigDecimal saldo = (BigDecimal) r[1];
          if (saldo.signum() <= 0) continue;
          if (dia.isBefore(hoje)) vencidoReceber = vencidoReceber.add(saldo);
          else if (!dia.isBefore(inicioPrevisto) && !dia.isAfter(ate)) movimentos.add(new Movimento(dia, saldo, true));
        }
      }
      String sqlPagar = "SELECT data_vencimento, valor FROM financeiro.contas_pagar WHERE tenant_id = ? AND deleted_at IS NULL "
          + "AND status IN ('PENDENTE','APROVADA')" + (conta == null ? "" : " AND conta_bancaria_id = ?");
      Object[] argsPagar = conta == null ? new Object[] {tenant} : new Object[] {tenant, conta};
      for (Object[] r : jdbc.query(sqlPagar, (rs, i) -> new Object[] {rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)}, argsPagar)) {
        LocalDate dia = (LocalDate) r[0];
        BigDecimal valor = (BigDecimal) r[1];
        if (dia.isBefore(hoje)) vencidoPagar = vencidoPagar.add(valor);
        else if (!dia.isBefore(inicioPrevisto) && !dia.isAfter(ate)) movimentos.add(new Movimento(dia, valor.negate(), true));
      }
      if (conta == null) {
        for (Movimento m : recorrenciasFuturas(inicioPrevisto, ate)) {
          movimentos.add(m);
          projetado = projetado.add(m.valor());
        }
      }
    }

    TreeMap<LocalDate, BigDecimal[]> baldes = new TreeMap<>();
    for (LocalDate d = inicioDoBalde(de, agrupamento); !d.isAfter(ate); d = proximo(d, agrupamento)) {
      baldes.put(d, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
    }
    for (Movimento m : movimentos) {
      BigDecimal[] b = baldes.get(inicioDoBalde(m.data(), agrupamento));
      if (b == null) continue;
      int i = (m.previsto() ? 2 : 0) + (m.valor().signum() >= 0 ? 0 : 1);
      b[i] = b[i].add(m.valor().abs());
    }
    List<Periodo> periodos = new ArrayList<>();
    BigDecimal acumulado = saldoInicial;
    BigDecimal[] totais = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    for (var e : baldes.entrySet()) {
      BigDecimal[] b = e.getValue();
      BigDecimal saldo = b[0].subtract(b[1]).add(b[2]).subtract(b[3]);
      acumulado = acumulado.add(saldo);
      for (int i = 0; i < 4; i++) totais[i] = totais[i].add(b[i]);
      LocalDate fim = proximo(e.getKey(), agrupamento).minusDays(1);
      periodos.add(new Periodo(rotulo(e.getKey(), agrupamento), e.getKey().isBefore(de) ? de : e.getKey(), fim.isAfter(ate) ? ate : fim,
          Dinheiro.de(b[0]), Dinheiro.de(b[1]), Dinheiro.de(b[2]), Dinheiro.de(b[3]), Dinheiro.de(saldo), Dinheiro.de(acumulado)));
    }
    return new Fluxo(de, ate, agrupamento, conta, Dinheiro.de(saldoInicial), periodos, Dinheiro.de(totais[0]), Dinheiro.de(totais[1]),
        Dinheiro.de(totais[2]), Dinheiro.de(totais[3]), Dinheiro.de(acumulado), Dinheiro.de(vencidoReceber), Dinheiro.de(vencidoPagar),
        Dinheiro.de(projetado));
  }

  /** Ciclos futuros das regras ativas (valor dos itens vigentes em cada competência), pelo vencimento. */
  List<Movimento> recorrenciasFuturas(LocalDate de, LocalDate ate) {
    List<Movimento> l = new ArrayList<>();
    for (Recorrencia r : regras.todas()) {
      if (!r.isAtiva()) continue;
      List<RecorrenciaItem> todos = itens.daRegra(r.getId());
      String comp = r.getProximaCompetencia();
      for (int guarda = 0; guarda < 36; guarda++) {
        LocalDate venc = r.vencimentoDe(comp);
        if (venc.isAfter(ate)) break;
        if (r.getVigenciaFim() != null && Competencia.primeiroDia(comp).isAfter(r.getVigenciaFim())) break;
        final String c = comp;
        BigDecimal valor = todos.stream().filter(i -> i.vigenteEm(c)).map(RecorrenciaItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (valor.signum() > 0 && !venc.isBefore(de)) l.add(new Movimento(venc, valor, true));
        comp = Competencia.somarMeses(comp, r.getPeriodicidade().meses());
      }
    }
    return l;
  }

  /** Entradas (+) e saídas (−) realizadas entre as datas, opcionalmente de uma conta. */
  List<Movimento> realizados(UUID tenant, LocalDate de, LocalDate ate, UUID conta) {
    List<Movimento> l = new ArrayList<>();
    String filtro = conta == null ? "" : " AND conta_bancaria_id = ?";
    Object[] args = conta == null ? new Object[] {tenant, Date.valueOf(de), Date.valueOf(ate)}
        : new Object[] {tenant, Date.valueOf(de), Date.valueOf(ate), conta};
    l.addAll(jdbc.query("""
        SELECT (confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date, valor FROM financeiro.pagamentos
         WHERE tenant_id = ? AND status IN ('CONFIRMADO','ESTORNADO') AND confirmado_em IS NOT NULL
           AND (confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?""" + filtro,
        (rs, i) -> new Movimento(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), false), args));
    l.addAll(jdbc.query("""
        SELECT (coalesce(estornado_em, updated_at) AT TIME ZONE 'America/Sao_Paulo')::date, coalesce(valor_estornado, valor)
          FROM financeiro.pagamentos
         WHERE tenant_id = ? AND status = 'ESTORNADO' AND confirmado_em IS NOT NULL
           AND (coalesce(estornado_em, updated_at) AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?""" + filtro,
        (rs, i) -> new Movimento(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2).negate(), false), args));
    l.addAll(jdbc.query("""
        SELECT data_pagamento, valor_pago FROM financeiro.contas_pagar
         WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'PAGA' AND data_pagamento BETWEEN ? AND ?""" + filtro,
        (rs, i) -> new Movimento(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2).negate(), false), args));
    l.addAll(jdbc.query("""
        SELECT data, valor FROM financeiro.transacoes_bancarias
         WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'CONCILIADA' AND pagamento_id IS NULL AND conta_pagar_id IS NULL
           AND classificacao IN ('TARIFA','JUROS','ESTORNO','OUTRO') AND data BETWEEN ? AND ?""" + filtro,
        (rs, i) -> new Movimento(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), false), args));
    return l;
  }

  static LocalDate inicioDoBalde(LocalDate d, Agrupamento a) {
    return switch (a) {
      case DIA -> d;
      case SEMANA -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      case MES -> d.withDayOfMonth(1);
    };
  }

  static LocalDate proximo(LocalDate d, Agrupamento a) {
    return switch (a) {
      case DIA -> d.plusDays(1);
      case SEMANA -> d.plusWeeks(1);
      case MES -> d.plusMonths(1);
    };
  }

  static String rotulo(LocalDate d, Agrupamento a) {
    return switch (a) {
      case DIA -> d.toString();
      case SEMANA -> "Semana de " + d;
      case MES -> YearMonth.from(d).toString();
    };
  }
}
