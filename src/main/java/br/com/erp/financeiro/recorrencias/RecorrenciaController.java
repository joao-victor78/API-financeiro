package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaDtos.CobrancaSaida;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.AdicionalEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.AdicionalSaida;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.CartaoEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ConsumoEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ConsumoSaida;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ReajusteEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ReajusteSaida;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.RegraEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.RegraSaida;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@Tag(name = "recorrencia")
@RestController
@RequestMapping("/api/financeiro/recorrencias")
public class RecorrenciaController {

  private final RecorrenciaService recorrencias;
  private final RecorrenciaExtrasService extras;
  private final Relogio relogio;

  public RecorrenciaController(RecorrenciaService recorrencias, RecorrenciaExtrasService extras, Relogio relogio) {
    this.recorrencias = recorrencias;
    this.extras = extras;
    this.relogio = relogio;
  }

  // ------------------------------------------------------------------ V2: consumo, adicionais, reajuste, cartão

  @GetMapping("/{id}/consumos")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.ver')")
  public Resposta<List<ConsumoSaida>> consumos(@PathVariable UUID id) {
    return Resposta.ok(extras.consumos(id));
  }

  /** §41: lança ou corrige o consumo do serviço na competência (entra como excedente na próxima cobrança). */
  @PutMapping("/{id}/consumos")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<ConsumoSaida> registrarConsumo(@PathVariable UUID id, @Valid @RequestBody ConsumoEntrada e) {
    return Resposta.ok(extras.registrarConsumo(id, e, RecorrenciaConsumo.Origem.MANUAL), "Consumo registrado.");
  }

  @GetMapping("/{id}/adicionais")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.ver')")
  public Resposta<List<AdicionalSaida>> adicionais(@PathVariable UUID id) {
    return Resposta.ok(extras.adicionais(id));
  }

  @PostMapping("/{id}/adicionais")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<AdicionalSaida> criarAdicional(@PathVariable UUID id, @Valid @RequestBody AdicionalEntrada e) {
    return Resposta.ok(extras.criarAdicional(id, e), "Adicional incluído: entra na próxima cobrança.");
  }

  @DeleteMapping("/{id}/adicionais/{adicionalId}")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<Void> excluirAdicional(@PathVariable UUID id, @PathVariable UUID adicionalId) {
    extras.excluirAdicional(id, adicionalId);
    return Resposta.ok(null, "Adicional removido.");
  }

  @GetMapping("/{id}/reajustes")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.ver')")
  public Resposta<List<ReajusteSaida>> reajustes(@PathVariable UUID id) {
    return Resposta.ok(extras.reajustes(id));
  }

  /** §69: IPCA, IGP-M, percentual fixo ou outro; registra valor anterior, índice, novo valor e data. */
  @PostMapping("/{id}/reajustes")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<ReajusteSaida> reajustar(@PathVariable UUID id, @Valid @RequestBody ReajusteEntrada e) {
    return Resposta.ok(extras.aplicarReajuste(id, e), "Reajuste aplicado a partir da competência informada.");
  }

  @GetMapping("/reajustes-pendentes")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.ver')")
  public Resposta<List<RegraSaida>> reajustesPendentes() {
    return Resposta.ok(extras.reajustesPendentes());
  }

  @PutMapping("/{id}/cartao")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<RegraSaida> registrarCartao(@PathVariable UUID id, @Valid @RequestBody CartaoEntrada e) {
    return Resposta.ok(extras.registrarCartao(id, e), "Cartão recorrente vinculado.");
  }

  @DeleteMapping("/{id}/cartao")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<RegraSaida> removerCartao(@PathVariable UUID id) {
    return Resposta.ok(extras.removerCartao(id), "Cartão desvinculado; as próximas cobranças voltam ao pagamento manual.");
  }

  @GetMapping("/{id}/cartao/link")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<java.util.Map<String, String>> linkCartao(@PathVariable UUID id) {
    return Resposta.ok(java.util.Map.of("link", extras.linkCadastroCartao(id)));
  }

  @PostMapping("/{id}/cartao/teste")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<RegraSaida> cartaoDeTeste(@PathVariable UUID id) {
    return Resposta.ok(extras.cartaoDeTeste(id), "Cartão de teste (provedor simulado) vinculado.");
  }

  @GetMapping
  @PreAuthorize("hasAuthority('financeiro.recorrencia.ver')")
  public Resposta<Pagina<RegraSaida>> listar(
      @RequestParam(required = false) Integer pagina,
      @RequestParam(required = false) Integer tamanho,
      @RequestParam(required = false) UUID clienteId,
      @RequestParam(required = false) Boolean ativa) {
    return Resposta.ok(recorrencias.listar(clienteId, ativa,
        Pagina.pedido(pagina, tamanho, Sort.by("createdAt").descending().and(Sort.by("id")))));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public ResponseEntity<Resposta<RegraSaida>> criar(@Valid @RequestBody RegraEntrada e) {
    RegraSaida r = recorrencias.criar(e);
    return ResponseEntity.created(URI.create("/api/financeiro/recorrencias/" + r.id())).body(Resposta.ok(r, "Regra criada."));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.ver')")
  public Resposta<RegraSaida> ver(@PathVariable UUID id) {
    return Resposta.ok(recorrencias.ver(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public Resposta<RegraSaida> atualizar(@PathVariable UUID id, @Valid @RequestBody RegraEntrada e) {
    return Resposta.ok(recorrencias.atualizar(id, e), "Regra atualizada: vale a partir da próxima geração.");
  }

  @PostMapping("/{id}/gerar-cobranca")
  @PreAuthorize("hasAuthority('financeiro.recorrencia.administrar')")
  public ResponseEntity<Resposta<CobrancaSaida>> gerar(@PathVariable UUID id) {
    Cobranca c = recorrencias.gerarManual(id);
    return ResponseEntity.created(URI.create("/api/financeiro/cobrancas/" + c.getId()))
        .body(Resposta.ok(CobrancaSaida.de(c, relogio.hoje()), "Cobrança do ciclo gerada."));
  }
}
