package br.com.erp.financeiro.cadastros;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cadastros.CadastroDtos.CategoriaEntrada;
import br.com.erp.financeiro.cadastros.CadastroDtos.CentroCustoEntrada;
import br.com.erp.financeiro.cadastros.CadastroDtos.ContaBancariaEntrada;
import br.com.erp.financeiro.cadastros.CategoriaFinanceira.GrupoDre;
import br.com.erp.financeiro.cadastros.CategoriaFinanceira.Tipo;
import br.com.erp.financeiro.comum.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastros que o PD-5 deixava em aberto (§25): categorias com grupo do DRE, centros de custo e contas
 * bancárias. Os campos de texto livre antigos (categoria, centro_custo) continuam existindo: ao vincular
 * um cadastro, o texto é preenchido com o nome dele, para as telas e exportações antigas não mudarem.
 */
@Service
public class CadastroService {

  private final CategoriaRepository categorias;
  private final CentroCustoRepository centros;
  private final ContaBancariaRepository contas;
  private final AuditoriaService auditoria;
  private final JdbcTemplate jdbc;

  public CadastroService(CategoriaRepository categorias, CentroCustoRepository centros, ContaBancariaRepository contas,
      AuditoriaService auditoria, JdbcTemplate jdbc) {
    this.categorias = categorias;
    this.centros = centros;
    this.contas = contas;
    this.auditoria = auditoria;
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------ categorias

  @Transactional(readOnly = true)
  public List<CategoriaFinanceira> categorias() {
    return categorias.ordenadas();
  }

  @Transactional(readOnly = true)
  public CategoriaFinanceira categoria(UUID id) {
    return Tenant.ativoDoTenant(categorias.findById(id), "Categoria");
  }

  /** Para quem vincula: a categoria precisa existir, estar ativa e ser do tipo esperado. */
  @Transactional(readOnly = true)
  public CategoriaFinanceira categoriaParaUso(UUID id, Tipo esperado, String campo) {
    if (id == null) return null;
    CategoriaFinanceira c = categorias.findById(id).filter(x -> x.getDeletedAt() == null)
        .filter(x -> x.getTenantId().equals(br.com.erp.financeiro.tenant.TenantContexto.exigir()))
        .orElseThrow(() -> ApiException.campo(campo, "CATEGORIA_INEXISTENTE", "Categoria não encontrada."));
    if (!c.isAtiva()) throw ApiException.campo(campo, "CATEGORIA_INATIVA", "A categoria " + c.getNome() + " está inativa.");
    if (esperado != null && c.getTipo() != esperado) {
      throw ApiException.campo(campo, "CATEGORIA_TIPO", "Use uma categoria de " + esperado.name().toLowerCase() + ".");
    }
    return c;
  }

  @Transactional
  public CategoriaFinanceira criarCategoria(CategoriaEntrada e) {
    String nome = e.nome().trim();
    categorias.porNome(e.tipo(), nome).ifPresent(x -> {
      throw ApiException.conflito("CATEGORIA_DUPLICADA", "Já existe a categoria de " + e.tipo().name().toLowerCase() + " \"" + nome + "\".");
    });
    validarGrupo(e.tipo(), e.grupoDre());
    CategoriaFinanceira c = categorias.save(new CategoriaFinanceira(nome, e.tipo(), e.grupoDre(), vazioNulo(e.contaContabil())));
    auditoria.registrar("CRIAR", "categoria", c.getId(), null, c.instantaneo());
    return c;
  }

  @Transactional
  public CategoriaFinanceira editarCategoria(UUID id, CategoriaEntrada e) {
    CategoriaFinanceira c = categoria(id);
    String nome = e.nome().trim();
    categorias.porNome(e.tipo(), nome).filter(x -> !x.getId().equals(id)).ifPresent(x -> {
      throw ApiException.conflito("CATEGORIA_DUPLICADA", "Já existe a categoria \"" + nome + "\".");
    });
    validarGrupo(e.tipo(), e.grupoDre());
    Map<String, Object> antes = c.instantaneo();
    c.configurar(nome, e.tipo(), e.grupoDre(), vazioNulo(e.contaContabil()), e.ativa() == null || e.ativa());
    auditoria.registrar("EDITAR", "categoria", c.getId(), antes, c.instantaneo());
    return c;
  }

  /** Exclusão lógica; categoria em uso só pode ser inativada (o histórico do DRE não muda). */
  @Transactional
  public void excluirCategoria(UUID id) {
    CategoriaFinanceira c = categoria(id);
    Integer uso = jdbc.queryForObject("""
        SELECT (SELECT count(*) FROM financeiro.cobrancas WHERE categoria_id = ?)
             + (SELECT count(*) FROM financeiro.contas_pagar WHERE categoria_id = ?)
             + (SELECT count(*) FROM financeiro.transacoes_bancarias WHERE categoria_id = ?)""", Integer.class, id, id, id);
    if (uso != null && uso > 0) {
      throw ApiException.conflito("CATEGORIA_EM_USO", "A categoria tem " + uso + " lançamento(s); inative-a em vez de excluir.");
    }
    Map<String, Object> antes = c.instantaneo();
    c.excluir();
    auditoria.registrar("EXCLUIR", "categoria", c.getId(), antes, null);
  }

  /** Plano inicial para empresa de serviços recorrentes (MSP), só quando o tenant ainda não tem categorias. */
  @Transactional
  public List<CategoriaFinanceira> criarPlanoPadrao() {
    if (!categorias.ordenadas().isEmpty()) {
      throw ApiException.conflito("PLANO_EXISTENTE", "O tenant já tem categorias; o plano padrão só é criado em cadastro vazio.");
    }
    List<CategoriaFinanceira> novas = new ArrayList<>();
    Object[][] plano = {
        {"Mensalidades de serviços gerenciados", Tipo.RECEITA, GrupoDre.RECEITA_BRUTA},
        {"Licenças e assinaturas", Tipo.RECEITA, GrupoDre.RECEITA_BRUTA},
        {"Implantação e projetos", Tipo.RECEITA, GrupoDre.RECEITA_BRUTA},
        {"Consumo excedente", Tipo.RECEITA, GrupoDre.RECEITA_BRUTA},
        {"Juros e multas recebidos", Tipo.RECEITA, GrupoDre.RECEITAS_FINANCEIRAS},
        {"Rendimentos de aplicação", Tipo.RECEITA, GrupoDre.RECEITAS_FINANCEIRAS},
        {"Impostos sobre faturamento (ISS, PIS, COFINS)", Tipo.DESPESA, GrupoDre.DEDUCOES},
        {"Licenças de fabricantes e distribuidores", Tipo.DESPESA, GrupoDre.CUSTOS},
        {"Cloud, backup e armazenamento", Tipo.DESPESA, GrupoDre.CUSTOS},
        {"Terceiros técnicos", Tipo.DESPESA, GrupoDre.CUSTOS},
        {"Salários e encargos", Tipo.DESPESA, GrupoDre.DESPESAS_OPERACIONAIS},
        {"Aluguel e condomínio", Tipo.DESPESA, GrupoDre.DESPESAS_ADMINISTRATIVAS},
        {"Softwares internos", Tipo.DESPESA, GrupoDre.DESPESAS_ADMINISTRATIVAS},
        {"Contabilidade e jurídico", Tipo.DESPESA, GrupoDre.DESPESAS_ADMINISTRATIVAS},
        {"Marketing e anúncios", Tipo.DESPESA, GrupoDre.DESPESAS_COMERCIAIS},
        {"Comissões", Tipo.DESPESA, GrupoDre.DESPESAS_COMERCIAIS},
        {"Tarifas bancárias", Tipo.DESPESA, GrupoDre.DESPESAS_FINANCEIRAS},
        {"Juros pagos", Tipo.DESPESA, GrupoDre.DESPESAS_FINANCEIRAS},
        {"IRPJ e CSLL", Tipo.DESPESA, GrupoDre.IMPOSTOS_SOBRE_LUCRO},
    };
    for (Object[] linha : plano) {
      novas.add(categorias.save(new CategoriaFinanceira((String) linha[0], (Tipo) linha[1], (GrupoDre) linha[2], null)));
    }
    auditoria.registrar("CRIAR_PLANO_PADRAO", "categoria", br.com.erp.financeiro.tenant.TenantContexto.exigir(), null,
        Map.of("categorias", novas.size()));
    return novas;
  }

  /** Receita só em grupos de receita; despesa só em grupos de despesa (OUTRAS vale para as duas). */
  private static void validarGrupo(Tipo tipo, GrupoDre grupo) {
    boolean receita = grupo == GrupoDre.RECEITA_BRUTA || grupo == GrupoDre.RECEITAS_FINANCEIRAS;
    if (grupo == GrupoDre.OUTRAS) return;
    if (tipo == Tipo.RECEITA && !receita) {
      throw ApiException.campo("grupoDre", "GRUPO_INCOMPATIVEL", "Categoria de receita vai em Receita bruta, Receitas financeiras ou Outras.");
    }
    if (tipo == Tipo.DESPESA && receita) {
      throw ApiException.campo("grupoDre", "GRUPO_INCOMPATIVEL", "Categoria de despesa não vai em grupo de receita.");
    }
  }

  // ------------------------------------------------------------------ centros de custo

  @Transactional(readOnly = true)
  public List<CentroCusto> centros() {
    return centros.ordenados();
  }

  @Transactional(readOnly = true)
  public CentroCusto centro(UUID id) {
    return Tenant.ativoDoTenant(centros.findById(id), "Centro de custo");
  }

  @Transactional(readOnly = true)
  public CentroCusto centroParaUso(UUID id, String campo) {
    if (id == null) return null;
    CentroCusto c = centros.findById(id).filter(x -> x.getDeletedAt() == null)
        .filter(x -> x.getTenantId().equals(br.com.erp.financeiro.tenant.TenantContexto.exigir()))
        .orElseThrow(() -> ApiException.campo(campo, "CENTRO_INEXISTENTE", "Centro de custo não encontrado."));
    if (!c.isAtivo()) throw ApiException.campo(campo, "CENTRO_INATIVO", "O centro de custo " + c.getCodigo() + " está inativo.");
    return c;
  }

  @Transactional
  public CentroCusto criarCentro(CentroCustoEntrada e) {
    String codigo = e.codigo().trim();
    centros.porCodigo(codigo).ifPresent(x -> {
      throw ApiException.conflito("CENTRO_DUPLICADO", "Já existe o centro de custo " + codigo + ".");
    });
    CentroCusto c = centros.save(new CentroCusto(codigo, e.nome().trim()));
    auditoria.registrar("CRIAR", "centro_custo", c.getId(), null, c.instantaneo());
    return c;
  }

  @Transactional
  public CentroCusto editarCentro(UUID id, CentroCustoEntrada e) {
    CentroCusto c = centro(id);
    String codigo = e.codigo().trim();
    centros.porCodigo(codigo).filter(x -> !x.getId().equals(id)).ifPresent(x -> {
      throw ApiException.conflito("CENTRO_DUPLICADO", "Já existe o centro de custo " + codigo + ".");
    });
    Map<String, Object> antes = c.instantaneo();
    c.configurar(codigo, e.nome().trim(), e.ativo() == null || e.ativo());
    auditoria.registrar("EDITAR", "centro_custo", c.getId(), antes, c.instantaneo());
    return c;
  }

  @Transactional
  public void excluirCentro(UUID id) {
    CentroCusto c = centro(id);
    Integer uso = jdbc.queryForObject("""
        SELECT (SELECT count(*) FROM financeiro.cobrancas WHERE centro_custo_id = ?)
             + (SELECT count(*) FROM financeiro.contas_pagar WHERE centro_custo_id = ?)""", Integer.class, id, id);
    if (uso != null && uso > 0) {
      throw ApiException.conflito("CENTRO_EM_USO", "O centro de custo tem " + uso + " lançamento(s); inative-o em vez de excluir.");
    }
    Map<String, Object> antes = c.instantaneo();
    c.excluir();
    auditoria.registrar("EXCLUIR", "centro_custo", c.getId(), antes, null);
  }

  // ------------------------------------------------------------------ contas bancárias

  @Transactional(readOnly = true)
  public List<ContaBancaria> contas() {
    return contas.ordenadas();
  }

  @Transactional(readOnly = true)
  public ContaBancaria conta(UUID id) {
    return Tenant.ativoDoTenant(contas.findById(id), "Conta bancária");
  }

  @Transactional(readOnly = true)
  public ContaBancaria contaParaUso(UUID id, String campo) {
    if (id == null) return null;
    ContaBancaria c = contas.findById(id).filter(x -> x.getDeletedAt() == null)
        .filter(x -> x.getTenantId().equals(br.com.erp.financeiro.tenant.TenantContexto.exigir()))
        .orElseThrow(() -> ApiException.campo(campo, "CONTA_INEXISTENTE", "Conta bancária não encontrada."));
    if (!c.isAtiva()) throw ApiException.campo(campo, "CONTA_INATIVA", "A conta " + c.getNome() + " está inativa.");
    return c;
  }

  /** Conta que recebe os pagamentos do provedor, quando há uma marcada como padrão. */
  @Transactional(readOnly = true)
  public UUID contaPadraoDeRecebimento() {
    return contas.padraoDeRecebimento().stream().findFirst().map(ContaBancaria::getId).orElse(null);
  }

  @Transactional
  public ContaBancaria criarConta(ContaBancariaEntrada e) {
    ContaBancaria c = new ContaBancaria(e.nome().trim(), e.tipo(), e.dataSaldoInicial());
    aplicar(c, e);
    contas.save(c);
    auditoria.registrar("CRIAR", "conta_bancaria", c.getId(), null, c.instantaneo());
    return c;
  }

  @Transactional
  public ContaBancaria editarConta(UUID id, ContaBancariaEntrada e) {
    ContaBancaria c = conta(id);
    Map<String, Object> antes = c.instantaneo();
    aplicar(c, e);
    auditoria.registrar("EDITAR", "conta_bancaria", c.getId(), antes, c.instantaneo());
    return c;
  }

  @Transactional
  public void excluirConta(UUID id) {
    ContaBancaria c = conta(id);
    Integer uso = jdbc.queryForObject("SELECT count(*) FROM financeiro.transacoes_bancarias WHERE conta_bancaria_id = ?",
        Integer.class, id);
    if (uso != null && uso > 0) {
      throw ApiException.conflito("CONTA_EM_USO", "A conta tem " + uso + " transação(ões) importada(s); inative-a em vez de excluir.");
    }
    Map<String, Object> antes = c.instantaneo();
    c.excluir();
    auditoria.registrar("EXCLUIR", "conta_bancaria", c.getId(), antes, null);
  }

  /** Só uma conta é a padrão de recebimento: marcar uma desmarca as outras. */
  private void aplicar(ContaBancaria c, ContaBancariaEntrada e) {
    boolean padrao = Boolean.TRUE.equals(e.padraoRecebimento());
    if (padrao) contas.padraoDeRecebimento().stream().filter(x -> !x.getId().equals(c.getId())).forEach(ContaBancaria::deixarDeSerPadrao);
    c.configurar(e.nome().trim(), vazioNulo(e.bancoCodigo()), vazioNulo(e.agencia()), vazioNulo(e.numero()), e.tipo(),
        e.saldoInicial(), e.dataSaldoInicial(), vazioNulo(e.contaContabil()), padrao, e.ativa() == null || e.ativa());
  }

  static String vazioNulo(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
