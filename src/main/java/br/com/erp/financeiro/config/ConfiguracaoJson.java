package br.com.erp.financeiro.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Colunas jsonb (itens da cobrança, payload dos eventos) usam o mesmo ObjectMapper da API:
 * chaves em snake_case e datas ISO, iguais ao que circula no JSON das requisições.
 */
@Configuration
public class ConfiguracaoJson {

  @Bean
  public HibernatePropertiesCustomizer jsonDoHibernate(ObjectMapper objectMapper) {
    return props -> props.put(AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(objectMapper));
  }
}
