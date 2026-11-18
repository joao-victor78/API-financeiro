package br.com.erp.financeiro.comum;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Fonte única de "agora" do módulo. Os jobs automáticos dependem de data; centralizar aqui permite
 * simular a passagem do tempo nos testes e na demonstração.
 */
@Component
public class Relogio {

  public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

  private volatile Instant fixo;

  public Instant agora() {
    return fixo != null ? fixo : Instant.now();
  }

  /** Data de hoje no fuso de Brasília. */
  public LocalDate hoje() {
    return LocalDate.ofInstant(agora(), BRASILIA);
  }

  /** Fixa o relógio ao meio-dia (horário de Brasília) da data informada; null volta ao relógio real. */
  public void fixar(LocalDate data) {
    fixo = data == null ? null : data.atTime(LocalTime.NOON).toInstant(ZoneOffset.ofHours(-3));
  }

  public void fixar(String dataIso) {
    fixar(dataIso == null ? null : LocalDate.parse(dataIso));
  }
}
