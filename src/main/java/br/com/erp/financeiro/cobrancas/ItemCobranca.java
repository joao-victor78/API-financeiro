package br.com.erp.financeiro.cobrancas;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Item de cobrança ou de contrato: serviço, consumo ou extra. Gravado como JSON (coluna jsonb "itens").
 *
 * @param servicoId UUID do serviço no catálogo (outro módulo), opcional
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemCobranca(
    @NotNull @Pattern(regexp = "servico|consumo|extra", message = "deve ser servico, consumo ou extra") String tipo,
    UUID servicoId,
    @NotBlank @Size(max = 200) String descricao,
    @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal quantidade,
    @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal valorUnitario) {}
