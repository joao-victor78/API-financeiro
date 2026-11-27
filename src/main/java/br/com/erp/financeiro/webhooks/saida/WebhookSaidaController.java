package br.com.erp.financeiro.webhooks.saida;

import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.webhooks.saida.WebhookSaidaService.AssinaturaEntrada;
import br.com.erp.financeiro.webhooks.saida.WebhookSaidaService.AssinaturaSaida;
import br.com.erp.financeiro.webhooks.saida.WebhookSaidaService.Entrega;
import br.com.erp.financeiro.webhooks.saida.WebhookSaidaService.Evento;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Central de webhooks de saída (§91). Tudo exige financeiro.webhook.administrar. */
@Tag(name = "webhooks-saida")
@RestController
@RequestMapping("/api/financeiro/webhooks")
public class WebhookSaidaController {

  private final WebhookSaidaService webhooks;

  public WebhookSaidaController(WebhookSaidaService webhooks) {
    this.webhooks = webhooks;
  }

  @GetMapping("/eventos")
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<List<Evento>> eventos() {
    return Resposta.ok(webhooks.eventosDisponiveis());
  }

  @GetMapping
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<List<AssinaturaSaida>> listar() {
    return Resposta.ok(webhooks.listar());
  }

  @PostMapping
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<AssinaturaSaida> criar(@Valid @RequestBody AssinaturaEntrada e) {
    return Resposta.ok(webhooks.criar(e), "Webhook criado. Guarde o segredo: ele não será mostrado de novo.");
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<AssinaturaSaida> editar(@PathVariable UUID id, @Valid @RequestBody AssinaturaEntrada e) {
    return Resposta.ok(webhooks.editar(id, e), "Webhook atualizado.");
  }

  @PostMapping("/{id}/segredo")
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<AssinaturaSaida> trocarSegredo(@PathVariable UUID id) {
    return Resposta.ok(webhooks.trocarSegredo(id), "Segredo trocado. Atualize o destino com o novo valor.");
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<Void> excluir(@PathVariable UUID id) {
    webhooks.excluir(id);
    return Resposta.ok(null, "Webhook excluído.");
  }

  @PostMapping("/{id}/teste")
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<Entrega> testar(@PathVariable UUID id) {
    Entrega e = webhooks.testar(id);
    return Resposta.ok(e, "ENTREGUE".equals(e.status()) ? "Teste entregue." : "Teste não entregue: " + e.ultimoErro());
  }

  @GetMapping("/entregas")
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<List<Entrega>> entregas(@RequestParam(required = false) UUID assinaturaId, @RequestParam(required = false) String status) {
    return Resposta.ok(webhooks.entregas(assinaturaId, status));
  }

  @PostMapping("/entregas/{id}/reenviar")
  @PreAuthorize("hasAuthority('financeiro.webhook.administrar')")
  public Resposta<Void> reenviar(@PathVariable UUID id) {
    webhooks.reenviar(id);
    return Resposta.ok(null, "Entrega recolocada na fila.");
  }
}
