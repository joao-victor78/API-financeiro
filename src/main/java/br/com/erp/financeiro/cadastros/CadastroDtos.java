package br.com.erp.financeiro.cadastros;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Entradas e saídas dos cadastros financeiros (categorias, centros de custo e contas bancárias). */
public final class CadastroDtos {

  private CadastroDtos() {}

  public record CategoriaEntrada(
      @NotBlank(message = "obrigatório") @Size(max = 80, message = "até 80 caracteres") String nome,
      @NotNull(message = "obrigatório") CategoriaFinanceira.Tipo tipo,
      @NotNull(message = "obrigatório") CategoriaFinanceira.GrupoDre grupoDre,
      @Size(max = 30, message = "até 30 caracteres") String contaContabil,
      Boolean ativa) {}

  public record CategoriaSaida(UUID id, String nome, CategoriaFinanceira.Tipo tipo, CategoriaFinanceira.GrupoDre grupoDre,
      String contaContabil, boolean ativa) {
    public static CategoriaSaida de(CategoriaFinanceira c) {
      return new CategoriaSaida(c.getId(), c.getNome(), c.getTipo(), c.getGrupoDre(), c.getContaContabil(), c.isAtiva());
    }
  }

  public record CentroCustoEntrada(
      @NotBlank(message = "obrigatório") @Size(max = 20, message = "até 20 caracteres") String codigo,
      @NotBlank(message = "obrigatório") @Size(max = 80, message = "até 80 caracteres") String nome,
      Boolean ativo) {}

  public record CentroCustoSaida(UUID id, String codigo, String nome, boolean ativo) {
    public static CentroCustoSaida de(CentroCusto c) {
      return new CentroCustoSaida(c.getId(), c.getCodigo(), c.getNome(), c.isAtivo());
    }
  }

  public record ContaBancariaEntrada(
      @NotBlank(message = "obrigatório") @Size(max = 80, message = "até 80 caracteres") String nome,
      @Pattern(regexp = "^\\d{3,5}$", message = "código do banco com 3 a 5 dígitos") String bancoCodigo,
      @Size(max = 10, message = "até 10 caracteres") String agencia,
      @Size(max = 20, message = "até 20 caracteres") String numero,
      @NotNull(message = "obrigatório") ContaBancaria.Tipo tipo,
      @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais") BigDecimal saldoInicial,
      @NotNull(message = "obrigatória") LocalDate dataSaldoInicial,
      @Size(max = 30, message = "até 30 caracteres") String contaContabil,
      Boolean padraoRecebimento,
      Boolean ativa) {}

  public record ContaBancariaSaida(UUID id, String nome, String bancoCodigo, String agencia, String numero,
      ContaBancaria.Tipo tipo, BigDecimal saldoInicial, LocalDate dataSaldoInicial, String contaContabil,
      boolean padraoRecebimento, boolean ativa) {
    public static ContaBancariaSaida de(ContaBancaria c) {
      return new ContaBancariaSaida(c.getId(), c.getNome(), c.getBancoCodigo(), c.getAgencia(), c.getNumero(), c.getTipo(),
          c.getSaldoInicial(), c.getDataSaldoInicial(), c.getContaContabil(), c.isPadraoRecebimento(), c.isAtiva());
    }
  }
}
