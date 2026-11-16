import { createHmac, randomBytes } from 'crypto';
import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { API, COOKIE_TOKEN, devDisponivel } from '@/lib/sessao-servidor';

export const dynamic = 'force-dynamic';

/**
 * DESENVOLVIMENTO: simula o PSP (banco) avisando que um Pix foi liquidado. Monta a notificação no
 * formato da API Pix do BCB e a assina com HMAC-SHA256, exatamente como o webhook real espera.
 * A partir daqui a baixa é 100% automática (máquina-a-máquina).
 */
export async function POST(req: Request) {
  if (!devDisponivel()) return NextResponse.json({ success: false, data: null, message: 'Simulação desativada' }, { status: 404 });
  if (!(await cookies()).get(COOKIE_TOKEN)) return NextResponse.json({ success: false, data: null, message: 'Sem sessão' }, { status: 401 });
  const corpo = await req.json().catch(() => ({}));
  const valor = Number(corpo?.valor);
  if (typeof corpo?.txid !== 'string' || !corpo.txid || !(valor > 0)) {
    return NextResponse.json({ success: false, data: null, message: 'Informe txid e valor' }, { status: 422 });
  }
  const endToEndId = ('E' + randomBytes(16).toString('hex')).toUpperCase().slice(0, 32);
  const notificacao = JSON.stringify({
    pix: [{ endToEndId, txid: corpo.txid, valor: valor.toFixed(2), horario: new Date().toISOString(), infoPagador: 'Pagamento simulado' }],
  });
  const segredo = process.env.DEV_PIX_WEBHOOK_SECRET ?? 'troque-este-segredo';
  const r = await fetch(`${API}/api/financeiro/webhooks/pix`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-pix-assinatura': 'sha256=' + createHmac('sha256', segredo).update(notificacao).digest('hex') },
    body: notificacao,
  }).catch(() => null);
  if (!r) return NextResponse.json({ success: false, data: null, message: 'API indisponível' }, { status: 502 });
  return NextResponse.json(await r.json(), { status: r.status });
}
