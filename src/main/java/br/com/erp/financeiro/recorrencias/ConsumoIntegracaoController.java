package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ConsumoIntegracao;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ConsumoSaida;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada de consumo por integração (§41, §25 Integrações): ferramenta de monitoramento, painel do
 * fabricante ou outro módulo envia o consumo medido com token de serviço e X-Tenant-Id.
 */
@Tag(name = "recorrencia")
@RestController
@org.springframework.validation.annotation.Validated
public class ConsumoIntegracaoController {

  private final RecorrenciaExtrasService extras;

  public ConsumoIntegracaoController(RecorrenciaExtrasService extras) {
    this.extras = extras;
  }

  @Operation(summary = "Registrar consumos em lote (integração, até 500)")
  @PostMapping("/api/financeiro/consumos")
  @PreAuthorize("hasAuthority('financeiro.consumo.registrar')")
  public Resposta<List<ConsumoSaida>> registrar(@RequestBody List<@Valid ConsumoIntegracao> lote) {
    List<ConsumoSaida> r = extras.registrarPorIntegracao(lote);
    return Resposta.ok(r, r.size() + " consumo(s) registrado(s).");
  }
}
