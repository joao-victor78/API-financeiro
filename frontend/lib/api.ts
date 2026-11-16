'use client';

/** Erro no formato padrão da API: { success:false, data:null, message, errors }. */
export class ErroApi extends Error {
  constructor(
    public status: number,
    message: string,
    public errors: { campo?: string; mensagem: string }[] | null = null,
  ) {
    super(message);
  }
  get detalhe() {
    return [this.message, ...(this.errors ?? []).map((e) => e.mensagem)].join(' — ');
  }
}

export interface Paginado<T> {
  items: T[];
  meta: { page: number; limit: number; total: number; total_pages: number };
}

type Opcoes = { method?: string; body?: unknown; query?: Record<string, unknown>; form?: FormData };

/** Remove campos vazios: a API rejeita (422) propriedades desconhecidas e "" em campos UUID/data. */
export function limpar<T>(obj: T): T {
  if (Array.isArray(obj)) return obj.map(limpar) as T;
  if (obj && typeof obj === 'object') {
    return Object.fromEntries(
      Object.entries(obj as Record<string, unknown>)
        .filter(([, v]) => v !== '' && v !== undefined && v !== null)
        .map(([k, v]) => [k, limpar(v)]),
    ) as T;
  }
  return obj;
}

export function url(caminho: string, query?: Record<string, unknown>) {
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) if (v !== '' && v !== undefined && v !== null) qs.set(k, String(v));
  const s = qs.toString();
  return `/api/financeiro${caminho}${s ? `?${s}` : ''}`;
}

/** Cliente da API. O cookie httpOnly com o JWT vai junto automaticamente (mesma origem). */
export async function api<T = unknown>(caminho: string, op: Opcoes = {}): Promise<{ data: T; message: string | null }> {
  const init: RequestInit = { method: op.method ?? 'GET', credentials: 'same-origin', cache: 'no-store' };
  if (op.form) init.body = op.form;
  else if (op.body !== undefined) {
    init.body = JSON.stringify(limpar(op.body));
    init.headers = { 'content-type': 'application/json' };
  }
  let r: Response;
  try {
    r = await fetch(url(caminho, op.query), init);
  } catch {
    throw new ErroApi(0, 'Não foi possível falar com o servidor');
  }
  if (r.status === 204) return { data: null as T, message: null };
  const corpo = await r.json().catch(() => null);
  if (r.status === 401 && typeof window !== 'undefined') {
    window.location.href = '/entrar?motivo=expirou';
  }
  if (!r.ok || !corpo?.success) {
    throw new ErroApi(r.status, corpo?.message ?? `Erro ${r.status}`, corpo?.errors ?? null);
  }
  return { data: corpo.data as T, message: corpo.message };
}
