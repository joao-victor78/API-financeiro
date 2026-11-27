package br.com.erp.financeiro.contabilidade;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Fechamento contábil mensal (§35, §36). */
@Entity
@Table(name = "fechamentos_contabeis")
public class FechamentoContabil extends EntidadeBase {

  /** §36, na ordem do fluxo. */
  public enum Status {
    NAO_INICIADO, EM_CONFERENCIA, COM_PENDENCIA, AGUARDANDO_DOCUMENTO, PRONTO, ENVIADO_PARA_CONTABILIDADE, EM_ANALISE, FINALIZADO
  }

  /** §35, checklist. */
  public enum Item {
    RECEITAS_CONFERIDAS, DESPESAS_CONFERIDAS, EXTRATOS_CONFERIDOS, NOTAS_EMITIDAS, NOTAS_RECEBIDAS, COMPROVANTES, COMISSOES,
    IMPOSTOS, LANCAMENTOS_CLASSIFICADOS, DOCUMENTACAO_COMPLETA
  }

  @Column(nullable = false, length = 7)
  private String competencia;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status = Status.NAO_INICIADO;

  private String pendencias;

  @Column(name = "enviado_em")
  private OffsetDateTime enviadoEm;

  @Column(name = "enviado_por")
  private UUID enviadoPor;

  @Column(name = "finalizado_em")
  private OffsetDateTime finalizadoEm;

  @Column(name = "finalizado_por")
  private UUID finalizadoPor;

  protected FechamentoContabil() {}

  public FechamentoContabil(String competencia) {
    this.competencia = competencia;
  }

  public void mudar(Status novo, String pendencias, UUID usuario, OffsetDateTime quando) {
    this.status = novo;
    if (pendencias != null) this.pendencias = pendencias;
    if (novo == Status.ENVIADO_PARA_CONTABILIDADE) {
      enviadoEm = quando;
      enviadoPor = usuario;
    }
    if (novo == Status.FINALIZADO) {
      finalizadoEm = quando;
      finalizadoPor = usuario;
    }
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("competencia", competencia);
    m.put("status", status);
    m.put("pendencias", pendencias);
    m.put("enviadoEm", enviadoEm);
    m.put("finalizadoEm", finalizadoEm);
    return m;
  }

  public String getCompetencia() {
    return competencia;
  }

  public Status getStatus() {
    return status;
  }

  public String getPendencias() {
    return pendencias;
  }

  public OffsetDateTime getEnviadoEm() {
    return enviadoEm;
  }

  public UUID getEnviadoPor() {
    return enviadoPor;
  }

  public OffsetDateTime getFinalizadoEm() {
    return finalizadoEm;
  }

  public UUID getFinalizadoPor() {
    return finalizadoPor;
  }
}
