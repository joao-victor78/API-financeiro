package br.com.erp.financeiro.fiscal;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaService;
import br.com.erp.financeiro.comum.Competencia;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.contabilidade.PeriodosContabeis;
import br.com.erp.financeiro.contaspagar.ContaPagar;
import br.com.erp.financeiro.contaspagar.ContaPagarService;
import br.com.erp.financeiro.documentos.Documento;
import br.com.erp.financeiro.documentos.DocumentoService;
import br.com.erp.financeiro.eventos.PedidosPlataforma;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notas fiscais (§23 faturamento, §34, §35). Emitida: ligada à empresa/cobrança faturada. Recebida:
 * ligada ao fornecedor/conta a pagar. Número repetido (mesmo tipo, modelo, série e participante) é 409.
 */
@Service
public class NotaFiscalService {

  private final NotaFiscalRepository notas;
  private final CobrancaService cobrancas;
  private final ContaPagarService contasPagar;
  private final DocumentoService documentos;
  private final PeriodosContabeis periodos;
  private final AuditoriaService auditoria;
  private final PedidosPlataforma plataforma;

  public NotaFiscalService(NotaFiscalRepository notas, CobrancaService cobrancas, ContaPagarService contasPagar,
      DocumentoService documentos, PeriodosContabeis periodos, AuditoriaService auditoria, PedidosPlataforma plataforma) {
    this.notas = notas;
    this.cobrancas = cobrancas;
    this.contasPagar = contasPagar;
    this.documentos = documentos;
    this.periodos = periodos;
    this.auditoria = auditoria;
    this.plataforma = plataforma;
  }

  public record NotaEntrada(
      @NotNull(message = "obrigatório") NotaFiscal.Tipo tipo,
      NotaFiscal.Modelo modelo,
      @NotBlank(message = "obrigatório") @Size(max = 20, message = "até 20 caracteres") String numero,
      @Size(max = 5, message = "até 5 caracteres") String serie,
      @Pattern(regexp = "^\\d{44}$|^$", message = "a chave de acesso tem 44 dígitos") String chaveAcesso,
      @NotNull(message = "obrigatória") LocalDate dataEmissao,
      LocalDate competencia,
      @NotNull(message = "obrigatório") @Positive(message = "deve ser maior que zero")
      @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais") BigDecimal valor,
      @PositiveOrZero(message = "não pode ser negativo") @Digits(integer = 13, fraction = 2, message = "no máximo 2 casas decimais")
      BigDecimal valorImpostos,
      UUID empresaId,
      @Size(max = 150, message = "até 150 caracteres") String participanteNome,
      @Size(max = 18, message = "até 18 caracteres") String participanteDocumento,
      UUID cobrancaId,
      UUID contaPagarId,
      UUID documentoId,
      @Size(max = 300, message = "até 300 caracteres") String observacao) {}

  public record NotaSaida(UUID id, NotaFiscal.Tipo tipo, NotaFiscal.Modelo modelo, String numero, String serie, String chaveAcesso,
      LocalDate dataEmissao, LocalDate competencia, BigDecimal valor, BigDecimal valorImpostos, UUID empresaId,
      String participanteNome, String participanteDocumento, UUID cobrancaId, UUID contaPagarId, UUID documentoId,
      NotaFiscal.Status status, String observacao, OffsetDateTime createdAt) {
    public static NotaSaida de(NotaFiscal n) {
      return new NotaSaida(n.getId(), n.getTipo(), n.getModelo(), n.getNumero(), n.getSerie(), n.getChaveAcesso(), n.getDataEmissao(),
          Competencia.primeiroDia(n.getCompetencia()), n.getValor(), n.getValorImpostos(), n.getEmpresaId(), n.getParticipanteNome(),
          n.getParticipanteDocumento(), n.getCobrancaId(), n.getContaPagarId(), n.getDocumentoId(), n.getStatus(), n.getObservacao(),
          n.getCreatedAt());
    }
  }

  @Transactional
  public NotaFiscal registrar(NotaEntrada e) {
    NotaFiscal.Modelo modelo = e.modelo() == null ? NotaFiscal.Modelo.NFSE : e.modelo();
    UUID empresa = e.empresaId();
    String nome = vazio(e.participanteNome());
    String documentoParticipante = vazio(e.participanteDocumento());
    String competencia = Competencia.de(e.competencia() != null ? e.competencia() : e.dataEmissao());
    if (e.tipo() == NotaFiscal.Tipo.EMITIDA) {
      if (e.contaPagarId() != null) throw ApiException.campo("contaPagarId", "TIPO_INCOMPATIVEL", "Nota emitida liga a cobrança, não a conta a pagar.");
      if (e.cobrancaId() != null) {
        Cobranca c = cobrancas.obter(e.cobrancaId());
        if (empresa == null) empresa = c.getEmpresaId();
        else if (!empresa.equals(c.getEmpresaId())) throw ApiException.campo("empresaId", "EMPRESA_DIFERENTE", "A cobrança é de outra empresa.");
        if (e.competencia() == null) competencia = c.getCompetencia();
      }
      if (empresa == null) throw ApiException.campo("empresaId", "OBRIGATORIA", "Nota emitida precisa da empresa (ou da cobrança).");
    } else {
      if (e.cobrancaId() != null) throw ApiException.campo("cobrancaId", "TIPO_INCOMPATIVEL", "Nota recebida liga a conta a pagar, não a cobrança.");
      if (e.contaPagarId() != null) {
        ContaPagar cp = contasPagar.obter(e.contaPagarId());
        if (nome == null) nome = cp.getFornecedorNome();
        if (documentoParticipante == null) documentoParticipante = cp.getFornecedorDocumento();
        if (e.competencia() == null) competencia = cp.getCompetencia();
      }
      if (nome == null) throw ApiException.campo("participanteNome", "OBRIGATORIO", "Informe o fornecedor (ou a conta a pagar).");
    }
    if ((modelo == NotaFiscal.Modelo.NFE || modelo == NotaFiscal.Modelo.NFCE) && vazio(e.chaveAcesso()) == null) {
      throw ApiException.campo("chaveAcesso", "OBRIGATORIA", "NF-e e NFC-e exigem a chave de acesso.");
    }
    periodos.exigirAberto(competencia);
    String serie = vazio(e.serie());
    if (notas.duplicadas(e.tipo(), modelo, serie == null ? "" : serie, e.numero().trim(), documentoParticipante == null ? "" : documentoParticipante) > 0) {
      throw ApiException.conflito("NOTA_DUPLICADA", "Já existe a nota " + e.numero().trim() + (serie == null ? "" : " série " + serie) + " registrada.");
    }
    Documento doc = documentos.paraVincular(e.documentoId(), "documentoId");
    NotaFiscal n = new NotaFiscal(e.tipo(), modelo, e.numero().trim(), serie, vazio(e.chaveAcesso()), e.dataEmissao(), competencia,
        e.valor(), e.valorImpostos());
    n.vincular(empresa, nome, documentoParticipante, e.cobrancaId(), e.contaPagarId(), e.documentoId(), vazio(e.observacao()));
    notas.save(n);
    if (doc != null) documentos.vincular(doc, "nota_fiscal", n.getId());
    auditoria.registrar("REGISTRAR_NOTA", "nota_fiscal", n.getId(), null, n.instantaneo());
    if (n.getTipo() == NotaFiscal.Tipo.EMITIDA && empresa != null) {
      plataforma.timeline(empresa, "financeiro.nota.emitida", "Nota fiscal " + n.getModelo() + " nº " + n.getNumero() + " de "
          + Dinheiro.brl(n.getValor()) + " registrada.", "/modulos/financeiro/notas-fiscais");
    }
    return n;
  }

  @Transactional(readOnly = true)
  public Pagina<NotaSaida> listar(NotaFiscal.Tipo tipo, LocalDate competencia, NotaFiscal.Status status, UUID cobrancaId, Pageable pagina) {
    Specification<NotaFiscal> s = (r, q, cb) -> cb.isNull(r.get("deletedAt"));
    if (tipo != null) s = s.and((r, q, cb) -> cb.equal(r.get("tipo"), tipo));
    if (competencia != null) s = s.and((r, q, cb) -> cb.equal(r.get("competencia"), Competencia.de(competencia)));
    if (status != null) s = s.and((r, q, cb) -> cb.equal(r.get("status"), status));
    if (cobrancaId != null) s = s.and((r, q, cb) -> cb.equal(r.get("cobrancaId"), cobrancaId));
    return Pagina.de(notas.findAll(s, pagina), NotaSaida::de);
  }

  @Transactional(readOnly = true)
  public NotaFiscal obter(UUID id) {
    return Tenant.ativoDoTenant(notas.findById(id), "Nota fiscal");
  }

  @Transactional
  public NotaFiscal anexar(UUID id, UUID documentoId) {
    NotaFiscal n = obter(id);
    Documento d = documentos.paraVincular(documentoId, "documentoId");
    Map<String, Object> antes = n.instantaneo();
    n.anexar(d.getId());
    documentos.vincular(d, "nota_fiscal", n.getId());
    auditoria.registrar("ANEXAR_DOCUMENTO", "nota_fiscal", n.getId(), antes, n.instantaneo());
    return n;
  }

  /** Cancelamento registrado aqui depois de feito no sistema fiscal (o módulo não fala com a prefeitura/SEFAZ). */
  @Transactional
  public NotaFiscal cancelar(UUID id, String motivo) {
    NotaFiscal n = obter(id);
    if (n.getStatus() == NotaFiscal.Status.CANCELADA) throw ApiException.conflito("NOTA_CANCELADA", "A nota já está cancelada.");
    if (motivo == null || motivo.isBlank()) throw ApiException.campo("motivo", "OBRIGATORIO", "Informe o motivo do cancelamento.");
    periodos.exigirAberto(n.getCompetencia());
    Map<String, Object> antes = n.instantaneo();
    n.cancelar(motivo.trim().length() > 300 ? motivo.trim().substring(0, 300) : motivo.trim());
    auditoria.registrar("CANCELAR_NOTA", "nota_fiscal", n.getId(), antes, n.instantaneo());
    return n;
  }

  private static String vazio(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
