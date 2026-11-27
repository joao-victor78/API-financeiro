package br.com.erp.financeiro.documentos;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Arquivo da biblioteca financeira (§96): comprovante, nota, boleto, extrato... ligado a um registro. */
@Entity
@Table(name = "documentos")
public class Documento extends EntidadeBase {

  public enum Categoria { COMPROVANTE, NOTA_FISCAL, BOLETO, EXTRATO, CONTRATO, RECIBO, RELATORIO, OUTRO }

  @Column(name = "nome_arquivo", nullable = false)
  private String nomeArquivo;

  @Column(name = "tipo_conteudo", nullable = false)
  private String tipoConteudo;

  @Column(name = "tamanho_bytes", nullable = false)
  private long tamanhoBytes;

  @Column(nullable = false, length = 64)
  private String sha256;

  @Column(name = "chave_armazenamento", nullable = false)
  private String chaveArmazenamento;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Categoria categoria;

  private String entidade;

  @Column(name = "registro_id")
  private UUID registroId;

  private String descricao;

  protected Documento() {}

  public Documento(String nomeArquivo, String tipoConteudo, long tamanhoBytes, String sha256, String chave,
      Categoria categoria, String entidade, UUID registroId, String descricao) {
    this.nomeArquivo = nomeArquivo;
    this.tipoConteudo = tipoConteudo;
    this.tamanhoBytes = tamanhoBytes;
    this.sha256 = sha256;
    this.chaveArmazenamento = chave;
    this.categoria = categoria;
    this.entidade = entidade;
    this.registroId = registroId;
    this.descricao = descricao;
  }

  public void vincular(String entidade, UUID registroId) {
    this.entidade = entidade;
    this.registroId = registroId;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nomeArquivo", nomeArquivo);
    m.put("tipoConteudo", tipoConteudo);
    m.put("tamanhoBytes", tamanhoBytes);
    m.put("sha256", sha256);
    m.put("categoria", categoria);
    m.put("entidade", entidade);
    m.put("registroId", registroId);
    return m;
  }

  public String getNomeArquivo() {
    return nomeArquivo;
  }

  public String getTipoConteudo() {
    return tipoConteudo;
  }

  public long getTamanhoBytes() {
    return tamanhoBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public String getChaveArmazenamento() {
    return chaveArmazenamento;
  }

  public Categoria getCategoria() {
    return categoria;
  }

  public String getEntidade() {
    return entidade;
  }

  public UUID getRegistroId() {
    return registroId;
  }

  public String getDescricao() {
    return descricao;
  }
}
