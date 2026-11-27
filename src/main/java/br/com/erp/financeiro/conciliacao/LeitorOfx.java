package br.com.erp.financeiro.conciliacao;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitor de extrato OFX (§32), sem biblioteca: aceita OFX 1.x (SGML, tags sem fechamento, como a maioria
 * dos bancos brasileiros exporta) e OFX 2.x (XML). Lê os STMTTRN, o período (DTSTART/DTEND) e o saldo
 * (LEDGERBAL). Valores com vírgula decimal também são aceitos.
 */
public final class LeitorOfx {

  private static final Pattern TRANSACAO = Pattern.compile("<STMTTRN>(.*?)(</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))",
      Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  private LeitorOfx() {}

  public record Lancamento(String identificador, LocalDate data, BigDecimal valor, String tipo, String descricao, String documento) {}

  public record Extrato(String banco, String conta, LocalDate inicio, LocalDate fim, BigDecimal saldoFinal, List<Lancamento> lancamentos) {}

  public static class OfxInvalidoException extends RuntimeException {
    public OfxInvalidoException(String mensagem) {
      super(mensagem);
    }
  }

  public static Extrato ler(byte[] bytes) {
    String texto = decodificar(bytes);
    if (!texto.toUpperCase(Locale.ROOT).contains("<OFX>")) throw new OfxInvalidoException("O arquivo não é um extrato OFX.");
    List<Lancamento> lancamentos = new ArrayList<>();
    Matcher m = TRANSACAO.matcher(texto);
    int sequencia = 0;
    while (m.find()) {
      String bloco = m.group(1);
      sequencia++;
      LocalDate data = data(campo(bloco, "DTPOSTED"));
      BigDecimal valor = valor(campo(bloco, "TRNAMT"));
      if (data == null || valor == null || valor.signum() == 0) continue;
      String fitid = campo(bloco, "FITID");
      String memo = campo(bloco, "MEMO");
      String nome = campo(bloco, "NAME");
      String descricao = juntar(nome, memo);
      String documento = campo(bloco, "CHECKNUM");
      if (documento == null) documento = campo(bloco, "REFNUM");
      String id = fitid != null && !fitid.isBlank() ? fitid.trim()
          : data + "|" + valor.toPlainString() + "|" + (descricao == null ? "" : descricao) + "|" + sequencia;
      lancamentos.add(new Lancamento(limitar(id, 120), data, valor.setScale(2, java.math.RoundingMode.HALF_EVEN),
          campo(bloco, "TRNTYPE"), limitar(descricao, 300), limitar(documento, 60)));
    }
    if (lancamentos.isEmpty() && !texto.toUpperCase(Locale.ROOT).contains("<BANKTRANLIST>")) {
      throw new OfxInvalidoException("O OFX não tem lista de transações (BANKTRANLIST).");
    }
    String saldoBloco = entre(texto, "LEDGERBAL");
    BigDecimal saldo = saldoBloco == null ? null : valor(campo(saldoBloco, "BALAMT"));
    return new Extrato(campo(texto, "BANKID"), campo(texto, "ACCTID"), data(campo(texto, "DTSTART")), data(campo(texto, "DTEND")),
        saldo, lancamentos);
  }

  /** CHARSET:1252 (ou ENCODING:USASCII) no cabeçalho SGML → windows-1252; senão UTF-8. */
  static String decodificar(byte[] bytes) {
    String cabecalho = new String(bytes, 0, Math.min(bytes.length, 600), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
    Charset cs = cabecalho.contains("CHARSET:1252") || cabecalho.contains("ENCODING=\"WINDOWS-1252\"")
        || (cabecalho.contains("ENCODING:USASCII") && !cabecalho.contains("CHARSET:UTF"))
        ? Charset.forName("windows-1252") : StandardCharsets.UTF_8;
    return new String(bytes, cs);
  }

  /** Valor de uma tag: até o próximo "<" (SGML) ou o fechamento (XML). */
  static String campo(String bloco, String tag) {
    Matcher m = Pattern.compile("<" + tag + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(bloco);
    if (!m.find()) return null;
    String v = m.group(1).trim();
    return v.isEmpty() ? null : v;
  }

  private static String entre(String texto, String tag) {
    Matcher m = Pattern.compile("<" + tag + ">(.*?)(</" + tag + ">|(?=<[A-Z]+>\\s*<))", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
        .matcher(texto);
    return m.find() ? m.group(1) : null;
  }

  /** 20261127, 20261127120000, 20261127120000[-3:BRT]. */
  static LocalDate data(String s) {
    if (s == null || s.length() < 8) return null;
    try {
      return LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)), Integer.parseInt(s.substring(6, 8)));
    } catch (RuntimeException e) {
      return null;
    }
  }

  static BigDecimal valor(String s) {
    if (s == null) return null;
    String v = s.trim().replace(" ", "");
    if (v.contains(",") && v.contains(".")) v = v.replace(".", "").replace(",", ".");
    else v = v.replace(",", ".");
    try {
      return new BigDecimal(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String juntar(String a, String b) {
    if (a == null) return b;
    if (b == null || b.equalsIgnoreCase(a)) return a;
    return a + " — " + b;
  }

  private static String limitar(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
