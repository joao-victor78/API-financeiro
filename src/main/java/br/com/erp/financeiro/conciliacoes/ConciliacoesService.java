package br.com.erp.financeiro.conciliacoes;

import static br.com.erp.financeiro.comum.Dinheiro.centavos;
import static br.com.erp.financeiro.eventos.EventosService.dados;

import br.com.erp.financeiro.comum.Dinheiro;
import br.com.erp.financeiro.comum.EntidadeBase;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Saldo;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.enums.StatusContaPagar;
import br.com.erp.financeiro.comum.enums.StatusContaReceber;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.conciliacoes.MotorConciliacao.Candidato;
import br.com.erp.financeiro.conciliacoes.MotorConciliacao.Lancamento;
import br.com.erp.financeiro.conciliacoes.MotorConciliacao.Pontuacao;
import br.com.erp.financeiro.contaspagar.ContaPagar;
import br.com.erp.financeiro.contaspagar.ContaPagarRepository;
import br.com.erp.financeiro.contaspagar.ContasPagarService;
import br.com.erp.financeiro.contasreceber.ContaReceber;
import br.com.erp.financeiro.contasreceber.ContaReceberRepository;
import br.com.erp.financeiro.contasreceber.ContasReceberService;
import br.com.erp.financeiro.eventos.EventosService;
import br.com.erp.financeiro.eventos.TipoEvento;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConciliacoesService {

  private static final int JANELA_DIAS = 45;

  private final ConciliacaoRepository conciliacoes;
  private final ExtratoTransacaoRepository transacoes;
  private final ContaReceberRepository contasReceber;
  private final ContaPagarRepository contasPagar;
  private final ContasReceberService receber;
  private final ContasPagarService pagar;
  private final EventosService eventos;
  private final Relogio relogio;

  @PersistenceContext
  private EntityManager em;

  public ConciliacoesService(ConciliacaoRepository conciliacoes, ExtratoTransacaoRepository transacoes,
      ContaReceberRepository contasReceber, ContaPagarRepository contasPagar, ContasReceberService receber, ContasPagarService pagar,
      EventosService eventos, Relogio relogio) {
    this.conciliacoes = conciliacoes;
    this.transacoes = transacoes;
    this.contasReceber = contasReceber;
    this.contasPagar = contasPagar;
    this.receber = receber;
    this.pagar = pagar;
    this.eventos = eventos;
    this.relogio = relogio;
  }

  public record Resultado(@JsonUnwrapped Conciliacao conciliacao, double taxaAutomaticaPercentual) {}

  public record Detalhe(@JsonUnwrapped Conciliacao conciliacao, List<ExtratoTransacao> transacoes) {}

  public record Sugestao(UUID id, int score, String criterio, String tipo) {}

  /**
   * Importa o extrato OFX e concilia automaticamente. Tudo em uma transação:
   * se algo falhar, nada é gravado (nem metade do extrato).
   */
  @Transactional
  public Resultado importar(AuthUser u, String nomeArquivo, byte[] arquivo) {
    OfxParser.Extrato extrato;
    try {
      extrato = OfxParser.ler(OfxParser.decodificar(arquivo));
    } catch (OfxParser.OfxInvalidoException e) {
      throw ApiException.invalido("OFX inválido: " + e.getMessage());
    }
    if (extrato.transacoes().isEmpty()) throw ApiException.invalido("Extrato sem transações");

    Conciliacao conc = new Conciliacao();
    conc.setArquivoNome(nomeArquivo.length() > 200 ? nomeArquivo.substring(0, 200) : nomeArquivo);
    conc.setBancoId(extrato.bancoId());
    conc.setContaId(extrato.contaId());
    conc.setPeriodoInicio(extrato.periodoInicio());
    conc.setPeriodoFim(extrato.periodoFim());
    conc.auditarCriacao(u.tenantId(), u.sub());
    conc = conciliacoes.save(conc);

    // Deduplicação por FITID: reimportar o mesmo extrato não gera lançamentos em dobro.
    Set<String> fitids = new LinkedHashSet<>(extrato.transacoes().stream().map(OfxParser.Transacao::fitid).toList());
    Set<String> vistos = new HashSet<>(transacoes.fitidsExistentes(u.tenantId(), fitids));
    List<ExtratoTransacao> salvas = new ArrayList<>();
    for (OfxParser.Transacao t : extrato.transacoes()) {
      if (!vistos.add(t.fitid())) continue;
      ExtratoTransacao e = new ExtratoTransacao();
      e.setConciliacaoId(conc.getId());
      e.setFitid(t.fitid());
      e.setData(t.data());
      e.setValor(t.valor());
      e.setTipo(t.tipo());
      e.setDescricao(t.descricao());
      e.auditarCriacao(u.tenantId(), u.sub());
      salvas.add(transacoes.save(e));
    }

    Set<UUID> usados = new HashSet<>();
    int automaticas = 0;
    for (ExtratoTransacao t : salvas) {
      if (conciliarAutomatico(u, t, usados)) automaticas++;
    }

    conc.setTotalTransacoes(extrato.transacoes().size());
    conc.setIgnoradasDuplicadas(extrato.transacoes().size() - salvas.size());
    conc.setConciliadasAutomaticamente(automaticas);
    conc.setPendentes(salvas.size() - automaticas);
    conciliacoes.save(conc);

    eventos.publicar(u.tenantId(), TipoEvento.CONCILIACAO_CONCLUIDA, conc.getId(), dados(
        "conciliacao_id", conc.getId(), "arquivo_nome", conc.getArquivoNome(), "total_transacoes", conc.getTotalTransacoes(),
        "conciliadas_automaticamente", automaticas, "pendentes", conc.getPendentes()));
    return new Resultado(conc, Dinheiro.percentual(automaticas, salvas.size()));
  }

  private static Lancamento lancamento(ExtratoTransacao t) {
    return new Lancamento(Math.abs(centavos(t.getValor())), t.getData(), t.getDescricao() == null ? "" : t.getDescricao());
  }

  private List<Candidato> candidatos(AuthUser u, ExtratoTransacao t) {
    var de = t.getData().minusDays(JANELA_DIAS);
    var ate = t.getData().plusDays(JANELA_DIAS);
    if ("credito".equals(t.getTipo())) {
      return contasReceber.candidatasConciliacao(u.tenantId(), StatusContaReceber.EM_ABERTO_COM_SALDO, de, ate, maiuscula(t.getDescricao()))
          .stream()
          .map(c -> new Candidato(c.id(), Saldo.centavosDevidos(c.valor(), c.desconto(), c.juros(), c.multa(), c.valorPago()),
              c.dataVencimento(), c.pixTxid(), "receber"))
          .toList();
    }
    return contasPagar.candidatasConciliacao(u.tenantId(), StatusContaPagar.EM_ABERTO_COM_SALDO, de, ate).stream()
        .map(c -> new Candidato(c.id(), Saldo.centavosDevidos(c.valor(), c.desconto(), c.juros(), c.multa(), c.valorPago()),
            c.dataVencimento(), null, "pagar"))
        .toList();
  }

  private static String maiuscula(String s) {
    return s == null ? "" : s.toUpperCase(Locale.ROOT);
  }

  /**
   * Pix já baixado em tempo real pelo webhook aparece depois no extrato: apenas vincula a transação
   * ao título (fecha o ciclo de auditoria) sem registrar o recebimento uma segunda vez.
   */
  private boolean vincularPixJaBaixado(AuthUser u, ExtratoTransacao t, Set<UUID> usados) {
    if (!"credito".equals(t.getTipo()) || t.getDescricao() == null || t.getDescricao().isEmpty()) return false;
    var conta = contasReceber.pixJaBaixadoSemVinculo(u.tenantId(), maiuscula(t.getDescricao())).stream().findFirst().orElse(null);
    if (conta == null || usados.contains(conta.id()) || centavos(conta.valorPago()) != Math.abs(centavos(t.getValor()))) return false;
    usados.add(conta.id());
    t.setStatus("conciliada");
    t.setContaReceberId(conta.id());
    t.setScore(100);
    t.setCriterio("txid_pix_ja_baixado");
    t.setConciliadaEm(relogio.agora());
    transacoes.save(t);
    return true;
  }

  private boolean conciliarAutomatico(AuthUser u, ExtratoTransacao t, Set<UUID> usados) {
    if (vincularPixJaBaixado(u, t, usados)) return true;
    List<Candidato> cands = candidatos(u, t).stream().filter(c -> !usados.contains(c.id())).toList();
    Pontuacao vencedor = MotorConciliacao.escolherAutomatico(MotorConciliacao.pontuar(lancamento(t), cands));
    if (vencedor == null) return false;
    usados.add(vencedor.id());
    String tipo = cands.stream().filter(c -> c.id().equals(vencedor.id())).findFirst().orElseThrow().tipo();
    baixar(u, t, tipo, vencedor.id(), vencedor.score(), vencedor.criterio(), null);
    return true;
  }

  /** Busca pela chave primária já com bloqueio (SELECT … FOR UPDATE), sem forçar flush da transação. */
  private <T extends EntidadeBase> T travar(Class<T> tipo, UUID id, AuthUser u) {
    T e = em.find(tipo, id, LockModeType.PESSIMISTIC_WRITE);
    return e != null && e.getDeletedAt() == null && u.tenantId().equals(e.getTenantId()) ? e : null;
  }

  private void baixar(AuthUser u, ExtratoTransacao t, String tipo, UUID contaId, Integer score, String criterio, UUID autor) {
    long valorCent = Math.abs(centavos(t.getValor()));
    if ("receber".equals(tipo)) {
      ContaReceber c = travar(ContaReceber.class, contaId, u);
      if (c == null) throw ApiException.naoEncontrado("Conta a receber não encontrada");
      receber.registrarRecebimento(c, valorCent, t.getData(), c.getFormaPagamento() == null ? FormaPagamento.TRANSFERENCIA : c.getFormaPagamento(),
          ContasReceberService.OrigemBaixa.CONCILIACAO_OFX, autor);
      t.setContaReceberId(c.getId());
    } else {
      ContaPagar c = travar(ContaPagar.class, contaId, u);
      if (c == null) throw ApiException.naoEncontrado("Conta a pagar não encontrada");
      pagar.registrarPagamento(c, valorCent, t.getData(), c.getFormaPagamento() == null ? FormaPagamento.TRANSFERENCIA : c.getFormaPagamento(),
          "conciliacao_ofx", autor);
      t.setContaPagarId(c.getId());
    }
    t.setStatus("conciliada");
    t.setScore(score);
    t.setCriterio(criterio);
    t.setConciliadaEm(relogio.agora());
    t.setUpdatedBy(autor);
    transacoes.save(t);
  }

  @Transactional(readOnly = true)
  public Paginado<Conciliacao> listar(AuthUser u, Paginado.Paginacao p) {
    return Paginado.de(conciliacoes.findByTenantId(u.tenantId(), p.pageable(Sort.by(Sort.Direction.DESC, "createdAt"))), p);
  }

  private Conciliacao obterConciliacao(AuthUser u, UUID id) {
    return conciliacoes.findByIdAndTenantId(id, u.tenantId()).orElseThrow(() -> ApiException.naoEncontrado("Conciliação não encontrada"));
  }

  @Transactional(readOnly = true)
  public Detalhe obter(AuthUser u, UUID id, String status) {
    Conciliacao conc = obterConciliacao(u, id);
    List<ExtratoTransacao> lista = status == null
        ? transacoes.findByConciliacaoIdAndTenantIdOrderByDataAscFitidAsc(id, u.tenantId())
        : transacoes.findByConciliacaoIdAndTenantIdAndStatusOrderByDataAscFitidAsc(id, u.tenantId(), status);
    return new Detalhe(conc, lista);
  }

  private ExtratoTransacao transacao(AuthUser u, UUID concId, UUID tId) {
    return transacoes.findByIdAndConciliacaoIdAndTenantId(tId, concId, u.tenantId())
        .orElseThrow(() -> ApiException.naoEncontrado("Transação não encontrada nesta conciliação"));
  }

  @Transactional(readOnly = true)
  public List<Sugestao> sugestoes(AuthUser u, UUID concId, UUID tId) {
    ExtratoTransacao t = transacao(u, concId, tId);
    List<Candidato> cands = candidatos(u, t);
    return MotorConciliacao.pontuar(lancamento(t), cands).stream()
        .limit(10)
        .map(p -> new Sugestao(p.id(), p.score(), p.criterio(),
            cands.stream().filter(c -> c.id().equals(p.id())).findFirst().orElseThrow().tipo()))
        .toList();
  }

  @Transactional
  public ExtratoTransacao conciliarManual(AuthUser u, UUID concId, UUID tId, UUID contaReceberId, UUID contaPagarId) {
    if ((contaReceberId == null) == (contaPagarId == null)) {
      throw ApiException.requisicaoInvalida("Informe exatamente um entre conta_receber_id e conta_pagar_id");
    }
    ExtratoTransacao t = transacao(u, concId, tId);
    if (!"pendente".equals(t.getStatus())) throw ApiException.conflito("Transação já está \"" + t.getStatus() + "\"");
    if (contaReceberId != null && !"credito".equals(t.getTipo())) throw ApiException.invalido("Débito do extrato só concilia com conta a pagar");
    if (contaPagarId != null && !"debito".equals(t.getTipo())) throw ApiException.invalido("Crédito do extrato só concilia com conta a receber");
    baixar(u, t, contaReceberId != null ? "receber" : "pagar", Stream.of(contaReceberId, contaPagarId).filter(x -> x != null).findFirst().orElseThrow(),
        null, "manual", u.sub());
    decrementarPendentes(u, concId);
    return t;
  }

  @Transactional
  public ExtratoTransacao ignorar(AuthUser u, UUID concId, UUID tId) {
    ExtratoTransacao t = transacao(u, concId, tId);
    if (!"pendente".equals(t.getStatus())) throw ApiException.conflito("Transação já está \"" + t.getStatus() + "\"");
    t.setStatus("ignorada");
    t.setUpdatedBy(u.sub());
    transacoes.save(t);
    decrementarPendentes(u, concId);
    return t;
  }

  private void decrementarPendentes(AuthUser u, UUID concId) {
    Conciliacao c = obterConciliacao(u, concId);
    c.setPendentes(Math.max(0, c.getPendentes() - 1));
  }
}
