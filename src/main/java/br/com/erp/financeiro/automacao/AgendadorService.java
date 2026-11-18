package br.com.erp.financeiro.automacao;

import br.com.erp.financeiro.config.Ambiente;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.inadimplencia.InadimplenciaService;
import br.com.erp.financeiro.recorrencias.RecorrenciasService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agendador: o "relógio" que faz o módulo agir sozinho, como um dispositivo IoT que reporta
 * leituras periodicamente sem que ninguém peça cada uma. Nenhum desses jobs exige ação humana.
 */
@Service
public class AgendadorService {

  private static final Logger log = LoggerFactory.getLogger("Agendador");

  public enum Job {
    recorrencias("diariamente 00:05 (America/Sao_Paulo)"),
    vencimentos("diariamente 00:15"),
    promessas("diariamente 00:20"),
    eventos("a cada 15 segundos");

    final String agenda;

    Job(String agenda) {
      this.agenda = agenda;
    }

    public static Job de(String nome) {
      for (Job j : values()) if (j.name().equals(nome)) return j;
      return null;
    }
  }

  public record Execucao(Job job, Instant inicio, long duracaoMs, boolean ok, Object resultado) {}

  public record ResultadoJob(Job job, long duracaoMs, Object resultado) {}

  private final RecorrenciasService recorrencias;
  private final InadimplenciaService inadimplencia;
  private final EventosService eventos;
  private final Ambiente ambiente;
  private final Map<Job, Execucao> ultimas = new ConcurrentHashMap<>();
  private final Set<Job> rodando = ConcurrentHashMap.newKeySet();

  public AgendadorService(RecorrenciasService recorrencias, InadimplenciaService inadimplencia, EventosService eventos, Ambiente ambiente) {
    this.recorrencias = recorrencias;
    this.inadimplencia = inadimplencia;
    this.eventos = eventos;
    this.ambiente = ambiente;
  }

  @Scheduled(cron = "0 5 0 * * *", zone = "America/Sao_Paulo")
  public void cronRecorrencias() {
    if (ambiente.jobsAtivos()) executarComLog(Job.recorrencias);
  }

  @Scheduled(cron = "0 15 0 * * *", zone = "America/Sao_Paulo")
  public void cronVencimentos() {
    if (ambiente.jobsAtivos()) executarComLog(Job.vencimentos);
  }

  @Scheduled(cron = "0 20 0 * * *", zone = "America/Sao_Paulo")
  public void cronPromessas() {
    if (ambiente.jobsAtivos()) executarComLog(Job.promessas);
  }

  @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
  public void intervaloEventos() {
    if (ambiente.jobsAtivos()) executarComLog(Job.eventos);
  }

  private void executarComLog(Job job) {
    try {
      executar(job, null);
    } catch (RuntimeException e) {
      // já registrado em executar()
    }
  }

  /** Executa um job (pelo cron ou pelo disparo manual, restrito a um tenant). */
  public Object executar(Job job, UUID tenant) {
    if (!rodando.add(job)) return Map.of("job", job, "ignorado", "execução anterior ainda em andamento");
    long t0 = System.currentTimeMillis();
    Instant inicio = Instant.now();
    try {
      Object resultado = switch (job) {
        case recorrencias -> recorrencias.processarPendentes(tenant);
        case vencimentos -> inadimplencia.marcarVencidas(tenant);
        case promessas -> inadimplencia.verificarPromessas(tenant);
        case eventos -> eventos.despachar();
      };
      long dur = System.currentTimeMillis() - t0;
      ultimas.put(job, new Execucao(job, inicio, dur, true, resultado));
      if (job != Job.eventos || ((EventosService.ResultadoDespacho) resultado).processados() > 0) log.info("{}: {}", job, resultado);
      return new ResultadoJob(job, dur, resultado);
    } catch (RuntimeException e) {
      ultimas.put(job, new Execucao(job, inicio, System.currentTimeMillis() - t0, false, e.getMessage()));
      log.error("{} falhou: {}", job, e.getMessage());
      throw e;
    } finally {
      rodando.remove(job);
    }
  }

  public Map<String, Object> status() {
    Map<String, Object> jobs = new LinkedHashMap<>();
    for (Job j : Job.values()) {
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("agenda", j.agenda);
      info.put("ultima", ultimas.get(j));
      jobs.put(j.name(), info);
    }
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("agendamento_ativo", ambiente.jobsAtivos());
    s.put("jobs", jobs);
    return s;
  }
}
