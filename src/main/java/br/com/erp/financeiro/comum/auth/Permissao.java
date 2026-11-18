package br.com.erp.financeiro.comum.auth;

/** Permissões do módulo presentes no claim "permissoes" do token. */
public final class Permissao {

  public static final String LEITURA = "financeiro:leitura";
  public static final String ESCRITA = "financeiro:escrita";
  public static final String ADMIN = "financeiro:admin";

  private Permissao() {}
}
