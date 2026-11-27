package br.com.erp.financeiro.fiscal;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Nota fiscal emitida (faturamento, §23) ou recebida de fornecedor (§35). O módulo registra a nota, liga
 * à cobrança ou à conta a pagar e guarda o XML/PDF; a emissão continua no sistema fiscal (§33).
 */
@Entity
@Table(name = "notas_fiscais")
public class NotaFiscal extends EntidadeBase {

  public enum Tipo { EMITIDA, RECEBIDA }

  public enum Modelo { NFSE, NFE, NFCE, OUTRO }

  public enum Status { AUTORIZADA, CANCELADA }

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Tipo tipo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Modelo modelo = Modelo.NFSE;

  @Column(nullable = false)
  private String numero;

  private String serie;

  @Column(name = "chave_acesso")
  private String chaveAcesso;

  @Column(name = "data_emissao", nullable = false)
  private LocalDate dataEmissao;

  @Column(nullable = false, length = 7)
  private String competencia;

  @Column(nullable = false)
  private BigDecimal valor;

  @Column(name = "valor_impostos", nullable = false)
  private BigDecimal valorImpostos = Dinheiro.ZERO;

  @Column(name = "empresa_id")
  private UUID empresaId;

  @Column(name = "participante_nome")
  private String participanteNome;

  @Column(name = "participante_documento")
  private String participanteDocumento;

  @Column(name = "cobranca_id")
  private UUID cobrancaId;

  @Column(name = "conta_pagar_id")
  private UUID contaPagarId;

  @Column(name = "documento_id")
  private UUID documentoId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.AUTORIZADA;

  private String observacao;

  protected NotaFiscal() {}

  public NotaFiscal(Tipo tipo, Modelo modelo, String numero, String serie, String chaveAcesso, LocalDate dataEmissao,
      String competencia, BigDecimal valor, BigDecimal valorImpostos) {
    this.tipo = tipo;
    this.modelo = modelo;
    this.numero = numero;
    this.serie = serie;
    this.chaveAcesso = chaveAcesso;
    this.dataEmissao = dataEmissao;
    this.competencia = competencia;
    this.valor = Dinheiro.de(valor);
    this.valorImpostos = Dinheiro.de(valorImpostos);
  }

  public void vincular(UUID empresaId, String participanteNome, String participanteDocumento, UUID cobrancaId,
      UUID contaPagarId, UUID documentoId, String observacao) {
    this.empresaId = empresaId;
    this.participanteNome = participanteNome;
    this.participanteDocumento = participanteDocumento;
    this.cobrancaId = cobrancaId;
    this.contaPagarId = contaPagarId;
    this.documentoId = documentoId;
    this.observacao = observacao;
  }

  public void anexar(UUID documentoId) {
    this.documentoId = documentoId;
  }

  public void cancelar(String motivo) {
    status = Status.CANCELADA;
    observacao = motivo;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tipo", tipo);
    m.put("modelo", modelo);
    m.put("numero", numero);
    m.put("serie", serie);
    m.put("chaveAcesso", chaveAcesso);
    m.put("dataEmissao", dataEmissao);
    m.put("competencia", competencia);
    m.put("valor", valor);
    m.put("valorImpostos", valorImpostos);
    m.put("empresaId", empresaId);
    m.put("participanteDocumento", participanteDocumento);
    m.put("cobrancaId", cobrancaId);
    m.put("contaPagarId", contaPagarId);
    m.put("documentoId", documentoId);
    m.put("status", status);
    return m;
  }

  public Tipo getTipo() {
    return tipo;
  }

  public Modelo getModelo() {
    return modelo;
  }

  public String getNumero() {
    return numero;
  }

  public String getSerie() {
    return serie;
  }

  public String getChaveAcesso() {
    return chaveAcesso;
  }

  public LocalDate getDataEmissao() {
    return dataEmissao;
  }

  public String getCompetencia() {
    return competencia;
  }

  public BigDecimal getValor() {
    return valor;
  }

  public BigDecimal getValorImpostos() {
    return valorImpostos;
  }

  public UUID getEmpresaId() {
    return empresaId;
  }

  public String getParticipanteNome() {
    return participanteNome;
  }

  public String getParticipanteDocumento() {
    return participanteDocumento;
  }

  public UUID getCobrancaId() {
    return cobrancaId;
  }

  public UUID getContaPagarId() {
    return contaPagarId;
  }

  public UUID getDocumentoId() {
    return documentoId;
  }

  public Status getStatus() {
    return status;
  }

  public String getObservacao() {
    return observacao;
  }
}
