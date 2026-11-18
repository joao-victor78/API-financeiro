package br.com.erp.financeiro.comum;

/** Expressões de validação compartilhadas pelos DTOs. */
public final class Validacao {

  public static final String COMPETENCIA = "^\\d{4}-(0[1-9]|1[0-2])$";
  public static final String MSG_COMPETENCIA = "competencia deve estar no formato AAAA-MM";

  private Validacao() {}
}
