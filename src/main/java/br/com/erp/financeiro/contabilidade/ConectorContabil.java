package br.com.erp.financeiro.contabilidade;

import java.time.YearMonth;
import java.util.List;

/**
 * Conector com sistema contábil (§37): Alterdata, Domínio, Omie, Conta Azul, Questor, Contmatic, SCI.
 * Cada sistema entra como uma implementação nova (API quando houver, senão layout de arquivo próprio),
 * sem mudar o fechamento: ele só chama {@link #enviar}. Nesta versão existe o conector de arquivo
 * (pacote ZIP baixado pelo escritório no portal da contabilidade).
 */
public interface ConectorContabil {

  /** Código do sistema (ARQUIVO, ALTERDATA, DOMINIO, OMIE, CONTA_AZUL, QUESTOR, CONTMATIC, SCI). */
  String codigo();

  String nome();

  /** Credenciais e layout configurados para este tenant. */
  boolean configurado();

  /** Envia (ou disponibiliza) os lançamentos da competência; devolve um texto para a auditoria/timeline. */
  String enviar(YearMonth competencia, List<LancamentosContabeis.Lancamento> lancamentos, byte[] pacote);

  /** Os sistemas que o Prompt Mestre lista, com a situação de cada um, para a tela de integrações. */
  List<String> SISTEMAS_PREVISTOS = List.of("ALTERDATA", "DOMINIO", "OMIE", "CONTA_AZUL", "QUESTOR", "CONTMATIC", "SCI");
}
