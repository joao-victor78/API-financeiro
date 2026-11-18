package br.com.erp.financeiro.recorrencias;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.Permissao;
import br.com.erp.financeiro.comum.auth.Permissoes;
import br.com.erp.financeiro.comum.http.Mensagem;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Atualizar;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Criar;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Detalhe;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.DetalheComExecucoes;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Filtro;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.FiltroPrevisao;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Previsao;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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

@Tag(name = "Recorrências")
@RestController
@RequestMapping("/recorrencias")
public class RecorrenciasController {

  private final RecorrenciasService svc;

  public RecorrenciasController(RecorrenciasService svc) {
    this.svc = svc;
  }

  @GetMapping
  @Permissoes(Permissao.LEITURA)
  public Paginado<Detalhe> listar(AuthUser u, @Valid Filtro f) {
    return svc.listar(u, f);
  }

  @GetMapping("/{id}")
  @Permissoes(Permissao.LEITURA)
  public DetalheComExecucoes obter(AuthUser u, @PathVariable UUID id) {
    return svc.detalhar(u, id);
  }

  @GetMapping("/{id}/previsao")
  @Permissoes(Permissao.LEITURA)
  public List<Previsao> previsao(AuthUser u, @PathVariable UUID id, @Valid FiltroPrevisao q) {
    return svc.previsao(u, id, q.ciclos() == null ? 6 : q.ciclos());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Recorrência criada")
  public Detalhe criar(AuthUser u, @Valid @RequestBody Criar dto) {
    return svc.criar(u, dto);
  }

  @PatchMapping("/{id}")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Recorrência atualizada")
  public Detalhe atualizar(AuthUser u, @PathVariable UUID id, @Valid @RequestBody Atualizar dto) {
    return svc.atualizar(u, id, dto);
  }

  @PostMapping("/{id}/pausar")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Recorrência pausada")
  public Detalhe pausar(AuthUser u, @PathVariable UUID id) {
    return svc.alterarAtiva(u, id, false);
  }

  @PostMapping("/{id}/reativar")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Recorrência reativada")
  public Detalhe reativar(AuthUser u, @PathVariable UUID id) {
    return svc.alterarAtiva(u, id, true);
  }

  @PostMapping("/{id}/gerar")
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Cobrança da próxima competência gerada")
  public Object gerar(AuthUser u, @PathVariable UUID id) {
    return svc.gerarAgora(u, id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Permissoes(Permissao.ESCRITA)
  public void remover(AuthUser u, @PathVariable UUID id) {
    svc.remover(u, id);
  }
}
