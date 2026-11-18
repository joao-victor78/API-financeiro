package br.com.erp.financeiro.inadimplencia;

import static br.com.erp.financeiro.comum.Dinheiro.centavos;
import static br.com.erp.financeiro.comum.Dinheiro.reais;
import static br.com.erp.financeiro.eventos.EventosService.dados;

import br.com.erp.financeiro.comum.Datas;
import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.Filtros;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.enums.StatusContaPagar;
import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.config.Ambiente;
import br.com.erp.financeiro.contaspagar.ContaPagar;
import br.com.erp.financeiro.contaspagar.ContaPagarRepository;
import br.com.erp.financeiro.contasreceber.ContaReceber;
import br.com.erp.financeiro.contasreceber.ContaReceberRepository;
import br.com.erp.financeiro.contasreceber.ContasReceberService;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.eventos.TipoEvento;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.Atraso;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.CriarPromessa;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.Faixa;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.Filtro;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.FiltroPromessas;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.ResultadoPromessas;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.ResultadoVencimentos;
import br.com.erp.financeiro.inadimplencia.InadimplenciaDtos.Resumo;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InadimplenciaService {

  private final ContaReceberRepository contas;
  private final ContaPagarRepository contasPagar;
  private final PromessaPagamentoRepository promessas;
  private final EventosService eventos;
  private final Relogio relogio;
  private final Ambiente ambiente;

  public InadimplenciaService(ContaReceberRepository contas, ContaPagarRepository contasPagar, PromessaPagamentoRepository promessas,
      EventosService eventos, Relogio relogio, Ambiente ambiente) {
    this.contas = contas;
    this.contasPagar = contasPagar;
    this.promessas = promessas;
    this.eventos = eventos;
    this.relogio = relogio;
    this.ambiente = ambiente;
  }

  private Atraso detalharAtraso(ContaReceber c, LocalDate hoje) {
    long saldo = ContasReceberService.saldoCentavos(c);
    long dias = Math.max(0, Datas.diferencaDias(c.getDataVencimento(), hoje));
    var e = Encargos.calcular(saldo, dias, ambiente.multaPercentual(), ambiente.jurosMensalPercentual());
    return new Atraso(c.getId(), c.getClienteId(), c.getContratoId(), c.getCobrancaId(), c.getDescricao(), c.getCompetencia(),
        c.getDataVencimento(), c.getStatus(), reais(saldo), dias, Encargos.faixaDe(dias), e.multa(), e.juros(), e.valorAtualizado());
  }

  /** Lançamentos vencidos com dias de atraso e valor atualizado (multa + juros). */
  @Transactional(readOnly = true)
  public Paginado<Atraso> listar(AuthUser u, Filtro f) {
    LocalDate hoje = relogio.hoje();
    Specification<ContaReceber> spec = Filtros.<ContaReceber>doTenant(u.tenantId())
        .igual("clienteId", f.clienteId())
        .spec()
        .and((r, q, cb) -> r.get("status").in(StatusContaReceber.EM_ABERTO_COM_SALDO))
        .and((r, q, cb) -> cb.lessThan(r.get("dataVencimento"), hoje));
    if (f.diasMin() != null) {
      LocalDate limite = hoje.minusDays(f.diasMin());
      spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("dataVencimento"), limite));
    }
    var pagina = contas.findAll(spec, f.pageable(Sort.by("dataVencimento", "createdAt")));
    return Paginado.de(pagina, f, c -> detalharAtraso(c, hoje));
  }

  /** Visão consolidada da carteira: aging, totais e taxa de inadimplência. */
  @Transactional(readOnly = true)
  public Resumo resumo(AuthUser u) {
    LocalDate hoje = relogio.hoje();
    Map<String, long[]> aging = new LinkedHashMap<>(); // faixa → {quantidade, centavos}
    Encargos.FAIXAS.forEach(f -> aging.put(f, new long[2]));
    Set<UUID> clientesInadimplentes = new HashSet<>();
    long totalAberto = 0;
    long totalVencido = 0;
    long totalAtualizado = 0;
    for (ContaReceber c : contas.findByTenantIdAndStatusIn(u.tenantId(), StatusContaReceber.EM_ABERTO_COM_SALDO)) {
      Atraso d = detalharAtraso(c, hoje);
      long saldo = centavos(d.saldo());
      totalAberto += saldo;
      long[] faixa = aging.get(d.faixaAging());
      faixa[0]++;
      faixa[1] += saldo;
      if (d.diasAtraso() > 0) {
        totalVencido += saldo;
        totalAtualizado += centavos(d.valorAtualizado());
        clientesInadimplentes.add(c.getClienteId());
      }
    }
    Map<String, Faixa> faixas = new LinkedHashMap<>();
    aging.forEach((k, v) -> faixas.put(k, new Faixa((int) v[0], reais(v[1]))));
    return new Resumo(hoje, reais(totalAberto), reais(totalVencido), reais(totalAtualizado),
        Dinheiro.percentual(totalVencido, totalAberto), clientesInadimplentes.size(), faixas);
  }

  @Transactional
  public PromessaPagamento criarPromessa(AuthUser u, CriarPromessa dto) {
    ContaReceber conta = contas.findByIdAndTenantId(dto.contaReceberId(), u.tenantId())
        .orElseThrow(() -> ApiException.naoEncontrado("Conta a receber não encontrada"));
    if (!StatusContaReceber.EM_ABERTO_COM_SALDO.contains(conta.getStatus())) {
      throw ApiException.conflito("Conta com status \"" + conta.getStatus() + "\" não aceita promessa");
    }
    if (dto.dataPrometida().isBefore(relogio.hoje())) throw ApiException.invalido("data_prometida não pode estar no passado");
    if (centavos(dto.valor()) > ContasReceberService.saldoCentavos(conta)) throw ApiException.invalido("Valor prometido maior que o saldo");
    PromessaPagamento p = new PromessaPagamento();
    p.setContaReceberId(conta.getId());
    p.setDataPrometida(dto.dataPrometida());
    p.setValor(dto.valor());
    p.setObservacao(dto.observacao());
    p.setStatus("pendente");
    p.auditarCriacao(u.tenantId(), u.sub());
    return promessas.save(p);
  }

  @Transactional(readOnly = true)
  public Paginado<PromessaPagamento> listarPromessas(AuthUser u, FiltroPromessas f) {
    var spec = Filtros.<PromessaPagamento>doTenant(u.tenantId())
        .igual("status", f.status())
        .igual("contaReceberId", f.contaReceberId())
        .spec();
    return Paginado.de(promessas.findAll(spec, f.pageable(Sort.by("dataPrometida", "createdAt"))), f);
  }

  // ------------------------------------------------------------------ automações (agendador)

  /** Marca como vencidas as contas cujo vencimento passou e publica o evento para os demais módulos. */
  @Transactional
  public ResultadoVencimentos marcarVencidas(UUID tenant) {
    LocalDate hoje = relogio.hoje();
    var abertasR = EnumSet.of(StatusContaReceber.EM_ABERTO, StatusContaReceber.AGENDADA);
    List<ContaReceber> vencidas = tenant == null ? contas.travarVencidas(abertasR, hoje) : contas.travarVencidas(abertasR, hoje, tenant);
    for (ContaReceber c : vencidas) {
      c.setStatus(StatusContaReceber.VENCIDA);
      c.setUpdatedBy(AuthUser.SISTEMA_ID);
      eventos.publicar(c.getTenantId(), TipoEvento.CONTA_RECEBER_VENCIDA, c.getId(), dados(
          "conta_receber_id", c.getId(), "cliente_id", c.getClienteId(), "contrato_id", c.getContratoId(),
          "cobranca_id", c.getCobrancaId(), "data_vencimento", c.getDataVencimento(), "valor", c.getValor()));
    }
    var abertasP = EnumSet.of(StatusContaPagar.EM_ABERTO, StatusContaPagar.AGENDADA);
    List<ContaPagar> pagar = tenant == null ? contasPagar.travarVencidas(abertasP, hoje) : contasPagar.travarVencidas(abertasP, hoje, tenant);
    for (ContaPagar c : pagar) {
      c.setStatus(StatusContaPagar.VENCIDA);
      c.setUpdatedBy(AuthUser.SISTEMA_ID);
    }
    return new ResultadoVencimentos(vencidas.size(), pagar.size());
  }

  /** Promessas cuja data passou sem pagamento são marcadas como quebradas. */
  @Transactional
  public ResultadoPromessas verificarPromessas(UUID tenant) {
    LocalDate hoje = relogio.hoje();
    List<PromessaPagamento> quebradas = tenant == null ? promessas.travarVencidas(hoje) : promessas.travarVencidas(hoje, tenant);
    for (PromessaPagamento p : quebradas) {
      p.setStatus("quebrada");
      p.setUpdatedBy(AuthUser.SISTEMA_ID);
      eventos.publicar(p.getTenantId(), TipoEvento.PROMESSA_QUEBRADA, p.getId(), dados(
          "promessa_id", p.getId(), "conta_receber_id", p.getContaReceberId(), "data_prometida", p.getDataPrometida(),
          "valor", p.getValor()));
    }
    return new ResultadoPromessas(quebradas.size());
  }
}
