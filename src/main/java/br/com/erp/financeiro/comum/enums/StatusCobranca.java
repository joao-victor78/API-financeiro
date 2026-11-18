package br.com.erp.financeiro.comum.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Converter;

/** Rascunho → aprovada (emitida) → paga, ou cancelada. */
public enum StatusCobranca implements ValorEnum {
  RASCUNHO("rascunho"),
  APROVADA("aprovada"),
  PAGA("paga"),
  CANCELADA("cancelada");

  private final String valor;

  StatusCobranca(String valor) {
    this.valor = valor;
  }

  @Override
  @JsonValue
  public String valor() {
    return valor;
  }

  @JsonCreator
  public static StatusCobranca de(String valor) {
    return ValorEnum.de(StatusCobranca.class, valor);
  }

  @Override
  public String toString() {
    return valor;
  }

  @Converter(autoApply = true)
  public static class ConversorJpa extends ValorEnum.Conversor<StatusCobranca> {
    public ConversorJpa() {
      super(StatusCobranca.class);
    }
  }
}
