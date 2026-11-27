package br.com.erp.financeiro.eventos;

/** Chaves de roteamento publicadas e consumidas (contratos/financeiro.asyncapi.yaml, EOR §7). */
public final class TiposDeEvento {

  public static final String EXCHANGE_PROPRIA = "financeiro.eventos";
  public static final String EXCHANGE_PLATAFORMA = "identity.entrada";

  // publicados pelo Financeiro
  public static final String COBRANCA_CRIADA = "financeiro.cobranca.criada";
  public static final String PAGAMENTO_CONFIRMADO = "financeiro.pagamento.confirmado";
  public static final String PAGAMENTO_ATRASADO = "financeiro.pagamento.atrasado";
  public static final String PAGAMENTO_FALHOU = "financeiro.pagamento.falhou";
  // V2: consumo acima do contratado (o CRM cria a oportunidade de upgrade, §41, §70, §105)
  public static final String CONSUMO_EXCEDIDO = "financeiro.consumo.excedido";
  public static final String REAJUSTE_APLICADO = "financeiro.reajuste.aplicado";
  public static final String FECHAMENTO_ENVIADO = "financeiro.fechamento.enviado";

  /** Eventos que os webhooks de saída podem assinar (§91), com o apelido usado no Prompt Mestre. */
  public static final java.util.Map<String, String> ASSINAVEIS = java.util.Map.of(
      COBRANCA_CRIADA, "invoice.created",
      PAGAMENTO_CONFIRMADO, "payment.received",
      PAGAMENTO_ATRASADO, "payment.overdue",
      PAGAMENTO_FALHOU, "payment.failed",
      CONSUMO_EXCEDIDO, "usage.exceeded",
      REAJUSTE_APLICADO, "contract.readjusted",
      FECHAMENTO_ENVIADO, "accounting.period.sent");

  // pedidos à plataforma (contratos/identity.asyncapi.yaml)
  public static final String TIMELINE_REGISTRAR = "identity.timeline.registrar";
  public static final String NOTIFICACAO_CRIAR = "identity.notificacao.criar";
  public static final String EMAIL_ENVIAR = "identity.email.enviar";

  // consumidos do módulo Contratos (Grupo 5 ainda não publicou o AsyncAPI)
  public static final String CONTRATO_ASSINADO = "contratos.contrato.assinado";
  public static final String CONTRATO_CANCELADO = "contratos.contrato.cancelado";

  private TiposDeEvento() {}

  /** Cada módulo publica só na própria exchange; pedidos à plataforma vão para identity.entrada. */
  public static String exchangePara(String routingKey) {
    return routingKey.startsWith("identity.") ? EXCHANGE_PLATAFORMA : EXCHANGE_PROPRIA;
  }
}
