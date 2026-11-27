package br.com.erp.financeiro.documentos;

/**
 * Onde ficam os arquivos (§95): armazenamento compatível com S3 em produção, disco em desenvolvimento.
 * O banco guarda só a chave; nenhum arquivo grande vai para o PostgreSQL.
 */
public interface Armazenamento {

  /** Nome gravado nos logs e na saúde do serviço ("s3", "local"). */
  String tipo();

  void salvar(String chave, byte[] conteudo, String tipoConteudo);

  byte[] ler(String chave);
}
