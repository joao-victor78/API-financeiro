package br.com.erp.financeiro.comum.auth;

import java.util.List;
import java.util.UUID;

/**
 * Identidade extraída do JWT. O tenant vem SEMPRE daqui, nunca do corpo/query da requisição.
 *
 * @param tipo "usuario" ou "servico" (outro módulo chamando a API)
 */
public record AuthUser(UUID sub, UUID tenantId, List<String> permissoes, String tipo, String nome) {

  /** Identidade usada pelos processos automáticos (agendador) na auditoria. */
  public static final UUID SISTEMA_ID = new UUID(0, 0);

  public static AuthUser sistema(UUID tenant) {
    return new AuthUser(SISTEMA_ID, tenant, List.of(Permissao.ADMIN), "servico", "sistema");
  }

  /** Admin tem todas as permissões. */
  public boolean tem(String permissao) {
    return permissoes.contains(permissao) || permissoes.contains(Permissao.ADMIN);
  }
}
