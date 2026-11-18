package br.com.erp.financeiro.comum;

import static br.com.erp.financeiro.comum.Dinheiro.centavos;

import java.math.BigDecimal;

/** Saldo devedor em centavos: valor − desconto + juros + multa − já pago. */
public final class Saldo {

  private Saldo() {}

  public static long centavosDevidos(BigDecimal valor, BigDecimal desconto, BigDecimal juros, BigDecimal multa, BigDecimal pago) {
    return centavos(valor) - centavos(desconto) + centavos(juros) + centavos(multa) - centavos(pago);
  }
}
