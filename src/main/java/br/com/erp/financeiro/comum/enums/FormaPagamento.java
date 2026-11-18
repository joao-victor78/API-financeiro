package br.com.erp.financeiro.comum.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Converter;

/** Formas de pagamento aceitas (ENUM financeiro.forma_pagamento). */
public enum FormaPagamento implements ValorEnum {
  PIX("pix"),
  BOLETO("boleto"),
  CARTAO_CREDITO("cartao_credito"),
  CARTAO_DEBITO("cartao_debito"),
  TRANSFERENCIA("transferencia"),
  DINHEIRO("dinheiro");

  private final String valor;

  FormaPagamento(String valor) {
    this.valor = valor;
  }

  @Override
  @JsonValue
  public String valor() {
    return valor;
  }

  @JsonCreator
  public static FormaPagamento de(String valor) {
    return ValorEnum.de(FormaPagamento.class, valor);
  }

  @Override
  public String toString() {
    return valor;
  }

  @Converter(autoApply = true)
  public static class ConversorJpa extends ValorEnum.Conversor<FormaPagamento> {
    public ConversorJpa() {
      super(FormaPagamento.class);
    }
  }
}
