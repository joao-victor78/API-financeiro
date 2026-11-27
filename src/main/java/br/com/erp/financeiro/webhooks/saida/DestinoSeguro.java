package br.com.erp.financeiro.webhooks.saida;

import br.com.erp.financeiro.api.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validação da URL de destino contra SSRF (§84): só HTTPS (HTTP só se liberado por configuração, para
 * desenvolvimento) e nunca endereço de loopback, rede privada, link-local ou metadados de nuvem — a não
 * ser que a rede interna seja liberada explicitamente. Conferida no cadastro e em cada entrega.
 */
@Component
public class DestinoSeguro {

  private final boolean permitirHttp;
  private final boolean permitirRedeInterna;

  public DestinoSeguro(@Value("${financeiro.webhooks-saida.permitir-http:false}") boolean permitirHttp,
      @Value("${financeiro.webhooks-saida.permitir-rede-interna:false}") boolean permitirRedeInterna) {
    this.permitirHttp = permitirHttp;
    this.permitirRedeInterna = permitirRedeInterna;
  }

  public URI validar(String url) {
    URI u;
    try {
      u = URI.create(url.trim());
    } catch (IllegalArgumentException e) {
      throw ApiException.campo("url", "URL_INVALIDA", "URL inválida.");
    }
    String esquema = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!esquema.equals("https") && !(permitirHttp && esquema.equals("http"))) {
      throw ApiException.campo("url", "URL_INSEGURA", "Use uma URL https://.");
    }
    if (u.getHost() == null || u.getUserInfo() != null) throw ApiException.campo("url", "URL_INVALIDA", "URL sem host ou com credenciais.");
    if (!permitirRedeInterna) {
      try {
        for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
          if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
              || a.isMulticastAddress() || privadoIpv6(a) || a.getHostAddress().startsWith("169.254.")) {
            throw ApiException.campo("url", "URL_REDE_INTERNA", "O destino aponta para a rede interna; use um endereço público.");
          }
        }
      } catch (UnknownHostException e) {
        throw ApiException.campo("url", "HOST_DESCONHECIDO", "Não foi possível resolver o endereço " + u.getHost() + ".");
      }
    }
    return u;
  }

  private static boolean privadoIpv6(InetAddress a) {
    byte[] b = a.getAddress();
    return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
  }
}
