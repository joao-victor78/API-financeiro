import { existsSync, readFileSync } from 'fs';
import { resolve } from 'path';
import { cookies } from 'next/headers';

/** Mesmo nome lido pelo JwtGuard da API. */
export const COOKIE_TOKEN = 'financeiro_token';
export const API = process.env.FINANCEIRO_API_URL ?? 'http://localhost:3008';

export interface Sessao {
  sub: string;
  tenant_id: string;
  permissoes: string[];
  tipo: string;
  nome?: string;
  exp?: number;
}

/** Lê as claims do JWT sem validar a assinatura — quem valida é a API a cada requisição. */
export function lerClaims(token: string): Sessao | null {
  try {
    const p = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    return {
      sub: p.sub,
      tenant_id: p.tenant_id,
      permissoes: Array.isArray(p.permissoes) ? p.permissoes : [],
      tipo: p.tipo ?? 'usuario',
      nome: p.nome,
      exp: p.exp,
    };
  } catch {
    return null;
  }
}

/**
 * Confere o token na própria API (assinatura RS256, validade, tenant e permissão) e, se aceito,
 * grava-o em cookie httpOnly + SameSite=Strict: o JavaScript da página nunca enxerga o token.
 */
export async function abrirSessao(token: string): Promise<{ ok: true; sessao: Sessao } | { ok: false; status: number; message: string }> {
  if (typeof token !== 'string' || token.split('.').length !== 3) return { ok: false, status: 400, message: 'Token em formato inválido' };
  let r: Response;
  try {
    r = await fetch(`${API}/api/financeiro/contas-receber?limit=1`, { headers: { authorization: `Bearer ${token}` }, cache: 'no-store' });
  } catch {
    return { ok: false, status: 502, message: `API do Financeiro indisponível em ${API}` };
  }
  if (r.status === 401 || r.status === 403) {
    const corpo = await r.json().catch(() => null);
    const msg = r.status === 403 ? 'Usuário sem permissão no módulo Financeiro' : corpo?.message ?? 'Token recusado';
    return { ok: false, status: r.status, message: msg };
  }
  if (!r.ok) return { ok: false, status: 502, message: `API respondeu ${r.status}` };

  const sessao = lerClaims(token)!;
  const maxAge = sessao.exp ? Math.max(60, sessao.exp - Math.floor(Date.now() / 1000)) : 8 * 3600;
  (await cookies()).set(COOKIE_TOKEN, token, {
    httpOnly: true,
    sameSite: 'strict',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge,
  });
  return { ok: true, sessao };
}

// ---------------------------------------------------------------- recursos de desenvolvimento

const caminhoChave = () => resolve(process.cwd(), process.env.DEV_CHAVE_PRIVADA ?? '../keys/auth-private.pem');

/** Login simulado e "banco simulado" só existem fora de produção e com a chave de dev presente. */
export function devDisponivel() {
  return process.env.NODE_ENV !== 'production' && existsSync(caminhoChave());
}

export function chavePrivadaDev() {
  return readFileSync(caminhoChave(), 'utf8');
}
