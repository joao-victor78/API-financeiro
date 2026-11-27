package br.com.erp.financeiro.contaspagar;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Despesa com fornecedor (RF-02, RN-09). Fornecedor é cadastro simplificado na própria conta (RN-10). */
@Entity
@Table(name = "contas_pagar")
public class ContaPagar extends EntidadeBase {

  public enum Status { PENDENTE, APROVADA, PAGA, CANCELADA }

  @Column(name = "fornecedor_nome", nullable = false)
  private String fornecedorNome;

  @Column(name = "fornecedor_documento")
  private String fornecedorDocumento;

  private String categoria;

  @Column(name = "centro_custo")
  private String centroCusto;

  @Column(nullable = false, length = 7)
  private String competencia;

  @Column(name = "data_vencimento", nullable = false)
  private LocalDate dataVencimento;

  @Column(nullable = false)
  private BigDecimal valor;

  private String documento;
  private String observacoes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.PENDENTE;

  @Column(name = "aprovado_por")
  private UUID aprovadoPor;

  @Column(name = "aprovado_em")
  private OffsetDateTime aprovadoEm;

  @Column(name = "data_pagamento")
  private LocalDate dataPagamento;

  @Column(name = "valor_pago")
  private BigDecimal valorPago;

  // ------------------------------------------------------------------ V2 (opcionais)

  @Column(name = "categoria_id")
  private UUID categoriaId;

  @Column(name = "centro_custo_id")
  private UUID centroCustoId;

  /** Conta bancária de onde o pagamento saiu (fluxo de caixa e conciliação, §32). */
  @Column(name = "conta_bancaria_id")
  private UUID contaBancariaId;

  /** Arquivo do documento (NF, boleto) na biblioteca de documentos (§25 "documento", §96). */
  @Column(name = "documento_id")
  private UUID documentoId;

  protected ContaPagar() {}

  /** Liga a conta aos cadastros; o texto livre recebe o nome do cadastro quando vier vazio. */
  public void classificar(UUID categoriaId, String nomeCategoria, UUID centroCustoId, String rotuloCentro, UUID contaBancariaId,
      UUID documentoId) {
    this.categoriaId = categoriaId;
    this.centroCustoId = centroCustoId;
    this.contaBancariaId = contaBancariaId;
    this.documentoId = documentoId;
    if (nomeCategoria != null && (categoria == null || categoria.isBlank())) categoria = limitar(nomeCategoria, 60);
    if (rotuloCentro != null && (centroCusto == null || centroCusto.isBlank())) centroCusto = limitar(rotuloCentro, 60);
  }

  public void anexarDocumento(UUID documentoId) {
    this.documentoId = documentoId;
  }

  public void definirContaBancaria(UUID contaBancariaId) {
    if (contaBancariaId != null) this.contaBancariaId = contaBancariaId;
  }

  private static String limitar(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  public UUID getCategoriaId() {
    return categoriaId;
  }

  public UUID getCentroCustoId() {
    return centroCustoId;
  }

  public UUID getContaBancariaId() {
    return contaBancariaId;
  }

  public UUID getDocumentoId() {
    return documentoId;
  }

  public ContaPagar(String fornecedorNome, String fornecedorDocumento, String categoria, String centroCusto,
      String competencia, LocalDate vencimento, BigDecimal valor, String documento, String observacoes) {
    this.fornecedorNome = fornecedorNome;
    this.fornecedorDocumento = fornecedorDocumento;
    this.categoria = categoria;
    this.centroCusto = centroCusto;
    this.competencia = competencia;
    this.dataVencimento = vencimento;
    this.valor = Dinheiro.de(valor);
    this.documento = documento;
    this.observacoes = observacoes;
  }

  /** RN-11: a aprovação registra quem aprovou e quando. */
  public void aprovar(UUID usuario, OffsetDateTime quando) {
    status = Status.APROVADA;
    aprovadoPor = usuario;
    aprovadoEm = quando;
  }

  public void registrarPagamento(BigDecimal valorPago, LocalDate data) {
    this.valorPago = Dinheiro.de(valorPago);
    this.dataPagamento = data;
    status = Status.PAGA;
  }

  public boolean aprovada() {
    return status == Status.APROVADA || status == Status.PAGA;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("fornecedorNome", fornecedorNome);
    m.put("fornecedorDocumento", fornecedorDocumento);
    m.put("categoria", categoria);
    m.put("centroCusto", centroCusto);
    m.put("competencia", competencia);
    m.put("vencimento", dataVencimento);
    m.put("valor", valor);
    m.put("status", status);
    m.put("aprovadoPor", aprovadoPor);
    m.put("aprovadoEm", aprovadoEm);
    m.put("dataPagamento", dataPagamento);
    m.put("valorPago", valorPago);
    if (categoriaId != null) m.put("categoriaId", categoriaId);
    if (centroCustoId != null) m.put("centroCustoId", centroCustoId);
    if (contaBancariaId != null) m.put("contaBancariaId", contaBancariaId);
    if (documentoId != null) m.put("documentoId", documentoId);
    return m;
  }

  public String getFornecedorNome() {
    return fornecedorNome;
  }

  public String getFornecedorDocumento() {
    return fornecedorDocumento;
  }

  public String getCategoria() {
    return categoria;
  }

  public String getCentroCusto() {
    return centroCusto;
  }

  public String getCompetencia() {
    return competencia;
  }

  public LocalDate getDataVencimento() {
    return dataVencimento;
  }

  public BigDecimal getValor() {
    return valor;
  }

  public String getDocumento() {
    return documento;
  }

  public String getObservacoes() {
    return observacoes;
  }

  public Status getStatus() {
    return status;
  }

  public UUID getAprovadoPor() {
    return aprovadoPor;
  }

  public OffsetDateTime getAprovadoEm() {
    return aprovadoEm;
  }

  public LocalDate getDataPagamento() {
    return dataPagamento;
  }

  public BigDecimal getValorPago() {
    return valorPago;
  }
}
