package br.com.erp.financeiro.automacao;

import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.Permissao;
import br.com.erp.financeiro.comum.auth.Permissoes;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.comum.http.Mensagem;
import br.com.erp.financeiro.eventos.EventosService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Automação")
@RestController
@RequestMapping("/automacao")
public class AutomacaoController {

  private final AgendadorService agendador;
  private final EventosService eventos;

  public AutomacaoController(AgendadorService agendador, EventosService eventos) {
    this.agendador = agendador;
    this.eventos = eventos;
  }

  @GetMapping("/status")
  @Permissoes(Permissao.LEITURA)
  public Map<String, Object> status(AuthUser u) {
    Map<String, Object> s = new LinkedHashMap<>(agendador.status());
    s.put("eventos_por_status", eventos.contarPorStatus(u.tenantId()));
    return s;
  }

  @Operation(summary = "Disparo manual de um job, restrito ao tenant de quem chama (útil para homologação e suporte)")
  @PostMapping("/executar/{job}")
  @Permissoes(Permissao.ADMIN)
  @Mensagem("Job executado")
  public Object executar(AuthUser u,
      @Parameter(schema = @Schema(allowableValues = {"recorrencias", "vencimentos", "promessas", "eventos"})) @PathVariable String job) {
    AgendadorService.Job j = AgendadorService.Job.de(job);
    if (j == null) throw ApiException.requisicaoInvalida("Job inválido: use recorrencias, vencimentos, promessas ou eventos");
    return agendador.executar(j, j == AgendadorService.Job.eventos ? null : u.tenantId());
  }
}
