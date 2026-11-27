package br.com.erp.financeiro.contaspagar;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cadastros.CadastroService;
import br.com.erp.financeiro.cadastros.CategoriaFinanceira;
import br.com.erp.financeiro.cadastros.CentroCusto;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.contabilidade.PeriodosContabeis;
import br.com.erp.financeiro.contaspagar.ContaPagarDtos.ContaPagarEntrada;
import br.com.erp.financeiro.contaspagar.ContaPagarDtos.ContaPagarSaida;
import br.com.erp.financeiro.contaspagar.ContaPagarDtos.PagamentoContaEntrada;
import br.com.erp.financeiro.documentos.Documento;
import br.com.erp.financeiro.documentos.DocumentoService;
import br.com.erp.financeiro.seguranca.Usuarios;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contas a pagar (RF-02): lançar, aprovar (permissão distinta, RN-11) e registrar o pagamento (RN-12).
 * V2: categoria, centro de custo e conta bancária do cadastro, documento anexo e trava de período.
 */
@Service
public class ContaPagarService {

  static final String ENTIDADE = "conta_pagar";

  private final ContaPagarRepository contas;
  private final AuditoriaService auditoria;
  private final Relogio relogio;
  private final CadastroService cadastros;
  private final DocumentoService documentos;
  private final PeriodosContabeis periodos;

  public ContaPagarService(ContaPagarRepository contas, AuditoriaService auditoria, Relogio relogio, CadastroService cadastros,
      DocumentoService documentos, PeriodosContabeis periodos) {
    this.contas = contas;
    this.auditoria = auditoria;
    this.relogio = relogio;
    this.cadastros = cadastros;
    this.documentos = documentos;
    this.periodos = periodos;
  }

  public record Filtro(Boolean aprovado, ContaPagar.Status status, UUID categoriaId, UUID centroCustoId, LocalDate competencia) {}

  @Transactional(readOnly = true)
  public Pagina<ContaPagarSaida> listar(Boolean aprovado, ContaPagar.Status status, Pageable pagina) {
    return listar(new Filtro(aprovado, status, null, null, null), pagina);
  }

  @Transactional(readOnly = true)
  public Pagina<ContaPagarSaida> listar(Filtro f, Pageable pagina) {
    return Pagina.de(contas.findAll(especificacao(f), pagina), ContaPagarSaida::de);
  }

  public static Specification<ContaPagar> especificacao(Filtro f) {
    Specification<ContaPagar> s = (r, q, cb) -> cb.isNull(r.get("deletedAt"));
    if (f.aprovado() != null) {
      s = s.and((r, q, cb) -> f.aprovado()
          ? r.get("status").in(ContaPagar.Status.APROVADA, ContaPagar.Status.PAGA)
          : cb.equal(r.get("status"), ContaPagar.Status.PENDENTE));
    }
    if (f.status() != null) s = s.and((r, q, cb) -> cb.equal(r.get("status"), f.status()));
    if (f.categoriaId() != null) s = s.and((r, q, cb) -> cb.equal(r.get("categoriaId"), f.categoriaId()));
    if (f.centroCustoId() != null) s = s.and((r, q, cb) -> cb.equal(r.get("centroCustoId"), f.centroCustoId()));
    if (f.competencia() != null) s = s.and((r, q, cb) -> cb.equal(r.get("competencia"), Competencia.de(f.competencia())));
    return s;
  }

  @Transactional(readOnly = true)
  public ContaPagar obter(UUID id) {
    return Tenant.ativoDoTenant(contas.findById(id), "Conta a pagar");
  }

  @Transactional
  public ContaPagar lancar(ContaPagarEntrada e) {
    String competencia = Competencia.de(e.competencia());
    periodos.exigirAberto(competencia);
    CategoriaFinanceira categoria = cadastros.categoriaParaUso(e.categoriaId(), CategoriaFinanceira.Tipo.DESPESA, "categoriaId");
    CentroCusto centro = cadastros.centroParaUso(e.centroCustoId(), "centroCustoId");
    cadastros.contaParaUso(e.contaBancariaId(), "contaBancariaId");
    Documento doc = documentos.paraVincular(e.documentoId(), "documentoId");
    ContaPagar c = new ContaPagar(e.fornecedorNome().trim(), vazioNulo(e.fornecedorDocumento()),
        vazioNulo(e.categoria()), vazioNulo(e.centroCusto()), competencia, e.vencimento(), e.valor(),
        vazioNulo(e.documento()), vazioNulo(e.observacoes()));
    c.classificar(e.categoriaId(), categoria == null ? null : categoria.getNome(), e.centroCustoId(),
        centro == null ? null : centro.rotulo(), e.contaBancariaId(), e.documentoId());
    contas.save(c);
    if (doc != null) documentos.vincular(doc, ENTIDADE, c.getId());
    auditoria.registrar("CRIAR", ENTIDADE, c.getId(), null, c.instantaneo());
    return c;
  }

  @Transactional
  public ContaPagar aprovar(UUID id) {
    ContaPagar c = obter(id);
    if (c.getStatus() != ContaPagar.Status.PENDENTE) {
      throw ApiException.conflito("CONTA_NAO_PENDENTE", "Só conta pendente pode ser aprovada (está " + c.getStatus() + ").");
    }
    Map<String, Object> antes = c.instantaneo();
    c.aprovar(Usuarios.atual(), relogio.agoraUtc());
    auditoria.registrar("APROVAR", ENTIDADE, c.getId(), antes, c.instantaneo());
    return c;
  }

  /** RN-12: só depois de aprovada. 422 para conta não aprovada (EOR, RF-02). */
  @Transactional
  public ContaPagar registrarPagamento(UUID id, PagamentoContaEntrada e) {
    ContaPagar c = obter(id);
    if (c.getStatus() == ContaPagar.Status.PENDENTE) {
      throw ApiException.regra("CONTA_NAO_APROVADA", "A conta precisa ser aprovada antes do pagamento.");
    }
    if (c.getStatus() != ContaPagar.Status.APROVADA) {
      throw ApiException.conflito("CONTA_NAO_PAGAVEL", "Conta " + c.getStatus() + " não aceita pagamento.");
    }
    LocalDate data = e == null || e.dataPagamento() == null ? relogio.hoje() : e.dataPagamento();
    if (data.isAfter(relogio.hoje())) throw ApiException.campo("dataPagamento", "DATA_FUTURA", "A data do pagamento não pode ser futura.");
    periodos.exigirAberto(Competencia.de(data));
    if (e != null) cadastros.contaParaUso(e.contaBancariaId(), "contaBancariaId");
    Map<String, Object> antes = c.instantaneo();
    c.registrarPagamento(e == null || e.valorPago() == null ? c.getValor() : e.valorPago(), data);
    if (e != null) c.definirContaBancaria(e.contaBancariaId());
    auditoria.registrar("BAIXAR", ENTIDADE, c.getId(), antes, c.instantaneo());
    return c;
  }

  /** Anexa (ou troca) o arquivo do documento da conta: NF, boleto, recibo (§25, §96). */
  @Transactional
  public ContaPagar anexar(UUID id, UUID documentoId) {
    ContaPagar c = obter(id);
    Documento d = documentos.paraVincular(documentoId, "documentoId");
    Map<String, Object> antes = c.instantaneo();
    c.anexarDocumento(d.getId());
    documentos.vincular(d, ENTIDADE, c.getId());
    auditoria.registrar("ANEXAR_DOCUMENTO", ENTIDADE, c.getId(), antes, c.instantaneo());
    return c;
  }

  private static String vazioNulo(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
