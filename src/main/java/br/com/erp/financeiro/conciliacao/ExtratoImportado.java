package br.com.erp.financeiro.conciliacao;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Um arquivo OFX importado (§32). O SHA-256 impede importar o mesmo arquivo duas vezes na conta. */
@Entity
@Table(name = "extratos_importados")
public class ExtratoImportado extends EntidadeBase {

  @Column(name = "conta_bancaria_id", nullable = false)
  private UUID contaBancariaId;

  @Column(name = "nome_arquivo", nullable = false)
  private String nomeArquivo;

  @Column(nullable = false, length = 64)
  private String sha256;

  @Column(name = "documento_id")
  private UUID documentoId;

  @Column(name = "periodo_inicio")
  private LocalDate periodoInicio;

  @Column(name = "periodo_fim")
  private LocalDate periodoFim;

  @Column(name = "saldo_final")
  private BigDecimal saldoFinal;

  @Column(nullable = false)
  private int lancamentos;

  @Column(nullable = false)
  private int novos;

  @Column(name = "conciliados_auto", nullable = false)
  private int conciliadosAuto;

  protected ExtratoImportado() {}

  public ExtratoImportado(UUID contaBancariaId, String nomeArquivo, String sha256, UUID documentoId, LocalDate inicio,
      LocalDate fim, BigDecimal saldoFinal) {
    this.contaBancariaId = contaBancariaId;
    this.nomeArquivo = nomeArquivo;
    this.sha256 = sha256;
    this.documentoId = documentoId;
    this.periodoInicio = inicio;
    this.periodoFim = fim;
    this.saldoFinal = saldoFinal;
  }

  public void totalizar(int lancamentos, int novos, int conciliadosAuto) {
    this.lancamentos = lancamentos;
    this.novos = novos;
    this.conciliadosAuto = conciliadosAuto;
  }

  public UUID getContaBancariaId() {
    return contaBancariaId;
  }

  public String getNomeArquivo() {
    return nomeArquivo;
  }

  public String getSha256() {
    return sha256;
  }

  public UUID getDocumentoId() {
    return documentoId;
  }

  public LocalDate getPeriodoInicio() {
    return periodoInicio;
  }

  public LocalDate getPeriodoFim() {
    return periodoFim;
  }

  public BigDecimal getSaldoFinal() {
    return saldoFinal;
  }

  public int getLancamentos() {
    return lancamentos;
  }

  public int getNovos() {
    return novos;
  }

  public int getConciliadosAuto() {
    return conciliadosAuto;
  }
}
