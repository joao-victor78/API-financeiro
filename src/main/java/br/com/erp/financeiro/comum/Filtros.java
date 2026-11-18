package br.com.erp.financeiro.comum;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Montagem de filtros opcionais sempre restritos ao tenant: um critério nulo simplesmente não restringe.
 * Ex.: {@code Filtros.<ContaReceber>doTenant(t).igual("status", s).aPartirDe("dataVencimento", d).spec()}
 */
public final class Filtros<T> {

  private Specification<T> spec;

  private Filtros(UUID tenant) {
    this.spec = (r, q, cb) -> cb.equal(r.get("tenantId"), tenant);
  }

  public static <T> Filtros<T> doTenant(UUID tenant) {
    return new Filtros<>(tenant);
  }

  public Filtros<T> igual(String campo, Object valor) {
    if (valor != null) spec = spec.and((r, q, cb) -> cb.equal(r.get(campo), valor));
    return this;
  }

  public Filtros<T> aPartirDe(String campo, LocalDate data) {
    if (data != null) spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get(campo), data));
    return this;
  }

  public Filtros<T> ate(String campo, LocalDate data) {
    if (data != null) spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get(campo), data));
    return this;
  }

  public Specification<T> spec() {
    return spec;
  }
}
