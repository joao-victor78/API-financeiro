package br.com.erp.financeiro.fiscal;

import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.fiscal.NotaFiscalService.NotaEntrada;
import br.com.erp.financeiro.fiscal.NotaFiscalService.NotaSaida;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "notas-fiscais")
@RestController
@RequestMapping("/api/financeiro/notas-fiscais")
public class NotaFiscalController {

  private final NotaFiscalService notas;

  public NotaFiscalController(NotaFiscalService notas) {
    this.notas = notas;
  }

  public record CancelamentoEntrada(@NotBlank(message = "obrigatório") String motivo) {}

  public record AnexoEntrada(@NotNull(message = "obrigatório") UUID documentoId) {}

  @GetMapping
  @PreAuthorize("hasAuthority('financeiro.notafiscal.ver')")
  public Resposta<Pagina<NotaSaida>> listar(
      @RequestParam(required = false) NotaFiscal.Tipo tipo,
      @RequestParam(required = false) LocalDate competencia,
      @RequestParam(required = false) NotaFiscal.Status status,
      @RequestParam(required = false) UUID cobrancaId,
      @RequestParam(required = false) Integer pagina,
      @RequestParam(required = false) Integer tamanho) {
    return Resposta.ok(notas.listar(tipo, competencia, status, cobrancaId,
        Pagina.pedido(pagina, tamanho, Sort.by("dataEmissao").descending().and(Sort.by("id")))));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('financeiro.notafiscal.registrar')")
  public ResponseEntity<Resposta<NotaSaida>> registrar(@Valid @RequestBody NotaEntrada e) {
    NotaFiscal n = notas.registrar(e);
    return ResponseEntity.created(URI.create("/api/financeiro/notas-fiscais/" + n.getId())).body(Resposta.ok(NotaSaida.de(n), "Nota registrada."));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('financeiro.notafiscal.ver')")
  public Resposta<NotaSaida> ver(@PathVariable UUID id) {
    return Resposta.ok(NotaSaida.de(notas.obter(id)));
  }

  @PutMapping("/{id}/documento")
  @PreAuthorize("hasAuthority('financeiro.notafiscal.registrar')")
  public Resposta<NotaSaida> anexar(@PathVariable UUID id, @Valid @RequestBody AnexoEntrada e) {
    return Resposta.ok(NotaSaida.de(notas.anexar(id, e.documentoId())), "Arquivo da nota anexado.");
  }

  @PostMapping("/{id}/cancelar")
  @PreAuthorize("hasAuthority('financeiro.notafiscal.registrar')")
  public Resposta<NotaSaida> cancelar(@PathVariable UUID id, @Valid @RequestBody CancelamentoEntrada e) {
    return Resposta.ok(NotaSaida.de(notas.cancelar(id, e.motivo())), "Nota cancelada.");
  }
}
