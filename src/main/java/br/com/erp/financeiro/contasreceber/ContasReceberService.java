package br.com.erp.financeiro.contasreceber;

import static br.com.erp.financeiro.comum.Dinheiro.centavos;
import static br.com.erp.financeiro.comum.Dinheiro.reais;
import static br.com.erp.financeiro.eventos.EventosService.dados;

import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancaRepository;
import br.com.erp.financeiro.comum.Filtros;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Saldo;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusCobranca;
import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.contasreceber.ContasReceberDtos.Atualizar;
import br.com.erp.financeiro.contasreceber.ContasReceberDtos.Baixa;
import br.com.erp.financeiro.contasreceber.ContasReceberDtos.ComSaldo;
import br.com.erp.financeiro.contasreceber.ContasReceberDtos.Criar;
import br.com.erp.financeiro.contasreceber.ContasReceberDtos.Filtro;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.eventos.TipoEvento;
import br.com.erp.financeiro.inadimplencia.PromessaPagamentoRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContasReceberService {

  private static final Set<StatusContaReceber> FINAIS =
      EnumSet.of(StatusContaReceber.PAGA, StatusContaReceber.CANCELADA, StatusContaReceber.ESTORNADA);
  private static final Set<StatusContaReceber> STATUS_EDITAVEIS =
      EnumSet.of(StatusContaReceber.EM_ABERTO, StatusContaReceber.AGENDADA, StatusContaReceber.CANCELADA);

  /** De onde veio o recebimento (vai no evento conta_receber.paga). */
  public enum OrigemBaixa {
    MANUAL("manual"), PIX_WEBHOOK("pix_webhook"), CONCILIACAO_OFX("conciliacao_ofx");

    public final String valor;

    OrigemBaixa(String valor) {
      this.valor = valor;
    }
  }

  private final ContaReceberRepository repo;
  private final CobrancaRepository cobrancas;
  private final PromessaPagamentoRepository promessas;
  private final EventosService eventos;
  private final Relogio relogio;

  public ContasReceberService(ContaReceberRepository repo, CobrancaRepository cobrancas, PromessaPagamentoRepository promessas,
      EventosService eventos, Relogio relogio) {
    this.repo = repo;
    this.cobrancas = cobrancas;
    this.promessas = promessas;
    this.eventos = eventos;
    this.relogio = relogio;
  }

  public static long saldoCentavos(ContaReceber c) {
    return Saldo.centavosDevidos(c.getValor(), c.getDesconto(), c.getJuros(), c.getMulta(), c.getValorPago());
  }

  public static ComSaldo comSaldo(ContaReceber c) {
    return new ComSaldo(c, reais(saldoCentavos(c)));
  }

  @Transactional(readOnly = true)
  public Paginado<ComSaldo> listar(AuthUser u, Filtro f) {
    var spec = Filtros.<ContaReceber>doTenant(u.tenantId())
        .igual("status", f.status())
        .igual("clienteId", f.clienteId())
        .igual("contratoId", f.contratoId())
        .igual("competencia", f.competencia())
        .aPartirDe("dataVencimento", f.vencimentoDe())
        .ate("dataVencimento", f.vencimentoAte())
        .spec();
    var pagina = repo.findAll(spec, f.pageable(Sort.by("dataVencimento", "createdAt")));
    return Paginado.de(pagina, f, ContasReceberService::comSaldo);
  }

  ContaReceber obter(AuthUser u, UUID id) {
    return repo.findByIdAndTenantId(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Conta a receber não encontrada"));
  }

  @Transactional(readOnly = true)
  public ComSaldo detalhar(AuthUser u, UUID id) {
    return comSaldo(obter(u, id));
  }

  @Transactional
  public ComSaldo criar(AuthUser u, Criar dto) {
    return comSaldo(criar(u, dto, null, null));
  }

  /** Criação usada também pela aprovação de cobrança (vínculo com a cobrança e txid do Pix). */
  public ContaReceber criar(AuthUser u, Criar dto, UUID cobrancaId, String pixTxid) {
    BigDecimal desconto = dto.desconto() == null ? BigDecimal.ZERO : dto.desconto();
    if (centavos(desconto) >= centavos(dto.valor())) throw ApiException.invalido("Desconto deve ser menor que o valor");
    ContaReceber c = new ContaReceber();
    c.setClienteId(dto.clienteId());
    c.setContratoId(dto.contratoId());
    c.setCobrancaId(cobrancaId);
    c.setDescricao(dto.descricao());
    c.setCompetencia(dto.competencia());
    c.setDataVencimento(dto.dataVencimento());
    c.setValor(dto.valor());
    c.setDesconto(desconto);
    c.setFormaPagamento(dto.formaPagamento());
    c.setPixTxid(pixTxid);
    c.setStatus(dto.dataVencimento().isBefore(relogio.hoje()) ? StatusContaReceber.VENCIDA : StatusContaReceber.EM_ABERTO);
    c.auditarCriacao(u.tenantId(), u.sub());
    return repo.save(c);
  }

  @Transactional
  public ComSaldo atualizar(AuthUser u, UUID id, Atualizar dto) {
    ContaReceber c = obter(u, id);
    if (FINAIS.contains(c.getStatus()) || c.getValorPago().signum() > 0) {
      throw ApiException.conflito("Conta com status \"" + c.getStatus() + "\" ou com pagamento registrado não pode ser alterada");
    }
    if (dto.status() != null && !STATUS_EDITAVEIS.contains(dto.status())) {
      throw ApiException.invalido("status só pode ser alterado para em_aberto, agendada ou cancelada");
    }
    if (dto.descricao() != null) c.setDescricao(dto.descricao());
    if (dto.dataVencimento() != null) c.setDataVencimento(dto.dataVencimento());
    if (dto.valor() != null) c.setValor(dto.valor());
    if (dto.desconto() != null) c.setDesconto(dto.desconto());
    if (dto.formaPagamento() != null) c.setFormaPagamento(dto.formaPagamento());
    if (dto.status() != null) c.setStatus(dto.status());
    c.setUpdatedBy(u.sub());
    if (centavos(c.getDesconto()) >= centavos(c.getValor())) throw ApiException.invalido("Desconto deve ser menor que o valor");
    if (dto.status() == null && c.getStatus() != StatusContaReceber.AGENDADA) {
      c.setStatus(c.getDataVencimento().isBefore(relogio.hoje()) ? StatusContaReceber.VENCIDA : StatusContaReceber.EM_ABERTO);
    }
    return comSaldo(repo.save(c));
  }

  @Transactional
  public void remover(AuthUser u, UUID id) {
    ContaReceber c = obter(u, id);
    if (c.getValorPago().signum() > 0) throw ApiException.conflito("Conta com pagamento registrado não pode ser excluída; use estorno");
    c.excluir(u.sub(), relogio.agora());
  }

  @Transactional
  public ComSaldo baixaManual(AuthUser u, UUID id, Baixa dto) {
    ContaReceber c = repo.travar(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Conta a receber não encontrada"));
    return comSaldo(registrarRecebimento(c, centavos(dto.valor()), dto.dataPagamento(), dto.formaPagamento(), OrigemBaixa.MANUAL, u.sub()));
  }

  @Transactional
  public ComSaldo estornar(AuthUser u, UUID id) {
    ContaReceber c = obter(u, id);
    if (c.getValorPago().signum() <= 0) throw ApiException.conflito("Só é possível estornar contas com pagamento registrado");
    c.setStatus(StatusContaReceber.ESTORNADA);
    c.setUpdatedBy(u.sub());
    return comSaldo(repo.save(c));
  }

  /**
   * Ponto único de "baixa" de recebimento, usado pela baixa manual, pelo webhook Pix e pela conciliação OFX.
   * Atualiza o status pelo saldo, propaga para a cobrança, cumpre promessas e publica eventos.
   * Roda dentro da transação de quem chama; valida tudo antes de gravar qualquer coisa.
   */
  public ContaReceber registrarRecebimento(ContaReceber c, long valorCent, LocalDate data, FormaPagamento forma, OrigemBaixa origem,
      UUID autor) {
    if (!StatusContaReceber.EM_ABERTO_COM_SALDO.contains(c.getStatus())) {
      throw ApiException.conflito("Conta com status \"" + c.getStatus() + "\" não aceita recebimento");
    }
    long saldo = saldoCentavos(c);
    if (valorCent > saldo) {
      throw ApiException.invalido("Valor (" + reais(valorCent) + ") maior que o saldo devedor (" + reais(saldo) + ")");
    }
    c.setValorPago(reais(centavos(c.getValorPago()) + valorCent));
    c.setDataPagamento(data);
    c.setFormaPagamento(forma);
    c.setStatus(valorCent == saldo ? StatusContaReceber.PAGA : StatusContaReceber.PARCIALMENTE_PAGA);
    c.setUpdatedBy(autor);
    repo.save(c);

    if (c.getStatus() == StatusContaReceber.PAGA) {
      eventos.publicar(c.getTenantId(), TipoEvento.CONTA_RECEBER_PAGA, c.getId(), dados(
          "conta_receber_id", c.getId(), "cliente_id", c.getClienteId(), "contrato_id", c.getContratoId(),
          "cobranca_id", c.getCobrancaId(), "valor_pago", c.getValorPago(), "data_pagamento", data, "origem", origem.valor));
      promessas.cumprir(c.getId(), autor);
      if (c.getCobrancaId() != null) {
        Cobranca cob = cobrancas.findById(c.getCobrancaId()).orElse(null);
        if (cob != null && cob.getStatus() == StatusCobranca.APROVADA) {
          cob.setStatus(StatusCobranca.PAGA);
          cob.setUpdatedBy(autor);
          cobrancas.save(cob);
          eventos.publicar(c.getTenantId(), TipoEvento.COBRANCA_PAGA, cob.getId(), dados(
              "cobranca_id", cob.getId(), "cliente_id", cob.getClienteId(), "contrato_id", cob.getContratoId(),
              "competencia", cob.getCompetencia(), "valor", c.getValorPago(), "origem", origem.valor));
        }
      }
    }
    return c;
  }
}
