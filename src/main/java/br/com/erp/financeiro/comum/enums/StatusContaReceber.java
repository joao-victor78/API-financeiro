package br.com.erp.financeiro.comum.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Converter;
import java.util.EnumSet;
import java.util.Set;

/** Ciclo de vida de uma conta a receber. */
public enum StatusContaReceber implements ValorEnum {
  EM_ABERTO("em_aberto"),
  AGENDADA("agendada"),
  PAGA("paga"),
  VENCIDA("vencida"),
  CANCELADA("cancelada"),
  ESTORNADA("estornada"),
  PARCIALMENTE_PAGA("parcialmente_paga");

  /** Status em que a conta ainda tem saldo a ser quitado. */
  public static final Set<StatusContaReceber> EM_ABERTO_COM_SALDO = EnumSet.of(EM_ABERTO, AGENDADA, VENCIDA, PARCIALMENTE_PAGA);

  private final String valor;

  StatusContaReceber(String valor) {
    this.valor = valor;
  }

  @Override
  @JsonValue
  public String valor() {
    return valor;
  }

  @JsonCreator
  public static StatusContaReceber de(String valor) {
    return ValorEnum.de(StatusContaReceber.class, valor);
  }

  @Override
  public String toString() {
    return valor;
  }

  @Converter(autoApply = true)
  public static class ConversorJpa extends ValorEnum.Conversor<StatusContaReceber> {
    public ConversorJpa() {
      super(StatusContaReceber.class);
    }
  }
}
