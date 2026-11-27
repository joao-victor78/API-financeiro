package br.com.erp.financeiro.gerencial;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.cadastros.CategoriaFinanceira.GrupoDre;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.tenant.TenantContexto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DRE gerencial (§23), por mês, em regime de competência ou de caixa. Não substitui o DRE contábil
 * (§33): é a visão de gestão a partir dos lançamentos do módulo. Despesas aparecem com sinal negativo.
 *
 * <p>Origem de cada linha:
 * <ul>
 *   <li>Receitas: cobranças (competência: valor bruto pela competência; caixa: pagamentos recebidos no mês,
 *       menos estornos), no grupo da categoria da cobrança — sem categoria, Receita bruta.</li>
 *   <li>Deduções: descontos concedidos (competência) e despesas de categoria do grupo Deduções.</li>
 *   <li>Receitas financeiras (competência): juros e multas cobrados.</li>
 *   <li>Despesas: contas a pagar (competência: valor pela competência; caixa: valor pago no mês), no grupo
 *       da categoria — sem categoria, Despesas operacionais.</li>
 *   <li>Extrato: tarifas, juros e outros lançamentos conciliados sem documento (pela data).</li>
 *   <li>Comissões a pagar ainda não lançadas em contas a pagar (competência), em Despesas comerciais.</li>
 * </ul>
 */
@Service
public class DreService {

  public enum Regime { COMPETENCIA, CAIXA }

  public record Linha(String codigo, String titulo, String tipo, List<BigDecimal> valores, BigDecimal total,
      BigDecimal percentualDaReceita) {}

  public record Dre(String de, String ate, Regime regime, List<String> meses, List<Linha> linhas, BigDecimal receitaLiquida,
      BigDecimal lucroBruto, BigDecimal resultadoOperacional, BigDecimal resultadoLiquido, BigDecimal margemBruta,
      BigDecimal margemLiquida) {}

  private record Lancamento(int mes, GrupoDre grupo, String categoria, BigDecimal valor) {}

  private static final Map<GrupoDre, String> TITULOS = new EnumMap<>(Map.of(
      GrupoDre.RECEITA_BRUTA, "Receita bruta",
      GrupoDre.DEDUCOES, "(−) Deduções e impostos sobre a receita",
      GrupoDre.CUSTOS, "(−) Custos dos serviços",
      GrupoDre.DESPESAS_OPERACIONAIS, "(−) Despesas operacionais",
      GrupoDre.DESPESAS_ADMINISTRATIVAS, "(−) Despesas administrativas",
      GrupoDre.DESPESAS_COMERCIAIS, "(−) Despesas comerciais",
      GrupoDre.RECEITAS_FINANCEIRAS, "(+) Receitas financeiras",
      GrupoDre.DESPESAS_FINANCEIRAS, "(−) Despesas financeiras",
      GrupoDre.IMPOSTOS_SOBRE_LUCRO, "(−) IRPJ e CSLL",
      GrupoDre.OUTRAS, "(±) Outras receitas e despesas"));

  private final JdbcTemplate jdbc;

  public DreService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Dre calcular(YearMonth de, YearMonth ate, Regime regime) {
    if (ate.isBefore(de)) throw ApiException.campo("ate", "PERIODO_INVALIDO", "O mês final é anterior ao inicial.");
    int n = (int) (de.until(ate, java.time.temporal.ChronoUnit.MONTHS) + 1);
    if (n > 24) throw ApiException.campo("ate", "PERIODO_LONGO", "Até 24 meses por consulta.");
    List<String> meses = new ArrayList<>();
    for (int i = 0; i < n; i++) meses.add(de.plusMonths(i).toString());
    List<Lancamento> l = regime == Regime.COMPETENCIA ? competencia(de, ate) : caixa(de, ate);
    l.addAll(extrato(de, ate, meses));

    // agrega por grupo e por categoria dentro do grupo
    Map<GrupoDre, BigDecimal[]> porGrupo = new EnumMap<>(GrupoDre.class);
    Map<GrupoDre, Map<String, BigDecimal[]>> porCategoria = new EnumMap<>(GrupoDre.class);
    for (GrupoDre g : GrupoDre.values()) {
      porGrupo.put(g, zeros(n));
      porCategoria.put(g, new LinkedHashMap<>());
    }
    for (Lancamento x : l) {
      if (x.mes() < 0 || x.mes() >= n) continue;
      porGrupo.get(x.grupo())[x.mes()] = porGrupo.get(x.grupo())[x.mes()].add(x.valor());
      BigDecimal[] c = porCategoria.get(x.grupo()).computeIfAbsent(x.categoria(), k -> zeros(n));
      c[x.mes()] = c[x.mes()].add(x.valor());
    }
    BigDecimal[] receitaLiquida = somar(n, porGrupo.get(GrupoDre.RECEITA_BRUTA), porGrupo.get(GrupoDre.DEDUCOES));
    BigDecimal[] lucroBruto = somar(n, receitaLiquida, porGrupo.get(GrupoDre.CUSTOS));
    BigDecimal[] operacional = somar(n, lucroBruto, porGrupo.get(GrupoDre.DESPESAS_OPERACIONAIS),
        porGrupo.get(GrupoDre.DESPESAS_ADMINISTRATIVAS), porGrupo.get(GrupoDre.DESPESAS_COMERCIAIS));
    BigDecimal[] antesIr = somar(n, operacional, porGrupo.get(GrupoDre.RECEITAS_FINANCEIRAS),
        porGrupo.get(GrupoDre.DESPESAS_FINANCEIRAS), porGrupo.get(GrupoDre.OUTRAS));
    BigDecimal[] liquido = somar(n, antesIr, porGrupo.get(GrupoDre.IMPOSTOS_SOBRE_LUCRO));
    BigDecimal baseReceita = total(receitaLiquida);

    List<Linha> linhas = new ArrayList<>();
    grupo(linhas, GrupoDre.RECEITA_BRUTA, porGrupo, porCategoria, baseReceita);
    grupo(linhas, GrupoDre.DEDUCOES, porGrupo, porCategoria, baseReceita);
    linhas.add(subtotal("RECEITA_LIQUIDA", "= Receita líquida", receitaLiquida, baseReceita));
    grupo(linhas, GrupoDre.CUSTOS, porGrupo, porCategoria, baseReceita);
    linhas.add(subtotal("LUCRO_BRUTO", "= Lucro bruto", lucroBruto, baseReceita));
    grupo(linhas, GrupoDre.DESPESAS_OPERACIONAIS, porGrupo, porCategoria, baseReceita);
    grupo(linhas, GrupoDre.DESPESAS_ADMINISTRATIVAS, porGrupo, porCategoria, baseReceita);
    grupo(linhas, GrupoDre.DESPESAS_COMERCIAIS, porGrupo, porCategoria, baseReceita);
    linhas.add(subtotal("RESULTADO_OPERACIONAL", "= Resultado operacional (EBITDA gerencial)", operacional, baseReceita));
    grupo(linhas, GrupoDre.RECEITAS_FINANCEIRAS, porGrupo, porCategoria, baseReceita);
    grupo(linhas, GrupoDre.DESPESAS_FINANCEIRAS, porGrupo, porCategoria, baseReceita);
    grupo(linhas, GrupoDre.OUTRAS, porGrupo, porCategoria, baseReceita);
    linhas.add(subtotal("RESULTADO_ANTES_IR", "= Resultado antes do IR e CSLL", antesIr, baseReceita));
    grupo(linhas, GrupoDre.IMPOSTOS_SOBRE_LUCRO, porGrupo, porCategoria, baseReceita);
    linhas.add(subtotal("RESULTADO_LIQUIDO", "= Resultado líquido", liquido, baseReceita));

    return new Dre(de.toString(), ate.toString(), regime, meses, linhas, Dinheiro.de(baseReceita), Dinheiro.de(total(lucroBruto)),
        Dinheiro.de(total(operacional)), Dinheiro.de(total(liquido)), pct(total(lucroBruto), baseReceita), pct(total(liquido), baseReceita));
  }

  // ------------------------------------------------------------------ fontes

  private List<Lancamento> competencia(YearMonth de, YearMonth ate) {
    UUID t = TenantContexto.exigir();
    List<Lancamento> l = new ArrayList<>();
    jdbc.query("""
        SELECT c.competencia, coalesce(cat.grupo_dre, 'RECEITA_BRUTA') AS grupo, coalesce(cat.nome, 'Sem categoria') AS nome,
               sum(c.valor) AS bruto, sum(c.desconto) AS desconto, sum(c.juros + c.multa) AS encargos
          FROM financeiro.cobrancas c LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = c.categoria_id
         WHERE c.tenant_id = ? AND c.deleted_at IS NULL AND c.status <> 'ESTORNADO' AND c.competencia BETWEEN ? AND ?
         GROUP BY 1, 2, 3""", rs -> {
          int mes = indice(de, rs.getString(1));
          GrupoDre g = GrupoDre.valueOf(rs.getString(2));
          l.add(new Lancamento(mes, g, rs.getString(3), rs.getBigDecimal(4)));
          if (rs.getBigDecimal(5).signum() > 0) l.add(new Lancamento(mes, GrupoDre.DEDUCOES, "Descontos concedidos", rs.getBigDecimal(5).negate()));
          if (rs.getBigDecimal(6).signum() > 0) l.add(new Lancamento(mes, GrupoDre.RECEITAS_FINANCEIRAS, "Juros e multas cobrados", rs.getBigDecimal(6)));
        }, t, de.toString(), ate.toString());
    jdbc.query("""
        SELECT cp.competencia, coalesce(cat.grupo_dre, 'DESPESAS_OPERACIONAIS'), coalesce(cat.nome, coalesce(cp.categoria, 'Sem categoria')), sum(cp.valor)
          FROM financeiro.contas_pagar cp LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = cp.categoria_id
         WHERE cp.tenant_id = ? AND cp.deleted_at IS NULL AND cp.status <> 'CANCELADA' AND cp.competencia BETWEEN ? AND ?
         GROUP BY 1, 2, 3""", rs -> {
          l.add(new Lancamento(indice(de, rs.getString(1)), GrupoDre.valueOf(rs.getString(2)), rs.getString(3), rs.getBigDecimal(4).negate()));
        }, t, de.toString(), ate.toString());
    jdbc.query("""
        SELECT competencia, sum(valor) FROM financeiro.comissoes
         WHERE tenant_id = ? AND deleted_at IS NULL AND status = 'A_PAGAR' AND competencia BETWEEN ? AND ? GROUP BY 1""", rs -> {
          l.add(new Lancamento(indice(de, rs.getString(1)), GrupoDre.DESPESAS_COMERCIAIS, "Comissões a pagar", rs.getBigDecimal(2).negate()));
        }, t, de.toString(), ate.toString());
    return l;
  }

  private List<Lancamento> caixa(YearMonth de, YearMonth ate) {
    UUID t = TenantContexto.exigir();
    Date d1 = Date.valueOf(de.atDay(1));
    Date d2 = Date.valueOf(ate.atEndOfMonth());
    List<Lancamento> l = new ArrayList<>();
    jdbc.query("""
        SELECT to_char((p.confirmado_em AT TIME ZONE 'America/Sao_Paulo'), 'YYYY-MM'), coalesce(cat.grupo_dre, 'RECEITA_BRUTA'),
               coalesce(cat.nome, 'Sem categoria'), sum(p.valor)
          FROM financeiro.pagamentos p JOIN financeiro.cobrancas c ON c.id = p.cobranca_id
          LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = c.categoria_id
         WHERE p.tenant_id = ? AND p.status IN ('CONFIRMADO','ESTORNADO') AND p.confirmado_em IS NOT NULL
           AND (p.confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
         GROUP BY 1, 2, 3""", rs -> {
          l.add(new Lancamento(indice(de, rs.getString(1)), GrupoDre.valueOf(rs.getString(2)), rs.getString(3), rs.getBigDecimal(4)));
        }, t, d1, d2);
    jdbc.query("""
        SELECT to_char((coalesce(p.estornado_em, p.updated_at) AT TIME ZONE 'America/Sao_Paulo'), 'YYYY-MM'), sum(coalesce(p.valor_estornado, p.valor))
          FROM financeiro.pagamentos p
         WHERE p.tenant_id = ? AND p.status = 'ESTORNADO' AND p.confirmado_em IS NOT NULL
           AND (coalesce(p.estornado_em, p.updated_at) AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?
         GROUP BY 1""", rs -> {
          l.add(new Lancamento(indice(de, rs.getString(1)), GrupoDre.DEDUCOES, "Estornos", rs.getBigDecimal(2).negate()));
        }, t, d1, d2);
    jdbc.query("""
        SELECT to_char(cp.data_pagamento, 'YYYY-MM'), coalesce(cat.grupo_dre, 'DESPESAS_OPERACIONAIS'),
               coalesce(cat.nome, coalesce(cp.categoria, 'Sem categoria')), sum(cp.valor_pago)
          FROM financeiro.contas_pagar cp LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = cp.categoria_id
         WHERE cp.tenant_id = ? AND cp.deleted_at IS NULL AND cp.status = 'PAGA' AND cp.data_pagamento BETWEEN ? AND ?
         GROUP BY 1, 2, 3""", rs -> {
          l.add(new Lancamento(indice(de, rs.getString(1)), GrupoDre.valueOf(rs.getString(2)), rs.getString(3), rs.getBigDecimal(4).negate()));
        }, t, d1, d2);
    return l;
  }

  /** Lançamentos do extrato conciliados sem documento: só existem no banco (tarifas, juros...). */
  private List<Lancamento> extrato(YearMonth de, YearMonth ate, List<String> meses) {
    List<Lancamento> l = new ArrayList<>();
    jdbc.query("""
        SELECT to_char(t.data, 'YYYY-MM'), t.classificacao, cat.grupo_dre, cat.nome, sum(t.valor)
          FROM financeiro.transacoes_bancarias t LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = t.categoria_id
         WHERE t.tenant_id = ? AND t.deleted_at IS NULL AND t.status = 'CONCILIADA' AND t.pagamento_id IS NULL AND t.conta_pagar_id IS NULL
           AND t.classificacao IN ('TARIFA','JUROS','ESTORNO','OUTRO') AND t.data BETWEEN ? AND ?
         GROUP BY 1, 2, 3, 4""", rs -> {
          BigDecimal v = rs.getBigDecimal(5);
          String grupo = rs.getString(3);
          GrupoDre g;
          String nome = rs.getString(4);
          if (grupo != null) g = GrupoDre.valueOf(grupo);
          else switch (rs.getString(2)) {
            case "TARIFA" -> { g = GrupoDre.DESPESAS_FINANCEIRAS; nome = "Tarifas bancárias"; }
            case "JUROS" -> { g = v.signum() >= 0 ? GrupoDre.RECEITAS_FINANCEIRAS : GrupoDre.DESPESAS_FINANCEIRAS; nome = "Juros (extrato)"; }
            default -> { g = GrupoDre.OUTRAS; nome = "Outros lançamentos do extrato"; }
          }
          l.add(new Lancamento(indice(de, rs.getString(1)), g, nome, v));
        }, TenantContexto.exigir(), Date.valueOf(de.atDay(1)), Date.valueOf(ate.atEndOfMonth()));
    return l;
  }

  // ------------------------------------------------------------------ montagem

  private static void grupo(List<Linha> linhas, GrupoDre g, Map<GrupoDre, BigDecimal[]> porGrupo,
      Map<GrupoDre, Map<String, BigDecimal[]>> porCategoria, BigDecimal base) {
    BigDecimal[] v = porGrupo.get(g);
    Map<String, BigDecimal[]> cats = porCategoria.get(g);
    if (cats.isEmpty() && g != GrupoDre.RECEITA_BRUTA) return; // grupo vazio some, para o DRE ficar curto
    linhas.add(new Linha(g.name(), TITULOS.get(g), "GRUPO", lista(v), Dinheiro.de(total(v)), pct(total(v), base)));
    cats.entrySet().stream().sorted((a, b) -> total(b.getValue()).abs().compareTo(total(a.getValue()).abs()))
        .forEach(e -> linhas.add(new Linha(g.name() + ":" + e.getKey(), e.getKey(), "CATEGORIA", lista(e.getValue()),
            Dinheiro.de(total(e.getValue())), pct(total(e.getValue()), base))));
  }

  private static Linha subtotal(String codigo, String titulo, BigDecimal[] v, BigDecimal base) {
    return new Linha(codigo, titulo, "SUBTOTAL", lista(v), Dinheiro.de(total(v)), pct(total(v), base));
  }

  private static int indice(YearMonth de, String competencia) {
    return (int) de.until(YearMonth.parse(competencia), java.time.temporal.ChronoUnit.MONTHS);
  }

  private static BigDecimal[] zeros(int n) {
    BigDecimal[] z = new BigDecimal[n];
    java.util.Arrays.fill(z, BigDecimal.ZERO);
    return z;
  }

  private static BigDecimal[] somar(int n, BigDecimal[]... partes) {
    BigDecimal[] s = zeros(n);
    for (BigDecimal[] p : partes) for (int i = 0; i < n; i++) s[i] = s[i].add(p[i]);
    return s;
  }

  private static BigDecimal total(BigDecimal[] v) {
    BigDecimal t = BigDecimal.ZERO;
    for (BigDecimal x : v) t = t.add(x);
    return t;
  }

  private static List<BigDecimal> lista(BigDecimal[] v) {
    List<BigDecimal> l = new ArrayList<>();
    for (BigDecimal x : v) l.add(Dinheiro.de(x));
    return l;
  }

  static BigDecimal pct(BigDecimal parte, BigDecimal todo) {
    return todo.signum() == 0 ? null : parte.multiply(BigDecimal.valueOf(100)).divide(todo, 2, RoundingMode.HALF_EVEN);
  }
}
