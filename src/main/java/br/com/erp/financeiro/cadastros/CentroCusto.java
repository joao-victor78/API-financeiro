package br.com.erp.financeiro.cadastros;

import br.com.erp.financeiro.comum.EntidadeBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;

/** Centro de custo (§25, §34): agrupa receitas e despesas por área, unidade ou projeto. */
@Entity
@Table(name = "centros_custo")
public class CentroCusto extends EntidadeBase {

  @Column(nullable = false)
  private String codigo;

  @Column(nullable = false)
  private String nome;

  @Column(nullable = false)
  private boolean ativo = true;

  protected CentroCusto() {}

  public CentroCusto(String codigo, String nome) {
    configurar(codigo, nome, true);
  }

  public void configurar(String codigo, String nome, boolean ativo) {
    this.codigo = codigo;
    this.nome = nome;
    this.ativo = ativo;
  }

  public Map<String, Object> instantaneo() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("codigo", codigo);
    m.put("nome", nome);
    m.put("ativo", ativo);
    return m;
  }

  /** "ADM — Administrativo", o texto que vai para o campo livre centro_custo das contas. */
  public String rotulo() {
    return codigo + " — " + nome;
  }

  public String getCodigo() {
    return codigo;
  }

  public String getNome() {
    return nome;
  }

  public boolean isAtivo() {
    return ativo;
  }
}
