package br.com.erp.financeiro.comissoes;

import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.comissoes.ComissaoService.ComissaoSaida;
import br.com.erp.financeiro.comissoes.ComissaoService.Fechamento;
import br.com.erp.financeiro.comissoes.ComissaoService.RegraEntrada;
import br.com.erp.financeiro.comissoes.ComissaoService.RegraSaida;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
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

/** Comissões (§71). Ver: financeiro.comissao.ver; regras e fechamento: financeiro.comissao.administrar. */
@Tag(name = "comissoes")
@RestController
@RequestMapping("/api/financeiro/comissoes")
public class ComissaoController {

  private final ComissaoService comissoes;

  public ComissaoController(ComissaoService comissoes) {
    this.comissoes = comissoes;
  }

  @GetMapping("/regras")
  @PreAuthorize("hasAuthority('financeiro.comissao.ver')")
  public Resposta<List<RegraSaida>> regras() {
    return Resposta.ok(comissoes.regras());
  }

  @PostMapping("/regras")
  @PreAuthorize("hasAuthority('financeiro.comissao.administrar')")
  public Resposta<RegraSaida> criarRegra(@Valid @RequestBody RegraEntrada e) {
    return Resposta.ok(comissoes.salvarRegra(null, e), "Regra de comissão criada.");
  }

  @PutMapping("/regras/{id}")
  @PreAuthorize("hasAuthority('financeiro.comissao.administrar')")
  public Resposta<RegraSaida> editarRegra(@PathVariable UUID id, @Valid @RequestBody RegraEntrada e) {
    return Resposta.ok(comissoes.salvarRegra(id, e), "Regra de comissão atualizada.");
  }

  @DeleteMapping("/regras/{id}")
  @PreAuthorize("hasAuthority('financeiro.comissao.administrar')")
  public Resposta<Void> excluirRegra(@PathVariable UUID id) {
    comissoes.excluirRegra(id);
    return Resposta.ok(null, "Regra excluída; as comissões já apuradas continuam.");
  }

  @GetMapping
  @PreAuthorize("hasAuthority('financeiro.comissao.ver')")
  public Resposta<Pagina<ComissaoSaida>> listar(
      @RequestParam(required = false) LocalDate competencia,
      @RequestParam(required = false) RegraComissao.Beneficiario beneficiarioTipo,
      @RequestParam(required = false) UUID beneficiarioId,
      @RequestParam(required = false) Comissao.Status status,
      @RequestParam(required = false) Integer pagina,
      @RequestParam(required = false) Integer tamanho) {
    // §3 "Vendedor só visualiza as suas": sem comissao.administrar, a lista é sempre a do próprio usuário.
    if (!br.com.erp.financeiro.seguranca.Usuarios.tem("financeiro.comissao.administrar")) {
      UUID eu = br.com.erp.financeiro.seguranca.Usuarios.atual();
      beneficiarioId = eu != null ? eu : new UUID(0, 0);
    }
    return Resposta.ok(comissoes.listar(competencia, beneficiarioTipo, beneficiarioId, status,
        Pagina.pedido(pagina, tamanho, Sort.by("createdAt").descending().and(Sort.by("id")))));
  }

  @Operation(summary = "Fechar a competência: lança as comissões a pagar em contas a pagar (uma por beneficiário)")
  @PostMapping("/fechamento")
  @PreAuthorize("hasAuthority('financeiro.comissao.administrar')")
  public Resposta<Fechamento> fechar(@RequestParam LocalDate competencia) {
    Fechamento f = comissoes.fechar(competencia);
    return Resposta.ok(f, f.contasPagar().size() + " conta(s) a pagar lançada(s), aguardando aprovação.");
  }

  @PostMapping("/{id}/cancelar")
  @PreAuthorize("hasAuthority('financeiro.comissao.administrar')")
  public Resposta<Void> cancelar(@PathVariable UUID id) {
    comissoes.cancelar(id);
    return Resposta.ok(null, "Comissão cancelada.");
  }
}
