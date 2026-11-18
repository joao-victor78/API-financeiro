package br.com.erp.financeiro.config;

import br.com.erp.financeiro.comum.auth.AuthUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentação em /api/financeiro/docs (Swagger UI) e /api/financeiro/docs-json (OpenAPI). */
@Configuration
public class ConfiguracaoOpenApi {

  static {
    // O usuário autenticado vem do token, não é parâmetro da requisição.
    SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthUser.class);
  }

  @Bean
  public OpenAPI documentacao() {
    return new OpenAPI()
        .info(new Info()
            .title("API do Módulo Financeiro")
            .version("0.1.0")
            .description("Contas a receber/pagar, cobranças, recorrências, inadimplência e conciliação bancária. "
                + "Autenticação JWT RS256 emitido pelo módulo de Autenticação; tenant extraído do token. "
                + "Respostas no formato { success, data, message, errors }."))
        .components(new Components().addSecuritySchemes("bearer",
            new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList("bearer"));
  }
}
