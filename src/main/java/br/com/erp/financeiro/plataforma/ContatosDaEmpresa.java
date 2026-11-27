package br.com.erp.financeiro.plataforma;

import java.util.Optional;
import java.util.UUID;

/** E-mail de contato da empresa para a régua (o dado é do CRM; o Financeiro não guarda, RN-03). */
public interface ContatosDaEmpresa {

  Optional<String> email(UUID tenant, UUID empresaId);

  /** Razão social e CNPJ/CPF da empresa no CRM: o provedor de pagamento real exige o cliente (V2, §28). */
  record DadosEmpresa(String razaoSocial, String documento, String email) {}

  default Optional<DadosEmpresa> dados(UUID tenant, UUID empresaId) {
    return Optional.empty();
  }
}
