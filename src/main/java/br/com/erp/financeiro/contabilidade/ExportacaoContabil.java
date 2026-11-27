package br.com.erp.financeiro.contabilidade;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.contabilidade.LancamentosContabeis.Lancamento;
import br.com.erp.financeiro.relatorios.Planilhas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Arquivos para o escritório contábil (§37): CSV, XLSX, PDF, OFX e o pacote ZIP do fechamento. */
public final class ExportacaoContabil {

  private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter OFX = DateTimeFormatter.ofPattern("yyyyMMdd");

  static final List<String> CABECALHO = List.of("Data", "Tipo", "Conta débito", "Conta crédito", "Valor", "Histórico", "Documento",
      "Participante", "Categoria", "Centro de custo", "Origem", "Id de origem");

  private ExportacaoContabil() {}

  static List<List<Object>> linhas(List<Lancamento> l) {
    List<List<Object>> linhas = new ArrayList<>();
    for (Lancamento x : l) {
      List<Object> r = new ArrayList<>();
      r.add(x.data().format(DATA));
      r.add(x.tipo());
      r.add(x.contaDebito());
      r.add(x.contaCredito());
      r.add(x.valor());
      r.add(x.historico());
      r.add(x.documento());
      r.add(x.participante());
      r.add(x.categoria());
      r.add(x.centroCusto());
      r.add(x.origem());
      r.add(x.origemId() == null ? null : x.origemId().toString());
      linhas.add(r);
    }
    return linhas;
  }

  public static byte[] csv(List<Lancamento> l) {
    return Planilhas.csv(CABECALHO, linhas(l));
  }

  public static byte[] xlsx(List<Lancamento> l) {
    return Planilhas.xlsx("Lançamentos", CABECALHO, linhas(l));
  }

  public static byte[] pdf(YearMonth mes, List<Lancamento> l) {
    List<String> texto = new ArrayList<>();
    String f = "%-10s %-12s %-14s %-14s %14s  %-60s";
    texto.add(String.format(f, "Data", "Tipo", "Débito", "Crédito", "Valor", "Histórico"));
    texto.add("-".repeat(130));
    java.util.Map<String, BigDecimal> totais = new java.util.TreeMap<>();
    for (Lancamento x : l) {
      String h = x.historico() == null ? "" : x.historico();
      texto.add(String.format(f, x.data().format(DATA), x.tipo(), nulo(x.contaDebito()), nulo(x.contaCredito()), Dinheiro.brl(x.valor()),
          h.length() > 60 ? h.substring(0, 59) + "…" : h));
      totais.merge(x.tipo(), x.valor(), BigDecimal::add);
    }
    texto.add("-".repeat(130));
    totais.forEach((tipo, v) -> texto.add(String.format("%-40s %20s", "Total " + tipo, Dinheiro.brl(v))));
    return Planilhas.pdf("Lançamentos contábeis — " + mes.getMonthValue() + "/" + mes.getYear() + " — " + l.size() + " lançamento(s)", texto);
  }

  /**
   * OFX 1.02 (SGML) com a movimentação bancária realizada pelo sistema na competência: recebimentos,
   * pagamentos, estornos e lançamentos do extrato. Um STMTTRN por lançamento; FITID = id de origem.
   */
  public static byte[] ofx(YearMonth mes, List<Lancamento> l) {
    StringBuilder s = new StringBuilder("""
        OFXHEADER:100
        DATA:OFXSGML
        VERSION:102
        SECURITY:NONE
        ENCODING:USASCII
        CHARSET:1252
        COMPRESSION:NONE
        OLDFILEUID:NONE
        NEWFILEUID:NONE

        <OFX>
        <BANKMSGSRSV1><STMTTRNRS><TRNUID>1<STATUS><CODE>0<SEVERITY>INFO</STATUS>
        <STMTRS><CURDEF>BRL<BANKACCTFROM><BANKID>000<ACCTID>FINANCEIRO<ACCTTYPE>CHECKING</BANKACCTFROM>
        <BANKTRANLIST>
        """.replace("\n", "\r\n"));
    s.append("<DTSTART>").append(mes.atDay(1).format(OFX)).append("\r\n<DTEND>").append(mes.atEndOfMonth().format(OFX)).append("\r\n");
    BigDecimal saldo = BigDecimal.ZERO;
    for (Lancamento x : l) {
      if (x.movimentoBancario() == null || x.movimentoBancario().signum() == 0) continue;
      BigDecimal v = x.movimentoBancario();
      saldo = saldo.add(v);
      s.append("<STMTTRN>\r\n<TRNTYPE>").append(v.signum() > 0 ? "CREDIT" : "DEBIT")
          .append("\r\n<DTPOSTED>").append(x.data().format(OFX))
          .append("\r\n<TRNAMT>").append(v.toPlainString())
          .append("\r\n<FITID>").append(x.origemId())
          .append("\r\n<MEMO>").append(sgml(x.historico()))
          .append("\r\n</STMTTRN>\r\n");
    }
    s.append("</BANKTRANLIST>\r\n<LEDGERBAL><BALAMT>").append(saldo.setScale(2).toPlainString())
        .append("<DTASOF>").append(mes.atEndOfMonth().format(OFX)).append("</LEDGERBAL>\r\n</STMTRS></STMTTRNRS></BANKMSGSRSV1>\r\n</OFX>\r\n");
    return s.toString().getBytes(java.nio.charset.Charset.forName("windows-1252"));
  }

  /** Pacote do fechamento: lançamentos (CSV, XLSX, PDF, OFX), notas e lista de documentos, e os arquivos anexos. */
  public static byte[] pacote(YearMonth mes, List<Lancamento> l, byte[] notasCsv, byte[] documentosCsv, Map<String, byte[]> anexos) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    String p = "fechamento-" + mes + "/";
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      entrada(zip, p + "lancamentos.csv", csv(l));
      entrada(zip, p + "lancamentos.xlsx", xlsx(l));
      entrada(zip, p + "lancamentos.pdf", pdf(mes, l));
      entrada(zip, p + "movimentacao.ofx", ofx(mes, l));
      entrada(zip, p + "notas-fiscais.csv", notasCsv);
      entrada(zip, p + "documentos.csv", documentosCsv);
      for (var a : anexos.entrySet()) entrada(zip, p + "documentos/" + a.getKey(), a.getValue());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static void entrada(ZipOutputStream zip, String nome, byte[] conteudo) throws IOException {
    zip.putNextEntry(new ZipEntry(nome));
    zip.write(conteudo);
    zip.closeEntry();
  }

  private static String sgml(String s) {
    if (s == null) return "";
    String t = s.replace("&", "e").replace("<", " ").replace(">", " ").replaceAll("[\\r\\n]", " ");
    return t.length() > 250 ? t.substring(0, 250) : t;
  }

  private static String nulo(String s) {
    return s == null ? "—" : s;
  }

  static String dataBr(LocalDate d) {
    return d == null ? "" : d.format(DATA);
  }
}
