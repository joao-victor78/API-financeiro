package br.com.erp.financeiro.conciliacoes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parser de extratos OFX (Open Financial Exchange) — suporta OFX 1.x (SGML, tags folha sem fechamento,
 * padrão da maioria dos bancos brasileiros) e OFX 2.x (XML). Sem dependências externas.
 */
public final class OfxParser {

  private OfxParser() {}

  /** tipo: "credito" ou "debito"; valor positivo = crédito, negativo = débito. */
  public record Transacao(String fitid, LocalDate data, BigDecimal valor, String tipo, String trntype, String descricao) {}

  public record Extrato(String bancoId, String contaId, LocalDate periodoInicio, LocalDate periodoFim, List<Transacao> transacoes) {}

  public static class OfxInvalidoException extends RuntimeException {
    public OfxInvalidoException(String m) {
      super(m);
    }
  }

  private static final Pattern CHARSET_LATIN =
      Pattern.compile("CHARSET:\\s*(1252|ISO-8859-1|8859-1)|encoding=\"(windows-1252|iso-8859-1)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern BLOCO = Pattern.compile("<STMTTRN>[\\s\\S]*?</STMTTRN>", Pattern.CASE_INSENSITIVE);
  private static final Pattern RAIZ = Pattern.compile("<OFX>", Pattern.CASE_INSENSITIVE);

  /** Decodifica respeitando o CHARSET do cabeçalho (bancos BR costumam usar 1252/Latin-1). */
  public static String decodificar(byte[] bytes) {
    String cabecalho = new String(bytes, 0, Math.min(400, bytes.length), StandardCharsets.ISO_8859_1);
    Charset cs = CHARSET_LATIN.matcher(cabecalho).find() ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
    return new String(bytes, cs);
  }

  private static String tag(String bloco, String nome) {
    Matcher m = Pattern.compile("<" + nome + ">\\s*([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(bloco);
    return m.find() ? m.group(1).trim() : null;
  }

  private static LocalDate dataOfx(String v) {
    if (v == null || !v.matches("^\\d{8}.*")) return null;
    return LocalDate.of(Integer.parseInt(v.substring(0, 4)), Integer.parseInt(v.substring(4, 6)), Integer.parseInt(v.substring(6, 8)));
  }

  public static Extrato ler(String conteudo) {
    if (!RAIZ.matcher(conteudo).find()) throw new OfxInvalidoException("Arquivo não contém o elemento <OFX>");
    List<Transacao> transacoes = new ArrayList<>();
    Matcher m = BLOCO.matcher(conteudo);
    int i = 0;
    while (m.find()) {
      i++;
      String b = m.group();
      String valorBruto = tag(b, "TRNAMT");
      LocalDate data;
      try {
        data = dataOfx(tag(b, "DTPOSTED"));
      } catch (RuntimeException e) {
        data = null;
      }
      if (valorBruto == null || valorBruto.isEmpty() || data == null) {
        throw new OfxInvalidoException("Transação #" + i + " sem TRNAMT ou DTPOSTED válidos");
      }
      BigDecimal valor;
      try {
        valor = new BigDecimal(valorBruto.replaceAll("\\s", "").replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
      } catch (NumberFormatException e) {
        throw new OfxInvalidoException("Transação #" + i + " com valor inválido: " + valorBruto);
      }
      if (valor.signum() == 0) throw new OfxInvalidoException("Transação #" + i + " com valor inválido: " + valorBruto);
      String fitid = tag(b, "FITID");
      String descricao = Stream.of(tag(b, "NAME"), tag(b, "MEMO")).filter(s -> s != null && !s.isEmpty()).collect(Collectors.joining(" | "));
      String trntype = tag(b, "TRNTYPE");
      transacoes.add(new Transacao(
          fitid == null || fitid.isEmpty() ? data + "-" + valor.stripTrailingZeros().toPlainString() + "-" + (i - 1) : fitid,
          data, valor, valor.signum() > 0 ? "credito" : "debito", trntype == null ? "" : trntype.toUpperCase(),
          descricao.length() > 300 ? descricao.substring(0, 300) : descricao));
    }
    return new Extrato(tag(conteudo, "BANKID"), tag(conteudo, "ACCTID"), dataOfx(tag(conteudo, "DTSTART")),
        dataOfx(tag(conteudo, "DTEND")), transacoes);
  }
}
