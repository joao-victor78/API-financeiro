/**
 * Popula a API com dados FICTÍCIOS para demonstração: 5 clientes com contratos recorrentes e
 * 4 meses de histórico, pagamentos por Pix e boleto, um pagamento parcial, inadimplência com
 * promessa, contas a pagar e um extrato OFX conciliado.
 *
 * Usa a API em execução (npm run dev), como um cliente qualquer faria: nada é gravado direto no banco.
 *
 * Uso: npm run popular            (tenant de demonstração do frontend)
 *      npm run popular -- <tenant_uuid>
 */
import { createHmac, randomUUID } from 'crypto';
import { config } from '../src/config';
import { emitirToken } from './emitir-token';

const TENANT = process.argv.find((a) => /^[0-9a-f-]{36}$/i.test(a)) ?? '17869f5d-a221-42c8-95f9-2dd6d4659d4b';
const BASE = process.env.API_URL ?? `http://localhost:${config.port}/api/financeiro`;
const TOKEN = emitirToken({ tenant_id: TENANT, permissoes: ['financeiro:admin'], expiresIn: '10m' });

// ---------- datas relativas a hoje (o histórico sempre termina "agora") ----------
const iso = (d: Date) => d.toISOString().slice(0, 10);
const HOJE = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(new Date());
const [ANO, MES] = HOJE.split('-').map(Number);
/** Competência AAAA-MM deslocada em `n` meses a partir do mês atual. */
const comp = (n: number) => iso(new Date(Date.UTC(ANO, MES - 1 + n, 1))).slice(0, 7);
const dia = (n: number, d: number) => `${comp(n)}-${String(d).padStart(2, '0')}`;
const somarDias = (data: string, n: number) => iso(new Date(Date.parse(`${data}T12:00:00Z`) + n * 864e5));
const noPassado = (data: string) => (data < HOJE ? data : somarDias(HOJE, -1));

// ---------- cliente HTTP ----------
async function chamar<T = any>(metodo: string, rota: string, corpo?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const r = await fetch(BASE + rota, {
    method: metodo,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json', ...headers },
    body: corpo === undefined ? undefined : typeof corpo === 'string' ? corpo : JSON.stringify(corpo),
  });
  const j: any = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`${metodo} ${rota} → ${r.status}: ${j.message ?? ''} ${JSON.stringify(j.errors ?? '')}`);
  return j.data;
}
const get = <T = any>(rota: string) => chamar<T>('GET', rota);
const post = <T = any>(rota: string, corpo: unknown = {}) => chamar<T>('POST', rota, corpo);

let seqE2E = 0;
async function pixPago(txid: string, valor: number, data: string) {
  const corpo = JSON.stringify({
    pix: [{ endToEndId: `E00000000${data.replace(/-/g, '')}1200demo${String(++seqE2E).padStart(6, '0')}`, txid, valor: valor.toFixed(2), horario: `${data}T15:00:00Z` }],
  });
  const assinatura = 'sha256=' + createHmac('sha256', config.pix.webhookSecret).update(corpo).digest('hex');
  return chamar('POST', '/webhooks/pix', corpo, { 'x-pix-assinatura': assinatura });
}
const baixa = (id: string, valor: number, data: string, forma: string) =>
  post(`/contas-receber/${id}/baixa`, { valor, data_pagamento: noPassado(data), forma_pagamento: forma });

const brl = (v: number) => v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const log = (t: string) => console.log(`  ✓ ${t}`);

// ---------- dados fictícios ----------
const LICENCA_PRO = { tipo: 'servico', descricao: 'Licença ERP — plano Pro', quantidade: 1, valor_unitario: 249.9 };
const LICENCA_BASICO = { tipo: 'servico', descricao: 'Licença ERP — plano Básico', quantidade: 1, valor_unitario: 149.9 };
const usuarios = (n: number) => ({ tipo: 'consumo', descricao: 'Usuários adicionais', quantidade: n, valor_unitario: 15 });

type Situacao = 'pago' | 'pago_pix' | 'parcial' | 'aberto' | 'ofx' | 'atrasado';
const CLIENTES: { nome: string; plano: string; dia: number; forma: 'pix' | 'boleto'; itens: object[]; historico: Situacao[] }[] = [
  // histórico = situação de cada mês, do mais antigo (-3) ao atual (0)
  { nome: 'Padaria Pão Dourado', plano: 'Plano Pro', dia: 5, forma: 'pix', itens: [LICENCA_PRO, usuarios(4)], historico: ['pago', 'pago', 'pago_pix', 'pago_pix'] },
  { nome: 'Clínica Sorriso Feliz', plano: 'Plano Básico', dia: 10, forma: 'boleto', itens: [LICENCA_BASICO], historico: ['pago', 'pago', 'parcial', 'ofx'] },
  { nome: 'Auto Peças Goiás', plano: 'Plano Pro', dia: 20, forma: 'pix', itens: [LICENCA_PRO, usuarios(2)], historico: ['pago', 'pago_pix', 'pago_pix', 'aberto'] },
  {
    nome: 'Mercado Bom Preço',
    plano: 'Plano Enterprise',
    dia: 25,
    forma: 'boleto',
    itens: [{ tipo: 'servico', descricao: 'Licença ERP — plano Enterprise', quantidade: 1, valor_unitario: 899 }, { tipo: 'servico', descricao: 'Suporte 24h', quantidade: 1, valor_unitario: 150 }, usuarios(10)],
    historico: ['pago', 'pago', 'pago', 'aberto'],
  },
  { nome: 'Academia Corpo em Forma', plano: 'Plano Básico', dia: 15, forma: 'pix', itens: [LICENCA_BASICO, usuarios(1)], historico: ['pago', 'atrasado', 'atrasado', 'atrasado'] },
];

const FORNECEDORES = {
  imobiliaria: randomUUID(), energia: randomUUID(), internet: randomUUID(), nuvem: randomUUID(), contador: randomUUID(), anuncios: randomUUID(),
};
const CENTRO = { administrativo: randomUUID(), tecnologia: randomUUID(), comercial: randomUUID() };
const CATEGORIA = { aluguel: randomUUID(), utilidades: randomUUID(), infraestrutura: randomUUID(), contabilidade: randomUUID(), marketing: randomUUID() };

async function main() {
  console.log(`\nPopulando dados fictícios em ${BASE}`);
  console.log(`Tenant ${TENANT} — hoje ${HOJE}\n`);

  try {
    await get('/health');
  } catch {
    throw new Error('API fora do ar. Suba a API com "npm run dev" e rode este comando de novo.');
  }
  const existentes = await get('/contas-receber?limit=1');
  if (existentes.meta.total > 0 && !process.argv.includes('--forcar')) {
    console.log('Este tenant já tem dados — nada foi alterado.');
    console.log('Para popular outro tenant: npm run popular -- <uuid>   (ou acrescente --forcar para duplicar)');
    return;
  }

  // 1. Contratos recorrentes + 4 meses de cobranças geradas pela automação
  console.log('Clientes e contratos recorrentes');
  const ofxCreditos: { data: string; valor: number; memo: string }[] = [];
  let atrasadaMaisAntiga: { id: string; valor: number } | null = null;

  for (const c of CLIENTES) {
    const r = await post('/recorrencias', {
      cliente_id: randomUUID(),
      contrato_id: randomUUID(),
      descricao: `${c.plano} — ${c.nome}`,
      periodicidade: 'mensal',
      dia_vencimento: c.dia,
      dias_antecedencia: 10,
      data_inicio: dia(-3, 1),
      forma_pagamento: c.forma,
      aprovacao_automatica: true,
      itens: c.itens,
    });

    for (let i = 0; i < c.historico.length; i++) {
      const cob = await post(`/recorrencias/${r.id}/gerar`);
      const contas = await get(`/contas-receber?cliente_id=${r.cliente_id}&competencia=${cob.competencia}`);
      const conta = contas.items.find((x: any) => x.cobranca_id === cob.id);
      const venc: string = conta.data_vencimento;
      const valor: number = conta.valor;

      switch (c.historico[i]) {
        case 'pago':
          await baixa(conta.id, valor, somarDias(venc, -(i % 3)), c.forma);
          break;
        case 'pago_pix':
          await pixPago(conta.pix_txid, valor, noPassado(somarDias(venc, -1)));
          ofxCreditos.push({ data: noPassado(somarDias(venc, -1)), valor, memo: `PIX RECEBIDO ${conta.pix_txid}` });
          break;
        case 'parcial':
          await baixa(conta.id, 100, somarDias(venc, 2), c.forma);
          break;
        case 'ofx':
          ofxCreditos.push({ data: noPassado(somarDias(venc, 1)), valor, memo: 'LIQUIDACAO BOLETO COBRANCA' });
          break;
        case 'atrasado':
          atrasadaMaisAntiga ??= { id: conta.id, valor };
          break;
      }
    }
    log(`${c.nome} — ${brl(r.valor_ciclo)}/mês (${c.forma}, vence dia ${c.dia}), 4 competências geradas`);
  }

  // 2. Recebimentos avulsos
  console.log('\nRecebimentos avulsos');
  const implantacao = await post('/contas-receber', {
    cliente_id: randomUUID(), descricao: 'Implantação e treinamento — Mercado Bom Preço', competencia: comp(-3), data_vencimento: dia(-3, 15), valor: 2500, forma_pagamento: 'transferencia',
  });
  await baixa(implantacao.id, 2500, dia(-3, 14), 'transferencia');
  log('Implantação e treinamento — R$ 2.500,00 (recebida)');
  await post('/contas-receber', {
    cliente_id: randomUUID(), descricao: 'Consultoria fiscal — Padaria Pão Dourado', competencia: comp(0), data_vencimento: somarDias(HOJE, 10), valor: 600, forma_pagamento: 'transferencia',
  });
  log('Consultoria fiscal — R$ 600,00 (a vencer)');

  // 3. Cobranças manuais em outros estados (rascunho e cancelada)
  console.log('\nCobranças manuais');
  await post('/cobrancas', {
    cliente_id: randomUUID(), competencia: comp(0), descricao: 'Customização de relatórios — Auto Peças Goiás', data_vencimento: somarDias(HOJE, 15), forma_pagamento: 'pix',
    itens: [{ tipo: 'extra', descricao: 'Horas de desenvolvimento', quantidade: 6, valor_unitario: 120 }],
  });
  log('Customização de relatórios — rascunho aguardando aprovação');
  const visita = await post('/cobrancas', {
    cliente_id: randomUUID(), competencia: comp(-1), descricao: 'Visita técnica — Clínica Sorriso Feliz', data_vencimento: dia(-1, 28), forma_pagamento: 'boleto',
    itens: [{ tipo: 'extra', descricao: 'Visita técnica presencial', quantidade: 1, valor_unitario: 180 }],
  });
  await post(`/cobrancas/${visita.id}/cancelar`, { motivo: 'Atendimento resolvido remotamente, sem custo para o cliente' });
  log('Visita técnica — cancelada com motivo');

  // 4. Contas a pagar (3 meses)
  console.log('\nContas a pagar');
  const despesas = [
    { nome: 'Aluguel da sala comercial', forn: FORNECEDORES.imobiliaria, cc: CENTRO.administrativo, cat: CATEGORIA.aluguel, dia: 5, valores: [3200, 3200, 3200], forma: 'boleto', pagaAtual: true },
    { nome: 'Hospedagem em nuvem (servidores)', forn: FORNECEDORES.nuvem, cc: CENTRO.tecnologia, cat: CATEGORIA.infraestrutura, dia: 8, valores: [1124.37, 1180.92, 1215.4], forma: 'cartao_credito', pagaAtual: true },
    { nome: 'Honorários contábeis', forn: FORNECEDORES.contador, cc: CENTRO.administrativo, cat: CATEGORIA.contabilidade, dia: 10, valores: [850, 850, 850], forma: 'pix', pagaAtual: true },
    { nome: 'Energia elétrica', forn: FORNECEDORES.energia, cc: CENTRO.administrativo, cat: CATEGORIA.utilidades, dia: 12, valores: [512.8, 488.15, 503.62], forma: 'boleto', pagaAtual: false },
    { nome: 'Internet fibra 1 Gbps', forn: FORNECEDORES.internet, cc: CENTRO.tecnologia, cat: CATEGORIA.utilidades, dia: 22, valores: [199.9, 199.9, 199.9], forma: 'boleto', pagaAtual: false },
    { nome: 'Anúncios online', forn: FORNECEDORES.anuncios, cc: CENTRO.comercial, cat: CATEGORIA.marketing, dia: 28, valores: [600, 750, 800], forma: 'cartao_credito', pagaAtual: false },
  ];
  let nf = 4810;
  for (const d of despesas) {
    for (let n = -2; n <= 0; n++) {
      const valor = d.valores[n + 2];
      const conta = await post('/contas-pagar', {
        fornecedor_id: d.forn, centro_custo_id: d.cc, categoria_id: d.cat, descricao: `${d.nome} — ${comp(n).slice(5)}/${comp(n).slice(0, 4)}`,
        documento: `NF ${nf++}`, competencia: comp(n), data_vencimento: dia(n, d.dia), valor, forma_pagamento: d.forma,
      });
      if (n < 0 || d.pagaAtual) await post(`/contas-pagar/${conta.id}/pagamento`, { valor, data_pagamento: noPassado(somarDias(dia(n, d.dia), -1)), forma_pagamento: d.forma });
    }
    log(`${d.nome} — 3 meses`);
  }

  // 5. Extrato OFX do mês: concilia o boleto da Clínica, vincula os Pix já baixados e deixa pendências
  console.log('\nConciliação bancária (OFX)');
  const transacoes = [
    ...ofxCreditos.filter((t) => t.data >= dia(0, 1)),
    { data: noPassado(dia(0, 3)), valor: -19.9, memo: 'TARIFA PACOTE DE SERVICOS' },
    { data: noPassado(dia(0, 9)), valor: 75, memo: 'PIX RECEBIDO JOSE DA SILVA' },
  ].sort((a, b) => a.data.localeCompare(b.data));
  const ofx =
    'OFXHEADER:100\nDATA:OFXSGML\nVERSION:102\nCHARSET:1252\n\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKACCTFROM><BANKID>0001<ACCTID>12345-6</BANKACCTFROM><BANKTRANLIST>\n' +
    transacoes
      .map((t, i) => `<STMTTRN><TRNTYPE>${t.valor > 0 ? 'CREDIT' : 'DEBIT'}<DTPOSTED>${t.data.replace(/-/g, '')}<TRNAMT>${t.valor.toFixed(2)}<FITID>DEMO${comp(0).replace('-', '')}${i + 1}<MEMO>${t.memo}</STMTTRN>`)
      .join('\n') +
    '\n</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>';
  const imp = await post('/conciliacoes/importar', { nome_arquivo: `extrato-${comp(0)}.ofx`, conteudo: ofx });
  log(`${imp.total_transacoes} transações: ${imp.conciliadas_automaticamente} conciliadas automaticamente, ${imp.pendentes} pendentes`);

  // 6. Inadimplência: promessa de pagamento da Academia
  console.log('\nInadimplência');
  if (atrasadaMaisAntiga) {
    await post('/inadimplencia/promessas', {
      conta_receber_id: atrasadaMaisAntiga.id, data_prometida: somarDias(HOJE, 5), valor: atrasadaMaisAntiga.valor,
      observacao: 'Cliente ligou: paga a mensalidade mais antiga na sexta e negocia o restante.',
    });
    log('Promessa de pagamento registrada (Academia Corpo em Forma)');
  }

  // 7. Roda os jobs como o agendador faria
  await post('/automacao/executar/vencimentos');
  await post('/automacao/executar/eventos');
  const res = await get('/inadimplencia/resumo');
  log(`Vencido: ${brl(res.total_vencido)} (com encargos ${brl(res.total_vencido_atualizado)}), taxa ${res.taxa_inadimplencia_percentual}%`);

  console.log('\nPronto! Abra http://localhost:3000 e entre com o tenant acima.\n');
}

main().catch((e) => {
  console.error(`\nErro: ${e.message}\n`);
  process.exit(1);
});
