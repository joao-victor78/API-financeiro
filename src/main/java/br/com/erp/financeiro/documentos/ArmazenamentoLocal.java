package br.com.erp.financeiro.documentos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Desenvolvimento e testes: grava em disco (ARMAZENAMENTO_DIR). Em produção use ARMAZENAMENTO_TIPO=s3. */
@Component
@ConditionalOnProperty(name = "financeiro.armazenamento.tipo", havingValue = "local", matchIfMissing = true)
public class ArmazenamentoLocal implements Armazenamento {

  private final Path raiz;

  public ArmazenamentoLocal(@Value("${financeiro.armazenamento.diretorio:./dados/documentos}") String diretorio) {
    this.raiz = Path.of(diretorio).toAbsolutePath().normalize();
  }

  @Override
  public String tipo() {
    return "local";
  }

  @Override
  public void salvar(String chave, byte[] conteudo, String tipoConteudo) {
    Path destino = resolver(chave);
    try {
      Files.createDirectories(destino.getParent());
      Files.write(destino, conteudo, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new UncheckedIOException("Não foi possível gravar o arquivo", e);
    }
  }

  @Override
  public byte[] ler(String chave) {
    try {
      return Files.readAllBytes(resolver(chave));
    } catch (IOException e) {
      throw new UncheckedIOException("Arquivo indisponível no armazenamento", e);
    }
  }

  /** A chave é gerada pelo módulo, mas a resolução ainda impede sair da raiz (path traversal). */
  private Path resolver(String chave) {
    Path p = raiz.resolve(chave).normalize();
    if (!p.startsWith(raiz)) throw new IllegalArgumentException("Chave de armazenamento inválida");
    return p;
  }
}
