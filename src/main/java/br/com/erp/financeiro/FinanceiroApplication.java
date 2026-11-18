package br.com.erp.financeiro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Ponto de entrada do Módulo Financeiro (API REST em http://localhost:3008/api/financeiro). */
@SpringBootApplication
@EnableScheduling
public class FinanceiroApplication {

  public static void main(String[] args) {
    SpringApplication.run(FinanceiroApplication.class, args);
  }
}
