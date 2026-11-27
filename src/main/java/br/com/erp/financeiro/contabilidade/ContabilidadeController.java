package br.com.erp.financeiro.contabilidade;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cadastros.CadastroDtos.CategoriaSaida;
import br.com.erp.financeiro.cadastros.CadastroDtos.CentroCustoSaida;
import br.com.erp.financeiro.cadastros.CadastroService;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.contabilidade.ContabilidadeService.FechamentoSaida;
import br.com.erp.financeiro.contabilidade.ContabilidadeService.Integracao;
import br.com.erp.financeiro.contabilidade.FechamentoContabil.Item;
import br.com.erp.financeiro.contabilidade.LancamentosContabeis.Lancamento;
import br.com.erp.financeiro.tenant.TenantContexto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portal da contabilidade e fechamento contábil (§34 a §37). Todas as rotas exigem
 * financeiro.contabilidade.ver; mudar o fechamento exige ainda fechar (financeiro) ou analisar
 * (contabilidade), conferido no serviço conforme a transição.
 */
@Tag(name = "contabilidade")
@RestController
@RequestMapping("/api/financeiro/contabilidade")
public class ContabilidadeController {

  private final ContabilidadeService contabilidade;
  private final CadastroService cadastros;
  private final AuditoriaService auditoria;
  private final JdbcTemplate jdbc;

  public ContabilidadeController(ContabilidadeService contabilidade, CadastroService cadastros, AuditoriaService auditoria, JdbcTemplate jdbc) {
    this.contabilidade = contabilidade;
    this.cadastros = cadastros;
    this.auditoria = auditoria;
    this.jdbc = jdbc;
  }

  public record ItemEntrada(@NotNull(message = "obrigatório") Boolean concluido, String observacao) {}

  public record StatusEntrada(@NotNull(message = "obrigatório") FechamentoContabil.Status status, String pendencias) {}

  @GetMapping("/fechamentos")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  public Resposta<List<FechamentoSaida>> fechamentos() {
    return Resposta.ok(contabilidade.fechamentos());
  }

  @GetMapping("/fechamentos/{competencia}")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  public Resposta<FechamentoSaida> fechamento(@PathVariable String competencia) {
    return Resposta.ok(contabilidade.fechamento(mes(competencia)));
  }

  @PostMapping("/fechamentos/{competencia}/iniciar")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  public Resposta<FechamentoSaida> iniciar(@PathVariable String competencia) {
    return Resposta.ok(contabilidade.iniciar(mes(competencia)), "Fechamento iniciado: em conferência.");
  }

  @PutMapping("/fechamentos/{competencia}/itens/{item}")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  public Resposta<FechamentoSaida> marcar(@PathVariable String competencia, @PathVariable Item item, @Valid @RequestBody ItemEntrada e) {
    return Resposta.ok(contabilidade.marcar(mes(competencia), item, e.concluido(), e.observacao()));
  }

  @PatchMapping("/fechamentos/{competencia}/status")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  public Resposta<FechamentoSaida> status(@PathVariable String competencia, @Valid @RequestBody StatusEntrada e) {
    return Resposta.ok(contabilidade.mudarStatus(mes(competencia), e.status(), e.pendencias()), "Fechamento: " + e.status() + ".");
  }

  @GetMapping("/integracoes")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  public Resposta<List<Integracao>> integracoes() {
    return Resposta.ok(contabilidade.integracoes());
  }

  @GetMapping("/lancamentos")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  public Resposta<List<Lancamento>> lancamentos(@RequestParam String competencia) {
    return Resposta.ok(contabilidade.lancamentos(mes(competencia)));
  }

  /**
   * §34: o que o escritório vê — receitas, despesas, recebimentos, pagamentos, notas, comprovantes,
   * movimentação bancária, contratos de origem das receitas, categorias e centros de custo.
   */
  @Operation(summary = "Visão do portal da contabilidade para a competência")
  @GetMapping("/portal")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  @Transactional(readOnly = true)
  public Resposta<Map<String, Object>> portal(@RequestParam String competencia) {
    YearMonth m = mes(competencia);
    List<Lancamento> l = contabilidade.lancamentos(m);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("competencia", m.atDay(1));
    r.put("fechamento", contabilidade.fechamento(m));
    Map<String, BigDecimal> totais = new LinkedHashMap<>();
    for (String tipo : List.of("RECEITA", "RECEBIMENTO", "DESPESA", "PAGAMENTO", "TARIFA", "JUROS", "ESTORNO", "TRANSFERENCIA", "OUTRO")) {
      totais.put(tipo, Dinheiro.de(l.stream().filter(x -> x.tipo().equals(tipo)).map(Lancamento::valor).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }
    r.put("totais", totais);
    r.put("receitas", l.stream().filter(x -> x.tipo().equals("RECEITA")).toList());
    r.put("recebimentos", l.stream().filter(x -> x.tipo().equals("RECEBIMENTO") || x.tipo().equals("ESTORNO")).toList());
    r.put("despesas", l.stream().filter(x -> x.tipo().equals("DESPESA")).toList());
    r.put("pagamentos", l.stream().filter(x -> x.tipo().equals("PAGAMENTO")).toList());
    r.put("movimentacoesBancarias", jdbc.queryForList("""
        SELECT t.id, t.data, t.valor, t.descricao, t.documento, t.classificacao, t.status, cb.nome AS conta
          FROM financeiro.transacoes_bancarias t JOIN financeiro.contas_bancarias cb ON cb.id = t.conta_bancaria_id
         WHERE t.tenant_id = ? AND t.deleted_at IS NULL AND to_char(t.data, 'YYYY-MM') = ? ORDER BY t.data, t.id""",
        TenantContexto.exigir(), m.toString()));
    r.put("documentosFiscais", jdbc.queryForList("""
        SELECT id, tipo, modelo, numero, serie, chave_acesso, data_emissao, valor, valor_impostos, empresa_id, participante_nome,
               participante_documento, cobranca_id, conta_pagar_id, documento_id, status
          FROM financeiro.notas_fiscais WHERE tenant_id = ? AND deleted_at IS NULL AND competencia = ? ORDER BY data_emissao, numero""",
        TenantContexto.exigir(), m.toString()));
    r.put("comprovantes", jdbc.queryForList("""
        SELECT d.id, d.nome_arquivo, d.categoria, d.entidade, d.registro_id, d.descricao, d.tamanho_bytes, d.created_at
          FROM financeiro.documentos d
         WHERE d.tenant_id = ? AND d.deleted_at IS NULL AND d.categoria IN ('COMPROVANTE','NOTA_FISCAL','RECIBO','BOLETO','EXTRATO','RELATORIO')
           AND to_char(d.created_at AT TIME ZONE 'America/Sao_Paulo', 'YYYY-MM') = ? ORDER BY d.created_at""",
        TenantContexto.exigir(), m.toString()));
    r.put("contratos", jdbc.queryForList("""
        SELECT contrato_id, empresa_id, count(*) AS cobrancas, sum(valor - desconto + juros + multa) AS total
          FROM financeiro.cobrancas WHERE tenant_id = ? AND deleted_at IS NULL AND competencia = ? AND contrato_id IS NOT NULL
         GROUP BY contrato_id, empresa_id ORDER BY total DESC""", TenantContexto.exigir(), m.toString()));
    r.put("categorias", cadastros.categorias().stream().map(CategoriaSaida::de).toList());
    r.put("centrosCusto", cadastros.centros().stream().map(CentroCustoSaida::de).toList());
    return Resposta.ok(r);
  }

  /** Lançamentos da competência em CSV, XLSX, PDF, OFX ou o pacote ZIP completo (§37). */
  @GetMapping("/exportar")
  @PreAuthorize("hasAuthority('financeiro.contabilidade.ver')")
  @Transactional
  public ResponseEntity<byte[]> exportar(@RequestParam String competencia, @RequestParam String formato) {
    YearMonth m = mes(competencia);
    String f = formato.toLowerCase();
    List<Lancamento> l = contabilidade.lancamentos(m);
    byte[] corpo;
    MediaType tipo;
    switch (f) {
      case "csv" -> {
        corpo = ExportacaoContabil.csv(l);
        tipo = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
      }
      case "xlsx" -> {
        corpo = ExportacaoContabil.xlsx(l);
        tipo = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      }
      case "pdf" -> {
        corpo = ExportacaoContabil.pdf(m, l);
        tipo = MediaType.APPLICATION_PDF;
      }
      case "ofx" -> {
        corpo = ExportacaoContabil.ofx(m, l);
        tipo = MediaType.parseMediaType("application/x-ofx");
      }
      case "zip" -> {
        corpo = contabilidade.pacote(m, l);
        tipo = MediaType.parseMediaType("application/zip");
      }
      default -> throw ApiException.campo("formato", "FORMATO_INVALIDO", "Use csv, xlsx, pdf, ofx ou zip.");
    }
    auditoria.registrar("EXPORTAR_CONTABIL", "fechamento", TenantContexto.exigir(), null, Map.of("competencia", m.toString(), "formato", f,
        "lancamentos", l.size()));
    return ResponseEntity.ok().contentType(tipo)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("contabil-" + m + "." + f).build().toString())
        .body(corpo);
  }

  static YearMonth mes(String s) {
    try {
      return YearMonth.parse(s.length() > 7 ? s.substring(0, 7) : s);
    } catch (DateTimeParseException | NullPointerException e) {
      throw ApiException.campo("competencia", "FORMATO_INVALIDO", "Use AAAA-MM.");
    }
  }
}
