package br.com.erp.financeiro.eventos;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.Permissao;
import br.com.erp.financeiro.comum.auth.Permissoes;
import br.com.erp.financeiro.comum.http.Mensagem;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Eventos e webhooks (integração entre módulos)")
@RestController
@RequestMapping("/eventos")
public class EventosController {

  private final EventosService svc;

  public EventosController(EventosService svc) {
    this.svc = svc;
  }

  public record FiltroEventos(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit,
      @Pattern(regexp = TipoEvento.REGEX, message = "tipo de evento desconhecido") String tipo) implements Paginado.Paginacao {}

  public record CriarAssinatura(
      @NotBlank @Size(max = 60) String modulo,
      @NotBlank @Pattern(regexp = "^https?://[^\\s/$.?#][^\\s]*$", message = "deve ser uma URL http:// ou https://") String url,
      @NotEmpty List<@NotNull @Pattern(regexp = TipoEvento.REGEX, message = "tipo de evento desconhecido") String> eventos) {}

  @GetMapping
  @Permissoes(Permissao.LEITURA)
  public Paginado<Evento> listar(AuthUser u, @Valid FiltroEventos f) {
    return svc.listar(u, f.tipo() == null || f.tipo().isBlank() ? null : f.tipo(), f);
  }

  @GetMapping("/assinaturas")
  @Permissoes(Permissao.ADMIN)
  public List<WebhookAssinatura> listarAssinaturas(AuthUser u) {
    return svc.listarAssinaturas(u);
  }

  @PostMapping("/assinaturas")
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ADMIN)
  @Mensagem("Assinatura criada. Guarde o segredo: ele não será exibido novamente.")
  public EventosService.AssinaturaCriada criar(AuthUser u, @Valid @RequestBody CriarAssinatura dto) {
    return svc.criarAssinatura(u, dto.modulo(), dto.url(), dto.eventos());
  }

  @DeleteMapping("/assinaturas/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Permissoes(Permissao.ADMIN)
  public void remover(AuthUser u, @PathVariable UUID id) {
    svc.removerAssinatura(u, id);
  }
}
