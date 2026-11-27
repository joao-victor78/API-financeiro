package br.com.erp.financeiro.webhooks.saida;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Destino externo que recebe os eventos do Financeiro (§91). O segredo fica cifrado. */
@Entity
@Table(name = "webhook_assinaturas")
public class WebhookAssinatura extends EntidadeBase {

  @Column(nullable = false)
  private String descricao;

  @Column(nullable = false)
  private String url;

  /** Lista separada por vírgula, ou "*" para todos os eventos. */
  @Column(nullable = false)
  private String eventos;

  @Column(name = "segredo_cifrado", nullable = false)
  private String segredoCifrado;

  @Column(nullable = false)
  private boolean ativa = true;

  protected WebhookAssinatura() {}

  public WebhookAssinatura(String descricao, String url, String eventos, String segredoCifrado) {
    this.descricao = descricao;
    this.url = url;
    this.eventos = eventos;
    this.segredoCifrado = segredoCifrado;
  }

  public void configurar(String descricao, String url, String eventos, boolean ativa) {
    this.descricao = descricao;
    this.url = url;
    this.eventos = eventos;
    this.ativa = ativa;
  }

  public void trocarSegredo(String segredoCifrado) {
    this.segredoCifrado = segredoCifrado;
  }

  public List<String> listaDeEventos() {
    return Arrays.stream(eventos.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("descricao", descricao);
    m.put("url", url);
    m.put("eventos", eventos);
    m.put("ativa", ativa);
    return m;
  }

  public String getDescricao() {
    return descricao;
  }

  public String getUrl() {
    return url;
  }

  public String getEventos() {
    return eventos;
  }

  public String getSegredoCifrado() {
    return segredoCifrado;
  }

  public boolean isAtiva() {
    return ativa;
  }
}
