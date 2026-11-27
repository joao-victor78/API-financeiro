package br.com.erp.financeiro.cadastros;

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

/** Conta bancária ou caixa (§32): origem dos extratos conciliados e do saldo do fluxo de caixa. */
@Entity
@Table(name = "contas_bancarias")
public class ContaBancaria extends EntidadeBase {

  public enum Tipo { CORRENTE, POUPANCA, PAGAMENTO, CAIXA }

  @Column(nullable = false)
  private String nome;

  @Column(name = "banco_codigo")
  private String bancoCodigo;

  private String agencia;
  private String numero;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Tipo tipo;

  @Column(name = "saldo_inicial", nullable = false)
  private BigDecimal saldoInicial = Dinheiro.ZERO;

  @Column(name = "data_saldo_inicial", nullable = false)
  private LocalDate dataSaldoInicial;

  @Column(name = "conta_contabil")
  private String contaContabil;

  /** Conta em que caem os recebimentos do provedor; usada na baixa e no fluxo realizado. */
  @Column(name = "padrao_recebimento", nullable = false)
  private boolean padraoRecebimento;

  @Column(nullable = false)
  private boolean ativa = true;

  protected ContaBancaria() {}

  public ContaBancaria(String nome, Tipo tipo, LocalDate dataSaldoInicial) {
    this.nome = nome;
    this.tipo = tipo;
    this.dataSaldoInicial = dataSaldoInicial;
  }

  public void configurar(String nome, String bancoCodigo, String agencia, String numero, Tipo tipo, BigDecimal saldoInicial,
      LocalDate dataSaldoInicial, String contaContabil, boolean padraoRecebimento, boolean ativa) {
    this.nome = nome;
    this.bancoCodigo = bancoCodigo;
    this.agencia = agencia;
    this.numero = numero;
    this.tipo = tipo;
    this.saldoInicial = Dinheiro.de(saldoInicial);
    this.dataSaldoInicial = dataSaldoInicial;
    this.contaContabil = contaContabil;
    this.padraoRecebimento = padraoRecebimento;
    this.ativa = ativa;
  }

  public void deixarDeSerPadrao() {
    padraoRecebimento = false;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nome", nome);
    m.put("bancoCodigo", bancoCodigo);
    m.put("agencia", agencia);
    m.put("numero", numero);
    m.put("tipo", tipo);
    m.put("saldoInicial", saldoInicial);
    m.put("dataSaldoInicial", dataSaldoInicial);
    m.put("contaContabil", contaContabil);
    m.put("padraoRecebimento", padraoRecebimento);
    m.put("ativa", ativa);
    return m;
  }

  public String getNome() {
    return nome;
  }

  public String getBancoCodigo() {
    return bancoCodigo;
  }

  public String getAgencia() {
    return agencia;
  }

  public String getNumero() {
    return numero;
  }

  public Tipo getTipo() {
    return tipo;
  }

  public BigDecimal getSaldoInicial() {
    return saldoInicial;
  }

  public LocalDate getDataSaldoInicial() {
    return dataSaldoInicial;
  }

  public String getContaContabil() {
    return contaContabil;
  }

  public boolean isPadraoRecebimento() {
    return padraoRecebimento;
  }

  public boolean isAtiva() {
    return ativa;
  }
}
