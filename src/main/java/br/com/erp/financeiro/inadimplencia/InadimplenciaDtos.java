package br.com.erp.financeiro.inadimplencia;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.BindParam;

public final class InadimplenciaDtos {

  private InadimplenciaDtos() {}

  public record Filtro(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit,
      @BindParam("cliente_id") UUID clienteId,
      @BindParam("dias_min") @Min(1) Integer diasMin) implements Paginado.Paginacao {}

  public record CriarPromessa(
      @NotNull UUID contaReceberId,
      @NotNull LocalDate dataPrometida,
      @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valor,
      @Size(max = 500) String observacao) {}

  public record FiltroPromessas(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit,
      @Pattern(regexp = "pendente|cumprida|quebrada", message = "status deve ser pendente, cumprida ou quebrada") String status,
      @BindParam("conta_receber_id") UUID contaReceberId) implements Paginado.Paginacao {}

  /** Título vencido com dias de atraso, faixa de aging e valor atualizado (multa + juros). */
  public record Atraso(UUID contaReceberId, UUID clienteId, UUID contratoId, UUID cobrancaId, String descricao, String competencia,
      LocalDate dataVencimento, StatusContaReceber status, BigDecimal saldo, long diasAtraso, String faixaAging,
      BigDecimal multa, BigDecimal juros, BigDecimal valorAtualizado) {}

  public record Faixa(int quantidade, BigDecimal valor) {}

  public record Resumo(LocalDate dataReferencia, BigDecimal totalEmAberto, BigDecimal totalVencido, BigDecimal totalVencidoAtualizado,
      double taxaInadimplenciaPercentual, int clientesInadimplentes, Map<String, Faixa> aging) {}

  public record ResultadoVencimentos(int contasReceberVencidas, int contasPagarVencidas) {}

  public record ResultadoPromessas(int promessasQuebradas) {}
}
