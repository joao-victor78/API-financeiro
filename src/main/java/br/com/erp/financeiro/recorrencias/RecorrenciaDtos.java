package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.cobrancas.MeioPagamento;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.seguranca.Usuarios;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** contratos/financeiro.yaml: ItemRecorrencia, RegraRecorrencia, RegraRecorrenciaEntrada (+ campos da V2 no fim). */
public final class RecorrenciaDtos {

  private RecorrenciaDtos() {}

  /** Quem pode ver custo e margem dos itens ("Técnico não visualiza margem financeira", §3). */
  public static final String PERMISSAO_MARGEM = "financeiro.margem.ver";

  public record ItemEntrada(
      @NotNull(message = "o serviço é obrigatório") UUID servicoId,
      @NotBlank(message = "obrigatória") @Size(max = 200, message = "até 200 caracteres") String descricao,
      @NotNull(message = "obrigatória") @Positive(message = "deve ser maior que zero")
      @Digits(integer = 9, fraction = 3, message = "no máximo 3 casas decimais") BigDecimal quantidade,
      @NotBlank(message = "obrigatória") @Size(max = 20, message = "até 20 caracteres") String unidade,
      @NotNull(message = "obrigatório") @PositiveOrZero(message = "não pode ser negativo")
      @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais") BigDecimal valorUnitario,
      @PositiveOrZero(message = "não pode ser negativa") @Digits(integer = 9, fraction = 3, message = "no máximo 3 casas decimais")
      BigDecimal franquia,
      // V2, opcionais
      @PositiveOrZero(message = "não pode ser negativo") @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais")
      BigDecimal valorExcedente,
      @PositiveOrZero(message = "não pode ser negativo") @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais")
      BigDecimal custoUnitario,
      @Size(max = 80, message = "até 80 caracteres") String fabricante) {

    /** Formato anterior (sem excedente, custo e fabricante). */
    public ItemEntrada(UUID servicoId, String descricao, BigDecimal quantidade, String unidade, BigDecimal valorUnitario,
        BigDecimal franquia) {
      this(servicoId, descricao, quantidade, unidade, valorUnitario, franquia, null, null, null);
    }
  }

  /** contratoId é opcional enquanto o módulo Contratos não publica o evento de assinatura (EOR §2.4). */
  public record RegraEntrada(
      UUID contratoId,
      @NotNull(message = "a empresa (clienteId) é obrigatória") UUID clienteId,
      @Size(max = 200, message = "até 200 caracteres") String descricao,
      Periodicidade periodicidade,
      @Min(value = 1, message = "entre 1 e 28") @Max(value = 28, message = "entre 1 e 28") Integer diaGeracao,
      @Min(value = 0, message = "entre 0 e 60") @Max(value = 60, message = "entre 0 e 60") Integer diasParaVencimento,
      MeioPagamento formaPagamento,
      Boolean ativa,
      @NotEmpty(message = "informe ao menos um item") List<@Valid ItemEntrada> itens,
      // V2, opcionais
      UUID vendedorId,
      UUID sdrId,
      UUID parceiroId,
      UUID categoriaId,
      UUID centroCustoId,
      IndiceReajuste indiceReajuste,
      @Min(value = 1, message = "entre 1 e 12") @Max(value = 12, message = "entre 1 e 12") Integer mesReajuste) {

    /** Formato anterior (usado pelo consumidor de contrato assinado). */
    public RegraEntrada(UUID contratoId, UUID clienteId, String descricao, Periodicidade periodicidade, Integer diaGeracao,
        Integer diasParaVencimento, MeioPagamento formaPagamento, Boolean ativa, List<ItemEntrada> itens) {
      this(contratoId, clienteId, descricao, periodicidade, diaGeracao, diasParaVencimento, formaPagamento, ativa, itens,
          null, null, null, null, null, null, null);
    }
  }

  public record ItemSaida(UUID id, UUID servicoId, String descricao, BigDecimal quantidade, String unidade,
      BigDecimal valorUnitario, BigDecimal franquia, BigDecimal total, LocalDate vigenteDesde, LocalDate vigenteAte,
      BigDecimal valorExcedente, BigDecimal custoUnitario, String fabricante, BigDecimal margemPercentual) {

    static ItemSaida de(RecorrenciaItem i) {
      boolean margem = Usuarios.tem(PERMISSAO_MARGEM);
      BigDecimal custo = i.custoTotal();
      BigDecimal pct = null;
      if (margem && custo != null && i.total().signum() > 0) {
        pct = i.total().subtract(custo).multiply(BigDecimal.valueOf(100)).divide(i.total(), 2, RoundingMode.HALF_EVEN);
      }
      return new ItemSaida(i.getId(), i.getServicoId(), i.getDescricao(), i.getQuantidade(), i.getUnidade(),
          i.getValorUnitario(), i.getFranquia(), i.total(), Competencia.primeiroDia(i.getVigenteDesde()),
          Competencia.primeiroDia(i.getVigenteAte()), i.getValorExcedente(), margem ? i.getCustoUnitario() : null,
          i.getFabricante(), pct);
    }
  }

  public record RegraSaida(
      UUID id,
      UUID tenantId,
      UUID contratoId,
      UUID clienteId,
      String descricao,
      Periodicidade periodicidade,
      int diaGeracao,
      int diasParaVencimento,
      MeioPagamento formaPagamento,
      LocalDate proximaCompetencia,
      BigDecimal valorProximoCiclo,
      List<ItemSaida> itens,
      boolean ativa,
      LocalDate vigenciaInicio,
      LocalDate vigenciaFim,
      OffsetDateTime createdAt,
      UUID vendedorId,
      UUID sdrId,
      UUID parceiroId,
      UUID categoriaId,
      UUID centroCustoId,
      IndiceReajuste indiceReajuste,
      Integer mesReajuste,
      boolean cobrancaAutomatica,
      String cartaoFinal,
      String cartaoBandeira,
      BigDecimal mrr) {

    /** {@code itens}: os vigentes na próxima geração (o que será cobrado). */
    static RegraSaida de(Recorrencia r, List<RecorrenciaItem> todosOsItens) {
      List<RecorrenciaItem> vigentes = todosOsItens.stream().filter(i -> i.vigenteEm(r.getProximaCompetencia())).toList();
      BigDecimal valor = vigentes.stream().map(RecorrenciaItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal mrr = r.isAtiva() ? valor.divide(BigDecimal.valueOf(r.mesesDoCiclo()), 2, RoundingMode.HALF_EVEN) : BigDecimal.ZERO.setScale(2);
      return new RegraSaida(r.getId(), r.getTenantId(), r.getContratoId(), r.getEmpresaId(), r.getDescricao(),
          r.getPeriodicidade(), r.getDiaGeracao(), r.getDiasParaVencimento(), r.getFormaPagamento(),
          Competencia.primeiroDia(r.getProximaCompetencia()), valor.setScale(2), vigentes.stream().map(ItemSaida::de).toList(),
          r.isAtiva(), r.getVigenciaInicio(), r.getVigenciaFim(), r.getCreatedAt(), r.getVendedorId(), r.getSdrId(),
          r.getParceiroId(), r.getCategoriaId(), r.getCentroCustoId(), r.getIndiceReajuste(), r.getMesReajuste(),
          r.isCobrancaAutomatica(), r.getCartaoFinal(), r.getCartaoBandeira(), mrr);
    }
  }

  // ------------------------------------------------------------------ consumo, adicionais, reajuste, cartão (V2)

  public record ConsumoEntrada(
      @NotNull(message = "o serviço é obrigatório") UUID servicoId,
      @NotNull(message = "obrigatória") LocalDate competencia,
      @NotNull(message = "obrigatória") @PositiveOrZero(message = "não pode ser negativa")
      @Digits(integer = 11, fraction = 3, message = "no máximo 3 casas decimais") BigDecimal quantidade,
      @Size(max = 300, message = "até 300 caracteres") String observacao) {}

  /** Envio em lote por integração (RMM, painel do fabricante): por regra ou por contrato. */
  public record ConsumoIntegracao(
      UUID recorrenciaId,
      UUID contratoId,
      @NotNull(message = "o serviço é obrigatório") UUID servicoId,
      @NotNull(message = "obrigatória") LocalDate competencia,
      @NotNull(message = "obrigatória") @PositiveOrZero(message = "não pode ser negativa")
      @Digits(integer = 11, fraction = 3, message = "no máximo 3 casas decimais") BigDecimal quantidade) {}

  public record ConsumoSaida(UUID id, UUID recorrenciaId, UUID servicoId, String descricao, String unidade, LocalDate competencia,
      BigDecimal quantidade, BigDecimal permitido, BigDecimal excedente, BigDecimal valorExcedente, BigDecimal percentualUso,
      String origem, String observacao, UUID cobrancaId, OffsetDateTime alertadoEm, String sugestao) {}

  public record AdicionalEntrada(
      @NotNull(message = "obrigatória") LocalDate competencia,
      @NotBlank(message = "obrigatória") @Size(max = 200, message = "até 200 caracteres") String descricao,
      @NotNull(message = "obrigatório") @Positive(message = "deve ser maior que zero")
      @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais") BigDecimal valor) {}

  public record AdicionalSaida(UUID id, UUID recorrenciaId, LocalDate competencia, String descricao, BigDecimal valor,
      UUID cobrancaId, OffsetDateTime createdAt) {}

  public record ReajusteEntrada(
      @NotNull(message = "obrigatório") IndiceReajuste indice,
      @NotNull(message = "obrigatório") @DecimalMin(value = "-99.9999", message = "maior que -100%")
      @DecimalMax(value = "100", message = "até 100%") @Digits(integer = 3, fraction = 4, message = "no máximo 4 casas decimais")
      BigDecimal percentual,
      LocalDate competenciaAplicacao,
      @Size(max = 300, message = "até 300 caracteres") String observacao) {}

  public record ReajusteSaida(UUID id, UUID recorrenciaId, IndiceReajuste indice, BigDecimal percentual,
      LocalDate competenciaAplicacao, BigDecimal valorAnterior, BigDecimal valorNovo, String observacao, UUID createdBy,
      OffsetDateTime createdAt) {}

  /** Só a referência do token no provedor, o final e a bandeira: o número do cartão nunca chega aqui (RN-22). */
  public record CartaoEntrada(
      @NotBlank(message = "obrigatório") @Size(max = 120, message = "até 120 caracteres") String tokenRef,
      @Pattern(regexp = "^\\d{4}$", message = "os 4 últimos dígitos") String finalCartao,
      @Size(max = 20, message = "até 20 caracteres") String bandeira,
      Boolean cobrancaAutomatica) {}
}
