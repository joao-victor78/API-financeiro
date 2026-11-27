package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.cobrancas.MeioPagamento;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Regra de cobrança recorrente de uma empresa (RF-03). O valor do ciclo vem sempre dos itens (RN-14). */
@Entity
@Table(name = "recorrencias")
public class Recorrencia extends EntidadeBase {

  @Column(name = "empresa_id", nullable = false)
  private UUID empresaId;

  @Column(name = "contrato_id")
  private UUID contratoId;

  private String descricao;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Periodicidade periodicidade = Periodicidade.MENSAL;

  @Column(name = "dia_geracao", nullable = false)
  private int diaGeracao;

  @Column(name = "dias_para_vencimento", nullable = false)
  private int diasParaVencimento = 10;

  @Column(name = "proxima_competencia", nullable = false, length = 7)
  private String proximaCompetencia;

  @Enumerated(EnumType.STRING)
  @Column(name = "forma_pagamento")
  private MeioPagamento formaPagamento;

  @Column(nullable = false)
  private boolean ativa = true;

  @Column(name = "vigencia_inicio", nullable = false)
  private LocalDate vigenciaInicio;

  @Column(name = "vigencia_fim")
  private LocalDate vigenciaFim;

  // ------------------------------------------------------------------ V2 (todos opcionais)

  @Column(name = "vendedor_id")
  private UUID vendedorId;

  @Column(name = "sdr_id")
  private UUID sdrId;

  @Column(name = "parceiro_id")
  private UUID parceiroId;

  @Column(name = "categoria_id")
  private UUID categoriaId;

  @Column(name = "centro_custo_id")
  private UUID centroCustoId;

  /** Cartão recorrente (§27): cobra o cartão tokenizado no dia da geração. */
  @Column(name = "cobranca_automatica", nullable = false)
  private boolean cobrancaAutomatica;

  @Column(name = "cartao_token_ref")
  private String cartaoTokenRef;

  @Column(name = "cartao_final")
  private String cartaoFinal;

  @Column(name = "cartao_bandeira")
  private String cartaoBandeira;

  @Enumerated(EnumType.STRING)
  @Column(name = "indice_reajuste")
  private IndiceReajuste indiceReajuste;

  @Column(name = "mes_reajuste")
  private Integer mesReajuste;

  protected Recorrencia() {}

  public Recorrencia(UUID empresaId, UUID contratoId, String primeiraCompetencia, LocalDate vigenciaInicio) {
    this.empresaId = empresaId;
    this.contratoId = contratoId;
    this.proximaCompetencia = primeiraCompetencia;
    this.vigenciaInicio = vigenciaInicio;
  }

  public void configurar(UUID empresaId, UUID contratoId, String descricao, Periodicidade periodicidade, int diaGeracao,
      int diasParaVencimento, MeioPagamento formaPagamento) {
    this.empresaId = empresaId;
    this.contratoId = contratoId;
    this.descricao = descricao;
    this.periodicidade = periodicidade;
    this.diaGeracao = diaGeracao;
    this.diasParaVencimento = diasParaVencimento;
    this.formaPagamento = formaPagamento;
  }

  /** Data em que o ciclo da competência deve ser gerado. */
  public LocalDate dataDeGeracao(String competencia) {
    return Competencia.mes(competencia).atDay(diaGeracao);
  }

  public LocalDate vencimentoDe(String competencia) {
    return dataDeGeracao(competencia).plusDays(diasParaVencimento);
  }

  /** Depois de gerar um ciclo, a próxima competência anda uma periodicidade a partir dele. */
  public void avancarDepoisDe(String competenciaGerada) {
    String seguinte = Competencia.somarMeses(competenciaGerada, periodicidade.meses());
    if (seguinte.compareTo(proximaCompetencia) > 0) proximaCompetencia = seguinte;
  }

  public void ativar() {
    ativa = true;
    vigenciaFim = null;
  }

  /** Contrato cancelado ou regra encerrada: não gera mais e entra como churn (RN-43). */
  public void encerrar(LocalDate fim) {
    ativa = false;
    vigenciaFim = fim;
  }

  /** Campos da V2: quem vendeu, classificação e reajuste anual. */
  public void configurarComplementos(UUID vendedorId, UUID sdrId, UUID parceiroId, UUID categoriaId, UUID centroCustoId,
      IndiceReajuste indiceReajuste, Integer mesReajuste) {
    this.vendedorId = vendedorId;
    this.sdrId = sdrId;
    this.parceiroId = parceiroId;
    this.categoriaId = categoriaId;
    this.centroCustoId = centroCustoId;
    this.indiceReajuste = indiceReajuste;
    this.mesReajuste = mesReajuste;
  }

  /** Guarda só a referência do cartão no provedor, o final e a bandeira (RN-22). */
  public void registrarCartao(String tokenRef, String fim, String bandeira, boolean automatica) {
    this.cartaoTokenRef = tokenRef;
    this.cartaoFinal = fim;
    this.cartaoBandeira = bandeira;
    this.cobrancaAutomatica = automatica;
  }

  public void removerCartao() {
    this.cartaoTokenRef = null;
    this.cartaoFinal = null;
    this.cartaoBandeira = null;
    this.cobrancaAutomatica = false;
  }

  /** Meses da periodicidade, para o MRR do ciclo. */
  public int mesesDoCiclo() {
    return periodicidade.meses();
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("empresaId", empresaId);
    m.put("contratoId", contratoId);
    m.put("descricao", descricao);
    m.put("periodicidade", periodicidade);
    m.put("diaGeracao", diaGeracao);
    m.put("diasParaVencimento", diasParaVencimento);
    m.put("proximaCompetencia", proximaCompetencia);
    m.put("formaPagamento", formaPagamento);
    m.put("ativa", ativa);
    m.put("vigenciaFim", vigenciaFim);
    if (vendedorId != null) m.put("vendedorId", vendedorId);
    if (categoriaId != null) m.put("categoriaId", categoriaId);
    if (cobrancaAutomatica) m.put("cobrancaAutomatica", true);
    if (cartaoFinal != null) m.put("cartaoFinal", cartaoFinal);
    if (indiceReajuste != null) m.put("indiceReajuste", indiceReajuste);
    if (mesReajuste != null) m.put("mesReajuste", mesReajuste);
    return m;
  }

  public UUID getVendedorId() {
    return vendedorId;
  }

  public UUID getSdrId() {
    return sdrId;
  }

  public UUID getParceiroId() {
    return parceiroId;
  }

  public UUID getCategoriaId() {
    return categoriaId;
  }

  public UUID getCentroCustoId() {
    return centroCustoId;
  }

  public boolean isCobrancaAutomatica() {
    return cobrancaAutomatica;
  }

  public String getCartaoTokenRef() {
    return cartaoTokenRef;
  }

  public String getCartaoFinal() {
    return cartaoFinal;
  }

  public String getCartaoBandeira() {
    return cartaoBandeira;
  }

  public IndiceReajuste getIndiceReajuste() {
    return indiceReajuste;
  }

  public Integer getMesReajuste() {
    return mesReajuste;
  }

  public UUID getEmpresaId() {
    return empresaId;
  }

  public UUID getContratoId() {
    return contratoId;
  }

  public String getDescricao() {
    return descricao;
  }

  public Periodicidade getPeriodicidade() {
    return periodicidade;
  }

  public int getDiaGeracao() {
    return diaGeracao;
  }

  public int getDiasParaVencimento() {
    return diasParaVencimento;
  }

  public String getProximaCompetencia() {
    return proximaCompetencia;
  }

  public MeioPagamento getFormaPagamento() {
    return formaPagamento;
  }

  public boolean isAtiva() {
    return ativa;
  }

  public LocalDate getVigenciaInicio() {
    return vigenciaInicio;
  }

  public LocalDate getVigenciaFim() {
    return vigenciaFim;
  }
}
