package br.com.erp.financeiro.contasreceber;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Validacao;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.BindParam;

/** Contratos de entrada e saída da API de contas a receber (JSON em snake_case). */
public final class ContasReceberDtos {

  private ContasReceberDtos() {}

  public record Criar(
      @NotNull UUID clienteId,
      UUID contratoId,
      @NotBlank @Size(max = 200) String descricao,
      @NotNull @Pattern(regexp = Validacao.COMPETENCIA, message = Validacao.MSG_COMPETENCIA) String competencia,
      @NotNull LocalDate dataVencimento,
      @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valor,
      @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal desconto,
      FormaPagamento formaPagamento) {}

  /** Campos ausentes não são alterados. */
  public record Atualizar(
      @Size(max = 200) String descricao,
      LocalDate dataVencimento,
      @Positive @Digits(integer = 12, fraction = 2) BigDecimal valor,
      @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal desconto,
      FormaPagamento formaPagamento,
      StatusContaReceber status) {}

  public record Baixa(
      @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valor,
      @NotNull LocalDate dataPagamento,
      @NotNull FormaPagamento formaPagamento) {}

  public record Filtro(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit,
      StatusContaReceber status,
      @BindParam("cliente_id") UUID clienteId,
      @BindParam("contrato_id") UUID contratoId,
      @Pattern(regexp = Validacao.COMPETENCIA, message = Validacao.MSG_COMPETENCIA) String competencia,
      @BindParam("vencimento_de") LocalDate vencimentoDe,
      @BindParam("vencimento_ate") LocalDate vencimentoAte) implements Paginado.Paginacao {}

  /** Conta com o saldo devedor calculado (valor − desconto + juros + multa − pago). */
  public record ComSaldo(@JsonUnwrapped ContaReceber conta, BigDecimal saldo) {}
}
