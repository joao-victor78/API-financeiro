package br.com.erp.financeiro.eventos;

import br.com.erp.financeiro.cobrancas.MeioPagamento;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.recorrencias.Periodicidade;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.ItemEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaDtos.RegraEntrada;
import br.com.erp.financeiro.recorrencias.RecorrenciaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Efeito dos eventos do módulo Contratos, idempotente pelo id do envelope (RNF-03, RF-03 critério 2).
 * O Grupo 5 ainda não publicou o AsyncAPI; o formato esperado de "dados" está em contratos/financeiro.asyncapi.yaml
 * (seção "consumidos"): contratoId, empresaId e itens [{servicoId, descricao, quantidade, unidade, valorUnitario}].
 */
@Service
public class ProcessadorContratos {

  private static final Logger log = LoggerFactory.getLogger(ProcessadorContratos.class);

  private final EventosProcessados processados;
  private final RecorrenciaService recorrencias;
  private final Relogio relogio;

  public ProcessadorContratos(EventosProcessados processados, RecorrenciaService recorrencias, Relogio relogio) {
    this.processados = processados;
    this.recorrencias = recorrencias;
    this.relogio = relogio;
  }

  @Transactional
  public void contratoAssinado(Evento e) {
    if (!processados.registrar(e.id(), e.tipo(), e.moduloOrigem())) {
      log.info("Evento {} ({}) já processado; ignorado.", e.tipo(), e.id());
      return;
    }
    Map<String, Object> d = e.dados();
    UUID contrato = uuid(d.get("contratoId"));
    UUID empresa = uuid(d.getOrDefault("empresaId", d.get("clienteId")));
    List<ItemEntrada> itens = itens(d.get("itens"));
    if (contrato == null || empresa == null || itens.isEmpty()) {
      log.warn("Contrato assinado {} sem contratoId, empresaId ou itens; nenhuma regra criada.", e.id());
      return;
    }
    // V2: vendedor, SDR e parceiro (comissões, §71) e índice/mês de reajuste (§69), quando o contrato informa.
    Integer mes = inteiro(d.get("mesReajuste"));
    RegraEntrada regra = new RegraEntrada(contrato, empresa, texto(d.get("descricao")),
        enumOu(Periodicidade.class, d.get("periodicidade"), Periodicidade.MENSAL),
        inteiro(d.get("diaGeracao")), null, enumOu(MeioPagamento.class, d.get("formaPagamento"), null), true, itens,
        uuid(d.get("vendedorId")), uuid(d.get("sdrId")), uuid(d.get("parceiroId")), null, null,
        enumOu(br.com.erp.financeiro.recorrencias.IndiceReajuste.class, d.get("indiceReajuste"), null),
        mes == null ? null : Math.min(12, mes));
    if (!recorrencias.criarDeContrato(contrato, regra)) log.info("Contrato {} já tem regra de recorrência.", contrato);
  }

  @Transactional
  public void contratoCancelado(Evento e) {
    if (!processados.registrar(e.id(), e.tipo(), e.moduloOrigem())) return;
    UUID contrato = uuid(e.dados().get("contratoId"));
    if (contrato == null) return;
    LocalDate quando = data(e.dados().get("canceladoEm"));
    recorrencias.encerrarDeContrato(contrato, quando != null ? quando : relogio.hoje());
  }

  private static List<ItemEntrada> itens(Object valor) {
    List<ItemEntrada> itens = new ArrayList<>();
    if (!(valor instanceof List<?> lista)) return itens;
    for (Object o : lista) {
      if (!(o instanceof Map<?, ?> m)) continue;
      UUID servico = uuid(m.get("servicoId"));
      BigDecimal qtd = decimal(m.get("quantidade"));
      BigDecimal unit = decimal(m.get("valorUnitario"));
      if (servico == null || qtd == null || unit == null) continue;
      String descricao = texto(m.get("descricao"));
      String unidade = texto(m.get("unidade"));
      BigDecimal excedente = decimal(m.get("valorExcedente"));
      BigDecimal custo = decimal(m.get("custoUnitario"));
      itens.add(new ItemEntrada(servico, descricao != null ? descricao : "Serviço", qtd,
          unidade != null ? unidade : "UN", unit.setScale(2, java.math.RoundingMode.HALF_EVEN), decimal(m.get("franquia")),
          excedente == null ? null : excedente.setScale(2, java.math.RoundingMode.HALF_EVEN),
          custo == null ? null : custo.setScale(2, java.math.RoundingMode.HALF_EVEN), texto(m.get("fabricante"))));
    }
    return itens;
  }

  private static UUID uuid(Object v) {
    try {
      return v == null ? null : UUID.fromString(v.toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static BigDecimal decimal(Object v) {
    try {
      return v == null ? null : new BigDecimal(v.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer inteiro(Object v) {
    BigDecimal d = decimal(v);
    return d == null ? null : Math.max(1, Math.min(28, d.intValue()));
  }

  private static String texto(Object v) {
    return v == null || v.toString().isBlank() ? null : v.toString();
  }

  private static LocalDate data(Object v) {
    if (v == null) return null;
    String s = v.toString();
    try {
      return s.length() > 10 ? OffsetDateTime.parse(s).toLocalDate() : LocalDate.parse(s);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static <E extends Enum<E>> E enumOu(Class<E> tipo, Object v, E padrao) {
    try {
      return v == null ? padrao : Enum.valueOf(tipo, v.toString().toUpperCase());
    } catch (IllegalArgumentException e) {
      return padrao;
    }
  }
}
