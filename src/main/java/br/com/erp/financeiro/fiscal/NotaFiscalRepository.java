package br.com.erp.financeiro.fiscal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface NotaFiscalRepository extends JpaRepository<NotaFiscal, UUID>, JpaSpecificationExecutor<NotaFiscal> {

  @Query("""
      select count(n) from NotaFiscal n where n.deletedAt is null and n.tipo = :tipo and n.modelo = :modelo
         and coalesce(n.serie, '') = :serie and n.numero = :numero and coalesce(n.participanteDocumento, '') = :documento""")
  long duplicadas(NotaFiscal.Tipo tipo, NotaFiscal.Modelo modelo, String serie, String numero, String documento);

  @Query("select n from NotaFiscal n where n.deletedAt is null and n.cobrancaId = :cobranca order by n.dataEmissao desc")
  List<NotaFiscal> daCobranca(UUID cobranca);

  /** Busca global (RF-09, §79): número, chave ou participante. */
  @Query("""
      select n from NotaFiscal n where n.deletedAt is null
         and (lower(n.numero) like :termo escape '\\' or lower(coalesce(n.chaveAcesso, '')) like :termo escape '\\'
              or lower(coalesce(n.participanteNome, '')) like :termo escape '\\')
       order by n.dataEmissao desc""")
  List<NotaFiscal> buscar(String termo, org.springframework.data.domain.Pageable limite);
}
