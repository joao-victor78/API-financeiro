package br.com.erp.financeiro.ferramentas;

import br.com.erp.financeiro.eventos.EventosService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Popula a API com dados FICTÍCIOS para demonstração: 5 clientes com contratos recorrentes e 4 meses de
 * histórico, pagamentos por Pix e boleto, um pagamento parcial, inadimplência com promessa, contas a pagar
 * e um extrato OFX conciliado.
 *
 * <p>Usa a API em execução, como um cliente qualquer faria: nada é gravado direto no banco.
 *
 * <pre>
 * Uso: mvn -q compile exec:java@popular                       (tenant de demonstração do frontend)
 *      mvn -q compile exec:java@popular -Dexec.args="&lt;tenant_uuid&gt;"
 *      mvn -q compile exec:java@popular -Dexec.args="--forcar"   (acrescenta mesmo se já houver dados)
 * </pre>
 */
public final class PopularDemo {

  static final String TENANT_DEMO = "17869f5d-a221-42c8-95f9-2dd6d4659d4b";

  private final String base;
  private final String token;
  private final String segredoPix;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final LocalDate hoje = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
  private int seqE2E;

  private PopularDemo(String base, String token, String segredoPix) {
    this.base = base;
    this.token = token;
    this.segredoPix = segredoPix;
  }

  // ---------- datas relativas a hoje (o histórico sempre termina "agora") ----------
  private String comp(int n) {
    return YearMonth.from(hoje).plusMonths(n).toString();
  }

  private String dia(int n, int d) {
    YearMonth ym = YearMonth.from(hoje).plusMonths(n);
    return ym.atDay(Math.min(d, ym.lengthOfMonth())).toString();
  }

  private static String somarDias(String data, int n) {
    return LocalDate.parse(data).plusDays(n).toString();
  }

  private String noPassado(String data) {
    return data.compareTo(hoje.toString()) < 0 ? data : hoje.minusDays(1).toString();
  }

  // ---------- cliente HTTP ----------
  private JsonNode chamar(String metodo, String rota, Object corpo, Map<String, String> headers) throws Exception {
    String texto = corpo == null ? null : corpo instanceof String s ? s : json.writeValueAsString(corpo);
    var b = HttpRequest.newBuilder(URI.create(base + rota)).timeout(Duration.ofSeconds(30))
        .header("authorization", "Bearer " + token).header("content-type", "application/json")
        .method(metodo, texto == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(texto));
    headers.forEach(b::header);
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode j = r.body().isEmpty() ? json.createObjectNode() : json.readTree(r.body());
    if (r.statusCode() >= 300) {
      throw new IllegalStateException(metodo + " " + rota + " → " + r.statusCode() + ": " + j.path("message").asText() + " " + j.path("errors"));
    }
    return j.get("data");
  }

  private JsonNode get(String rota) throws Exception {
    return chamar("GET", rota, null, Map.of());
  }

  private JsonNode post(String rota, Object corpo) throws Exception {
    return chamar("POST", rota, corpo == null ? Map.of() : corpo, Map.of());
  }

  private void pixPago(String txid, double valor, String data) throws Exception {
    String corpo = json.writeValueAsString(Map.of("pix", List.of(Map.of(
        // endToEndId é único no SPI: parte aleatória para não colidir com cargas anteriores
        "endToEndId", "E00000000" + data.replace("-", "") + String.format("%04d", ++seqE2E)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
        "txid", txid, "valor", String.format(Locale.ROOT, "%.2f", valor), "horario", data + "T15:00:00Z"))));
    chamar("POST", "/webhooks/pix", corpo, Map.of("x-pix-assinatura", "sha256=" + EventosService.hmacSha256(segredoPix, corpo)));
  }

  private void baixa(String id, double valor, String data, String forma) throws Exception {
    post("/contas-receber/" + id + "/baixa", Map.of("valor", valor, "data_pagamento", noPassado(data), "forma_pagamento", forma));
  }

  /** Mapa com chaves e valores alternados, aceitando mais de 10 pares (Map.of aceita só 10). */
  private static Map<String, Object> m(Object... kv) {
    Map<String, Object> r = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
    return r;
  }

  private static final NumberFormat BRL = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));

  private static String brl(double v) {
    return BRL.format(v);
  }

  private static void log(String t) {
    System.out.println("  ✓ " + t);
  }

  // ---------- dados fictícios ----------
  private static Map<String, Object> item(String tipo, String descricao, double qtd, double valor) {
    return m("tipo", tipo, "descricao", descricao, "quantidade", qtd, "valor_unitario", valor);
  }

  private static final Map<String, Object> LICENCA_PRO = item("servico", "Licença ERP — plano Pro", 1, 249.9);
  private static final Map<String, Object> LICENCA_BASICO = item("servico", "Licença ERP — plano Básico", 1, 149.9);

  private static Map<String, Object> usuarios(int n) {
    return item("consumo", "Usuários adicionais", n, 15);
  }

  /** historico = situação de cada mês, do mais antigo (-3) ao atual (0). */
  record ClienteDemo(String nome, String plano, int dia, String forma, List<Map<String, Object>> itens, List<String> historico) {}

  private static final List<ClienteDemo> CLIENTES = List.of(
      new ClienteDemo("Padaria Pão Dourado", "Plano Pro", 5, "pix", List.of(LICENCA_PRO, usuarios(4)), List.of("pago", "pago", "pago_pix", "pago_pix")),
      new ClienteDemo("Clínica Sorriso Feliz", "Plano Básico", 10, "boleto", List.of(LICENCA_BASICO), List.of("pago", "pago", "parcial", "ofx")),
      new ClienteDemo("Auto Peças Goiás", "Plano Pro", 20, "pix", List.of(LICENCA_PRO, usuarios(2)), List.of("pago", "pago_pix", "pago_pix", "aberto")),
      new ClienteDemo("Mercado Bom Preço", "Plano Enterprise", 25, "boleto",
          List.of(item("servico", "Licença ERP — plano Enterprise", 1, 899), item("servico", "Suporte 24h", 1, 150), usuarios(10)),
          List.of("pago", "pago", "pago", "aberto")),
      new ClienteDemo("Academia Corpo em Forma", "Plano Básico", 15, "pix", List.of(LICENCA_BASICO, usuarios(1)), List.of("pago", "atrasado", "atrasado", "atrasado")));

  record Despesa(String nome, UUID fornecedor, UUID centro, UUID categoria, int dia, double[] valores, String forma, boolean pagaAtual) {}

  record Credito(String data, double valor, String memo) {}

  private void executar(boolean forcar) throws Exception {
    try {
      get("/health");
    } catch (Exception e) {
      throw new IllegalStateException("API fora do ar. Suba a API com \"mvn spring-boot:run\" e rode este comando de novo.");
    }
    if (get("/contas-receber?limit=1").at("/meta/total").asLong() > 0 && !forcar) {
      System.out.println("Este tenant já tem dados — nada foi alterado.");
      System.out.println("Para popular outro tenant: -Dexec.args=\"<uuid>\"   (ou acrescente --forcar para duplicar)");
      return;
    }

    // 1. Contratos recorrentes + 4 meses de cobranças geradas pela automação
    System.out.println("Clientes e contratos recorrentes");
    List<Credito> ofxCreditos = new ArrayList<>();
    String atrasadaId = null;
    double atrasadaValor = 0;
    for (ClienteDemo c : CLIENTES) {
      JsonNode r = post("/recorrencias", m("cliente_id", UUID.randomUUID(), "contrato_id", UUID.randomUUID(),
          "descricao", c.plano() + " — " + c.nome(), "periodicidade", "mensal", "dia_vencimento", c.dia(), "dias_antecedencia", 10,
          "data_inicio", dia(-3, 1), "forma_pagamento", c.forma(), "aprovacao_automatica", true, "itens", c.itens()));
      for (int i = 0; i < c.historico().size(); i++) {
        JsonNode cob = post("/recorrencias/" + r.get("id").asText() + "/gerar", null);
        String contaId = cob.get("conta_receber_id").asText();
        JsonNode conta = get("/contas-receber/" + contaId);
        String venc = conta.get("data_vencimento").asText();
        double valor = conta.get("valor").asDouble();
        switch (c.historico().get(i)) {
          case "pago" -> baixa(contaId, valor, somarDias(venc, -(i % 3)), c.forma());
          case "pago_pix" -> {
            String data = noPassado(somarDias(venc, -1));
            pixPago(conta.get("pix_txid").asText(), valor, data);
            ofxCreditos.add(new Credito(data, valor, "PIX RECEBIDO " + conta.get("pix_txid").asText()));
          }
          case "parcial" -> baixa(contaId, 100, somarDias(venc, 2), c.forma());
          case "ofx" -> ofxCreditos.add(new Credito(noPassado(somarDias(venc, 1)), valor, "LIQUIDACAO BOLETO COBRANCA"));
          case "atrasado" -> {
            if (atrasadaId == null) {
              atrasadaId = contaId;
              atrasadaValor = valor;
            }
          }
          default -> { /* em aberto */ }
        }
      }
      log(c.nome() + " — " + brl(r.get("valor_ciclo").asDouble()) + "/mês (" + c.forma() + ", vence dia " + c.dia() + "), 4 competências geradas");
    }

    // 2. Recebimentos avulsos
    System.out.println("\nRecebimentos avulsos");
    JsonNode implantacao = post("/contas-receber", m("cliente_id", UUID.randomUUID(), "descricao", "Implantação e treinamento — Mercado Bom Preço",
        "competencia", comp(-3), "data_vencimento", dia(-3, 15), "valor", 2500, "forma_pagamento", "transferencia"));
    baixa(implantacao.get("id").asText(), 2500, dia(-3, 14), "transferencia");
    log("Implantação e treinamento — R$ 2.500,00 (recebida)");
    post("/contas-receber", m("cliente_id", UUID.randomUUID(), "descricao", "Consultoria fiscal — Padaria Pão Dourado",
        "competencia", comp(0), "data_vencimento", hoje.plusDays(10).toString(), "valor", 600, "forma_pagamento", "transferencia"));
    log("Consultoria fiscal — R$ 600,00 (a vencer)");

    // 3. Cobranças manuais em outros estados (rascunho e cancelada)
    System.out.println("\nCobranças manuais");
    post("/cobrancas", m("cliente_id", UUID.randomUUID(), "competencia", comp(0), "descricao", "Customização de relatórios — Auto Peças Goiás",
        "data_vencimento", hoje.plusDays(15).toString(), "forma_pagamento", "pix",
        "itens", List.of(item("extra", "Horas de desenvolvimento", 6, 120))));
    log("Customização de relatórios — rascunho aguardando aprovação");
    JsonNode visita = post("/cobrancas", m("cliente_id", UUID.randomUUID(), "competencia", comp(-1), "descricao", "Visita técnica — Clínica Sorriso Feliz",
        "data_vencimento", dia(-1, 28), "forma_pagamento", "boleto", "itens", List.of(item("extra", "Visita técnica presencial", 1, 180))));
    post("/cobrancas/" + visita.get("id").asText() + "/cancelar", Map.of("motivo", "Atendimento resolvido remotamente, sem custo para o cliente"));
    log("Visita técnica — cancelada com motivo");

    // 4. Contas a pagar (3 meses)
    System.out.println("\nContas a pagar");
    UUID administrativo = UUID.randomUUID();
    UUID tecnologia = UUID.randomUUID();
    UUID comercial = UUID.randomUUID();
    UUID utilidades = UUID.randomUUID();
    List<Despesa> despesas = List.of(
        new Despesa("Aluguel da sala comercial", UUID.randomUUID(), administrativo, UUID.randomUUID(), 5, new double[] {3200, 3200, 3200}, "boleto", true),
        new Despesa("Hospedagem em nuvem (servidores)", UUID.randomUUID(), tecnologia, UUID.randomUUID(), 8, new double[] {1124.37, 1180.92, 1215.4}, "cartao_credito", true),
        new Despesa("Honorários contábeis", UUID.randomUUID(), administrativo, UUID.randomUUID(), 10, new double[] {850, 850, 850}, "pix", true),
        new Despesa("Energia elétrica", UUID.randomUUID(), administrativo, utilidades, 12, new double[] {512.8, 488.15, 503.62}, "boleto", false),
        new Despesa("Internet fibra 1 Gbps", UUID.randomUUID(), tecnologia, utilidades, 22, new double[] {199.9, 199.9, 199.9}, "boleto", false),
        new Despesa("Anúncios online", UUID.randomUUID(), comercial, UUID.randomUUID(), 28, new double[] {600, 750, 800}, "cartao_credito", false));
    int nf = 4810;
    for (Despesa d : despesas) {
      for (int n = -2; n <= 0; n++) {
        double valor = d.valores()[n + 2];
        String cp = comp(n);
        JsonNode conta = post("/contas-pagar", m("fornecedor_id", d.fornecedor(), "centro_custo_id", d.centro(), "categoria_id", d.categoria(),
            "descricao", d.nome() + " — " + cp.substring(5) + "/" + cp.substring(0, 4), "documento", "NF " + nf++, "competencia", cp,
            "data_vencimento", dia(n, d.dia()), "valor", valor, "forma_pagamento", d.forma()));
        if (n < 0 || d.pagaAtual()) {
          post("/contas-pagar/" + conta.get("id").asText() + "/pagamento",
              Map.of("valor", valor, "data_pagamento", noPassado(somarDias(dia(n, d.dia()), -1)), "forma_pagamento", d.forma()));
        }
      }
      log(d.nome() + " — 3 meses");
    }

    // 5. Extrato OFX do mês: concilia o boleto da Clínica, vincula os Pix já baixados e deixa pendências
    System.out.println("\nConciliação bancária (OFX)");
    List<Credito> trans = new ArrayList<>(ofxCreditos.stream().filter(t -> t.data().compareTo(dia(0, 1)) >= 0).toList());
    trans.add(new Credito(noPassado(dia(0, 3)), -19.9, "TARIFA PACOTE DE SERVICOS"));
    trans.add(new Credito(noPassado(dia(0, 9)), 75, "PIX RECEBIDO JOSE DA SILVA"));
    trans.sort(Comparator.comparing(Credito::data));
    StringBuilder ofx = new StringBuilder("OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nCHARSET:1252\n\n"
        + "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKACCTFROM><BANKID>0001<ACCTID>12345-6</BANKACCTFROM><BANKTRANLIST>\n");
    for (int i = 0; i < trans.size(); i++) {
      Credito t = trans.get(i);
      ofx.append("<STMTTRN><TRNTYPE>").append(t.valor() > 0 ? "CREDIT" : "DEBIT").append("<DTPOSTED>").append(t.data().replace("-", ""))
          .append("<TRNAMT>").append(String.format(Locale.ROOT, "%.2f", t.valor())).append("<FITID>DEMO").append(comp(0).replace("-", ""))
          .append(i + 1).append("<MEMO>").append(t.memo()).append("</STMTTRN>\n");
    }
    ofx.append("</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>");
    JsonNode imp = post("/conciliacoes/importar", Map.of("nome_arquivo", "extrato-" + comp(0) + ".ofx", "conteudo", ofx.toString()));
    log(imp.get("total_transacoes").asInt() + " transações: " + imp.get("conciliadas_automaticamente").asInt()
        + " conciliadas automaticamente, " + imp.get("pendentes").asInt() + " pendentes");

    // 6. Inadimplência: promessa de pagamento da Academia
    System.out.println("\nInadimplência");
    if (atrasadaId != null) {
      post("/inadimplencia/promessas", m("conta_receber_id", atrasadaId, "data_prometida", hoje.plusDays(5).toString(), "valor", atrasadaValor,
          "observacao", "Cliente ligou: paga a mensalidade mais antiga na sexta e negocia o restante."));
      log("Promessa de pagamento registrada (Academia Corpo em Forma)");
    }

    // 7. Roda os jobs como o agendador faria
    post("/automacao/executar/vencimentos", null);
    post("/automacao/executar/eventos", null);
    JsonNode res = get("/inadimplencia/resumo");
    log("Vencido: " + brl(res.get("total_vencido").asDouble()) + " (com encargos " + brl(res.get("total_vencido_atualizado").asDouble())
        + "), taxa " + res.get("taxa_inadimplencia_percentual").asDouble() + "%");
    System.out.println("\nPronto! Abra http://localhost:3000 e entre com o tenant acima.\n");
  }

  public static void main(String[] args) throws Exception {
    String tenant = Arrays.stream(args).filter(a -> a.matches("(?i)^[0-9a-f-]{36}$")).findFirst().orElse(TENANT_DEMO);
    boolean forcar = Arrays.asList(args).contains("--forcar");
    String base = Env.get("API_URL", "http://localhost:" + Env.get("PORT", "3008") + "/api/financeiro");
    Path chave = Path.of(Env.get("DEV_CHAVE_PRIVADA", "keys/auth-private.pem"));
    if (!Files.exists(chave)) {
      System.err.println("Chave privada de desenvolvimento não encontrada em " + chave + ". Rode: mvn -q compile exec:java@chaves");
      System.exit(1);
    }
    String token = EmitirToken.emitir(EmitirToken.lerChavePrivada(Files.readString(chave)), tenant, "usuario",
        List.of("financeiro:admin"), Duration.ofMinutes(10));
    var p = new PopularDemo(base, token, Env.get("PIX_WEBHOOK_SECRET", "troque-este-segredo"));
    System.out.println("\nPopulando dados fictícios em " + base);
    System.out.println("Tenant " + tenant + " — hoje " + p.hoje + "\n");
    try {
      p.executar(forcar);
    } catch (IllegalStateException e) {
      System.err.println("\nErro: " + e.getMessage() + "\n");
      System.exit(1);
    }
  }
}
