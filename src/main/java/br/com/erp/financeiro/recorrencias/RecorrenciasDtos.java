package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.cobrancas.ItemCobranca;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.Periodicidade;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.BindParam;

public final class RecorrenciasDtos {

  private RecorrenciasDtos() {}

  public record Criar(
      @NotNull UUID clienteId,
      @NotNull UUID contratoId,
      @NotBlank @Size(max = 200) String descricao,
      @NotNull Periodicidade periodicidade,
      @NotNull @Min(1) @Max(31) Integer diaVencimento,
      @Min(0) @Max(60) Integer diasAntecedencia,
      @NotNull LocalDate dataInicio,
      LocalDate dataFim,
      @NotNull FormaPagamento formaPagamento,
      Boolean aprovacaoAutomatica,
      @NotEmpty @Size(max = 200) List<@Valid @NotNull ItemCobranca> itens) {}

  public record Atualizar(
      @Size(max = 200) String descricao,
      @Min(1) @Max(31) Integer diaVencimento,
      @Min(0) @Max(60) Integer diasAntecedencia,
      LocalDate dataFim,
      FormaPagamento formaPagamento,
      Boolean aprovacaoAutomatica,
      @Size(min = 1, max = 200) List<@Valid @NotNull ItemCobranca> itens) {}

  public record Filtro(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit,
      @BindParam("cliente_id") UUID clienteId,
      @BindParam("contrato_id") UUID contratoId,
      Boolean ativa) implements Paginado.Paginacao {}

  public record FiltroPrevisao(@Min(1) @Max(24) Integer ciclos) {}

  /** Um ciclo de cobrança: vencimento no dia configurado e geração N dias antes. */
  public record Ciclo(String competencia, LocalDate dataVencimento, LocalDate dataGeracao) {}

  public record Previsao(String competencia, LocalDate dataVencimento, LocalDate dataGeracao, BigDecimal valor) {}

  /** Recorrência com o valor do ciclo e a próxima geração calculados. */
  public record Detalhe(@JsonUnwrapped Recorrencia recorrencia, BigDecimal valorCiclo, Ciclo proximoCiclo) {}

  public record DetalheComExecucoes(@JsonUnwrapped Recorrencia recorrencia, BigDecimal valorCiclo, Ciclo proximoCiclo,
      List<RecorrenciaExecucao> execucoes) {}

  public record Erro(UUID recorrenciaId, String erro) {}

  public record ResultadoProcessamento(int recorrenciasAvaliadas, int cobrancasGeradas, int cobrancasAprovadas, int encerradas,
      List<Erro> erros) {}
}
