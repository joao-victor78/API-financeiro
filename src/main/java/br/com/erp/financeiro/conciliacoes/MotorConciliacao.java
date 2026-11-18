package br.com.erp.financeiro.conciliacoes;

import br.com.erp.financeiro.comum.Datas;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Motor de conciliação: dado um lançamento do extrato, pontua os títulos em aberto candidatos.
 * Função pura (sem banco) para ser testável isoladamente.
 *
 * <pre>
 *  - txid do Pix presente na descrição do extrato ......... 100 pontos (identificação unívoca)
 *  - valor idêntico ao saldo do título .................... 60 pontos (condição obrigatória sem txid)
 *  - data do crédito até 3 dias do vencimento ............. +30
 *  - data do crédito entre 4 e 10 dias do vencimento ...... +15
 *  - diferença acima de 45 dias ........................... descartado
 * </pre>
 *
 * A baixa automática ocorre apenas quando o melhor candidato atinge LIMIAR_AUTOMATICO e é ÚNICO
 * com essa pontuação; empates ou pontuação menor ficam pendentes para decisão humana (com sugestões).
 */
public final class MotorConciliacao {

  public static final int LIMIAR_AUTOMATICO = 75;

  private MotorConciliacao() {}

  /** tipo: "receber" ou "pagar". */
  public record Candidato(UUID id, long saldoCent, LocalDate dataVencimento, String pixTxid, String tipo) {}

  /** valorCent sempre positivo (absoluto). */
  public record Lancamento(long valorCent, LocalDate data, String descricao) {}

  public record Pontuacao(UUID id, int score, String criterio) {}

  public static List<Pontuacao> pontuar(Lancamento l, List<Candidato> candidatos) {
    String desc = l.descricao() == null ? "" : l.descricao().toUpperCase(Locale.ROOT);
    List<Pontuacao> out = new ArrayList<>();
    for (Candidato c : candidatos) {
      if (c.pixTxid() != null && desc.contains(c.pixTxid().toUpperCase(Locale.ROOT)) && l.valorCent() <= c.saldoCent()) {
        out.add(new Pontuacao(c.id(), 100, "txid_pix"));
        continue;
      }
      if (c.saldoCent() != l.valorCent()) continue;
      long dias = Math.abs(Datas.diferencaDias(c.dataVencimento(), l.data()));
      if (dias > 45) continue;
      int bonus = dias <= 3 ? 30 : dias <= 10 ? 15 : 0;
      out.add(new Pontuacao(c.id(), 60 + bonus, bonus > 0 ? "valor_exato+data(" + dias + "d)" : "valor_exato"));
    }
    out.sort(Comparator.comparingInt(Pontuacao::score).reversed()); // ordenação estável: empates mantêm a ordem
    return out;
  }

  /** Candidato vencedor para baixa automática, ou null se a decisão for ambígua. */
  public static Pontuacao escolherAutomatico(List<Pontuacao> p) {
    if (p.isEmpty() || p.get(0).score() < LIMIAR_AUTOMATICO) return null;
    if (p.size() > 1 && p.get(1).score() == p.get(0).score()) return null;
    return p.get(0);
  }
}
