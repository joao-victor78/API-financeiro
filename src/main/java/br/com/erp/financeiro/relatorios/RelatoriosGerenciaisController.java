package br.com.erp.financeiro.relatorios;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.comissoes.Comissao;
import br.com.erp.financeiro.comissoes.ComissaoService;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.conciliacao.ConciliacaoService;
import br.com.erp.financeiro.conciliacao.TransacaoBancaria;
import br.com.erp.financeiro.contaspagar.ContaPagar;
import br.com.erp.financeiro.contaspagar.ContaPagarRepository;
import br.com.erp.financeiro.contaspagar.ContaPagarService;
import br.com.erp.financeiro.fiscal.NotaFiscal;
import br.com.erp.financeiro.fiscal.NotaFiscalService;
import br.com.erp.financeiro.gerencial.DreService;
import br.com.erp.financeiro.gerencial.FluxoCaixaService;
import br.com.erp.financeiro.inadimplencia.FaixaAging;
import br.com.erp.financeiro.inadimplencia.InadimplenciaService;
import br.com.erp.financeiro.indicadores.IndicadoresService;
import br.com.erp.financeiro.seguranca.Usuarios;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exportação dos demais relatórios (§77, §78) em PDF, XLSX e CSV, no mesmo formato da exportação de
 * cobranças (RF-11). Exige financeiro.relatorio.exportar e a permissão de leitura do próprio relatório.
 */
@Tag(name = "relatorios")
@RestController
public class RelatoriosGerenciaisController {

  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final ContaPagarRepository contasPagar;
  private final InadimplenciaService inadimplencia;
  private final IndicadoresService indicadores;
  private final FluxoCaixaService fluxo;
  private final DreService dre;
  private final ComissaoService comissoes;
  private final ConciliacaoService conciliacao;
  private final NotaFiscalService notas;
  private final Relogio relogio;

  public RelatoriosGerenciaisController(ContaPagarRepository contasPagar, InadimplenciaService inadimplencia,
      IndicadoresService indicadores, FluxoCaixaService fluxo, DreService dre, ComissaoService comissoes,
      ConciliacaoService conciliacao, NotaFiscalService notas, Relogio relogio) {
    this.contasPagar = contasPagar;
    this.inadimplencia = inadimplencia;
    this.indicadores = indicadores;
    this.fluxo = fluxo;
    this.dre = dre;
    this.comissoes = comissoes;
    this.conciliacao = conciliacao;
    this.notas = notas;
    this.relogio = relogio;
  }

  private record Tabela(String titulo, List<String> cabecalho, List<List<Object>> linhas) {}

  @Operation(summary = "Exportar relatório: contas-pagar, inadimplencia, mrr, fluxo-caixa, dre, comissoes, conciliacao, notas-fiscais")
  @GetMapping("/api/financeiro/relatorios/{relatorio}/exportar")
  @PreAuthorize("hasAuthority('financeiro.relatorio.exportar')")
  @Transactional(readOnly = true)
  public ResponseEntity<byte[]> exportar(
      @PathVariable String relatorio,
      @RequestParam String formato,
      @RequestParam(required = false) LocalDate de,
      @RequestParam(required = false) LocalDate ate,
      @RequestParam(required = false) String competencia,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID contaBancariaId,
      @RequestParam(required = false) String regime,
      @RequestParam(required = false) String agrupamento) {
    String f = formato.toLowerCase();
    if (!List.of("pdf", "xlsx", "csv").contains(f)) throw ApiException.campo("formato", "FORMATO_INVALIDO", "Use pdf, xlsx ou csv.");
    LocalDate hoje = relogio.hoje();
    Tabela t;
    try {
      t = montar(relatorio, hoje, de, ate, competencia, status, contaBancariaId, regime, agrupamento);
    } catch (IllegalArgumentException e) {
      throw ApiException.campo(null, "FILTRO_INVALIDO", "Filtro inválido: confira status, regime e agrupamento.");
    }
    String nome = relatorio + "-" + hoje + "." + f;
    byte[] corpo;
    MediaType tipo;
    switch (f) {
      case "csv" -> {
        corpo = Planilhas.csv(t.cabecalho(), t.linhas());
        tipo = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
      }
      case "xlsx" -> {
        corpo = Planilhas.xlsx(t.titulo().length() > 31 ? t.titulo().substring(0, 31) : t.titulo(), t.cabecalho(), t.linhas());
        tipo = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      }
      default -> {
        corpo = Planilhas.pdf(t.titulo() + " — " + hoje.format(DATA) + " — " + t.linhas().size() + " linha(s)", texto(t));
        tipo = MediaType.APPLICATION_PDF;
      }
    }
    return ResponseEntity.ok().contentType(tipo)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nome).build().toString())
        .body(corpo);
  }

  private Tabela montar(String relatorio, LocalDate hoje, LocalDate de, LocalDate ate, String competencia, String status,
      UUID contaBancariaId, String regime, String agrupamento) {
    return switch (relatorio) {
      case "contas-pagar" -> {
        exigir("financeiro.contapagar.ver");
        yield contasPagar(status, competencia);
      }
      case "inadimplencia" -> {
        exigir("financeiro.inadimplencia.ver");
        yield inadimplencia();
      }
      case "mrr" -> {
        exigir("financeiro.dashboard.ver");
        yield mrr(competencia == null ? relogio.mesAtual() : mes(competencia));
      }
      case "fluxo-caixa" -> {
        exigir("financeiro.fluxocaixa.ver");
        LocalDate inicio = de != null ? de : hoje.withDayOfMonth(1);
        yield fluxo(inicio, ate != null ? ate : inicio.plusMonths(3).minusDays(1), agrupamento, contaBancariaId);
      }
      case "dre" -> {
        exigir("financeiro.dre.ver");
        YearMonth fim = ate != null ? YearMonth.from(ate) : relogio.mesAtual();
        yield dre(de != null ? YearMonth.from(de) : fim.minusMonths(5), fim, regime);
      }
      case "comissoes" -> {
        exigir("financeiro.comissao.ver");
        yield comissoes(competencia == null ? null : mes(competencia).atDay(1), status);
      }
      case "conciliacao" -> {
        exigir("financeiro.conciliacao.ver");
        yield conciliacao(contaBancariaId, status, de, ate);
      }
      case "notas-fiscais" -> {
        exigir("financeiro.notafiscal.ver");
        yield notas(competencia == null ? null : mes(competencia).atDay(1));
      }
      default -> throw ApiException.naoEncontrado("Relatório");
    };
  }

  // ------------------------------------------------------------------ relatórios

  private Tabela contasPagar(String status, String competencia) {
    ContaPagar.Status s = status == null || status.isBlank() ? null : ContaPagar.Status.valueOf(status.toUpperCase());
    List<ContaPagar> l = contasPagar.findAll(ContaPagarService.especificacao(new ContaPagarService.Filtro(null, s, null, null,
        competencia == null ? null : mes(competencia).atDay(1))), PageRequest.of(0, 10_000, Sort.by("dataVencimento").and(Sort.by("id")))).getContent();
    List<List<Object>> linhas = new ArrayList<>();
    for (ContaPagar c : l) {
      linhas.add(linha(c.getFornecedorNome(), c.getFornecedorDocumento(), c.getDocumento(), c.getCategoria(), c.getCentroCusto(),
          c.getCompetencia(), c.getDataVencimento().format(DATA), c.getValor(), c.getStatus().name(),
          c.getDataPagamento() == null ? null : c.getDataPagamento().format(DATA), c.getValorPago()));
    }
    return new Tabela("Contas a pagar", List.of("Fornecedor", "CNPJ/CPF", "Documento", "Categoria", "Centro de custo", "Competência",
        "Vencimento", "Valor", "Status", "Pago em", "Valor pago"), linhas);
  }

  private Tabela inadimplencia() {
    InadimplenciaService.Painel p = inadimplencia.painel(null, InadimplenciaService.Ordem.DIAS, 0, 10_000);
    List<List<Object>> linhas = new ArrayList<>();
    for (InadimplenciaService.Item i : p.itens()) {
      linhas.add(linha(i.clienteId().toString(), i.descricao(), i.vencimento().format(DATA), String.valueOf(i.diasAtraso()),
          i.faixa().rotulo(), i.saldoDevedor(), i.status().name(), i.cobrancaId().toString()));
    }
    for (FaixaAging f : FaixaAging.values()) linhas.add(linha("Total faixa " + f.rotulo(), null, null, null, f.rotulo(), p.totalPorFaixa().get(f.rotulo()), null, null));
    return new Tabela("Inadimplência", List.of("Empresa (id CRM)", "Descrição", "Vencimento", "Dias de atraso", "Faixa", "Saldo devedor",
        "Status", "Cobrança"), linhas);
  }

  private Tabela mrr(YearMonth ate) {
    List<List<Object>> linhas = new ArrayList<>();
    for (IndicadoresService.Mrr m : indicadores.historico(ate, 12)) {
      linhas.add(linha(m.periodo(), m.mrrNovo(), m.mrrExpansion(), m.mrrContraction(), m.mrrChurned(), m.mrrNetNew(), m.mrrTotal(),
          m.arr(), String.valueOf(m.empresasAtivas()), m.logoChurn(), m.mrrChurn(), m.revenueChurn()));
    }
    return new Tabela("MRR", List.of("Período", "Novo", "Expansion", "Contraction", "Churned", "Net New", "MRR", "ARR", "Empresas ativas",
        "Logo churn %", "MRR churn %", "Revenue churn %"), linhas);
  }

  private Tabela fluxo(LocalDate de, LocalDate ate, String agrupamento, UUID conta) {
    FluxoCaixaService.Agrupamento a = agrupamento == null ? FluxoCaixaService.Agrupamento.MES
        : FluxoCaixaService.Agrupamento.valueOf(agrupamento.toUpperCase());
    FluxoCaixaService.Fluxo r = fluxo.calcular(de, ate, a, conta);
    List<List<Object>> linhas = new ArrayList<>();
    linhas.add(linha("Saldo inicial", null, null, null, null, null, r.saldoInicial()));
    for (FluxoCaixaService.Periodo p : r.periodos()) {
      linhas.add(linha(p.periodo(), p.entradasRealizadas(), p.saidasRealizadas(), p.entradasPrevistas(), p.saidasPrevistas(),
          p.saldoDoPeriodo(), p.saldoAcumulado()));
    }
    linhas.add(linha("Vencido a receber (fora do saldo)", r.vencidoAReceber(), null, null, null, null, null));
    linhas.add(linha("Vencido a pagar (fora do saldo)", null, r.vencidoAPagar(), null, null, null, null));
    return new Tabela("Fluxo de caixa", List.of("Período", "Entradas realizadas", "Saídas realizadas", "Entradas previstas",
        "Saídas previstas", "Saldo do período", "Saldo acumulado"), linhas);
  }

  private Tabela dre(YearMonth de, YearMonth ate, String regime) {
    DreService.Dre r = dre.calcular(de, ate, regime == null ? DreService.Regime.COMPETENCIA : DreService.Regime.valueOf(regime.toUpperCase()));
    List<String> cab = new ArrayList<>(List.of("Linha"));
    cab.addAll(r.meses());
    cab.add("Total");
    cab.add("% receita");
    List<List<Object>> linhas = new ArrayList<>();
    for (DreService.Linha l : r.linhas()) {
      List<Object> x = new ArrayList<>();
      x.add(("CATEGORIA".equals(l.tipo()) ? "    " : "") + l.titulo());
      x.addAll(l.valores());
      x.add(l.total());
      x.add(l.percentualDaReceita());
      linhas.add(x);
    }
    return new Tabela("DRE gerencial (" + r.regime() + ")", cab, linhas);
  }

  private Tabela comissoes(LocalDate competencia, String status) {
    Comissao.Status s = status == null || status.isBlank() ? null : Comissao.Status.valueOf(status.toUpperCase());
    List<List<Object>> linhas = new ArrayList<>();
    for (ComissaoService.ComissaoSaida c : comissoes.listar(competencia, null, null, s, PageRequest.of(0, 10_000, Sort.by("createdAt"))).itens()) {
      linhas.add(linha(c.competencia().toString().substring(0, 7), c.beneficiarioTipo().name(), c.beneficiarioId().toString(), c.regra(),
          c.cobrancaId().toString(), c.baseCalculo(), c.percentual(), c.valor(), c.status().name()));
    }
    return new Tabela("Comissões", List.of("Competência", "Tipo", "Beneficiário", "Regra", "Cobrança", "Base", "%", "Valor", "Status"), linhas);
  }

  private Tabela conciliacao(UUID conta, String status, LocalDate de, LocalDate ate) {
    TransacaoBancaria.Status s = status == null || status.isBlank() ? null : TransacaoBancaria.Status.valueOf(status.toUpperCase());
    List<List<Object>> linhas = new ArrayList<>();
    for (var t : conciliacao.listar(new ConciliacaoService.Filtro(conta, s, de, ate, null), PageRequest.of(0, 10_000, Sort.by("data"))).itens()) {
      linhas.add(linha(t.data().format(DATA), t.descricao(), t.documento(), t.valor(), t.classificacao() == null ? null : t.classificacao().name(),
          t.status().name(), t.modo() == null ? null : t.modo().name(), t.pagamentoId() == null ? null : t.pagamentoId().toString(),
          t.contaPagarId() == null ? null : t.contaPagarId().toString()));
    }
    return new Tabela("Conciliação bancária", List.of("Data", "Descrição", "Documento", "Valor", "Classificação", "Status", "Modo",
        "Pagamento", "Conta a pagar"), linhas);
  }

  private Tabela notas(LocalDate competencia) {
    List<List<Object>> linhas = new ArrayList<>();
    for (NotaFiscalService.NotaSaida n : notas.listar(null, competencia, null, null, PageRequest.of(0, 10_000, Sort.by("dataEmissao"))).itens()) {
      linhas.add(linha(n.tipo().name(), n.modelo().name(), n.numero(), n.serie(), n.chaveAcesso(), n.dataEmissao().format(DATA),
          n.valor(), n.valorImpostos(), n.participanteNome() != null ? n.participanteNome() : n.empresaId() == null ? null : n.empresaId().toString(),
          n.status().name()));
    }
    return new Tabela("Notas fiscais", List.of("Tipo", "Modelo", "Número", "Série", "Chave", "Emissão", "Valor", "Impostos", "Participante",
        "Status"), linhas);
  }

  // ------------------------------------------------------------------ apoio

  private static List<Object> linha(Object... v) {
    return new ArrayList<>(java.util.Arrays.asList(v));
  }

  private static List<String> texto(Tabela t) {
    List<String> l = new ArrayList<>();
    l.add(String.join(" | ", t.cabecalho()));
    l.add("-".repeat(130));
    for (List<Object> r : t.linhas()) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < r.size(); i++) {
        if (i > 0) sb.append(" | ");
        Object v = r.get(i);
        sb.append(v == null ? "" : v instanceof BigDecimal d ? Dinheiro.brl(d) : v.toString());
      }
      String s = sb.toString();
      l.add(s.length() > 170 ? s.substring(0, 169) + "…" : s);
    }
    return l;
  }

  private static YearMonth mes(String s) {
    try {
      return YearMonth.parse(s.length() > 7 ? s.substring(0, 7) : s);
    } catch (RuntimeException e) {
      throw ApiException.campo("competencia", "FORMATO_INVALIDO", "Use AAAA-MM.");
    }
  }

  private static void exigir(String permissao) {
    if (!Usuarios.tem(permissao)) throw new AccessDeniedException("Sem a permissão " + permissao);
  }
}
