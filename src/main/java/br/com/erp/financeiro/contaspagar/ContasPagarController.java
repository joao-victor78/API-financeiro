package br.com.erp.financeiro.contaspagar;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.Permissao;
import br.com.erp.financeiro.comum.auth.Permissoes;
import br.com.erp.financeiro.comum.http.Mensagem;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Atualizar;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.ComSaldo;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Criar;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Filtro;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Pagamento;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Contas a pagar")
@RestController
@RequestMapping("/contas-pagar")
public class ContasPagarController {

  private final ContasPagarService svc;

  public ContasPagarController(ContasPagarService svc) {
    this.svc = svc;
  }

  @GetMapping
  @Permissoes(Permissao.LEITURA)
  public Paginado<ComSaldo> listar(AuthUser u, @Valid Filtro f) {
    return svc.listar(u, f);
  }

  @GetMapping("/{id}")
  @Permissoes(Permissao.LEITURA)
  public ComSaldo obter(AuthUser u, @PathVariable UUID id) {
    return svc.detalhar(u, id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Conta a pagar criada")
  public ComSaldo criar(AuthUser u, @Valid @RequestBody Criar dto) {
    return svc.criar(u, dto);
  }

  @PatchMapping("/{id}")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Conta a pagar atualizada")
  public ComSaldo atualizar(AuthUser u, @PathVariable UUID id, @Valid @RequestBody Atualizar dto) {
    return svc.atualizar(u, id, dto);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Permissoes(Permissao.ESCRITA)
  public void remover(AuthUser u, @PathVariable UUID id) {
    svc.remover(u, id);
  }

  @PostMapping("/{id}/pagamento")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Pagamento registrado")
  public ComSaldo pagar(AuthUser u, @PathVariable UUID id, @Valid @RequestBody Pagamento dto) {
    return svc.pagar(u, id, dto);
  }
}
