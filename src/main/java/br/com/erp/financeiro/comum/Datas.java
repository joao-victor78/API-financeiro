package br.com.erp.financeiro.comum;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** Utilitários de data. Competência é sempre o texto "AAAA-MM" (coluna char(7)). */
public final class Datas {

  private Datas() {}

  /** Soma meses mantendo o dia, limitado ao último dia do mês de destino (31/01 + 1 mês = 28/02). */
  public static LocalDate somarMeses(LocalDate data, int meses) {
    return data.plusMonths(meses);
  }

  /** Número de dias de {@code de} até {@code ate} (positivo se {@code ate} é posterior). */
  public static long diferencaDias(LocalDate de, LocalDate ate) {
    return ChronoUnit.DAYS.between(de, ate);
  }

  public static String competenciaDe(LocalDate data) {
    return YearMonth.from(data).toString();
  }

  /** Dia da competência; se o mês for mais curto, cai no último dia (dia 31 em fevereiro = 28/29). */
  public static LocalDate comDia(String competencia, int dia) {
    YearMonth ym = YearMonth.parse(competencia);
    return ym.atDay(Math.min(dia, ym.lengthOfMonth()));
  }

  public static String somarMesesCompetencia(String competencia, int meses) {
    return YearMonth.parse(competencia).plusMonths(meses).toString();
  }
}
