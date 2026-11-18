package br.com.erp.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.ItemCobranca;
import br.com.erp.financeiro.comum.Datas;
import br.com.erp.financeiro.comum.auth.JwtInterceptor;
import br.com.erp.financeiro.comum.auth.ValidadorJwt;
import br.com.erp.financeiro.comum.enums.Periodicidade;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.conciliacoes.MotorConciliacao;
import br.com.erp.financeiro.conciliacoes.MotorConciliacao.Candidato;
import br.com.erp.financeiro.conciliacoes.MotorConciliacao.Lancamento;
import br.com.erp.financeiro.conciliacoes.MotorConciliacao.Pontuacao;
import br.com.erp.financeiro.conciliacoes.OfxParser;
import br.com.erp.financeiro.config.Ambiente;
import br.com.erp.financeiro.inadimplencia.Encargos;
import br.com.erp.financeiro.pix.BrCode;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Ciclo;
import br.com.erp.financeiro.recorrencias.RecorrenciasService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/** Regras puras do domínio, sem banco nem servidor. */
class UnitariosTest {

  private static LocalDate d(String s) {
    return LocalDate.parse(s);
  }

  @Nested
  class DatasTest {
    @Test
    void somaMesesRespeitandoFimDeMes() {
      assertThat(Datas.somarMeses(d("2026-01-31"), 1)).isEqualTo("2026-02-28");
      assertThat(Datas.somarMeses(d("2028-01-31"), 1)).isEqualTo("2028-02-29");
      assertThat(Datas.somarMeses(d("2026-11-15"), 3)).isEqualTo("2027-02-15");
    }

    @Test
    void diferencaESomaDeDias() {
      assertThat(Datas.diferencaDias(d("2026-10-01"), d("2026-10-31"))).isEqualTo(30);
      assertThat(Datas.diferencaDias(d("2026-12-31"), d("2027-01-01"))).isEqualTo(1);
      assertThat(d("2026-03-01").minusDays(1)).isEqualTo("2026-02-28");
    }

    @Test
    void diaDeVencimento31EmMesCurtoCaiNoUltimoDia() {
      assertThat(Datas.comDia("2026-02", 31)).isEqualTo("2026-02-28");
      assertThat(Datas.comDia("2026-04", 31)).isEqualTo("2026-04-30");
    }
  }

  @Nested
  class RecorrenciaCicloTest {
    @Test
    void geraNDiasAntesDoVencimento() {
      assertThat(RecorrenciasService.cicloDe(10, 10, "2026-11")).isEqualTo(new Ciclo("2026-11", d("2026-11-10"), d("2026-10-31")));
    }

    @Test
    void avancaPelaPeriodicidade() {
      assertThat(RecorrenciasService.proximaCompetencia(Periodicidade.MENSAL, "2026-12")).isEqualTo("2027-01");
      assertThat(RecorrenciasService.proximaCompetencia(Periodicidade.TRIMESTRAL, "2026-11")).isEqualTo("2027-02");
      assertThat(RecorrenciasService.proximaCompetencia(Periodicidade.ANUAL, "2026-10")).isEqualTo("2027-10");
    }

    @Test
    void somaItensEmCentavosSemErroDePontoFlutuante() {
      var itens = List.of(
          new ItemCobranca("servico", null, "a", new BigDecimal("3"), new BigDecimal("0.1")),
          new ItemCobranca("consumo", null, "b", new BigDecimal("1.5"), new BigDecimal("19.99")));
      assertThat(Cobranca.totalItens(itens)).isEqualByComparingTo("30.29");
    }
  }

  @Nested
  class EncargosEAgingTest {
    private final BigDecimal multa = new BigDecimal("2");
    private final BigDecimal juros = new BigDecimal("1");

    @Test
    void semAtrasoNaoHaEncargos() {
      var e = Encargos.calcular(10000, 0, multa, juros);
      assertThat(e.multa()).isZero();
      assertThat(e.juros()).isZero();
      assertThat(e.valorAtualizado()).isEqualByComparingTo("100");
    }

    @Test
    void multa2PorCentoMaisJuros1PorCentoAoMesProRata() {
      // R$ 1.000,00 com 15 dias: multa 20,00 + juros 5,00
      var e = Encargos.calcular(100000, 15, multa, juros);
      assertThat(e.multa()).isEqualByComparingTo("20");
      assertThat(e.juros()).isEqualByComparingTo("5");
      assertThat(e.valorAtualizado()).isEqualByComparingTo("1025");
    }

    @Test
    void classificaFaixasDeAging() {
      assertThat(Stream.of(0, 1, 30, 31, 61, 91, 400).map(Encargos::faixaDe))
          .containsExactly("a_vencer", "1_30", "1_30", "31_60", "61_90", "acima_90", "acima_90");
    }
  }

  @Nested
  class PixBrCodeTest {
    @Test
    void crc16CcittVetorDeReferencia() {
      assertThat(BrCode.crc16("123456789")).isEqualTo("29B1");
    }

    @Test
    void payloadTerminaComCrcValidoEContemChaveValorETxid() {
      String br = BrCode.gerar("financeiro@empresa.com.br", new BigDecimal("199.9"), "ABC123", null, null);
      assertThat(br).startsWith("000201").contains("0014br.gov.bcb.pix").contains("5406199.90").contains("0506ABC123");
      assertThat(BrCode.crc16(br.substring(0, br.length() - 4))).isEqualTo(br.substring(br.length() - 4));
    }
  }

  @Nested
  class ParserOfxTest {
    static final String OFX_SGML = """
        OFXHEADER:100
        DATA:OFXSGML
        VERSION:102
        ENCODING:USASCII
        CHARSET:1252

        <OFX>
        <BANKMSGSRSV1><STMTTRNRS><STMTRS>
        <BANKACCTFROM><BANKID>0001<ACCTID>12345-6</BANKACCTFROM>
        <BANKTRANLIST><DTSTART>20261001<DTEND>20261031
        <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261010120000[-3:BRT]<TRNAMT>199,90<FITID>A1<NAME>PIX RECEBIDO<MEMO>Pagamento São João</STMTTRN>
        <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261015<TRNAMT>-850.00<FITID>A2<MEMO>TED fornecedor</STMTTRN>
        </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>""";

    @Test
    void leOfx1SgmlComVirgulaDecimalELatin1() {
      var e = OfxParser.ler(OfxParser.decodificar(OFX_SGML.getBytes(StandardCharsets.ISO_8859_1)));
      assertThat(e.bancoId()).isEqualTo("0001");
      assertThat(e.contaId()).isEqualTo("12345-6");
      assertThat(e.periodoInicio()).isEqualTo("2026-10-01");
      assertThat(e.periodoFim()).isEqualTo("2026-10-31");
      assertThat(e.transacoes()).containsExactly(
          new OfxParser.Transacao("A1", d("2026-10-10"), new BigDecimal("199.90"), "credito", "CREDIT", "PIX RECEBIDO | Pagamento São João"),
          new OfxParser.Transacao("A2", d("2026-10-15"), new BigDecimal("-850.00"), "debito", "DEBIT", "TED fornecedor"));
    }

    @Test
    void leOfx2Xml() {
      String xml = """
          <?xml version="1.0" encoding="UTF-8"?><?OFX OFXHEADER="200" VERSION="220"?>
          <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
          <STMTTRN><TRNTYPE>CREDIT</TRNTYPE><DTPOSTED>20261005</DTPOSTED><TRNAMT>10.00</TRNAMT><FITID>X</FITID><MEMO>ok</MEMO></STMTTRN>
          </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>""";
      var t = OfxParser.ler(xml).transacoes().get(0);
      assertThat(t.fitid()).isEqualTo("X");
      assertThat(t.valor()).isEqualByComparingTo("10");
      assertThat(t.data()).isEqualTo("2026-10-05");
    }

    @Test
    void rejeitaArquivoQueNaoEOfxOuTransacaoSemValor() {
      assertThatThrownBy(() -> OfxParser.ler("nome;valor\nfoo;10")).isInstanceOf(OfxParser.OfxInvalidoException.class);
      assertThatThrownBy(() -> OfxParser.ler("<OFX><STMTTRN><DTPOSTED>20261001</STMTTRN></OFX>")).isInstanceOf(OfxParser.OfxInvalidoException.class);
    }
  }

  @Nested
  class MotorConciliacaoTest {
    private final UUID a = uuid(1);
    private final UUID b = uuid(2);
    private final Lancamento base = new Lancamento(19990, d("2026-10-10"), "PIX RECEBIDO");

    private static UUID uuid(int n) {
      return new UUID(0, n);
    }

    private Candidato cand(UUID id, long saldo, String venc, String txid) {
      return new Candidato(id, saldo, d(venc), txid, "receber");
    }

    @Test
    void txidNaDescricaoIdentificaUnivocamente() {
      var p = MotorConciliacao.pontuar(new Lancamento(19990, d("2026-10-10"), "PIX abcDEF123"),
          List.of(cand(a, 19990, "2026-10-10", "ABCDEF123"), cand(b, 19990, "2026-10-10", null)));
      assertThat(MotorConciliacao.escolherAutomatico(p)).isEqualTo(new Pontuacao(a, 100, "txid_pix"));
    }

    @Test
    void valorExatoMaisDataProximaConciliaSozinho() {
      var p = MotorConciliacao.pontuar(base, List.of(cand(a, 19990, "2026-10-08", null)));
      assertThat(MotorConciliacao.escolherAutomatico(p).id()).isEqualTo(a);
    }

    @Test
    void empateFicaPendenteParaDecisaoHumana() {
      var p = MotorConciliacao.pontuar(base, List.of(cand(a, 19990, "2026-10-10", null), cand(b, 19990, "2026-10-11", null)));
      assertThat(p).hasSize(2);
      assertThat(MotorConciliacao.escolherAutomatico(p)).isNull();
    }

    @Test
    void valorExatoMasDataDistanteNaoPassaDoLimiar() {
      var p = MotorConciliacao.pontuar(base, List.of(cand(a, 19990, "2026-09-20", null)));
      assertThat(p.get(0).score()).isEqualTo(60);
      assertThat(MotorConciliacao.escolherAutomatico(p)).isNull();
    }

    @Test
    void valorDiferenteEDescartado() {
      assertThat(MotorConciliacao.pontuar(base, List.of(cand(a, 19991, "2026-10-10", null)))).isEmpty();
    }
  }

  @Nested
  class AutenticacaoPorCookieTest {
    @Test
    void aceitaOTokenNoCookieFinanceiroTokenERejeitaCookieInvalido() {
      UUID tenant = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
      var env = new MockEnvironment().withProperty("JWT_PUBLIC_KEY", TesteBase.pemPublica()).withProperty("JWT_ISSUER", "auth");
      var validador = new ValidadorJwt(new Ambiente(env));

      var req = new MockHttpServletRequest();
      req.setCookies(new Cookie("tema", "escuro"), new Cookie(JwtInterceptor.COOKIE_TOKEN, TesteBase.token(tenant)), new Cookie("x", "1"));
      assertThat(validador.validar(JwtInterceptor.extrairToken(req)).tenantId()).isEqualTo(tenant);

      var lixo = new MockHttpServletRequest();
      lixo.setCookies(new Cookie(JwtInterceptor.COOKIE_TOKEN, "lixo"));
      assertThatThrownBy(() -> validador.validar(JwtInterceptor.extrairToken(lixo))).isInstanceOf(ApiException.class).hasMessage("Token inválido");
      assertThatThrownBy(() -> validador.validar(JwtInterceptor.extrairToken(new MockHttpServletRequest())))
          .isInstanceOf(ApiException.class).hasMessage("Token de acesso ausente");
    }

    @Test
    void cabecalhoBearerTemPrioridadeSobreOCookie() {
      var req = new MockHttpServletRequest();
      req.addHeader("Authorization", "Bearer do-cabecalho");
      req.setCookies(new Cookie(JwtInterceptor.COOKIE_TOKEN, "do-cookie"));
      assertThat(JwtInterceptor.extrairToken(req)).isEqualTo("do-cabecalho");
    }
  }
}
