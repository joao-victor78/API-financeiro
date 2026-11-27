package br.com.erp.financeiro.configuracoes;

import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.cobrancas.EncargosService;
import br.com.erp.financeiro.configuracoes.ConfiguracaoService.Configuracao;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "configuracoes")
@RestController
@RequestMapping("/api/financeiro/configuracoes")
public class ConfiguracaoController {

  private final ConfiguracaoService configuracoes;
  private final EncargosService encargos;

  public ConfiguracaoController(ConfiguracaoService configuracoes, EncargosService encargos) {
    this.configuracoes = configuracoes;
    this.encargos = encargos;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('financeiro.configuracao.administrar')")
  public Resposta<Configuracao> ver() {
    return Resposta.ok(configuracoes.atual());
  }

  @PutMapping
  @PreAuthorize("hasAuthority('financeiro.configuracao.administrar')")
  public Resposta<Configuracao> salvar(@Valid @RequestBody Configuracao c) {
    return Resposta.ok(configuracoes.salvar(c), "Configuração salva.");
  }

  @Operation(summary = "Recalcular agora juros e multa das cobranças atrasadas do tenant")
  @PostMapping("/encargos/recalcular")
  @PreAuthorize("hasAuthority('financeiro.configuracao.administrar')")
  public Resposta<Map<String, Integer>> recalcular() {
    int n = encargos.recalcular();
    return Resposta.ok(Map.of("cobrancasAtualizadas", n), n + " cobrança(s) com encargos atualizados.");
  }
}
