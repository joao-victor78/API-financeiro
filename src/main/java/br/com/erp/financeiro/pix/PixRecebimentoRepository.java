package br.com.erp.financeiro.pix;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PixRecebimentoRepository extends JpaRepository<PixRecebimento, UUID> {

  boolean existsByEndToEndId(String endToEndId);
}
