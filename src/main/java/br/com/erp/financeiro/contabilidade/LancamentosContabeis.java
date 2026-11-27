package br.com.erp.financeiro.contabilidade;

import br.com.erp.financeiro.tenant.TenantContexto;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Monta os lançamentos de uma competência no layout neutro que a contabilidade importa (§37): data,
 * conta débito, conta crédito, valor, histórico, documento, participante, categoria e centro de custo.
 * As contas contábeis vêm do cadastro (categoria e conta bancária); sem código, a coluna vai vazia e o
 * escritório completa no próprio sistema.
 *
 * <ul>
 *   <li>RECEITA (competência): D cliente / C categoria de receita — valor da cobrança.</li>
 *   <li>RECEBIMENTO (caixa): D banco / C cliente — pagamento confirmado.</li>
 *   <li>DESPESA (competência): D categoria de despesa / C fornecedor — conta a pagar.</li>
 *   <li>PAGAMENTO (caixa): D fornecedor / C banco — conta paga.</li>
 *   <li>TARIFA, JUROS, ESTORNO, OUTRO: lançamentos do extrato conciliados sem documento.</li>
 * </ul>
 */
@Component
public class LancamentosContabeis {

  public static final String CONTA_CLIENTES = "CLIENTES";
  public static final String CONTA_FORNECEDORES = "FORNECEDORES";

  public record Lancamento(LocalDate data, String tipo, String contaDebito, String contaCredito, BigDecimal valor, String historico,
      String documento, String participante, String categoria, String centroCusto, UUID origemId, String origem,
      BigDecimal movimentoBancario) {}

  private final JdbcTemplate jdbc;

  public LancamentosContabeis(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Lancamento> daCompetencia(YearMonth mes) {
    UUID t = TenantContexto.exigir();
    String comp = mes.toString();
    Date d1 = Date.valueOf(mes.atDay(1));
    Date d2 = Date.valueOf(mes.atEndOfMonth());
    List<Lancamento> l = new ArrayList<>();
    l.addAll(jdbc.query("""
        SELECT c.id, c.data_vencimento, c.valor - c.desconto + c.juros + c.multa AS total, c.descricao, c.empresa_id,
               cat.nome AS categoria, cat.conta_contabil, cc.codigo AS centro,
               (SELECT n.numero FROM financeiro.notas_fiscais n WHERE n.cobranca_id = c.id AND n.deleted_at IS NULL AND n.status = 'AUTORIZADA' LIMIT 1) AS nota
          FROM financeiro.cobrancas c
          LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = c.categoria_id
          LEFT JOIN financeiro.centros_custo cc ON cc.id = c.centro_custo_id
         WHERE c.tenant_id = ? AND c.deleted_at IS NULL AND c.status <> 'ESTORNADO' AND c.competencia = ?""",
        (rs, i) -> new Lancamento(mes.atDay(1), "RECEITA", CONTA_CLIENTES, rs.getString("conta_contabil"), rs.getBigDecimal("total"),
            "Receita " + texto(rs.getString("descricao"), "cobrança") + " " + comp, rs.getString("nota"), rs.getString("empresa_id"),
            rs.getString("categoria"), rs.getString("centro"), rs.getObject("id", UUID.class), "cobranca", null), t, comp));
    l.addAll(jdbc.query("""
        SELECT p.id, (p.confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date AS dia, p.valor, p.meio, c.descricao, c.empresa_id,
               cat.nome AS categoria, cb.conta_contabil AS banco, cb.nome AS conta
          FROM financeiro.pagamentos p JOIN financeiro.cobrancas c ON c.id = p.cobranca_id
          LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = c.categoria_id
          LEFT JOIN financeiro.contas_bancarias cb ON cb.id = p.conta_bancaria_id
         WHERE p.tenant_id = ? AND p.status IN ('CONFIRMADO','ESTORNADO') AND p.confirmado_em IS NOT NULL
           AND (p.confirmado_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?""",
        (rs, i) -> new Lancamento(rs.getDate("dia").toLocalDate(), "RECEBIMENTO", rs.getString("banco"), CONTA_CLIENTES,
            rs.getBigDecimal("valor"), "Recebimento " + rs.getString("meio") + " " + texto(rs.getString("descricao"), "") +
                (rs.getString("conta") == null ? "" : " — " + rs.getString("conta")),
            null, rs.getString("empresa_id"), rs.getString("categoria"), null, rs.getObject("id", UUID.class), "pagamento",
            rs.getBigDecimal("valor")), t, d1, d2));
    l.addAll(jdbc.query("""
        SELECT p.id, (coalesce(p.estornado_em, p.updated_at) AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
               coalesce(p.valor_estornado, p.valor) AS valor, c.empresa_id, cb.conta_contabil AS banco, p.motivo_estorno
          FROM financeiro.pagamentos p JOIN financeiro.cobrancas c ON c.id = p.cobranca_id
          LEFT JOIN financeiro.contas_bancarias cb ON cb.id = p.conta_bancaria_id
         WHERE p.tenant_id = ? AND p.status = 'ESTORNADO' AND p.confirmado_em IS NOT NULL
           AND (coalesce(p.estornado_em, p.updated_at) AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?""",
        (rs, i) -> new Lancamento(rs.getDate("dia").toLocalDate(), "ESTORNO", CONTA_CLIENTES, rs.getString("banco"), rs.getBigDecimal("valor"),
            "Estorno de recebimento" + (rs.getString("motivo_estorno") == null ? "" : " — " + rs.getString("motivo_estorno")), null,
            rs.getString("empresa_id"), null, null, rs.getObject("id", UUID.class), "pagamento", rs.getBigDecimal("valor").negate()), t, d1, d2));
    l.addAll(jdbc.query("""
        SELECT cp.id, cp.data_vencimento, cp.valor, cp.fornecedor_nome, cp.fornecedor_documento, cp.documento,
               coalesce(cat.nome, cp.categoria) AS categoria, cat.conta_contabil, coalesce(cc.codigo, cp.centro_custo) AS centro
          FROM financeiro.contas_pagar cp
          LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = cp.categoria_id
          LEFT JOIN financeiro.centros_custo cc ON cc.id = cp.centro_custo_id
         WHERE cp.tenant_id = ? AND cp.deleted_at IS NULL AND cp.status <> 'CANCELADA' AND cp.competencia = ?""",
        (rs, i) -> new Lancamento(mes.atDay(1), "DESPESA", rs.getString("conta_contabil"), CONTA_FORNECEDORES, rs.getBigDecimal("valor"),
            "Despesa " + rs.getString("fornecedor_nome") + " " + comp, rs.getString("documento"),
            participante(rs.getString("fornecedor_nome"), rs.getString("fornecedor_documento")), rs.getString("categoria"),
            rs.getString("centro"), rs.getObject("id", UUID.class), "conta_pagar", null), t, comp));
    l.addAll(jdbc.query("""
        SELECT cp.id, cp.data_pagamento, cp.valor_pago, cp.fornecedor_nome, cp.fornecedor_documento, cp.documento,
               coalesce(cat.nome, cp.categoria) AS categoria, cb.conta_contabil AS banco
          FROM financeiro.contas_pagar cp
          LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = cp.categoria_id
          LEFT JOIN financeiro.contas_bancarias cb ON cb.id = cp.conta_bancaria_id
         WHERE cp.tenant_id = ? AND cp.deleted_at IS NULL AND cp.status = 'PAGA' AND cp.data_pagamento BETWEEN ? AND ?""",
        (rs, i) -> new Lancamento(rs.getDate("data_pagamento").toLocalDate(), "PAGAMENTO", CONTA_FORNECEDORES, rs.getString("banco"),
            rs.getBigDecimal("valor_pago"), "Pagamento " + rs.getString("fornecedor_nome"), rs.getString("documento"),
            participante(rs.getString("fornecedor_nome"), rs.getString("fornecedor_documento")), rs.getString("categoria"), null,
            rs.getObject("id", UUID.class), "conta_pagar", rs.getBigDecimal("valor_pago").negate()), t, d1, d2));
    l.addAll(jdbc.query("""
        SELECT t.id, t.data, t.valor, t.descricao, t.documento, t.classificacao, cat.nome AS categoria, cat.conta_contabil,
               cb.conta_contabil AS banco
          FROM financeiro.transacoes_bancarias t
          JOIN financeiro.contas_bancarias cb ON cb.id = t.conta_bancaria_id
          LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = t.categoria_id
         WHERE t.tenant_id = ? AND t.deleted_at IS NULL AND t.status = 'CONCILIADA' AND t.pagamento_id IS NULL AND t.conta_pagar_id IS NULL
           AND t.classificacao IN ('TARIFA','JUROS','ESTORNO','OUTRO','TRANSFERENCIA') AND t.data BETWEEN ? AND ?""",
        (rs, i) -> {
          BigDecimal v = rs.getBigDecimal("valor");
          boolean credito = v.signum() > 0;
          return new Lancamento(rs.getDate("data").toLocalDate(), rs.getString("classificacao"),
              credito ? rs.getString("banco") : rs.getString("conta_contabil"), credito ? rs.getString("conta_contabil") : rs.getString("banco"),
              v.abs(), texto(rs.getString("descricao"), rs.getString("classificacao")), rs.getString("documento"), null,
              rs.getString("categoria"), null, rs.getObject("id", UUID.class), "transacao_bancaria", v);
        }, t, d1, d2));
    l.sort(Comparator.comparing(Lancamento::data).thenComparing(Lancamento::tipo));
    return l;
  }

  private static String participante(String nome, String documento) {
    return documento == null ? nome : nome + " (" + documento + ")";
  }

  private static String texto(String s, String padrao) {
    return s == null || s.isBlank() ? padrao : s;
  }
}
