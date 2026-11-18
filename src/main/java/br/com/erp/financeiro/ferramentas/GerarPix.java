package br.com.erp.financeiro.ferramentas;

import br.com.erp.financeiro.pix.BrCode;
import br.com.erp.financeiro.pix.QrCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Gera um QR Code Pix (estático, com valor) para uma chave e valor quaisquer — útil para testar com a sua chave.
 * Não usa banco nem servidor.
 *
 * <pre>
 * Uso:   mvn -q compile exec:java@pix -Dexec.args="&lt;chave&gt; &lt;valor&gt; [nome] [cidade]"
 * Ex.:   mvn -q compile exec:java@pix -Dexec.args="meuemail@gmail.com 10,50"
 *        mvn -q compile exec:java@pix -Dexec.args="+5562999998888 1 'Pedro Santos' Goiania"
 * </pre>
 *
 * Telefone deve vir com +55 e DDD (11 dígitos soltos são tratados como CPF).
 */
public final class GerarPix {

  private GerarPix() {}

  record Chave(String chave, String tipo) {}

  static Chave normalizarChave(String bruta) {
    String c = bruta.trim();
    if (c.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return new Chave(c.toLowerCase(Locale.ROOT), "e-mail");
    if (c.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) return new Chave(c.toLowerCase(Locale.ROOT), "chave aleatória");
    if (c.startsWith("+")) {
      String tel = "+" + c.replaceAll("\\D", "");
      if (!tel.matches("^\\+55\\d{10,11}$")) throw new IllegalArgumentException("Telefone deve estar no formato +55 + DDD + número (ex.: +5562999998888)");
      return new Chave(tel, "telefone");
    }
    String dig = c.replaceAll("\\D", "");
    if (dig.length() == 11 && c.matches("^[\\d.\\-\\s]+$")) return new Chave(dig, "CPF");
    if (dig.length() == 14 && c.matches("^[\\d.\\-/\\s]+$")) return new Chave(dig, "CNPJ");
    throw new IllegalArgumentException("Não reconheci \"" + bruta + "\" como chave Pix (e-mail, CPF, CNPJ, +55telefone ou chave aleatória)");
  }

  static BigDecimal normalizarValor(String bruto) {
    String s = bruto.trim().replaceAll("\\s", "").replaceAll("\\.(?=\\d{3}(\\D|$))", "").replace(',', '.');
    BigDecimal v;
    try {
      v = new BigDecimal(s);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Valor inválido: \"" + bruto + "\"");
    }
    if (v.signum() <= 0) throw new IllegalArgumentException("Valor inválido: \"" + bruto + "\"");
    if (v.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("Use no máximo 2 casas decimais");
    if (v.compareTo(new BigDecimal("1000000")) > 0) throw new IllegalArgumentException("Valor alto demais para um teste");
    return v.setScale(2, RoundingMode.UNNECESSARY);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println("Uso: mvn -q compile exec:java@pix -Dexec.args=\"<chave> <valor> [nome] [cidade]\"");
      System.out.println("Ex.: mvn -q compile exec:java@pix -Dexec.args=\"meuemail@gmail.com 10,50\"");
      System.exit(1);
    }
    try {
      Chave chave = normalizarChave(args[0]);
      BigDecimal valor = normalizarValor(args[1]);
      String nome = args.length > 2 ? args[2] : Env.get("PIX_NOME_RECEBEDOR", "EMPRESA");
      String cidade = args.length > 3 ? args[3] : Env.get("PIX_CIDADE_RECEBEDOR", "GOIANIA");
      byte[] aleatorio = new byte[8];
      new SecureRandom().nextBytes(aleatorio);
      // txid curto e alfanumérico: aceito por todos os bancos em QR estático
      String txid = "TESTE" + HexFormat.of().withUpperCase().formatHex(aleatorio).substring(0, 15);

      String copiaCola = BrCode.gerar(chave.chave(), valor, txid, nome, cidade);
      String arquivo = "pix-" + valor.toPlainString().replace('.', '_') + ".png";
      Files.write(Path.of(arquivo), QrCode.png(copiaCola, 400));

      System.out.println();
      System.out.println(QrCode.terminal(copiaCola));
      System.out.println("Chave (" + chave.tipo() + "): " + chave.chave());
      System.out.println("Valor:          " + NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(valor));
      System.out.println("Recebedor:      " + nome + " — " + cidade);
      System.out.println("Identificador:  " + txid);
      System.out.println("\nPix copia e cola:\n" + copiaCola + "\n");
      System.out.println("Imagem salva em: " + arquivo);
      System.out.println("Atenção: o QR é real. Ao ler no app do banco, confira os dados; só confirme se quiser mesmo transferir.\n");
    } catch (IllegalArgumentException e) {
      System.err.println("Erro: " + e.getMessage());
      System.exit(1);
    }
  }
}
