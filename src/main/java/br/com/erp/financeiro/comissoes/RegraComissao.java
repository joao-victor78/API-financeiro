package br.com.erp.financeiro.comissoes;

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
 * Regra de comissão (§71): por vendedor, SDR ou parceiro, opcionalmente só de um serviço ou fabricante.
 * O valor é em R$ na base FIXA e em % nas demais.
 */
@Entity
@Table(name = "regras_comissao")
public class RegraComissao extends EntidadeBase {

  public enum Beneficiario { VENDEDOR, SDR, PARCEIRO }

  /**
   * FIXA: valor fixo, uma vez, no primeiro pagamento da venda (da recorrência ou da cobrança avulsa).
   * PERCENTUAL: % de cada pagamento de cobrança avulsa (implantação, projeto, venda única).
   * SOBRE_PRIMEIRA_MENSALIDADE: % do pagamento da primeira cobrança da recorrência.
   * SOBRE_MRR: % do MRR do contrato, uma vez, no primeiro pagamento da recorrência.
   * SOBRE_MARGEM: % da margem (pago − custo dos itens) de cada pagamento.
   * RECORRENTE: % de cada pagamento de cobrança recorrente, por N meses (vazio = enquanto durar).
   */
  public enum Base { FIXA, PERCENTUAL, SOBRE_PRIMEIRA_MENSALIDADE, SOBRE_MRR, SOBRE_MARGEM, RECORRENTE }

  @Column(nullable = false)
  private String nome;

  @Enumerated(EnumType.STRING)
  @Column(name = "beneficiario_tipo", nullable = false)
  private Beneficiario beneficiarioTipo;

  @Column(name = "beneficiario_id")
  private UUID beneficiarioId;

  @Column(name = "servico_id")
  private UUID servicoId;

  private String fabricante;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Base base;

  @Column(nullable = false)
  private BigDecimal valor;

  @Column(name = "meses_recorrencia")
  private Integer mesesRecorrencia;

  @Column(nullable = false)
  private int prioridade = 100;

  @Column(nullable = false)
  private boolean ativa = true;

  @Column(name = "vigencia_inicio")
  private LocalDate vigenciaInicio;

  @Column(name = "vigencia_fim")
  private LocalDate vigenciaFim;

  protected RegraComissao() {}

  public RegraComissao(String nome) {
    this.nome = nome;
  }

  public void configurar(String nome, Beneficiario tipo, UUID beneficiarioId, UUID servicoId, String fabricante, Base base,
      BigDecimal valor, Integer mesesRecorrencia, int prioridade, boolean ativa, LocalDate inicio, LocalDate fim) {
    this.nome = nome;
    this.beneficiarioTipo = tipo;
    this.beneficiarioId = beneficiarioId;
    this.servicoId = servicoId;
    this.fabricante = fabricante;
    this.base = base;
    this.valor = valor;
    this.mesesRecorrencia = mesesRecorrencia;
    this.prioridade = prioridade;
    this.ativa = ativa;
    this.vigenciaInicio = inicio;
    this.vigenciaFim = fim;
  }

  public boolean vigenteEm(LocalDate d) {
    return ativa && getDeletedAt() == null && (vigenciaInicio == null || !d.isBefore(vigenciaInicio))
        && (vigenciaFim == null || !d.isAfter(vigenciaFim));
  }

  /** Quanto mais específica, mais forte: beneficiário (4), serviço (2), fabricante (1). */
  public int especificidade() {
    return (beneficiarioId != null ? 4 : 0) + (servicoId != null ? 2 : 0) + (fabricante != null ? 1 : 0);
  }

  public boolean filtraItens() {
    return servicoId != null || fabricante != null;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nome", nome);
    m.put("beneficiarioTipo", beneficiarioTipo);
    m.put("beneficiarioId", beneficiarioId);
    m.put("servicoId", servicoId);
    m.put("fabricante", fabricante);
    m.put("base", base);
    m.put("valor", valor);
    m.put("mesesRecorrencia", mesesRecorrencia);
    m.put("prioridade", prioridade);
    m.put("ativa", ativa);
    m.put("vigenciaInicio", vigenciaInicio);
    m.put("vigenciaFim", vigenciaFim);
    return m;
  }

  public String getNome() {
    return nome;
  }

  public Beneficiario getBeneficiarioTipo() {
    return beneficiarioTipo;
  }

  public UUID getBeneficiarioId() {
    return beneficiarioId;
  }

  public UUID getServicoId() {
    return servicoId;
  }

  public String getFabricante() {
    return fabricante;
  }

  public Base getBase() {
    return base;
  }

  public BigDecimal getValor() {
    return valor;
  }

  public Integer getMesesRecorrencia() {
    return mesesRecorrencia;
  }

  public int getPrioridade() {
    return prioridade;
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
