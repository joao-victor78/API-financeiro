package br.com.erp.financeiro.comum.enums;

import jakarta.persistence.AttributeConverter;
import java.util.Arrays;

/**
 * Enum com o valor textual usado no banco (ENUM do PostgreSQL) e no JSON, ex.: EM_ABERTO ↔ "em_aberto".
 */
public interface ValorEnum {

  String valor();

  static <E extends Enum<E> & ValorEnum> E de(Class<E> tipo, String valor) {
    return Arrays.stream(tipo.getEnumConstants())
        .filter(e -> e.valor().equals(valor))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "valor \"" + valor + "\" inválido; use um de: " + String.join(", ", valores(tipo))));
  }

  static <E extends Enum<E> & ValorEnum> String[] valores(Class<E> tipo) {
    return Arrays.stream(tipo.getEnumConstants()).map(ValorEnum::valor).toArray(String[]::new);
  }

  /** Conversor JPA genérico: cada enum declara uma subclasse com @Converter(autoApply = true). */
  abstract class Conversor<E extends Enum<E> & ValorEnum> implements AttributeConverter<E, String> {
    private final Class<E> tipo;

    protected Conversor(Class<E> tipo) {
      this.tipo = tipo;
    }

    @Override
    public String convertToDatabaseColumn(E e) {
      return e == null ? null : e.valor();
    }

    @Override
    public E convertToEntityAttribute(String v) {
      return v == null ? null : de(tipo, v);
    }
  }
}
