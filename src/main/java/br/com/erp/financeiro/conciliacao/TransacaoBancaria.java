package br.com.erp.financeiro.conciliacao;

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

/** Lançamento do extrato bancário (§32). Valor positivo = crédito; negativo = débito. */
@Entity
@Table(name = "transacoes_bancarias")
public class TransacaoBancaria extends EntidadeBase {

  public enum Status { PENDENTE, CONCILIADA, IGNORADA }

  public enum Modo { AUTOMATICA, MANUAL }

  /** §32 "Identificar: pagamento, transferência, tarifa, juros, estorno". */
  public enum Classificacao { PAGAMENTO_RECEBIDO, PAGAMENTO_EFETUADO, TRANSFERENCIA, TARIFA, JUROS, ESTORNO, OUTRO }

  @Column(name = "conta_bancaria_id", nullable = false)
  private UUID contaBancariaId;

  @Column(name = "extrato_id")
  private UUID extratoId;

  @Column(nullable = false)
  private String identificador;

  @Column(nullable = false)
  private LocalDate data;

  @Column(nullable = false)
  private BigDecimal valor;

  private String descricao;
  private String documento;

  @Enumerated(EnumType.STRING)
  private Classificacao classificacao;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.PENDENTE;

  @Enumerated(EnumType.STRING)
  private Modo modo;

  @Column(name = "pagamento_id")
  private UUID pagamentoId;

  @Column(name = "conta_pagar_id")
  private UUID contaPagarId;

  @Column(name = "categoria_id")
  private UUID categoriaId;

  @Column(name = "conciliado_por")
  private UUID conciliadoPor;

  @Column(name = "conciliado_em")
  private OffsetDateTime conciliadoEm;

  protected TransacaoBancaria() {}

  public TransacaoBancaria(UUID contaBancariaId, UUID extratoId, String identificador, LocalDate data, BigDecimal valor,
      String descricao, String documento) {
    this.contaBancariaId = contaBancariaId;
    this.extratoId = extratoId;
    this.identificador = identificador;
    this.data = data;
    this.valor = Dinheiro.de(valor);
    this.descricao = descricao;
    this.documento = documento;
  }

  public boolean credito() {
    return valor.signum() > 0;
  }

  public void sugerir(Classificacao c) {
    if (status == Status.PENDENTE) classificacao = c;
  }

  public void conciliarComPagamento(UUID pagamentoId, Modo modo, UUID usuario, OffsetDateTime quando) {
    this.pagamentoId = pagamentoId;
    this.contaPagarId = null;
    concluir(Classificacao.PAGAMENTO_RECEBIDO, modo, usuario, quando);
  }

  public void conciliarComContaPagar(UUID contaPagarId, Modo modo, UUID usuario, OffsetDateTime quando) {
    this.contaPagarId = contaPagarId;
    this.pagamentoId = null;
    concluir(Classificacao.PAGAMENTO_EFETUADO, modo, usuario, quando);
  }

  /** Tarifa, juros, transferência entre contas, estorno ou outro, sem documento no sistema. */
  public void classificar(Classificacao c, UUID categoriaId, UUID usuario, OffsetDateTime quando) {
    this.pagamentoId = null;
    this.contaPagarId = null;
    this.categoriaId = categoriaId;
    concluir(c, Modo.MANUAL, usuario, quando);
  }

  public void ignorar(UUID usuario, OffsetDateTime quando) {
    status = Status.IGNORADA;
    modo = Modo.MANUAL;
    conciliadoPor = usuario;
    conciliadoEm = quando;
  }

  public void desfazer() {
    status = Status.PENDENTE;
    modo = null;
    pagamentoId = null;
    contaPagarId = null;
    categoriaId = null;
    conciliadoPor = null;
    conciliadoEm = null;
  }

  private void concluir(Classificacao c, Modo m, UUID usuario, OffsetDateTime quando) {
    classificacao = c;
    status = Status.CONCILIADA;
    modo = m;
    conciliadoPor = usuario;
    conciliadoEm = quando;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("data", data);
    m.put("valor", valor);
    m.put("descricao", descricao);
    m.put("status", status);
    m.put("classificacao", classificacao);
    m.put("modo", modo);
    m.put("pagamentoId", pagamentoId);
    m.put("contaPagarId", contaPagarId);
    m.put("categoriaId", categoriaId);
    return m;
  }

  public UUID getContaBancariaId() {
    return contaBancariaId;
  }

  public UUID getExtratoId() {
    return extratoId;
  }

  public String getIdentificador() {
    return identificador;
  }

  public LocalDate getData() {
    return data;
  }

  public BigDecimal getValor() {
    return valor;
  }

  public String getDescricao() {
    return descricao;
  }

  public String getDocumento() {
    return documento;
  }

  public Classificacao getClassificacao() {
    return classificacao;
  }

  public Status getStatus() {
    return status;
  }

  public Modo getModo() {
    return modo;
  }

  public UUID getPagamentoId() {
    return pagamentoId;
  }

  public UUID getContaPagarId() {
    return contaPagarId;
  }

  public UUID getCategoriaId() {
    return categoriaId;
  }

  public UUID getConciliadoPor() {
    return conciliadoPor;
  }

  public OffsetDateTime getConciliadoEm() {
    return conciliadoEm;
  }
}
