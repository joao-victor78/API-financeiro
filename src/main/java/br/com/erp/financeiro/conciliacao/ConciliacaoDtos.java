package br.com.erp.financeiro.conciliacao;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ConciliacaoDtos {

  private ConciliacaoDtos() {}

  public record TransacaoSaida(UUID id, UUID contaBancariaId, UUID extratoId, String identificador, LocalDate data,
      BigDecimal valor, String descricao, String documento, TransacaoBancaria.Classificacao classificacao,
      TransacaoBancaria.Status status, TransacaoBancaria.Modo modo, UUID pagamentoId, UUID contaPagarId, UUID categoriaId,
      UUID conciliadoPor, OffsetDateTime conciliadoEm) {
    public static TransacaoSaida de(TransacaoBancaria t) {
      return new TransacaoSaida(t.getId(), t.getContaBancariaId(), t.getExtratoId(), t.getIdentificador(), t.getData(),
          t.getValor(), t.getDescricao(), t.getDocumento(), t.getClassificacao(), t.getStatus(), t.getModo(), t.getPagamentoId(),
          t.getContaPagarId(), t.getCategoriaId(), t.getConciliadoPor(), t.getConciliadoEm());
    }
  }

  public record Importacao(UUID extratoId, UUID documentoId, String nomeArquivo, LocalDate periodoInicio, LocalDate periodoFim,
      BigDecimal saldoFinal, int lancamentos, int novos, int repetidos, int conciliadosAutomaticamente, int pendentes) {}

  public record ExtratoSaida(UUID id, UUID contaBancariaId, String nomeArquivo, LocalDate periodoInicio, LocalDate periodoFim,
      BigDecimal saldoFinal, int lancamentos, int novos, int conciliadosAuto, UUID documentoId, UUID createdBy, OffsetDateTime createdAt) {
    public static ExtratoSaida de(ExtratoImportado e) {
      return new ExtratoSaida(e.getId(), e.getContaBancariaId(), e.getNomeArquivo(), e.getPeriodoInicio(), e.getPeriodoFim(),
          e.getSaldoFinal(), e.getLancamentos(), e.getNovos(), e.getConciliadosAuto(), e.getDocumentoId(), e.getCreatedBy(),
          e.getCreatedAt());
    }
  }

  /** Lançamento enviado sem arquivo: integração com API do banco ou movimento de caixa. */
  public record TransacaoEntrada(
      @NotNull(message = "obrigatória") UUID contaBancariaId,
      @Size(max = 120, message = "até 120 caracteres") String identificador,
      @NotNull(message = "obrigatória") LocalDate data,
      @NotNull(message = "obrigatório") @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais") BigDecimal valor,
      @Size(max = 300, message = "até 300 caracteres") String descricao,
      @Size(max = 60, message = "até 60 caracteres") String documento) {}

  /**
   * Conciliação manual: informe exatamente um destino — pagamento já recebido, cobrança em aberto (faz a
   * baixa), conta a pagar (paga ou aprovada, registra o pagamento) ou uma classificação sem documento.
   */
  public record ConciliacaoEntrada(UUID pagamentoId, UUID cobrancaId, UUID contaPagarId,
      TransacaoBancaria.Classificacao classificacao, UUID categoriaId) {}

  public record Candidato(String tipo, UUID id, String titulo, BigDecimal valor, LocalDate data, long diasDeDiferenca) {}

  public record Sugestoes(UUID transacaoId, TransacaoBancaria.Classificacao classificacaoSugerida, List<Candidato> candidatos) {}

  public record ResumoConta(UUID contaBancariaId, String conta, long pendentes, long conciliadas, long ignoradas,
      BigDecimal saldoPeloExtrato, BigDecimal saldoPeloSistema, BigDecimal diferenca, LocalDate ultimoExtrato) {}
}
