package br.com.erp.financeiro.pagamentos;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaService;
import br.com.erp.financeiro.cobrancas.MeioPagamento;
import br.com.erp.financeiro.cobrancas.StatusCobranca;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.pagamentos.provedor.ProvedorPagamento;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pedido de PIX, boleto ou link de cartão pelo adaptador de provedor (RF-04), cartão recorrente e estorno (§27, §28). */
@Service
public class PagamentoService {

  private final PagamentoRepository pagamentos;
  private final CobrancaService cobrancas;
  private final ProvedorPagamento provedor;
  private final AuditoriaService auditoria;
  private final Relogio relogio;

  public PagamentoService(PagamentoRepository pagamentos, CobrancaService cobrancas, ProvedorPagamento provedor,
      AuditoriaService auditoria, Relogio relogio) {
    this.pagamentos = pagamentos;
    this.cobrancas = cobrancas;
    this.provedor = provedor;
    this.auditoria = auditoria;
    this.relogio = relogio;
  }

  /** O valor pedido é sempre o saldo em aberto. Cobrança PAGO ou CANCELADO é recusada (RF-04, critério 3). */
  @Transactional
  public Pagamento solicitar(UUID cobrancaId, MeioPagamento meio) {
    Cobranca c = cobrancas.obter(cobrancaId);
    if (c.getDeletedAt() != null || !StatusCobranca.PAGAVEIS.contains(c.getStatus()) || c.saldo().signum() == 0) {
      throw ApiException.conflito("COBRANCA_NAO_PAGAVEL",
          "Cobrança " + c.getStatus() + " não aceita novo pedido de pagamento.");
    }
    Pagamento p = Pagamento.pedido(c.getId(), meio, c.saldo(), provedor.nome());
    try {
      p.registrarResposta(provedor.createCharge(meio, new ProvedorPagamento.Solicitacao(
          p.getId(), c.getId(), c.saldo(), c.getDescricao(), c.getDataVencimento(), c.getEmpresaId())));
    } catch (IllegalStateException e) {
      // provedor real fora do ar ou recusando (dados do cliente, limite): 422 com o motivo, nada gravado
      throw ApiException.regra("PROVEDOR_RECUSOU", e.getMessage());
    }
    pagamentos.save(p);
    auditoria.registrar("SOLICITAR_PAGAMENTO", "pagamento", p.getId(), null, p.instantaneo());
    return p;
  }

  /**
   * Cartão recorrente (§27): cobra o cartão tokenizado da regra no dia da geração. O pedido fica PENDENTE
   * e a cobrança AGENDADO até o webhook confirmar (ou falhar, e aí a régua segue normalmente).
   */
  // Sem @Transactional de propósito: roda na transação da geração do ciclo, e uma recusa do provedor
  // (exceção) não pode marcar essa transação para rollback — a cobrança é gerada mesmo assim.
  public Pagamento cobrarCartaoSalvo(Cobranca c, String tokenCartao) {
    Pagamento p = Pagamento.pedido(c.getId(), MeioPagamento.CARTAO, c.saldo(), provedor.nome());
    p.registrarResposta(provedor.chargeSavedCard(tokenCartao, new ProvedorPagamento.Solicitacao(
        p.getId(), c.getId(), c.saldo(), c.getDescricao(), c.getDataVencimento(), c.getEmpresaId())));
    pagamentos.save(p);
    auditoria.registrar("COBRAR_CARTAO_RECORRENTE", "pagamento", p.getId(), null, p.instantaneo());
    return p;
  }

  /**
   * Estorno pedido pelo usuário (§28 refund): total ou parcial, só de pagamento CONFIRMADO. Pelo provedor
   * quando o pagamento veio dele; baixa manual (transferência) só registra a devolução. Os efeitos são os
   * mesmos do estorno informado por webhook (saldo volta, comissão cai, financeiro.pagamento.falhou).
   */
  @Transactional
  public Pagamento estornar(UUID pagamentoId, BigDecimal valor, String motivo) {
    Pagamento p = obter(pagamentoId);
    if (p.getStatus() != StatusPagamento.CONFIRMADO) {
      throw ApiException.conflito("PAGAMENTO_NAO_CONFIRMADO", "Só pagamento confirmado pode ser estornado (está " + p.getStatus() + ").");
    }
    BigDecimal v = valor == null ? p.getValor() : Dinheiro.de(valor);
    if (v.compareTo(p.getValor()) > 0) {
      throw ApiException.regra("ESTORNO_ACIMA_DO_PAGO", "O estorno (" + Dinheiro.brl(v) + ") passa do valor pago (" + Dinheiro.brl(p.getValor()) + ").");
    }
    if (motivo == null || motivo.isBlank()) throw ApiException.campo("motivo", "OBRIGATORIO", "Informe o motivo do estorno.");
    boolean viaProvedor = p.getReferenciaProvedor() != null && !"manual".equals(p.getProvedor());
    if (viaProvedor && !provedor.refund(p.getReferenciaProvedor(), v)) {
      throw ApiException.regra("ESTORNO_RECUSADO", "O provedor de pagamento recusou o estorno.");
    }
    Cobranca c = cobrancas.obter(p.getCobrancaId());
    Map<String, Object> antes = p.instantaneo();
    p.estornar(v, motivo.trim(), relogio.agoraUtc());
    Map<String, Object> depois = new LinkedHashMap<>(p.instantaneo());
    depois.put("valorEstornado", v);
    depois.put("motivo", motivo.trim());
    depois.put("viaProvedor", viaProvedor);
    auditoria.registrar("ESTORNAR_PAGAMENTO", "pagamento", p.getId(), antes, depois);
    cobrancas.aplicarFalhaOuEstorno(c, p, true, v);
    return p;
  }

  @Transactional(readOnly = true)
  public Pagamento obter(UUID id) {
    return Tenant.doTenant(pagamentos.findById(id), "Pagamento");
  }

  @Transactional(readOnly = true)
  public List<Pagamento> daCobranca(UUID cobrancaId) {
    cobrancas.obter(cobrancaId); // 404 se for de outro tenant
    return pagamentos.daCobranca(cobrancaId);
  }
}
