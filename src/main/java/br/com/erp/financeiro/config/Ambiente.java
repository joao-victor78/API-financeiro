package br.com.erp.financeiro.config;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Configuração do módulo lida do .env / variáveis de ambiente (mesmos nomes usados pelo frontend e pela
 * documentação). Centralizar aqui evita espalhar nomes de variáveis pelo código.
 */
@Component
public class Ambiente {

  public static final String DATABASE_URL_PADRAO = "postgres://financeiro:financeiro@localhost:5432/erp";

  private final Environment env;

  public Ambiente(Environment env) {
    this.env = env;
  }

  private String valor(String nome, String padrao) {
    String v = env.getProperty(nome);
    return v == null || v.isBlank() ? padrao : v.trim();
  }

  public String databaseUrl() {
    return valor("DATABASE_URL", DATABASE_URL_PADRAO);
  }

  /** Chave pública RS256 do módulo de Autenticação: JWT_PUBLIC_KEY (PEM) ou o arquivo de JWT_PUBLIC_KEY_PATH. */
  public String chavePublicaJwt() {
    String pem = env.getProperty("JWT_PUBLIC_KEY");
    if (pem != null && !pem.isBlank()) return pem.replace("\\n", "\n");
    Path caminho = Path.of(valor("JWT_PUBLIC_KEY_PATH", "./keys/auth-public.pem"));
    try {
      return Files.exists(caminho) ? Files.readString(caminho) : "";
    } catch (IOException e) {
      return "";
    }
  }

  /** Emissor exigido no token (claim iss); vazio = não verifica. */
  public String emissorJwt() {
    return valor("JWT_ISSUER", null);
  }

  public String pixWebhookSecret() {
    return valor("PIX_WEBHOOK_SECRET", "troque-este-segredo");
  }

  public String pixChaveRecebedor() {
    return valor("PIX_CHAVE_RECEBEDOR", "financeiro@empresa.com.br");
  }

  /** Aparece no app do pagador ao ler o QR Code (até 25 caracteres, sem acento). */
  public String pixNomeRecebedor() {
    return valor("PIX_NOME_RECEBEDOR", "EMPRESA");
  }

  /** Até 15 caracteres, sem acento. */
  public String pixCidadeRecebedor() {
    return valor("PIX_CIDADE_RECEBEDOR", "GOIANIA");
  }

  public boolean jobsAtivos() {
    return Boolean.parseBoolean(valor("JOBS_ENABLED", "true"));
  }

  public BigDecimal multaPercentual() {
    return new BigDecimal(valor("MULTA_PERCENTUAL", "2"));
  }

  public BigDecimal jurosMensalPercentual() {
    return new BigDecimal(valor("JUROS_MENSAL_PERCENTUAL", "1"));
  }
}
