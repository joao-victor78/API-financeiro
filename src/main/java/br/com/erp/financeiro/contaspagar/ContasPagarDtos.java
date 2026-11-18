package br.com.erp.financeiro.contaspagar;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Validacao;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusContaPagar;
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

public final class ContasPagarDtos {

  private ContasPagarDtos() {}

  public record Criar(
      @NotNull UUID fornecedorId,
      @NotNull UUID centroCustoId,
      @NotNull UUID categoriaId,
      @NotBlank @Size(max = 200) String descricao,
      @Size(max = 60) String documento,
      @NotNull @Pattern(regexp = Validacao.COMPETENCIA, message = Validacao.MSG_COMPETENCIA) String competencia,
      @NotNull LocalDate dataVencimento,
      @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valor,
      @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal desconto,
      FormaPagamento formaPagamento) {}

  public record Atualizar(
      @Size(max = 200) String descricao,
      @Size(max = 60) String documento,
      LocalDate dataVencimento,
      @Positive @Digits(integer = 12, fraction = 2) BigDecimal valor,
      @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal desconto,
      FormaPagamento formaPagamento,
      UUID centroCustoId,
      UUID categoriaId,
      StatusContaPagar status) {}

  public record Pagamento(
      @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valor,
      @NotNull LocalDate dataPagamento,
      @NotNull FormaPagamento formaPagamento) {}

  public record Filtro(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit,
      StatusContaPagar status,
      @BindParam("fornecedor_id") UUID fornecedorId,
      @BindParam("centro_custo_id") UUID centroCustoId,
      @BindParam("categoria_id") UUID categoriaId,
      @Pattern(regexp = Validacao.COMPETENCIA, message = Validacao.MSG_COMPETENCIA) String competencia,
      @BindParam("vencimento_de") LocalDate vencimentoDe,
      @BindParam("vencimento_ate") LocalDate vencimentoAte) implements Paginado.Paginacao {}

  public record ComSaldo(@JsonUnwrapped ContaPagar conta, BigDecimal saldo) {}
}
