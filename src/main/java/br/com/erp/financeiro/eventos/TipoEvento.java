package br.com.erp.financeiro.eventos;

import java.util.List;

/** Eventos publicados pelo módulo para os demais (Core, Notificações…). */
public final class TipoEvento {

  public static final String COBRANCA_APROVADA = "cobranca.aprovada";
  public static final String COBRANCA_CANCELADA = "cobranca.cancelada";
  public static final String COBRANCA_PAGA = "cobranca.paga";
  public static final String CONTA_RECEBER_PAGA = "conta_receber.paga";
  public static final String CONTA_RECEBER_VENCIDA = "conta_receber.vencida";
  public static final String CONTA_PAGAR_PAGA = "conta_pagar.paga";
  public static final String RECORRENCIA_COBRANCA_GERADA = "recorrencia.cobranca_gerada";
  public static final String CONCILIACAO_CONCLUIDA = "conciliacao.concluida";
  public static final String PROMESSA_QUEBRADA = "promessa_pagamento.quebrada";

  public static final List<String> TODOS = List.of(COBRANCA_APROVADA, COBRANCA_CANCELADA, COBRANCA_PAGA, CONTA_RECEBER_PAGA,
      CONTA_RECEBER_VENCIDA, CONTA_PAGAR_PAGA, RECORRENCIA_COBRANCA_GERADA, CONCILIACAO_CONCLUIDA, PROMESSA_QUEBRADA);

  /** Para validação com @Pattern. */
  public static final String REGEX = "cobranca\\.aprovada|cobranca\\.cancelada|cobranca\\.paga|conta_receber\\.paga"
      + "|conta_receber\\.vencida|conta_pagar\\.paga|recorrencia\\.cobranca_gerada|conciliacao\\.concluida"
      + "|promessa_pagamento\\.quebrada";

  private TipoEvento() {}
}
