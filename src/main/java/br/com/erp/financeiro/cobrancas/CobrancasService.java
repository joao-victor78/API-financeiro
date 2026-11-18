package br.com.erp.financeiro.cobrancas;

import static br.com.erp.financeiro.eventos.EventosService.dados;

import br.com.erp.financeiro.cobrancas.CobrancasDtos.Aprovada;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.Atualizar;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.Criar;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.Filtro;
import br.com.erp.financeiro.comum.Filtros;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusCobranca;
import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.config.Ambiente;
import br.com.erp.financeiro.contasreceber.ContaReceber;
import br.com.erp.financeiro.contasreceber.ContaReceberRepository;
import br.com.erp.financeiro.contasreceber.ContasReceberDtos;
import br.com.erp.financeiro.contasreceber.ContasReceberService;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.eventos.TipoEvento;
import br.com.erp.financeiro.pix.BrCode;
import br.com.erp.financeiro.pix.QrCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CobrancasService {

  private final CobrancaRepository repo;
  private final ContaReceberRepository contas;
  private final ContasReceberService contasReceber;
  private final EventosService eventos;
  private final Relogio relogio;
  private final Ambiente ambiente;

  public CobrancasService(CobrancaRepository repo, ContaReceberRepository contas, ContasReceberService contasReceber,
      EventosService eventos, Relogio relogio, Ambiente ambiente) {
    this.repo = repo;
    this.contas = contas;
    this.contasReceber = contasReceber;
    this.eventos = eventos;
    this.relogio = relogio;
    this.ambiente = ambiente;
  }

  @Transactional(readOnly = true)
  public Paginado<Cobranca> listar(AuthUser u, Filtro f) {
    var spec = Filtros.<Cobranca>doTenant(u.tenantId())
        .igual("status", f.status())
        .igual("clienteId", f.clienteId())
        .igual("contratoId", f.contratoId())
        .igual("recorrenciaId", f.recorrenciaId())
        .igual("competencia", f.competencia())
        .spec();
    return Paginado.de(repo.findAll(spec, f.pageable(Sort.by(Sort.Direction.DESC, "createdAt"))), f);
  }

  @Transactional(readOnly = true)
  public Cobranca obter(AuthUser u, UUID id) {
    return repo.findByIdAndTenantId(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Cobrança não encontrada"));
  }

  @Transactional
  public Cobranca criar(AuthUser u, Criar dto) {
    return criar(u, dto, null);
  }

  /** Criação em rascunho; a recorrência informa de qual contrato a cobrança veio. */
  public Cobranca criar(AuthUser u, Criar dto, UUID recorrenciaId) {
    Cobranca c = new Cobranca();
    c.setClienteId(dto.clienteId());
    c.setContratoId(dto.contratoId());
    c.setRecorrenciaId(recorrenciaId);
    c.setCompetencia(dto.competencia());
    c.setDescricao(dto.descricao());
    c.setItens(new ArrayList<>(dto.itens()));
    c.setValorTotal(Cobranca.totalItens(dto.itens()));
    c.setDataVencimento(dto.dataVencimento());
    c.setFormaPagamento(dto.formaPagamento());
    c.setStatus(StatusCobranca.RASCUNHO);
    c.auditarCriacao(u.tenantId(), u.sub());
    return repo.save(c);
  }

  @Transactional
  public Cobranca atualizar(AuthUser u, UUID id, Atualizar dto) {
    Cobranca c = obter(u, id);
    if (c.getStatus() != StatusCobranca.RASCUNHO) throw ApiException.conflito("Apenas cobranças em rascunho podem ser alteradas");
    if (dto.descricao() != null) c.setDescricao(dto.descricao());
    if (dto.dataVencimento() != null) c.setDataVencimento(dto.dataVencimento());
    if (dto.formaPagamento() != null) c.setFormaPagamento(dto.formaPagamento());
    if (dto.itens() != null) {
      c.setItens(new ArrayList<>(dto.itens()));
      c.setValorTotal(Cobranca.totalItens(dto.itens()));
    }
    c.setUpdatedBy(u.sub());
    return repo.save(c);
  }

  @Transactional
  public Aprovada aprovar(AuthUser u, UUID id) {
    Cobranca c = repo.travar(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Cobrança não encontrada"));
    return aprovarInterno(u, c);
  }

  /**
   * Aprovação = emissão: gera a conta a receber vinculada e, se Pix, o txid e o BR Code "copia e cola".
   * Usado tanto pela rota manual quanto pela recorrência com aprovação automática.
   */
  public Aprovada aprovarInterno(AuthUser u, Cobranca c) {
    if (c.getStatus() != StatusCobranca.RASCUNHO) {
      throw ApiException.conflito("Cobrança com status \"" + c.getStatus() + "\" não pode ser aprovada");
    }
    c.setStatus(StatusCobranca.APROVADA);
    c.setAprovadaEm(relogio.agora());
    c.setAprovadaPor(u.sub());
    c.setUpdatedBy(u.sub());
    if (c.getFormaPagamento() == FormaPagamento.PIX) {
      c.setPixTxid(BrCode.gerarTxid());
      c.setPixCopiaCola(BrCode.gerar(ambiente.pixChaveRecebedor(), c.getValorTotal(), c.getPixTxid(),
          ambiente.pixNomeRecebedor(), ambiente.pixCidadeRecebedor()));
    }
    repo.save(c);

    ContaReceber conta = contasReceber.criar(u, new ContasReceberDtos.Criar(c.getClienteId(), c.getContratoId(), c.getDescricao(),
        c.getCompetencia(), c.getDataVencimento(), c.getValorTotal(), null, c.getFormaPagamento()), c.getId(), c.getPixTxid());

    eventos.publicar(c.getTenantId(), TipoEvento.COBRANCA_APROVADA, c.getId(), dados(
        "cobranca_id", c.getId(), "conta_receber_id", conta.getId(), "cliente_id", c.getClienteId(),
        "contrato_id", c.getContratoId(), "competencia", c.getCompetencia(), "valor_total", c.getValorTotal(),
        "data_vencimento", c.getDataVencimento(), "forma_pagamento", c.getFormaPagamento(), "pix_copia_cola", c.getPixCopiaCola()));
    return new Aprovada(c, conta.getId());
  }

  public record Imagem(MediaType tipo, byte[] corpo) {}

  /** QR Code do Pix "copia e cola" da cobrança (PNG ou SVG). */
  @Transactional(readOnly = true)
  public Imagem qrCode(AuthUser u, UUID id, String formato, int tamanho) {
    Cobranca c = obter(u, id);
    if (c.getFormaPagamento() != FormaPagamento.PIX) throw ApiException.invalido("A forma de pagamento desta cobrança não é Pix");
    if (c.getPixCopiaCola() == null) throw ApiException.conflito("O QR Code é gerado quando a cobrança é aprovada");
    if (c.getStatus() != StatusCobranca.APROVADA) {
      throw ApiException.conflito("Cobrança com status \"" + c.getStatus() + "\" não está aguardando pagamento");
    }
    return "svg".equals(formato)
        ? new Imagem(MediaType.valueOf("image/svg+xml"), QrCode.svg(c.getPixCopiaCola()).getBytes(StandardCharsets.UTF_8))
        : new Imagem(MediaType.IMAGE_PNG, QrCode.png(c.getPixCopiaCola(), tamanho));
  }

  @Transactional
  public Cobranca cancelar(AuthUser u, UUID id, String motivo) {
    Cobranca c = repo.travar(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Cobrança não encontrada"));
    if (c.getStatus() != StatusCobranca.RASCUNHO && c.getStatus() != StatusCobranca.APROVADA) {
      throw ApiException.conflito("Cobrança com status \"" + c.getStatus() + "\" não pode ser cancelada");
    }
    ContaReceber conta = contas.findFirstByCobrancaIdAndTenantId(c.getId(), u.tenantId()).orElse(null);
    if (conta != null && conta.getValorPago().signum() > 0) {
      throw ApiException.conflito("Cobrança com pagamento parcial registrado: estorne antes de cancelar");
    }
    if (conta != null) {
      conta.setStatus(StatusContaReceber.CANCELADA);
      conta.setUpdatedBy(u.sub());
    }
    c.setStatus(StatusCobranca.CANCELADA);
    c.setCanceladaEm(relogio.agora());
    c.setMotivoCancelamento(motivo);
    c.setUpdatedBy(u.sub());
    repo.save(c);
    eventos.publicar(c.getTenantId(), TipoEvento.COBRANCA_CANCELADA, c.getId(), dados(
        "cobranca_id", c.getId(), "cliente_id", c.getClienteId(), "contrato_id", c.getContratoId(), "motivo", motivo));
    return c;
  }
}
