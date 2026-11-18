package br.com.erp.financeiro.inadimplencia;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.Permissao;
import br.com.erp.financeiro.comum.auth.Permissoes;
import br.com.erp.financeiro.comum.http.Mensagem;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.Atraso;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.CriarPromessa;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.Filtro;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.FiltroPromessas;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.Resumo;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inadimplência")
@RestController
@RequestMapping("/inadimplencia")
public class InadimplenciaController {

  private final InadimplenciaService svc;

  public InadimplenciaController(InadimplenciaService svc) {
    this.svc = svc;
  }

  @GetMapping
  @Permissoes(Permissao.LEITURA)
  public Paginado<Atraso> listar(AuthUser u, @Valid Filtro f) {
    return svc.listar(u, f);
  }

  @GetMapping("/resumo")
  @Permissoes(Permissao.LEITURA)
  public Resumo resumo(AuthUser u) {
    return svc.resumo(u);
  }

  @GetMapping("/promessas")
  @Permissoes(Permissao.LEITURA)
  public Paginado<PromessaPagamento> promessas(AuthUser u, @Valid FiltroPromessas f) {
    return svc.listarPromessas(u, f);
  }

  @PostMapping("/promessas")
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Promessa de pagamento registrada")
  public PromessaPagamento criarPromessa(AuthUser u, @Valid @RequestBody CriarPromessa dto) {
    return svc.criarPromessa(u, dto);
  }
}
