package br.com.erp.financeiro.pagamentos;

import br.com.erp.financeiro.cobrancas.MeioPagamento;
import br.com.erp.financeiro.pagamentos.provedor.QrCode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * contratos/financeiro.yaml, PagamentoSolicitado. pixQrCode é a imagem PNG em data URI, para a tela.
 * Os campos depois de createdAt são da V2 (conta e estorno). O nome do provedor continua fora: o
 * formato é neutro (RF-04, critério 1).
 */
public record PagamentoSaida(
    UUID pagamentoId,
    UUID cobrancaId,
    MeioPagamento meio,
    StatusPagamento status,
    BigDecimal valor,
    String pixCopiaCola,
    String pixQrCode,
    String boletoUrl,
    String boletoLinhaDigitavel,
    String linkPagamentoCartao,
    OffsetDateTime expiraEm,
    OffsetDateTime confirmadoEm,
    UUID comprovanteId,
    OffsetDateTime createdAt,
    UUID contaBancariaId,
    OffsetDateTime estornadoEm,
    BigDecimal valorEstornado,
    String motivoEstorno) {

  public static PagamentoSaida de(Pagamento p) {
    String qr = p.getPixCopiaCola() == null || !p.pendente() ? null
        : "data:image/png;base64," + Base64.getEncoder().encodeToString(QrCode.png(p.getPixCopiaCola(), 280));
    return new PagamentoSaida(p.getId(), p.getCobrancaId(), p.getMeio(), p.getStatus(), p.getValor(),
        p.getPixCopiaCola(), qr, p.getBoletoUrl(), p.getBoletoLinhaDigitavel(), p.getLinkPagamento(),
        p.getExpiraEm(), p.getConfirmadoEm(), p.getComprovanteId(), p.getCreatedAt(),
        p.getContaBancariaId(), p.getEstornadoEm(), p.getValorEstornado(), p.getMotivoEstorno());
  }
}
