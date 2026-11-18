package br.com.erp.financeiro.comum.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Converter;

/** Periodicidade de um contrato recorrente. */
public enum Periodicidade implements ValorEnum {
  MENSAL("mensal", 1),
  TRIMESTRAL("trimestral", 3),
  SEMESTRAL("semestral", 6),
  ANUAL("anual", 12);

  private final String valor;
  private final int meses;

  Periodicidade(String valor, int meses) {
    this.valor = valor;
    this.meses = meses;
  }

  /** Quantos meses separam uma competência da seguinte. */
  public int meses() {
    return meses;
  }

  @Override
  @JsonValue
  public String valor() {
    return valor;
  }

  @JsonCreator
  public static Periodicidade de(String valor) {
    return ValorEnum.de(Periodicidade.class, valor);
  }

  @Override
  public String toString() {
    return valor;
  }

  @Converter(autoApply = true)
  public static class ConversorJpa extends ValorEnum.Conversor<Periodicidade> {
    public ConversorJpa() {
      super(Periodicidade.class);
    }
  }
}
