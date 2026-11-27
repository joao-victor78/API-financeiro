package br.com.erp.financeiro.seguranca;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Criptografia de segredos guardados no banco (§84, §85): AES-256-GCM com IV aleatório por valor.
 * A chave vem de FINANCEIRO_CHAVE_CRIPTOGRAFIA (32 bytes em Base64). Sem ela, uma chave de
 * desenvolvimento fixa é usada e o log avisa — em produção a variável é obrigatória.
 */
@Component
public class Cifra {

  private static final Logger log = LoggerFactory.getLogger(Cifra.class);
  private static final String PREFIXO = "v1:";

  private final SecretKeySpec chave;
  private final SecureRandom aleatorio = new SecureRandom();

  public Cifra(@Value("${financeiro.criptografia.chave:}") String chaveBase64) {
    byte[] bytes;
    if (chaveBase64 == null || chaveBase64.isBlank()) {
      log.warn("FINANCEIRO_CHAVE_CRIPTOGRAFIA não definida: usando chave de DESENVOLVIMENTO. Defina a variável em produção.");
      bytes = sha256("financeiro-chave-de-desenvolvimento-nao-use-em-producao");
    } else {
      bytes = Base64.getDecoder().decode(chaveBase64.trim());
      if (bytes.length != 32) throw new IllegalStateException("FINANCEIRO_CHAVE_CRIPTOGRAFIA deve ter 32 bytes em Base64");
    }
    this.chave = new SecretKeySpec(bytes, "AES");
  }

  public String cifrar(String texto) {
    try {
      byte[] iv = new byte[12];
      aleatorio.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(128, iv));
      byte[] cifrado = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
      return PREFIXO + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array());
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao cifrar", e);
    }
  }

  public String decifrar(String valor) {
    if (valor == null || !valor.startsWith(PREFIXO)) throw new IllegalArgumentException("Valor cifrado em formato desconhecido");
    try {
      byte[] tudo = Base64.getDecoder().decode(valor.substring(PREFIXO.length()));
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(128, tudo, 0, 12));
      return new String(c.doFinal(tudo, 12, tudo.length - 12), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao decifrar (chave trocada?)", e);
    }
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
