package br.com.erp.financeiro.pix;

import static br.com.erp.financeiro.comum.Dinheiro.centavos;

import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.enums.FormaPagamento;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.contasreceber.ContaReceber;
import br.com.erp.financeiro.contasreceber.ContaReceberRepository;
import br.com.erp.financeiro.contasreceber.ContasReceberService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Baixa automática: o banco (PSP) avisa que um Pix foi pago e a conta é quitada sem operador. */
@Service
public class PixService {

  private static final Logger log = LoggerFactory.getLogger("Pix");

  private final ContaReceberRepository contas;
  private final PixRecebimentoRepository recebimentos;
  private final ContasReceberService receber;
  private final TransactionTemplate tx;

  public PixService(ContaReceberRepository contas, PixRecebimentoRepository recebimentos, ContasReceberService receber, TransactionTemplate tx) {
    this.contas = contas;
    this.recebimentos = recebimentos;
    this.receber = receber;
    this.tx = tx;
  }

  /** resultado: baixado | duplicado | txid_desconhecido | rejeitado */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Resultado(@JsonProperty("endToEndId") String endToEndId, String resultado, String detalhe) {}

  public record Processamento(int recebidos, List<Resultado> resultados) {}

  /** Formato do callback da API Pix (BCB): { "pix": [ { endToEndId, txid, valor, horario } ] } */
  public Processamento processarNotificacao(JsonNode corpo) {
    JsonNode lista = corpo == null ? null : corpo.get("pix");
    if (lista == null || !lista.isArray() || lista.isEmpty()) throw ApiException.invalido("Corpo deve conter o array \"pix\"");
    List<Resultado> resultados = new ArrayList<>();
    for (JsonNode p : lista) {
      String e2e = texto(p, "endToEndId");
      String txid = texto(p, "txid");
      String valor = texto(p, "valor");
      String horario = texto(p, "horario");
      if (e2e == null || txid == null || valor == null || horario == null) {
        resultados.add(new Resultado(e2e == null ? "?" : e2e, "rejeitado", "campos obrigatórios ausentes"));
        continue;
      }
      try {
        resultados.add(processarUm(e2e, txid, new BigDecimal(valor), Instant.parse(horario)));
      } catch (NumberFormatException | java.time.format.DateTimeParseException ex) {
        resultados.add(new Resultado(e2e, "rejeitado", "valor ou horário em formato inválido"));
      }
    }
    return new Processamento(lista.size(), resultados);
  }

  private static String texto(JsonNode n, String campo) {
    JsonNode v = n == null ? null : n.get(campo);
    return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
  }

  /** Cada Pix em sua própria transação: um item ruim não desfaz os outros do lote. */
  private Resultado processarUm(String e2e, String txid, BigDecimal valor, Instant horario) {
    return tx.execute(status -> {
      if (recebimentos.existsByEndToEndId(e2e)) return new Resultado(e2e, "duplicado", null);
      ContaReceber conta = contas.travarPorTxid(txid).orElse(null);
      if (conta == null) {
        log.warn("Pix {} com txid desconhecido {}", e2e, txid);
        return new Resultado(e2e, "txid_desconhecido", null);
      }
      LocalDate data = LocalDate.ofInstant(horario, Relogio.BRASILIA);
      try {
        receber.registrarRecebimento(conta, centavos(valor), data, FormaPagamento.PIX, ContasReceberService.OrigemBaixa.PIX_WEBHOOK, null);
      } catch (ApiException ex) {
        return new Resultado(e2e, "rejeitado", ex.getMessage());
      }
      PixRecebimento r = new PixRecebimento();
      r.setTenantId(conta.getTenantId());
      r.setEndToEndId(e2e);
      r.setTxid(txid);
      r.setValor(valor);
      r.setHorario(horario);
      r.setContaReceberId(conta.getId());
      recebimentos.save(r);
      return new Resultado(e2e, "baixado", null);
    });
  }
}
