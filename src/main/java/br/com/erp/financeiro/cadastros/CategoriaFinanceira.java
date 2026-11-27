package br.com.erp.financeiro.cadastros;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Categoria de receita ou despesa (§25, §34). O grupo do DRE decide em que linha do DRE gerencial o
 * lançamento entra (§23); a conta contábil é o código do plano de contas do escritório (§37).
 */
@Entity
@Table(name = "categorias_financeiras")
public class CategoriaFinanceira extends EntidadeBase {

  public enum Tipo { RECEITA, DESPESA }

  /** Linhas do DRE gerencial, na ordem em que aparecem. */
  public enum GrupoDre {
    RECEITA_BRUTA, DEDUCOES, CUSTOS, DESPESAS_OPERACIONAIS, DESPESAS_ADMINISTRATIVAS, DESPESAS_COMERCIAIS,
    RECEITAS_FINANCEIRAS, DESPESAS_FINANCEIRAS, IMPOSTOS_SOBRE_LUCRO, OUTRAS
  }

  @Column(nullable = false)
  private String nome;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Tipo tipo;

  @Enumerated(EnumType.STRING)
  @Column(name = "grupo_dre", nullable = false)
  private GrupoDre grupoDre;

  @Column(name = "conta_contabil")
  private String contaContabil;

  @Column(nullable = false)
  private boolean ativa = true;

  protected CategoriaFinanceira() {}

  public CategoriaFinanceira(String nome, Tipo tipo, GrupoDre grupoDre, String contaContabil) {
    configurar(nome, tipo, grupoDre, contaContabil, true);
  }

  public void configurar(String nome, Tipo tipo, GrupoDre grupoDre, String contaContabil, boolean ativa) {
    this.nome = nome;
    this.tipo = tipo;
    this.grupoDre = grupoDre;
    this.contaContabil = contaContabil;
    this.ativa = ativa;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nome", nome);
    m.put("tipo", tipo);
    m.put("grupoDre", grupoDre);
    m.put("contaContabil", contaContabil);
    m.put("ativa", ativa);
    return m;
  }

  public String getNome() {
    return nome;
  }

  public Tipo getTipo() {
    return tipo;
  }

  public GrupoDre getGrupoDre() {
    return grupoDre;
  }

  public String getContaContabil() {
    return contaContabil;
  }

  public boolean isAtiva() {
    return ativa;
  }
}
