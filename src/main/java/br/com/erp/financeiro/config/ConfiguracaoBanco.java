package br.com.erp.financeiro.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Monta o pool de conexões a partir da DATABASE_URL no formato postgres://usuario:senha@host:porta/banco
 * (o mesmo formato usado pela maioria das plataformas e pelo docker-compose do projeto).
 * O módulo usa o schema próprio "financeiro" dentro desse banco.
 */
@Configuration
public class ConfiguracaoBanco {

  /** Converte postgres://u:s@h:p/db em jdbc:postgresql://h:p/db, usuário e senha. */
  public record Conexao(String jdbcUrl, String usuario, String senha) {

    public static Conexao de(String url) {
      URI u = URI.create(url.replaceFirst("^postgres(ql)?://", "postgresql://"));
      if (!"postgresql".equals(u.getScheme())) {
        throw new IllegalArgumentException("DATABASE_URL deve começar com postgres:// (recebido: " + u.getScheme() + ")");
      }
      String usuario = null;
      String senha = null;
      if (u.getRawUserInfo() != null) {
        String[] partes = u.getRawUserInfo().split(":", 2);
        usuario = URLDecoder.decode(partes[0], StandardCharsets.UTF_8);
        if (partes.length > 1) senha = URLDecoder.decode(partes[1], StandardCharsets.UTF_8);
      }
      int porta = u.getPort() > 0 ? u.getPort() : 5432;
      String banco = u.getPath() == null || u.getPath().length() <= 1 ? "postgres" : u.getPath().substring(1);
      // stringtype=unspecified: textos enviados sem tipo, para o Postgres converter nos ENUMs do schema.
      String jdbc = "jdbc:postgresql://" + u.getHost() + ":" + porta + "/" + banco + "?stringtype=unspecified";
      return new Conexao(jdbc, usuario, senha);
    }
  }

  @Bean
  public DataSource dataSource(Ambiente ambiente) {
    Conexao c = Conexao.de(ambiente.databaseUrl());
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(c.jdbcUrl());
    ds.setUsername(c.usuario());
    ds.setPassword(c.senha());
    ds.setPoolName("financeiro");
    ds.setMaximumPoolSize(10);
    return ds;
  }
}
