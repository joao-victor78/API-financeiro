package br.com.erp.financeiro.contabilidade;

import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Component;

/** Conector padrão: o pacote do fechamento fica na biblioteca de documentos para o escritório baixar. */
@Component
public class ConectorArquivo implements ConectorContabil {

  @Override
  public String codigo() {
    return "ARQUIVO";
  }

  @Override
  public String nome() {
    return "Pacote de arquivos (CSV, XLSX, OFX, PDF)";
  }

  @Override
  public boolean configurado() {
    return true;
  }

  @Override
  public String enviar(YearMonth competencia, List<LancamentosContabeis.Lancamento> lancamentos, byte[] pacote) {
    return "Pacote com " + lancamentos.size() + " lançamento(s) disponível no portal da contabilidade.";
  }
}
