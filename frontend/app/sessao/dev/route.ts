import { randomUUID } from 'crypto';
import jwt from 'jsonwebtoken';
import { NextResponse } from 'next/server';
import { abrirSessao, chavePrivadaDev, devDisponivel } from '@/lib/sessao-servidor';

export const dynamic = 'force-dynamic';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PERFIS: Record<string, string[]> = {
  admin: ['financeiro:admin'],
  operador: ['financeiro:leitura', 'financeiro:escrita'],
  leitura: ['financeiro:leitura'],
};

export async function GET() {
  return NextResponse.json({ success: true, data: { disponivel: devDisponivel() } });
}

/**
 * DESENVOLVIMENTO: faz o papel do módulo de Autenticação, emitindo um JWT RS256 com a chave
 * gerada por "mvn -q compile exec:java@chaves" na API — o mesmo que a ferramenta EmitirToken faz no terminal.
 */
export async function POST(req: Request) {
  if (!devDisponivel()) return NextResponse.json({ success: false, data: null, message: 'Login de desenvolvimento desativado' }, { status: 404 });
  const corpo = await req.json().catch(() => ({}));
  const tenant = String(corpo?.tenant_id ?? '');
  if (!UUID.test(tenant)) return NextResponse.json({ success: false, data: null, message: 'tenant_id deve ser um UUID' }, { status: 422 });
  const permissoes = PERFIS[corpo?.perfil] ?? PERFIS.admin;
  const token = jwt.sign(
    { tenant_id: tenant, tipo: 'usuario', permissoes, nome: String(corpo?.nome || 'Usuário de demonstração').slice(0, 60) },
    chavePrivadaDev(),
    { algorithm: 'RS256', subject: randomUUID(), issuer: 'auth', expiresIn: '8h' },
  );
  const r = await abrirSessao(token);
  if (!r.ok) return NextResponse.json({ success: false, data: null, message: r.message }, { status: r.status });
  return NextResponse.json({ success: true, data: r.sessao });
}
