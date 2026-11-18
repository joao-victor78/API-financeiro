package br.com.erp.financeiro.contaspagar;

import static br.com.erp.financeiro.comum.Dinheiro.centavos;
import static br.com.erp.financeiro.comum.Dinheiro.reais;
import static br.com.erp.financeiro.eventos.EventosService.dados;

import br.com.erp.financeiro.comum.Filtros;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Saldo;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusContaPagar;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Atualizar;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.ComSaldo;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Criar;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Filtro;
import br.com.erp.financeiro.contaspagar.ContasPagarDtos.Pagamento;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.eventos.TipoEvento;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContasPagarService {

  private final ContaPagarRepository repo;
  private final EventosService eventos;
  private final Relogio relogio;

  public ContasPagarService(ContaPagarRepository repo, EventosService eventos, Relogio relogio) {
    this.repo = repo;
    this.eventos = eventos;
    this.relogio = relogio;
  }

  public static long saldoCentavos(ContaPagar c) {
    return Saldo.centavosDevidos(c.getValor(), c.getDesconto(), c.getJuros(), c.getMulta(), c.getValorPago());
  }

  public static ComSaldo comSaldo(ContaPagar c) {
    return new ComSaldo(c, reais(saldoCentavos(c)));
  }

  @Transactional(readOnly = true)
  public Paginado<ComSaldo> listar(AuthUser u, Filtro f) {
    var spec = Filtros.<ContaPagar>doTenant(u.tenantId())
        .igual("status", f.status())
        .igual("fornecedorId", f.fornecedorId())
        .igual("centroCustoId", f.centroCustoId())
        .igual("categoriaId", f.categoriaId())
        .igual("competencia", f.competencia())
        .aPartirDe("dataVencimento", f.vencimentoDe())
        .ate("dataVencimento", f.vencimentoAte())
        .spec();
    return Paginado.de(repo.findAll(spec, f.pageable(Sort.by("dataVencimento", "createdAt"))), f, ContasPagarService::comSaldo);
  }

  private ContaPagar obter(AuthUser u, UUID id) {
    return repo.findByIdAndTenantId(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Conta a pagar não encontrada"));
  }

  @Transactional(readOnly = true)
  public ComSaldo detalhar(AuthUser u, UUID id) {
    return comSaldo(obter(u, id));
  }

  @Transactional
  public ComSaldo criar(AuthUser u, Criar dto) {
    BigDecimal desconto = dto.desconto() == null ? BigDecimal.ZERO : dto.desconto();
    if (centavos(desconto) >= centavos(dto.valor())) throw ApiException.invalido("Desconto deve ser menor que o valor");
    ContaPagar c = new ContaPagar();
    c.setFornecedorId(dto.fornecedorId());
    c.setCentroCustoId(dto.centroCustoId());
    c.setCategoriaId(dto.categoriaId());
    c.setDescricao(dto.descricao());
    c.setDocumento(dto.documento());
    c.setCompetencia(dto.competencia());
    c.setDataVencimento(dto.dataVencimento());
    c.setValor(dto.valor());
    c.setDesconto(desconto);
    c.setFormaPagamento(dto.formaPagamento());
    c.setStatus(dto.dataVencimento().isBefore(relogio.hoje()) ? StatusContaPagar.VENCIDA : StatusContaPagar.EM_ABERTO);
    c.auditarCriacao(u.tenantId(), u.sub());
    return comSaldo(repo.save(c));
  }

  @Transactional
  public ComSaldo atualizar(AuthUser u, UUID id, Atualizar dto) {
    ContaPagar c = obter(u, id);
    if (c.getStatus() == StatusContaPagar.PAGA || c.getStatus() == StatusContaPagar.CANCELADA || c.getValorPago().signum() > 0) {
      throw ApiException.conflito("Conta com status \"" + c.getStatus() + "\" ou com pagamento registrado não pode ser alterada");
    }
    if (dto.status() != null && !EnumSet.of(StatusContaPagar.EM_ABERTO, StatusContaPagar.AGENDADA, StatusContaPagar.CANCELADA).contains(dto.status())) {
      throw ApiException.invalido("status só pode ser alterado para em_aberto, agendada ou cancelada");
    }
    if (dto.descricao() != null) c.setDescricao(dto.descricao());
    if (dto.documento() != null) c.setDocumento(dto.documento());
    if (dto.dataVencimento() != null) c.setDataVencimento(dto.dataVencimento());
    if (dto.valor() != null) c.setValor(dto.valor());
    if (dto.desconto() != null) c.setDesconto(dto.desconto());
    if (dto.formaPagamento() != null) c.setFormaPagamento(dto.formaPagamento());
    if (dto.centroCustoId() != null) c.setCentroCustoId(dto.centroCustoId());
    if (dto.categoriaId() != null) c.setCategoriaId(dto.categoriaId());
    if (dto.status() != null) c.setStatus(dto.status());
    c.setUpdatedBy(u.sub());
    if (centavos(c.getDesconto()) >= centavos(c.getValor())) throw ApiException.invalido("Desconto deve ser menor que o valor");
    if (dto.status() == null && c.getStatus() != StatusContaPagar.AGENDADA) {
      c.setStatus(c.getDataVencimento().isBefore(relogio.hoje()) ? StatusContaPagar.VENCIDA : StatusContaPagar.EM_ABERTO);
    }
    return comSaldo(repo.save(c));
  }

  @Transactional
  public void remover(AuthUser u, UUID id) {
    ContaPagar c = obter(u, id);
    if (c.getValorPago().signum() > 0) throw ApiException.conflito("Conta com pagamento registrado não pode ser excluída");
    c.excluir(u.sub(), relogio.agora());
  }

  @Transactional
  public ComSaldo pagar(AuthUser u, UUID id, Pagamento dto) {
    ContaPagar c = repo.travar(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Conta a pagar não encontrada"));
    return comSaldo(registrarPagamento(c, centavos(dto.valor()), dto.dataPagamento(), dto.formaPagamento(), "manual", u.sub()));
  }

  /** Ponto único de pagamento (manual ou pela conciliação OFX); roda na transação de quem chama. */
  public ContaPagar registrarPagamento(ContaPagar c, long valorCent, LocalDate data, FormaPagamento forma, String origem, UUID autor) {
    if (!StatusContaPagar.EM_ABERTO_COM_SALDO.contains(c.getStatus())) {
      throw ApiException.conflito("Conta com status \"" + c.getStatus() + "\" não aceita pagamento");
    }
    long saldo = saldoCentavos(c);
    if (valorCent > saldo) throw ApiException.invalido("Valor (" + reais(valorCent) + ") maior que o saldo (" + reais(saldo) + ")");
    c.setValorPago(reais(centavos(c.getValorPago()) + valorCent));
    c.setDataPagamento(data);
    c.setFormaPagamento(forma);
    c.setStatus(valorCent == saldo ? StatusContaPagar.PAGA : StatusContaPagar.PARCIALMENTE_PAGA);
    c.setUpdatedBy(autor);
    repo.save(c);
    if (c.getStatus() == StatusContaPagar.PAGA) {
      eventos.publicar(c.getTenantId(), TipoEvento.CONTA_PAGAR_PAGA, c.getId(), dados(
          "conta_pagar_id", c.getId(), "fornecedor_id", c.getFornecedorId(), "centro_custo_id", c.getCentroCustoId(),
          "valor_pago", c.getValorPago(), "data_pagamento", data, "origem", origem));
    }
    return c;
  }
}
