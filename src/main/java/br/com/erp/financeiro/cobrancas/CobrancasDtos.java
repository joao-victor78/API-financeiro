package br.com.erp.financeiro.cobrancas;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Validacao;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusCobranca;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.BindParam;

public final class CobrancasDtos {

  private CobrancasDtos() {}

  public record Criar(
      @NotNull UUID clienteId,
      UUID contratoId,
      @NotNull @Pattern(regexp = Validacao.COMPETENCIA, message = Validacao.MSG_COMPETENCIA) String competencia,
      @NotBlank @Size(max = 200) String descricao,
      @NotEmpty @Size(max = 200) List<@Valid @NotNull ItemCobranca> itens,
      @NotNull LocalDate dataVencimento,
      @NotNull FormaPagamento formaPagamento) {}

  /** Só cobranças em rascunho podem ser alteradas; campos ausentes ficam como estão. */
  public record Atualizar(
      @Size(max = 200) String descricao,
      @Size(min = 1, max = 200) List<@Valid @NotNull ItemCobranca> itens,
      LocalDate dataVencimento,
      FormaPagamento formaPagamento) {}

  public record Cancelar(@NotBlank @Size(min = 3, max = 300) String motivo) {}

  public record FiltroQrCode(
      @Pattern(regexp = "png|svg", message = "formato deve ser png ou svg") String formato,
      @Min(128) @Max(1024) Integer tamanho) {}

  public record Filtro(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit,
      StatusCobranca status,
      @BindParam("cliente_id") UUID clienteId,
      @BindParam("contrato_id") UUID contratoId,
      @BindParam("recorrencia_id") UUID recorrenciaId,
      @Pattern(regexp = Validacao.COMPETENCIA, message = Validacao.MSG_COMPETENCIA) String competencia) implements Paginado.Paginacao {}

  /** Cobrança aprovada junto com o id da conta a receber que a aprovação gerou. */
  public record Aprovada(@JsonUnwrapped Cobranca cobranca, UUID contaReceberId) {}
}
