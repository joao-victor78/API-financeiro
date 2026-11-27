package br.com.erp.financeiro.webhooks.saida;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WebhookAssinaturaRepository extends JpaRepository<WebhookAssinatura, UUID> {

  @Query("select w from WebhookAssinatura w where w.deletedAt is null order by w.createdAt desc")
  List<WebhookAssinatura> todas();
}
