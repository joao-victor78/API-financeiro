export const brl = (v: number | null | undefined) =>
  Number(v ?? 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

/** 'AAAA-MM-DD' → 'DD/MM/AAAA' sem passar por Date (evita deslocamento de fuso). */
export const data = (s: string | null | undefined) => {
  if (!s) return '—';
  const [a, m, d] = s.slice(0, 10).split('-');
  return `${d}/${m}/${a}`;
};

export const dataHora = (s: string | Date | null | undefined) =>
  s ? new Date(s).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—';

export const competencia = (s: string | null | undefined) => {
  if (!s) return '—';
  const [a, m] = s.split('-');
  return `${m}/${a}`;
};

export const hoje = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

export const competenciaAtual = () => hoje().slice(0, 7);

export const curto = (id: string | null | undefined) => (id ? id.slice(0, 8) : '—');

export const ROTULOS: Record<string, string> = {
  em_aberto: 'Em aberto',
  agendada: 'Agendada',
  paga: 'Paga',
  vencida: 'Vencida',
  cancelada: 'Cancelada',
  estornada: 'Estornada',
  parcialmente_paga: 'Parcial',
  rascunho: 'Rascunho',
  aprovada: 'Aprovada',
  pendente: 'Pendente',
  conciliada: 'Conciliada',
  ignorada: 'Ignorada',
  cumprida: 'Cumprida',
  quebrada: 'Quebrada',
  entregue: 'Entregue',
  sem_assinantes: 'Sem assinantes',
  falha: 'Falha',
  ativa: 'Ativa',
  pausada: 'Pausada',
  ok: 'Operacional',
  degradado: 'Degradado',
  credito: 'Crédito',
  debito: 'Débito',
};

export const FORMAS: Record<string, string> = {
  pix: 'Pix',
  boleto: 'Boleto',
  cartao_credito: 'Cartão de crédito',
  cartao_debito: 'Cartão de débito',
  transferencia: 'Transferência',
  dinheiro: 'Dinheiro',
};

export const PERIODICIDADES: Record<string, string> = {
  mensal: 'Mensal',
  trimestral: 'Trimestral',
  semestral: 'Semestral',
  anual: 'Anual',
};

export const FAIXAS_AGING: Record<string, string> = {
  a_vencer: 'A vencer',
  '1_30': '1–30 dias',
  '31_60': '31–60 dias',
  '61_90': '61–90 dias',
  acima_90: '+90 dias',
};

export const EVENTOS = [
  'cobranca.aprovada',
  'cobranca.cancelada',
  'cobranca.paga',
  'conta_receber.paga',
  'conta_receber.vencida',
  'conta_pagar.paga',
  'recorrencia.cobranca_gerada',
  'conciliacao.concluida',
  'promessa_pagamento.quebrada',
];

export const tom = (status: string): 'ok' | 'alerta' | 'erro' | 'info' | 'neutro' => {
  if (['paga', 'conciliada', 'cumprida', 'entregue', 'aprovada', 'ok', 'ativa'].includes(status)) return 'ok';
  if (['vencida', 'quebrada', 'falha', 'degradado'].includes(status)) return 'erro';
  if (['parcialmente_paga', 'pendente', 'agendada', 'rascunho', 'pausada'].includes(status)) return 'alerta';
  if (['em_aberto'].includes(status)) return 'info';
  return 'neutro';
};
