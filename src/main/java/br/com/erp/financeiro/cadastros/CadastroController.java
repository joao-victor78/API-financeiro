package br.com.erp.financeiro.cadastros;

import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.cadastros.CadastroDtos.CategoriaEntrada;
import br.com.erp.financeiro.cadastros.CadastroDtos.CategoriaSaida;
import br.com.erp.financeiro.cadastros.CadastroDtos.CentroCustoEntrada;
import br.com.erp.financeiro.cadastros.CadastroDtos.CentroCustoSaida;
import br.com.erp.financeiro.cadastros.CadastroDtos.ContaBancariaEntrada;
import br.com.erp.financeiro.cadastros.CadastroDtos.ContaBancariaSaida;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cadastros financeiros (§25, §32, §34). Ler: financeiro.cadastro.ver; alterar: financeiro.cadastro.administrar. */
@Tag(name = "cadastros")
@RestController
@RequestMapping("/api/financeiro/cadastros")
public class CadastroController {

  private static final String BASE = "/api/financeiro/cadastros/";

  private final CadastroService cadastros;

  public CadastroController(CadastroService cadastros) {
    this.cadastros = cadastros;
  }

  // ------------------------------------------------------------------ categorias

  @GetMapping("/categorias")
  @PreAuthorize("hasAuthority('financeiro.cadastro.ver')")
  public Resposta<List<CategoriaSaida>> categorias() {
    return Resposta.ok(cadastros.categorias().stream().map(CategoriaSaida::de).toList());
  }

  @PostMapping("/categorias")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public ResponseEntity<Resposta<CategoriaSaida>> criarCategoria(@Valid @RequestBody CategoriaEntrada e) {
    CategoriaFinanceira c = cadastros.criarCategoria(e);
    return ResponseEntity.created(URI.create(BASE + "categorias/" + c.getId())).body(Resposta.ok(CategoriaSaida.de(c), "Categoria criada."));
  }

  @Operation(summary = "Criar o plano de categorias padrão (só com o cadastro vazio)")
  @PostMapping("/categorias/plano-padrao")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public Resposta<List<CategoriaSaida>> planoPadrao() {
    List<CategoriaSaida> lista = cadastros.criarPlanoPadrao().stream().map(CategoriaSaida::de).toList();
    return Resposta.ok(lista, lista.size() + " categorias criadas.");
  }

  @PutMapping("/categorias/{id}")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public Resposta<CategoriaSaida> editarCategoria(@PathVariable UUID id, @Valid @RequestBody CategoriaEntrada e) {
    return Resposta.ok(CategoriaSaida.de(cadastros.editarCategoria(id, e)), "Categoria atualizada.");
  }

  @DeleteMapping("/categorias/{id}")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public Resposta<Void> excluirCategoria(@PathVariable UUID id) {
    cadastros.excluirCategoria(id);
    return Resposta.ok(null, "Categoria excluída.");
  }

  // ------------------------------------------------------------------ centros de custo

  @GetMapping("/centros-custo")
  @PreAuthorize("hasAuthority('financeiro.cadastro.ver')")
  public Resposta<List<CentroCustoSaida>> centros() {
    return Resposta.ok(cadastros.centros().stream().map(CentroCustoSaida::de).toList());
  }

  @PostMapping("/centros-custo")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public ResponseEntity<Resposta<CentroCustoSaida>> criarCentro(@Valid @RequestBody CentroCustoEntrada e) {
    CentroCusto c = cadastros.criarCentro(e);
    return ResponseEntity.created(URI.create(BASE + "centros-custo/" + c.getId())).body(Resposta.ok(CentroCustoSaida.de(c), "Centro de custo criado."));
  }

  @PutMapping("/centros-custo/{id}")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public Resposta<CentroCustoSaida> editarCentro(@PathVariable UUID id, @Valid @RequestBody CentroCustoEntrada e) {
    return Resposta.ok(CentroCustoSaida.de(cadastros.editarCentro(id, e)), "Centro de custo atualizado.");
  }

  @DeleteMapping("/centros-custo/{id}")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public Resposta<Void> excluirCentro(@PathVariable UUID id) {
    cadastros.excluirCentro(id);
    return Resposta.ok(null, "Centro de custo excluído.");
  }

  // ------------------------------------------------------------------ contas bancárias

  @GetMapping("/contas-bancarias")
  @PreAuthorize("hasAuthority('financeiro.cadastro.ver')")
  public Resposta<List<ContaBancariaSaida>> contas() {
    return Resposta.ok(cadastros.contas().stream().map(ContaBancariaSaida::de).toList());
  }

  @PostMapping("/contas-bancarias")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public ResponseEntity<Resposta<ContaBancariaSaida>> criarConta(@Valid @RequestBody ContaBancariaEntrada e) {
    ContaBancaria c = cadastros.criarConta(e);
    return ResponseEntity.created(URI.create(BASE + "contas-bancarias/" + c.getId())).body(Resposta.ok(ContaBancariaSaida.de(c), "Conta bancária criada."));
  }

  @PutMapping("/contas-bancarias/{id}")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public Resposta<ContaBancariaSaida> editarConta(@PathVariable UUID id, @Valid @RequestBody ContaBancariaEntrada e) {
    return Resposta.ok(ContaBancariaSaida.de(cadastros.editarConta(id, e)), "Conta bancária atualizada.");
  }

  @DeleteMapping("/contas-bancarias/{id}")
  @PreAuthorize("hasAuthority('financeiro.cadastro.administrar')")
  public Resposta<Void> excluirConta(@PathVariable UUID id) {
    cadastros.excluirConta(id);
    return Resposta.ok(null, "Conta bancária excluída.");
  }
}
