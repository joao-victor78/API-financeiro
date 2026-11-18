package br.com.erp.financeiro.health;

import br.com.erp.financeiro.automacao.AgendadorService;
import br.com.erp.financeiro.comum.auth.Publico;
import br.com.erp.financeiro.comum.http.Resposta;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint público de disponibilidade, consultado automaticamente por outros módulos e monitoramento. */
@Tag(name = "Health")
@RestController
@RequestMapping("/health")
public class HealthController {

  public static final String VERSAO = "0.1.0";

  private final JdbcTemplate jdbc;
  private final AgendadorService agendador;

  public HealthController(JdbcTemplate jdbc, AgendadorService agendador) {
    this.jdbc = jdbc;
    this.agendador = agendador;
  }

  @GetMapping
  @Publico
  public ResponseEntity<?> health() {
    long t0 = System.currentTimeMillis();
    String banco = "ok";
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
    } catch (RuntimeException e) {
      banco = "indisponivel";
    }
    if (!"ok".equals(banco)) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Resposta.erro("Banco de dados indisponível", List.of()));
    }
    Map<String, Object> corpo = new LinkedHashMap<>();
    corpo.put("status", "ok");
    corpo.put("modulo", "financeiro");
    corpo.put("versao", VERSAO);
    corpo.put("uptime_s", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    corpo.put("dependencias", Map.of("banco", Map.of("tipo", "postgres", "status", banco, "latencia_ms", System.currentTimeMillis() - t0)));
    corpo.put("agendador", agendador.status());
    return ResponseEntity.ok(corpo);
  }
}
