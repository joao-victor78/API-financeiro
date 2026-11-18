package br.com.erp.financeiro.recorrencias;

import static br.com.erp.financeiro.eventos.EventosService.dados;

import br.com.erp.financeiro.cobrancas.Cobranca;
import br.com.erp.financeiro.cobrancas.CobrancasDtos;
import br.com.erp.financeiro.cobrancas.CobrancasService;
import br.com.erp.financeiro.comum.Datas;
import br.com.erp.financeiro.comum.Filtros;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.enums.Periodicidade;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.eventos.TipoEvento;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Atualizar;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Ciclo;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Criar;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Detalhe;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.DetalheComExecucoes;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Erro;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Filtro;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.Previsao;
import br.com.erp.financeiro.recorrencias.RecorrenciasDtos.ResultadoProcessamento;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecorrenciasService {

  private static final Logger log = LoggerFactory.getLogger("Recorrencias");
  private static final int MAX_CICLOS_POR_EXECUCAO = 24;

  private final RecorrenciaRepository repo;
  private final RecorrenciaExecucaoRepository execucoes;
  private final CobrancasService cobrancas;
  private final EventosService eventos;
  private final Relogio relogio;
  private final TransactionTemplate tx;

  public RecorrenciasService(RecorrenciaRepository repo, RecorrenciaExecucaoRepository execucoes, CobrancasService cobrancas,
      EventosService eventos, Relogio relogio, TransactionTemplate tx) {
    this.repo = repo;
    this.execucoes = execucoes;
    this.cobrancas = cobrancas;
    this.eventos = eventos;
    this.relogio = relogio;
    this.tx = tx;
  }

  /** Ciclo de uma competência: vencimento no dia configurado e geração N dias antes. */
  public static Ciclo cicloDe(int diaVencimento, int diasAntecedencia, String competencia) {
    LocalDate venc = Datas.comDia(competencia, diaVencimento);
    return new Ciclo(competencia, venc, venc.minusDays(diasAntecedencia));
  }

  static Ciclo cicloDe(Recorrencia r, String competencia) {
    return cicloDe(r.getDiaVencimento(), r.getDiasAntecedencia(), competencia);
  }

  public static String proximaCompetencia(Periodicidade p, String competencia) {
    return Datas.somarMesesCompetencia(competencia, p.meses());
  }

  private static Detalhe detalhe(Recorrencia r) {
    return new Detalhe(r, Cobranca.totalItens(r.getItens()), r.isAtiva() ? cicloDe(r, r.getProximaCompetencia()) : null);
  }

  @Transactional(readOnly = true)
  public Paginado<Detalhe> listar(AuthUser u, Filtro f) {
    var spec = Filtros.<Recorrencia>doTenant(u.tenantId())
        .igual("clienteId", f.clienteId())
        .igual("contratoId", f.contratoId())
        .igual("ativa", f.ativa())
        .spec();
    return Paginado.de(repo.findAll(spec, f.pageable(Sort.by(Sort.Direction.DESC, "createdAt"))), f, RecorrenciasService::detalhe);
  }

  private Recorrencia obter(AuthUser u, UUID id) {
    return repo.findByIdAndTenantId(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Recorrência não encontrada"));
  }

  @Transactional(readOnly = true)
  public DetalheComExecucoes detalhar(AuthUser u, UUID id) {
    Recorrencia r = obter(u, id);
    Detalhe d = detalhe(r);
    return new DetalheComExecucoes(r, d.valorCiclo(), d.proximoCiclo(),
        execucoes.findTop24ByRecorrenciaIdAndTenantIdOrderByCompetenciaDesc(r.getId(), u.tenantId()));
  }

  @Transactional
  public Detalhe criar(AuthUser u, Criar dto) {
    if (dto.dataFim() != null && dto.dataFim().isBefore(dto.dataInicio())) {
      throw ApiException.invalido("data_fim deve ser posterior a data_inicio");
    }
    int antecedencia = dto.diasAntecedencia() == null ? 10 : dto.diasAntecedencia();
    // Primeira competência cujo vencimento não é anterior ao início do contrato.
    String comp = Datas.competenciaDe(dto.dataInicio());
    if (cicloDe(dto.diaVencimento(), antecedencia, comp).dataVencimento().isBefore(dto.dataInicio())) {
      comp = proximaCompetencia(dto.periodicidade(), comp);
    }
    Recorrencia r = new Recorrencia();
    r.setClienteId(dto.clienteId());
    r.setContratoId(dto.contratoId());
    r.setDescricao(dto.descricao());
    r.setPeriodicidade(dto.periodicidade());
    r.setDiaVencimento(dto.diaVencimento());
    r.setDiasAntecedencia(antecedencia);
    r.setDataInicio(dto.dataInicio());
    r.setDataFim(dto.dataFim());
    r.setFormaPagamento(dto.formaPagamento());
    r.setAprovacaoAutomatica(dto.aprovacaoAutomatica() == null || dto.aprovacaoAutomatica());
    r.setItens(new ArrayList<>(dto.itens()));
    r.setProximaCompetencia(comp);
    r.setAtiva(true);
    r.auditarCriacao(u.tenantId(), u.sub());
    return detalhe(repo.save(r));
  }

  @Transactional
  public Detalhe atualizar(AuthUser u, UUID id, Atualizar dto) {
    Recorrencia r = obter(u, id);
    if (dto.descricao() != null) r.setDescricao(dto.descricao());
    if (dto.diaVencimento() != null) r.setDiaVencimento(dto.diaVencimento());
    if (dto.diasAntecedencia() != null) r.setDiasAntecedencia(dto.diasAntecedencia());
    if (dto.dataFim() != null) r.setDataFim(dto.dataFim());
    if (dto.formaPagamento() != null) r.setFormaPagamento(dto.formaPagamento());
    if (dto.aprovacaoAutomatica() != null) r.setAprovacaoAutomatica(dto.aprovacaoAutomatica());
    if (dto.itens() != null) r.setItens(new ArrayList<>(dto.itens()));
    r.setUpdatedBy(u.sub());
    if (r.getDataFim() != null && r.getDataFim().isBefore(r.getDataInicio())) {
      throw ApiException.invalido("data_fim deve ser posterior a data_inicio");
    }
    return detalhe(repo.save(r));
  }

  @Transactional
  public Detalhe alterarAtiva(AuthUser u, UUID id, boolean ativa) {
    Recorrencia r = obter(u, id);
    if (r.isAtiva() == ativa) throw ApiException.conflito(ativa ? "Recorrência já está ativa" : "Recorrência já está pausada");
    r.setAtiva(ativa);
    r.setUpdatedBy(u.sub());
    return detalhe(repo.save(r));
  }

  @Transactional
  public void remover(AuthUser u, UUID id) {
    Recorrencia r = obter(u, id);
    r.setAtiva(false);
    r.excluir(u.sub(), relogio.agora());
  }

  @Transactional(readOnly = true)
  public List<Previsao> previsao(AuthUser u, UUID id, int ciclos) {
    Recorrencia r = obter(u, id);
    BigDecimal valor = Cobranca.totalItens(r.getItens());
    List<Previsao> out = new ArrayList<>();
    String comp = r.getProximaCompetencia();
    for (int i = 0; i < ciclos; i++) {
      Ciclo c = cicloDe(r, comp);
      if (r.getDataFim() != null && c.dataVencimento().isAfter(r.getDataFim())) break;
      out.add(new Previsao(c.competencia(), c.dataVencimento(), c.dataGeracao(), valor));
      comp = proximaCompetencia(r.getPeriodicidade(), comp);
    }
    return out;
  }

  /** Geração manual imediata da próxima competência (ignora a antecedência). */
  @Transactional
  public Object gerarAgora(AuthUser u, UUID id) {
    Recorrencia r = repo.travar(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Recorrência não encontrada"));
    if (!r.isAtiva()) throw ApiException.conflito("Recorrência pausada");
    Ciclo ciclo = cicloDe(r, r.getProximaCompetencia());
    if (r.getDataFim() != null && ciclo.dataVencimento().isAfter(r.getDataFim())) {
      throw ApiException.conflito("Contrato encerrado: não há próxima competência");
    }
    return gerarCiclo(r, ciclo, "manual", u);
  }

  public ResultadoProcessamento processarPendentes() {
    return processarPendentes(null);
  }

  /**
   * NÚCLEO DA AUTOMAÇÃO. Chamado pelo agendador (cron) sem nenhuma ação humana:
   * para cada recorrência ativa cuja data de geração chegou, emite a cobrança da competência,
   * aprova (se configurado), gera a conta a receber/Pix, publica eventos e avança o ciclo.
   * Idempotente: a UNIQUE (recorrencia_id, competencia) impede cobrança duplicada mesmo com
   * mais de uma instância do serviço rodando, e o lock SKIP LOCKED evita disputa.
   */
  public ResultadoProcessamento processarPendentes(UUID tenant) {
    LocalDate hoje = relogio.hoje();
    List<UUID> ids = tenant == null ? repo.possivelmenteDevidas(hoje) : repo.possivelmenteDevidas(hoje, tenant);
    AtomicInteger geradas = new AtomicInteger();
    AtomicInteger aprovadas = new AtomicInteger();
    AtomicInteger encerradas = new AtomicInteger();
    List<Erro> erros = new ArrayList<>();

    for (UUID id : ids) {
      try {
        tx.executeWithoutResult(s -> {
          List<Recorrencia> livre = repo.travarSeLivre(id);
          if (livre.isEmpty()) return; // outra instância está cuidando dela
          Recorrencia r = livre.get(0);
          AuthUser sistema = AuthUser.sistema(r.getTenantId());
          for (int i = 0; i < MAX_CICLOS_POR_EXECUCAO; i++) {
            Ciclo ciclo = cicloDe(r, r.getProximaCompetencia());
            if (r.getDataFim() != null && ciclo.dataVencimento().isAfter(r.getDataFim())) {
              r.setAtiva(false);
              r.setUpdatedBy(AuthUser.SISTEMA_ID);
              repo.save(r);
              encerradas.incrementAndGet();
              break;
            }
            if (ciclo.dataGeracao().isAfter(hoje)) break;
            Object cob = gerarCiclo(r, ciclo, "agendador", sistema);
            geradas.incrementAndGet();
            if (cob instanceof CobrancasDtos.Aprovada) aprovadas.incrementAndGet();
          }
        });
      } catch (RuntimeException e) {
        log.error("Recorrência {}: {}", id, e.getMessage());
        erros.add(new Erro(id, e.getMessage()));
      }
    }
    return new ResultadoProcessamento(ids.size(), geradas.get(), aprovadas.get(), encerradas.get(), erros);
  }

  /** Emite a cobrança de um ciclo e avança a recorrência. Devolve a cobrança (aprovada ou em rascunho). */
  private Object gerarCiclo(Recorrencia r, Ciclo ciclo, String origem, AuthUser u) {
    String comp = ciclo.competencia();
    Cobranca cobranca = cobrancas.criar(u, new CobrancasDtos.Criar(r.getClienteId(), r.getContratoId(), comp,
        r.getDescricao() + " — " + comp.substring(5) + "/" + comp.substring(0, 4), r.getItens(), ciclo.dataVencimento(),
        r.getFormaPagamento()), r.getId());

    RecorrenciaExecucao ex = new RecorrenciaExecucao();
    ex.setTenantId(r.getTenantId());
    ex.setRecorrenciaId(r.getId());
    ex.setCompetencia(comp);
    ex.setCobrancaId(cobranca.getId());
    ex.setOrigem(origem);
    execucoes.saveAndFlush(ex); // força a UNIQUE (recorrencia_id, competencia) já aqui

    Object resultado = r.isAprovacaoAutomatica() ? cobrancas.aprovarInterno(u, cobranca) : cobranca;

    eventos.publicar(r.getTenantId(), TipoEvento.RECORRENCIA_COBRANCA_GERADA, r.getId(), dados(
        "recorrencia_id", r.getId(), "cobranca_id", cobranca.getId(), "contrato_id", r.getContratoId(),
        "cliente_id", r.getClienteId(), "competencia", comp, "data_vencimento", ciclo.dataVencimento(),
        "valor_total", cobranca.getValorTotal(), "aprovada_automaticamente", r.isAprovacaoAutomatica(), "origem", origem));

    r.setProximaCompetencia(proximaCompetencia(r.getPeriodicidade(), comp));
    r.setUpdatedBy(u.sub());
    repo.save(r);
    return resultado;
  }
}
