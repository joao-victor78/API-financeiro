package br.com.erp.financeiro.ferramentas;

import br.com.erp.financeiro.TesteBase;
import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaRepository;
import br.com.erp.financeiro.cobrancas.CobrancasDtos;
import br.com.erp.financeiro.cobrancas.CobrancasService;
import br.com.erp.financeiro.cobrancas.ItemCobranca;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.Periodicidade;
import br.com.erp.financeiro.conciliacoes.ConciliacoesService;
import br.com.erp.financeiro.conciliacoes.ExtratoTransacao;
import br.com.erp.financeiro.conciliacoes.ExtratoTransacaoRepository;
import br.com.erp.financeiro.contasreceber.ContaReceber;
import br.com.erp.financeiro.contasreceber.ContasReceberDtos;
import br.com.erp.financeiro.contasreceber.ContasReceberService;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos;
import br.com.erp.financeiro.recorrencias.RecorrenciasService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Benchmark dos mecanismos de automação com dados sintéticos (resultados usados na seção 4.7 do artigo).
 *
 * <p>Uso: {@code mvn -q test -Dtest=Benchmark} (usa o banco erp_test e o recria do zero);
 * grava benchmark-resultados.json na raiz do projeto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Benchmark extends TesteBase {

  @LocalServerPort int porta;
  @Autowired RecorrenciasService recorrencias;
  @Autowired CobrancasService cobrancas;
  @Autowired ContasReceberService receber;
  @Autowired ConciliacoesService conciliacoes;
  @Autowired CobrancaRepository cobrancaRepo;
  @Autowired ExtratoTransacaoRepository transacoes;
  @Autowired TransactionTemplate tx;

  private final HttpClient http = HttpClient.newHttpClient();

  // gerador pseudoaleatório determinístico (resultados reprodutíveis)
  private long semente = 42;

  private double rnd() {
    semente = (semente * 1103515245 + 12345) % (1L << 31);
    return (double) semente / (1L << 31);
  }

  private static double percentil(List<Double> xs, int p) {
    List<Double> s = new ArrayList<>(xs);
    Collections.sort(s);
    return Math.round(s.get(Math.min(s.size() - 1, (int) Math.floor(p / 100.0 * s.size()))) * 100) / 100.0;
  }

  private static double ms(long t0) {
    return (System.nanoTime() - t0) / 1e6;
  }

  private static AuthUser sistema(UUID tenant) {
    return new AuthUser(uuid(), tenant, List.of("financeiro:admin"), "usuario", null);
  }

  private static BigDecimal dinheiro(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
  }

  private String url(String p) {
    return "http://127.0.0.1:" + porta + BASE + p;
  }

  @Test
  void medir() throws Exception {
    Map<String, Object> resultados = new LinkedHashMap<>();
    String versaoBanco = jdbc.queryForObject("SELECT version()", String.class).split(" on ")[0];
    resultados.put("ambiente", Map.of("cpu", System.getProperty("os.arch") + " / " + Runtime.getRuntime().availableProcessors() + " núcleos",
        "java", System.getProperty("java.version"), "banco", versaoBanco, "data", Instant.now().toString()));

    // ---------------------------------------------------------------- 1. Recorrências
    {
      int tenants = 10;
      int porTenant = 100;
      relogio.fixar("2026-09-01");
      for (int t = 0; t < tenants; t++) {
        AuthUser u = sistema(uuid());
        for (int i = 0; i < porTenant; i++) {
          recorrencias.criar(u, new RecorrenciasDtos.Criar(uuid(), uuid(), "Contrato " + i, Periodicidade.MENSAL, 1 + (int) Math.floor(rnd() * 28),
              10, LocalDate.parse("2026-10-01"), null, FormaPagamento.PIX, true, List.of(
                  new ItemCobranca("servico", null, "Plano", BigDecimal.ONE, BigDecimal.valueOf(Math.round(50 + rnd() * 500))),
                  new ItemCobranca("consumo", null, "Uso", BigDecimal.valueOf(Math.ceil(rnd() * 10)), new BigDecimal("2.5")))));
        }
      }
      // Avança o relógio para o fim de outubro: cada contrato tem 1 ciclo devido (out) e parte deles o de novembro.
      relogio.fixar("2026-10-25");
      long t0 = System.nanoTime();
      var r = recorrencias.processarPendentes();
      double dur = ms(t0);
      long cobs = cobrancaRepo.count();
      long evs = jdbc.queryForObject("SELECT count(*) FROM financeiro.eventos", Long.class);
      long t1 = System.nanoTime();
      var r2 = recorrencias.processarPendentes();
      Map<String, Object> res = new LinkedHashMap<>();
      res.put("contratos", tenants * porTenant);
      res.put("tenants", tenants);
      res.put("cobrancas_geradas", r.cobrancasGeradas());
      res.put("cobrancas_no_banco", cobs);
      res.put("eventos_publicados", evs);
      res.put("erros", r.erros().size());
      res.put("duracao_ms", Math.round(dur));
      res.put("cobrancas_por_segundo", Math.round(r.cobrancasGeradas() / dur * 1000));
      res.put("segunda_execucao_mesmo_dia", Map.of("cobrancas_geradas", r2.cobrancasGeradas(), "duracao_ms", Math.round(ms(t1))));
      resultados.put("recorrencias", res);
      System.out.println("recorrencias " + res);
    }

    // ---------------------------------------------------------------- 2. Conciliação OFX
    {
      UUID tenant = uuid();
      AuthUser u = sistema(tenant);
      relogio.fixar("2026-10-01");
      List<String> linhas = new ArrayList<>();
      Map<String, UUID> gabarito = new LinkedHashMap<>(); // fitid → conta esperada (null = deve ficar pendente)
      int[] n = {0};
      Supplier<LocalDate> venc = () -> LocalDate.of(2026, 10, 1 + (int) Math.floor(rnd() * 28));
      Set<Long> usados = new HashSet<>();
      Supplier<BigDecimal> valorUnico = () -> {
        long v;
        do v = Math.round((100 + rnd() * 4900) * 100); while (!usados.add(v));
        return BigDecimal.valueOf(v, 2);
      };
      Function<Object[], String> stmt = a -> {
        String fitid = "B" + (++n[0]);
        BigDecimal valor = (BigDecimal) a[1];
        linhas.add("<STMTTRN><TRNTYPE>" + (valor.signum() > 0 ? "CREDIT" : "DEBIT") + "<DTPOSTED>" + a[0].toString().replace("-", "")
            + "<TRNAMT>" + valor.toPlainString() + "<FITID>" + fitid + "<MEMO>" + a[2] + "</STMTTRN>");
        return fitid;
      };

      // (a) 150 cobranças Pix pagas com txid no extrato
      for (int i = 0; i < 150; i++) {
        BigDecimal valor = valorUnico.get();
        LocalDate v = venc.get();
        var ap = tx.execute(s -> {
          Cobranca c = cobrancas.criar(u, new CobrancasDtos.Criar(uuid(), null, "2026-10", "Fatura",
              List.of(new ItemCobranca("servico", null, "Plano", BigDecimal.ONE, valor)), v, FormaPagamento.PIX), null);
          return cobrancas.aprovarInterno(u, c);
        });
        gabarito.put(stmt.apply(new Object[] {v.plusDays((long) Math.floor(rnd() * 5) - 2), valor, "PIX " + ap.cobranca().getPixTxid()}),
            ap.contaReceberId());
      }
      Function<BigDecimal, ContaReceber> titulo = valor -> tx.execute(s -> receber.criar(u,
          new ContasReceberDtos.Criar(uuid(), null, "Boleto", "2026-10", venc.get(), valor, null, null), null, null));
      // (b) 250 boletos/TED pagos com valor exato, até 3 dias do vencimento
      for (int i = 0; i < 250; i++) {
        ContaReceber c = titulo.apply(valorUnico.get());
        gabarito.put(stmt.apply(new Object[] {c.getDataVencimento().plusDays((long) Math.floor(rnd() * 4)), c.getValor(), "LIQ BOLETO"}), c.getId());
      }
      // (c) 50 pagos com atraso de 5 a 10 dias (pontuação 75 → ainda automático)
      for (int i = 0; i < 50; i++) {
        ContaReceber c = titulo.apply(valorUnico.get());
        gabarito.put(stmt.apply(new Object[] {c.getDataVencimento().plusDays(5 + (long) Math.floor(rnd() * 6)), c.getValor(), "TED"}), c.getId());
      }
      // (d) 40 pagos com juros embutidos (valor diferente do título) → devem ficar pendentes
      for (int i = 0; i < 40; i++) {
        ContaReceber c = titulo.apply(valorUnico.get());
        gabarito.put(stmt.apply(new Object[] {c.getDataVencimento().plusDays(12), c.getValor().multiply(new BigDecimal("1.024")).setScale(2, RoundingMode.HALF_UP),
            "LIQ BOLETO C/ JUROS"}), null);
      }
      // (e) 30 pares de títulos com mesmo valor e mesmo vencimento → ambíguos, devem ficar pendentes
      for (int i = 0; i < 30; i++) {
        BigDecimal v = valorUnico.get();
        LocalDate d = venc.get();
        for (int k = 0; k < 2; k++) {
          tx.execute(s -> receber.criar(u, new ContasReceberDtos.Criar(uuid(), null, "Mensalidade", "2026-10", d, v, null, null), null, null));
        }
        gabarito.put(stmt.apply(new Object[] {d, v, "PIX RECEBIDO"}), null);
      }
      // (f) 30 créditos sem título correspondente (rendimentos, estornos de tarifa…)
      for (int i = 0; i < 30; i++) gabarito.put(stmt.apply(new Object[] {venc.get(), valorUnico.get(), "CREDITO DIVERSO"}), null);

      // embaralha a ordem das linhas do extrato
      for (int i = linhas.size() - 1; i > 0; i--) {
        int j = (int) Math.floor(rnd() * (i + 1));
        Collections.swap(linhas, i, j);
      }
      String ofx = "OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nCHARSET:1252\n\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKACCTFROM><BANKID>0001<ACCTID>1-0</BANKACCTFROM><BANKTRANLIST>\n"
          + String.join("\n", linhas) + "\n</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";

      long t0 = System.nanoTime();
      var imp = conciliacoes.importar(u, "benchmark.ofx", ofx.getBytes(StandardCharsets.ISO_8859_1));
      double dur = ms(t0);

      Map<String, ExtratoTransacao> porFitid = transacoes.findByConciliacaoId(imp.conciliacao().getId()).stream()
          .collect(Collectors.toMap(ExtratoTransacao::getFitid, Function.identity()));
      int vp = 0;
      int fp = 0;
      int fn = 0;
      int vn = 0;
      for (var g : gabarito.entrySet()) {
        ExtratoTransacao t = porFitid.get(g.getKey());
        boolean auto = "conciliada".equals(t.getStatus());
        if (g.getValue() != null && auto && g.getValue().equals(t.getContaReceberId())) vp++;
        else if (auto) fp++;
        else if (g.getValue() != null) fn++;
        else vn++;
      }
      Map<String, Object> res = new LinkedHashMap<>();
      res.put("transacoes_no_extrato", gabarito.size());
      res.put("com_titulo_identificavel", gabarito.values().stream().filter(x -> x != null).count());
      res.put("conciliadas_automaticamente", imp.conciliacao().getConciliadasAutomaticamente());
      res.put("pendentes_para_revisao", imp.conciliacao().getPendentes());
      res.put("taxa_automatica_percentual", imp.taxaAutomaticaPercentual());
      res.put("matriz", Map.of("verdadeiros_positivos", vp, "falsos_positivos", fp, "falsos_negativos", fn, "verdadeiros_negativos", vn));
      res.put("precisao_percentual", vp + fp == 0 ? 100 : Math.round((double) vp / (vp + fp) * 10000) / 100.0);
      res.put("revocacao_percentual", vp + fn == 0 ? 100 : Math.round((double) vp / (vp + fn) * 10000) / 100.0);
      res.put("duracao_ms", Math.round(dur));
      res.put("ms_por_transacao", Math.round(dur / gabarito.size() * 100) / 100.0);
      resultados.put("conciliacao", res);
      System.out.println("conciliacao " + res);
    }

    // ---------------------------------------------------------------- 3. Webhook Pix
    {
      UUID tenant = uuid();
      AuthUser u = sistema(tenant);
      List<Map<String, String>> notifs = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        var ap = tx.execute(s -> cobrancas.aprovarInterno(u, cobrancas.criar(u, new CobrancasDtos.Criar(uuid(), null, "2026-10", "Fatura",
            List.of(new ItemCobranca("servico", null, "Plano", BigDecimal.ONE, new BigDecimal("99.90"))), LocalDate.parse("2026-10-20"),
            FormaPagamento.PIX), null)));
        notifs.add(Map.of("endToEndId", "E" + String.format("%031d", i), "txid", ap.cobranca().getPixTxid(), "valor", "99.90",
            "horario", "2026-10-19T13:00:00Z"));
      }
      List<Double> lat = new ArrayList<>();
      int baixados = 0;
      for (var p : notifs) {
        String corpo = json.writeValueAsString(Map.of("pix", List.of(p)));
        long t0 = System.nanoTime();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url("/webhooks/pix"))).header("content-type", "application/json")
            .header("x-pix-assinatura", assinarPix(corpo)).POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
        lat.add(ms(t0));
        if ("baixado".equals(json.readTree(r.body()).at("/data/resultados/0/resultado").asText())) baixados++;
      }
      long pagas = jdbc.queryForObject("SELECT count(*) FROM financeiro.contas_receber WHERE tenant_id = ?::uuid AND status = 'paga'",
          Long.class, tenant.toString());
      Map<String, Object> res = new LinkedHashMap<>();
      res.put("notificacoes", notifs.size());
      res.put("baixados", baixados);
      res.put("contas_pagas_no_banco", pagas);
      res.put("latencia_ms", Map.of("p50", percentil(lat, 50), "p95", percentil(lat, 95), "p99", percentil(lat, 99)));
      resultados.put("webhook_pix", res);
      System.out.println("webhook_pix " + res);
    }

    // ---------------------------------------------------------------- 4. Latência da API (inclui validação do JWT RS256)
    {
      String auth = "Bearer " + token(uuid());
      Map<String, Object> res = new LinkedHashMap<>();
      res.put("POST /contas-receber", medir(300, () -> HttpRequest.newBuilder(URI.create(url("/contas-receber")))
          .header("Authorization", auth).header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(paraJson(Map.of("cliente_id", uuid(), "descricao", "X", "competencia", "2026-11",
              "data_vencimento", "2026-11-10", "valor", dinheiro(10 + rnd() * 100))))).build()));
      res.put("GET /contas-receber?limit=100", medir(300, () -> HttpRequest.newBuilder(URI.create(url("/contas-receber?limit=100")))
          .header("Authorization", auth).build()));
      res.put("GET /health", medir(300, () -> HttpRequest.newBuilder(URI.create(url("/health"))).build()));
      resultados.put("latencia_api_ms", res);
      System.out.println("latencia " + res);
    }

    Files.writeString(Path.of("benchmark-resultados.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(resultados));
    System.out.println("\nResultados gravados em benchmark-resultados.json");
  }

  private Map<String, Object> medir(int n, Supplier<HttpRequest> req) throws Exception {
    List<Double> xs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      long t0 = System.nanoTime();
      HttpResponse<String> r = http.send(req.get(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() >= 300) throw new IllegalStateException("HTTP " + r.statusCode() + ": " + r.body());
      xs.add(ms(t0));
    }
    return Map.of("requisicoes", n, "p50", percentil(xs, 50), "p95", percentil(xs, 95), "p99", percentil(xs, 99));
  }
}
