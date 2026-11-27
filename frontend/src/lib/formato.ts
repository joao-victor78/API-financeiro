export const brl = (v: number | null | undefined) => Number(v ?? 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

/** 'AAAA-MM-DD' → 'DD/MM/AAAA' sem passar por Date (evita deslocamento de fuso). */
export const data = (s: string | null | undefined) => {
  if (!s) return '—'
  const [a, m, d] = s.slice(0, 10).split('-')
  return `${d}/${m}/${a}`
}

export const dataHora = (s: string | null | undefined) => (s ? new Date(s).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—')

/** '2026-09-01' ou '2026-09' → '09/2026'. */
export const competencia = (s: string | null | undefined) => {
  if (!s) return '—'
  const [a, m] = s.split('-')
  return `${m}/${a}`
}

export const hoje = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export const mesAtual = () => hoje().slice(0, 7)

export const curto = (id: string | null | undefined) => (id ? id.slice(0, 8) : '—')

export const etapa = (d: number) => (d === 0 ? 'D0' : d > 0 ? `D+${d}` : `D${d}`)

export const ROTULOS: Record<string, string> = {
  ABERTO: 'Aberto',
  AGENDADO: 'Agendado',
  PAGO: 'Pago',
  ATRASADO: 'Atrasado',
  CANCELADO: 'Cancelado',
  ESTORNADO: 'Estornado',
  PARCIAL: 'Parcial',
  PENDENTE: 'Pendente',
  PROCESSANDO: 'Processando',
  CONFIRMADO: 'Confirmado',
  FALHOU: 'Falhou',
  EXPIRADO: 'Expirado',
  APROVADA: 'Aprovada',
  PAGA: 'Paga',
  CANCELADA: 'Cancelada',
  CUMPRIDA: 'Cumprida',
  QUEBRADA: 'Quebrada',
  SOLICITADO: 'Solicitado',
  SEM_DESTINATARIO: 'Sem destinatário',
  CANAL_INDISPONIVEL: 'Canal indisponível',
  ativa: 'Ativa',
  inativa: 'Encerrada',
  // V2
  CONCILIADA: 'Conciliada',
  IGNORADA: 'Ignorada',
  A_PAGAR: 'A pagar',
  LANCADA: 'Lançada',
  AUTORIZADA: 'Autorizada',
  ENTREGUE: 'Entregue',
  NAO_INICIADO: 'Não iniciado',
  EM_CONFERENCIA: 'Em conferência',
  COM_PENDENCIA: 'Com pendência',
  AGUARDANDO_DOCUMENTO: 'Aguardando documento',
  PRONTO: 'Pronto',
  ENVIADO_PARA_CONTABILIDADE: 'Enviado para a contabilidade',
  EM_ANALISE: 'Em análise',
  FINALIZADO: 'Finalizado',
  AUTOMATICA: 'Automática',
  MANUAL: 'Manual',
}

export type VarianteDeBadge = 'green' | 'red' | 'blue' | 'indigo' | 'yellow' | 'gray' | 'purple' | 'orange'

/** Cor do badge por status, no espírito do design system §7 (verde = ok, vermelho = problema). */
export function corDoStatus(status: string): VarianteDeBadge {
  if (['PAGO', 'CONFIRMADO', 'APROVADA', 'PAGA', 'CUMPRIDA', 'SOLICITADO', 'ativa', 'CONCILIADA', 'AUTORIZADA', 'ENTREGUE', 'FINALIZADO', 'LANCADA'].includes(status)) return 'green'
  if (['ATRASADO', 'FALHOU', 'QUEBRADA', 'ESTORNADO', 'COM_PENDENCIA'].includes(status)) return 'red'
  if (['PARCIAL', 'SEM_DESTINATARIO', 'CANAL_INDISPONIVEL', 'AGUARDANDO_DOCUMENTO', 'A_PAGAR'].includes(status)) return 'yellow'
  if (['ABERTO', 'PROCESSANDO', 'EM_CONFERENCIA', 'EM_ANALISE'].includes(status)) return 'blue'
  if (['AGENDADO', 'PENDENTE', 'PRONTO', 'ENVIADO_PARA_CONTABILIDADE'].includes(status)) return 'indigo'
  return 'gray'
}

export const pct = (v: number | null | undefined) => (v === null || v === undefined ? '—' : `${Number(v).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`)

export const numero = (v: number | null | undefined) => (v === null || v === undefined ? '—' : Number(v).toLocaleString('pt-BR', { maximumFractionDigits: 3 }))

export const GRUPOS_DRE: Record<string, string> = {
  RECEITA_BRUTA: 'Receita bruta', DEDUCOES: 'Deduções e impostos sobre receita', CUSTOS: 'Custos dos serviços',
  DESPESAS_OPERACIONAIS: 'Despesas operacionais', DESPESAS_ADMINISTRATIVAS: 'Despesas administrativas',
  DESPESAS_COMERCIAIS: 'Despesas comerciais', RECEITAS_FINANCEIRAS: 'Receitas financeiras', DESPESAS_FINANCEIRAS: 'Despesas financeiras',
  IMPOSTOS_SOBRE_LUCRO: 'IRPJ e CSLL', OUTRAS: 'Outras',
}
export const CLASSIFICACOES: Record<string, string> = {
  PAGAMENTO_RECEBIDO: 'Recebimento', PAGAMENTO_EFETUADO: 'Pagamento', TRANSFERENCIA: 'Transferência entre contas',
  TARIFA: 'Tarifa bancária', JUROS: 'Juros', ESTORNO: 'Estorno', OUTRO: 'Outro',
}
export const INDICES: Record<string, string> = { IPCA: 'IPCA', IGPM: 'IGP-M', PERCENTUAL_FIXO: 'Percentual fixo', OUTRO: 'Outro' }
export const BASES_COMISSAO: Record<string, string> = {
  FIXA: 'Valor fixo (1ª cobrança paga)', PERCENTUAL: '% de cobrança avulsa', SOBRE_PRIMEIRA_MENSALIDADE: '% da 1ª mensalidade',
  SOBRE_MRR: '% do MRR (uma vez)', SOBRE_MARGEM: '% da margem', RECORRENTE: '% recorrente (por N meses)',
}
export const BENEFICIARIOS: Record<string, string> = { VENDEDOR: 'Vendedor', SDR: 'SDR', PARCEIRO: 'Parceiro' }
export const CONTAS_TIPO: Record<string, string> = { CORRENTE: 'Conta corrente', POUPANCA: 'Poupança', PAGAMENTO: 'Conta de pagamento', CAIXA: 'Caixa' }
export const ITENS_FECHAMENTO: Record<string, string> = {
  RECEITAS_CONFERIDAS: 'Receitas conferidas', DESPESAS_CONFERIDAS: 'Despesas conferidas', EXTRATOS_CONFERIDOS: 'Extratos conferidos',
  NOTAS_EMITIDAS: 'Notas emitidas', NOTAS_RECEBIDAS: 'Notas recebidas', COMPROVANTES: 'Comprovantes', COMISSOES: 'Comissões',
  IMPOSTOS: 'Impostos', LANCAMENTOS_CLASSIFICADOS: 'Lançamentos classificados', DOCUMENTACAO_COMPLETA: 'Documentação completa',
}
export const CATEGORIAS_DOCUMENTO: Record<string, string> = {
  COMPROVANTE: 'Comprovante', NOTA_FISCAL: 'Nota fiscal', BOLETO: 'Boleto', EXTRATO: 'Extrato', CONTRATO: 'Contrato',
  RECIBO: 'Recibo', RELATORIO: 'Relatório', OUTRO: 'Outro',
}

export const FORMAS: Record<string, string> = { PIX: 'PIX', BOLETO: 'Boleto', CARTAO: 'Cartão (link)', TRANSFERENCIA: 'Transferência' }
export const PERIODICIDADES: Record<string, string> = { MENSAL: 'Mensal', TRIMESTRAL: 'Trimestral', ANUAL: 'Anual' }
export const CANAIS: Record<string, string> = { EMAIL: 'E-mail', NOTIFICACAO_INTERNA: 'Notificação interna', WHATSAPP: 'WhatsApp (sem canal nesta fase)' }
export const FAIXAS = ['1-5', '6-15', '16-30', '31-60', '61-90', '90+']
