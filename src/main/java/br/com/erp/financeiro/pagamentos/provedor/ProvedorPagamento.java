package br.com.erp.financeiro.pagamentos.provedor;

import br.com.erp.financeiro.cobrancas.MeioPagamento;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Adaptador de provedor de pagamento (RN-19, §28). Nenhuma regra de negócio chama um provedor
 * específico: tudo passa por aqui, em formato neutro. Nesta fase só existe o simulado (RN-20);
 * Asaas, Efí, Inter etc. entram como novas implementações, com conta de sandbox.
 */
public interface ProvedorPagamento {

  /** Nome gravado em pagamentos.provedor e webhook_eventos.provedor. */
  String nome();

  /** Despacha para createPix, createBoleto ou createCardPayment conforme o meio. */
  default Cobrado createCharge(MeioPagamento meio, Solicitacao s) {
    return switch (meio) {
      case PIX -> createPix(s);
      case BOLETO -> createBoleto(s);
      case CARTAO -> createCardPayment(s);
      case TRANSFERENCIA -> throw new IllegalArgumentException("Transferência é baixa manual, não passa pelo provedor");
    };
  }

  Cobrado createPix(Solicitacao s);

  Cobrado createBoleto(Solicitacao s);

  /** Link de pagamento com cartão (o cliente digita o cartão na página do provedor). */
  Cobrado createCardPayment(Solicitacao s);

  /**
   * Cartão recorrente (§27): cobra um cartão já tokenizado no provedor. O módulo só conhece a referência
   * do token (RN-22); a confirmação chega pelo webhook, como nos outros meios.
   */
  default Cobrado chargeSavedCard(String tokenCartao, Solicitacao s) {
    throw new UnsupportedOperationException("O provedor " + nome() + " não cobra cartão salvo.");
  }

  /** Página do provedor onde o cliente cadastra o cartão para a cobrança recorrente (o número nunca passa aqui). */
  default String linkCadastroCartao(UUID recorrenciaId, UUID empresaId) {
    throw new UnsupportedOperationException("O provedor " + nome() + " não cadastra cartão para recorrência.");
  }

  boolean refund(String referencia, BigDecimal valor);

  SituacaoNoProvedor getPayment(String referencia);

  boolean cancelPayment(String referencia);

  /** Valida a assinatura antes de qualquer outra coisa e traduz o payload para o formato neutro. */
  EventoProvedor processWebhook(WebhookRecebido payload);

  /** O que o módulo pede ao provedor. */
  record Solicitacao(UUID pagamentoId, UUID cobrancaId, BigDecimal valor, String descricao, LocalDate vencimento,
      UUID empresaId) {

    /** Formato anterior (sem a empresa), mantido para quem já chamava assim. */
    public Solicitacao(UUID pagamentoId, UUID cobrancaId, BigDecimal valor, String descricao, LocalDate vencimento) {
      this(pagamentoId, cobrancaId, valor, descricao, vencimento, null);
    }
  }

  /** Resposta neutra: nenhum campo específico de provedor vaza para a API (critério 1 do RF-04). */
  record Cobrado(String referencia, String pixCopiaCola, String boletoLinhaDigitavel, String boletoUrl,
      String linkPagamento, OffsetDateTime expiraEm) {}

  enum SituacaoNoProvedor { PENDENTE, PAGO, CANCELADO, ESTORNADO, DESCONHECIDO }

  /** Corpo do webhook como chega (contratos/financeiro.yaml, WebhookPagamentoEntrada). */
  record WebhookRecebido(String eventoId, String assinatura, String tipo, UUID cobrancaId, UUID pagamentoId,
      BigDecimal valorPago, OffsetDateTime dataPagamento) {}

  enum TipoEventoProvedor { CONFIRMADO, ESTORNADO, FALHOU }

  record EventoProvedor(String eventoId, TipoEventoProvedor tipo, UUID cobrancaId, UUID pagamentoId,
      BigDecimal valor, OffsetDateTime quando) {}

  /** Assinatura ausente ou que não confere: 400 e nenhuma alteração (RN-27). */
  class AssinaturaInvalidaException extends RuntimeException {
    public AssinaturaInvalidaException() {
      super("Assinatura do webhook inválida.");
    }
  }
}
