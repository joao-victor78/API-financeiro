package br.com.erp.financeiro.cobrancas;

import br.com.erp.financeiro.pagamentos.Pagamento;
import java.math.BigDecimal;

/**
 * Fatos do próprio módulo, publicados como evento Spring síncrono (mesma transação). Assim comissões e
 * outros efeitos internos reagem ao pagamento sem que o CobrancaService precise conhecê-los (§105).
 */
public final class EventosInternos {

  private EventosInternos() {}

  /** Todo pagamento confirmado: webhook do provedor ou baixa manual. */
  public record PagamentoConfirmado(Cobranca cobranca, Pagamento pagamento) {}

  /** Estorno total ou parcial de um pagamento já confirmado. */
  public record PagamentoEstornado(Cobranca cobranca, Pagamento pagamento, BigDecimal valor) {}
}
