'use client';

import { createContext, ReactNode, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { api, ErroApi } from '@/lib/api';
import { ROTULOS, tom } from '@/lib/formato';

// ------------------------------------------------------------------ avisos (toasts)

type Aviso = { id: number; tipo: 'ok' | 'erro'; texto: string };
const AvisosCtx = createContext<{ ok: (t: string) => void; erro: (e: unknown) => void }>({ ok: () => {}, erro: () => {} });

export function AvisosProvider({ children }: { children: ReactNode }) {
  const [avisos, setAvisos] = useState<Aviso[]>([]);
  const seq = useRef(0);
  const add = useCallback((tipo: Aviso['tipo'], texto: string) => {
    const id = ++seq.current;
    setAvisos((a) => [...a, { id, tipo, texto }]);
    setTimeout(() => setAvisos((a) => a.filter((x) => x.id !== id)), tipo === 'erro' ? 7000 : 4000);
  }, []);
  const ok = useCallback((t: string) => add('ok', t), [add]);
  const erro = useCallback((e: unknown) => add('erro', e instanceof ErroApi ? e.detalhe : String((e as Error)?.message ?? e)), [add]);
  return (
    <AvisosCtx.Provider value={{ ok, erro }}>
      {children}
      <div className="avisos" role="status" aria-live="polite">
        {avisos.map((a) => (
          <div key={a.id} className={`aviso aviso-${a.tipo}`}>
            {a.texto}
          </div>
        ))}
      </div>
    </AvisosCtx.Provider>
  );
}

export const useAvisos = () => useContext(AvisosCtx);

// ------------------------------------------------------------------ sessão / permissões

export interface Sessao {
  sub: string;
  tenant_id: string;
  permissoes: string[];
  tipo: string;
  nome?: string;
  exp?: number;
}

export const SessaoCtx = createContext<Sessao | null>(null);

export function usePermissao() {
  const s = useContext(SessaoCtx);
  const p = s?.permissoes ?? [];
  const admin = p.includes('financeiro:admin');
  return { sessao: s, admin, escrita: admin || p.includes('financeiro:escrita') };
}

// ------------------------------------------------------------------ dados

/** Carrega um recurso da API e recarrega quando as dependências mudam. */
export function useDados<T>(caminho: string | null, query?: Record<string, unknown>) {
  const [dados, setDados] = useState<T | null>(null);
  const [erro, setErro] = useState<ErroApi | null>(null);
  const [carregando, setCarregando] = useState(true);
  const chave = caminho ? caminho + JSON.stringify(query ?? {}) : null;
  const [versao, setVersao] = useState(0);

  useEffect(() => {
    if (!caminho) return;
    let vivo = true;
    setCarregando(true);
    api<T>(caminho, { query })
      .then((r) => vivo && (setDados(r.data), setErro(null)))
      .catch((e) => vivo && setErro(e instanceof ErroApi ? e : new ErroApi(0, String(e))))
      .finally(() => vivo && setCarregando(false));
    return () => {
      vivo = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chave, versao]);

  return { dados, erro, carregando, recarregar: () => setVersao((v) => v + 1) };
}

/** Executa uma ação de escrita mostrando aviso de sucesso/erro. */
export function useAcao() {
  const avisos = useAvisos();
  const [ocupado, setOcupado] = useState(false);
  const executar = useCallback(
    async <T,>(fn: () => Promise<{ data: T; message: string | null }>, depois?: (d: T) => void) => {
      setOcupado(true);
      try {
        const r = await fn();
        if (r.message) avisos.ok(r.message);
        depois?.(r.data);
        return true;
      } catch (e) {
        avisos.erro(e);
        return false;
      } finally {
        setOcupado(false);
      }
    },
    [avisos],
  );
  return { executar, ocupado };
}

// ------------------------------------------------------------------ componentes

export function Badge({ status, texto }: { status: string; texto?: string }) {
  return <span className={`badge badge-${tom(status)}`}>{texto ?? ROTULOS[status] ?? status}</span>;
}

export function Cabecalho({ titulo, descricao, children }: { titulo: string; descricao?: string; children?: ReactNode }) {
  return (
    <header className="cabecalho">
      <div>
        <h1>{titulo}</h1>
        {descricao && <p className="sutil">{descricao}</p>}
      </div>
      {children && <div className="acoes">{children}</div>}
    </header>
  );
}

export function Modal({ titulo, aberto, onFechar, children, largo }: { titulo: string; aberto: boolean; onFechar: () => void; children: ReactNode; largo?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (aberto && !d.open) d.showModal();
    if (!aberto && d.open) d.close();
  }, [aberto]);
  return (
    <dialog ref={ref} className={`modal ${largo ? 'modal-largo' : ''}`} onClose={onFechar} onCancel={onFechar}>
      <div className="modal-topo">
        <h2>{titulo}</h2>
        <button type="button" className="btn-icone" aria-label="Fechar" onClick={onFechar}>
          ×
        </button>
      </div>
      <div className="modal-corpo">{aberto && children}</div>
    </dialog>
  );
}

export function Campo({ rotulo, children, dica, largo }: { rotulo: string; children: ReactNode; dica?: string; largo?: boolean }) {
  return (
    <label className={`campo ${largo ? 'campo-largo' : ''}`}>
      <span>{rotulo}</span>
      {children}
      {dica && <small className="sutil">{dica}</small>}
    </label>
  );
}

/** Campo de UUID de outro módulo (cliente, contrato, fornecedor…). Enquanto o Core não existe, permite gerar um. */
export function CampoUuid({ rotulo, valor, onChange, obrigatorio }: { rotulo: string; valor: string; onChange: (v: string) => void; obrigatorio?: boolean }) {
  return (
    <Campo rotulo={rotulo} dica="UUID do registro no módulo de origem">
      <div className="linha-input">
        <input value={valor} onChange={(e) => onChange(e.target.value.trim())} required={obrigatorio} pattern="[0-9a-fA-F-]{36}" placeholder="00000000-0000-0000-0000-000000000000" />
        <button type="button" className="btn btn-sec" onClick={() => onChange(crypto.randomUUID())}>
          Gerar
        </button>
      </div>
    </Campo>
  );
}

export function Paginacao({ meta, onPagina }: { meta?: { page: number; total_pages: number; total: number }; onPagina: (p: number) => void }) {
  if (!meta) return null;
  return (
    <div className="paginacao">
      <span className="sutil">
        {meta.total} registro{meta.total === 1 ? '' : 's'}
      </span>
      <div className="acoes">
        <button className="btn btn-sec" disabled={meta.page <= 1} onClick={() => onPagina(meta.page - 1)}>
          Anterior
        </button>
        <span>
          {meta.page} / {Math.max(1, meta.total_pages)}
        </span>
        <button className="btn btn-sec" disabled={meta.page >= meta.total_pages} onClick={() => onPagina(meta.page + 1)}>
          Próxima
        </button>
      </div>
    </div>
  );
}

export function Estado({ carregando, erro, vazio, children }: { carregando: boolean; erro: ErroApi | null; vazio?: boolean; children: ReactNode }) {
  if (erro) return <div className="estado estado-erro">{erro.detalhe}</div>;
  if (carregando && vazio !== false) return <div className="estado sutil">Carregando…</div>;
  if (vazio) return <div className="estado sutil">Nenhum registro encontrado.</div>;
  return <>{children}</>;
}

export function Copiar({ texto, rotulo = 'Copiar' }: { texto: string; rotulo?: string }) {
  const [ok, setOk] = useState(false);
  return (
    <button
      type="button"
      className="btn btn-sec"
      onClick={async () => {
        await navigator.clipboard.writeText(texto);
        setOk(true);
        setTimeout(() => setOk(false), 1500);
      }}
    >
      {ok ? 'Copiado!' : rotulo}
    </button>
  );
}

export function Kpi({ rotulo, valor, detalhe, tom: t }: { rotulo: string; valor: ReactNode; detalhe?: ReactNode; tom?: 'erro' | 'ok' }) {
  return (
    <div className={`kpi ${t ? `kpi-${t}` : ''}`}>
      <span className="sutil">{rotulo}</span>
      <strong>{valor}</strong>
      {detalhe && <small className="sutil">{detalhe}</small>}
    </div>
  );
}
