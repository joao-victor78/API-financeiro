import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { abrirSessao, COOKIE_TOKEN, lerClaims } from '@/lib/sessao-servidor';

export const dynamic = 'force-dynamic';

/** Quem está logado (claims do token), sem expor o token em si. */
export async function GET() {
  const token = (await cookies()).get(COOKIE_TOKEN)?.value;
  const sessao = token ? lerClaims(token) : null;
  if (!sessao) return NextResponse.json({ success: false, data: null, message: 'Sem sessão' }, { status: 401 });
  return NextResponse.json({ success: true, data: sessao });
}

/** Recebe o JWT emitido pelo módulo de Autenticação (via postMessage do sistema principal) e abre a sessão. */
export async function POST(req: Request) {
  const corpo = await req.json().catch(() => ({}));
  const r = await abrirSessao(corpo?.token);
  if (!r.ok) return NextResponse.json({ success: false, data: null, message: r.message }, { status: r.status });
  return NextResponse.json({ success: true, data: r.sessao });
}

export async function DELETE() {
  (await cookies()).delete(COOKIE_TOKEN);
  return new NextResponse(null, { status: 204 });
}
