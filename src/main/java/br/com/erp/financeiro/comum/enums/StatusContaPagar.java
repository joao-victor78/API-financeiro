package br.com.erp.financeiro.comum.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Converter;
import java.util.EnumSet;
import java.util.Set;

/** Ciclo de vida de uma conta a pagar. */
public enum StatusContaPagar implements ValorEnum {
  EM_ABERTO("em_aberto"),
  AGENDADA("agendada"),
  PAGA("paga"),
  VENCIDA("vencida"),
  CANCELADA("cancelada"),
  PARCIALMENTE_PAGA("parcialmente_paga");

  /** Status em que a conta ainda tem saldo a ser quitado. */
  public static final Set<StatusContaPagar> EM_ABERTO_COM_SALDO = EnumSet.of(EM_ABERTO, AGENDADA, VENCIDA, PARCIALMENTE_PAGA);

  private final String valor;

  StatusContaPagar(String valor) {
    this.valor = valor;
  }

  @Override
  @JsonValue
  public String valor() {
    return valor;
  }

  @JsonCreator
  public static StatusContaPagar de(String valor) {
    return ValorEnum.de(StatusContaPagar.class, valor);
  }

  @Override
  public String toString() {
    return valor;
  }

  @Converter(autoApply = true)
  public static class ConversorJpa extends ValorEnum.Conversor<StatusContaPagar> {
    public ConversorJpa() {
      super(StatusContaPagar.class);
    }
  }
}
