package br.com.erp.financeiro.pix;

import br.com.erp.financeiro.comum.auth.Publico;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.comum.http.Mensagem;
import br.com.erp.financeiro.config.Ambiente;
import br.com.erp.financeiro.eventos.EventosService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint chamado pelo PSP (banco) quando um Pix é liquidado — notificação máquina-a-máquina.
 * Não usa JWT (o chamador é o banco); a autenticidade é garantida por assinatura HMAC-SHA256
 * sobre o corpo bruto. Em produção, soma-se mTLS conforme o padrão de webhooks da API Pix do BCB.
 */
@Tag(name = "Webhook Pix (PSP → Financeiro)")
@RestController
@RequestMapping("/webhooks/pix")
public class PixWebhookController {

  private final PixService svc;
  private final Ambiente ambiente;
  private final ObjectMapper json;

  public PixWebhookController(PixService svc, Ambiente ambiente, ObjectMapper json) {
    this.svc = svc;
    this.ambiente = ambiente;
    this.json = json;
  }

  @PostMapping
  @Publico
  @Mensagem("Notificação Pix processada")
  public PixService.Processamento receber(
      @Parameter(description = "sha256=<hex HMAC-SHA256 do corpo com o segredo compartilhado>")
      @RequestHeader(value = "x-pix-assinatura", required = false) String assinatura,
      @RequestBody(required = false) byte[] corpo) {
    byte[] bruto = corpo == null ? new byte[0] : corpo;
    byte[] esperado = ("sha256=" + EventosService.hmacSha256(ambiente.pixWebhookSecret(), bruto)).getBytes(StandardCharsets.UTF_8);
    byte[] recebido = (assinatura == null ? "" : assinatura).getBytes(StandardCharsets.UTF_8);
    // Comparação em tempo constante: não vaza, pelo tempo de resposta, quantos caracteres estavam certos.
    if (!MessageDigest.isEqual(esperado, recebido)) throw ApiException.naoAutorizado("Assinatura do webhook inválida");
    JsonNode no;
    try {
      no = json.readTree(bruto);
    } catch (IOException e) {
      throw ApiException.requisicaoInvalida("JSON inválido");
    }
    return svc.processarNotificacao(no);
  }
}
