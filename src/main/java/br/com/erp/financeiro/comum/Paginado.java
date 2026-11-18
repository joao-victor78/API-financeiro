package br.com.erp.financeiro.comum;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Lista paginada: { items, meta: { page, limit, total, total_pages } }. Limite máximo de 100 por página. */
public record Paginado<T>(List<T> items, Meta meta) {

  public static final int LIMITE_MAXIMO = 100;
  public static final String MSG_LIMITE = "limit deve ser no máximo " + LIMITE_MAXIMO;

  public record Meta(int page, int limit, long total, long totalPages) {}

  /** Parâmetros page/limit comuns a todos os filtros (padrão: página 1, 20 itens). */
  public interface Paginacao {
    Integer page();

    Integer limit();

    default int pagina() {
      return page() == null ? 1 : page();
    }

    default int limite() {
      return limit() == null ? 20 : limit();
    }

    default Pageable pageable(Sort ordem) {
      return PageRequest.of(pagina() - 1, limite(), ordem);
    }
  }

  public static <E, T> Paginado<T> de(Page<E> pagina, Paginacao p, Function<E, T> mapa) {
    return new Paginado<>(pagina.getContent().stream().map(mapa).toList(),
        new Meta(p.pagina(), p.limite(), pagina.getTotalElements(), (pagina.getTotalElements() + p.limite() - 1) / p.limite()));
  }

  public static <E> Paginado<E> de(Page<E> pagina, Paginacao p) {
    return de(pagina, p, Function.identity());
  }

  public static <T> Paginado<T> de(List<T> itens, long total, Paginacao p) {
    return new Paginado<>(itens, new Meta(p.pagina(), p.limite(), total, (total + p.limite() - 1) / p.limite()));
  }
}
