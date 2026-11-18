package br.com.erp.financeiro.recorrencias;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecorrenciaExecucaoRepository extends JpaRepository<RecorrenciaExecucao, UUID> {

  List<RecorrenciaExecucao> findTop24ByRecorrenciaIdAndTenantIdOrderByCompetenciaDesc(UUID recorrenciaId, UUID tenantId);
}
