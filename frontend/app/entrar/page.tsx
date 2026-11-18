'use client';

import { FormEvent, useEffect, useState } from 'react';
import { Campo } from '@/components/ui';

const ORIGEM_SISTEMA = process.env.NEXT_PUBLIC_ORIGEM_SISTEMA || '';
const TENANT_DEMO = '17869f5d-a221-42c8-95f9-2dd6d4659d4b';

async function abrir(caminho: string, corpo: unknown) {
  const r = await fetch(caminho, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(corpo) });
  const j = await r.json().catch(() => null);
  if (!r.ok) throw new Error(j?.message ?? `Erro ${r.status}`);
}

/**
 * Entrada do módulo. Em produção, o sistema principal (que hospeda o iframe) envia o JWT emitido
 * pelo módulo de Autenticação via postMessage; o token é trocado por um cookie httpOnly e nunca
 * fica no localStorage.
 */
export default function Entrar() {
  const [erro, setErro] = useState('');
  const [aguardando, setAguardando] = useState(false);
  const [dev, setDev] = useState(false);
  const [tenant, setTenant] = useState(TENANT_DEMO);
  const [perfil, setPerfil] = useState('admin');
  const [nome, setNome] = useState('Usuário de demonstração');
  const [token, setToken] = useState('');
  const [expirou, setExpirou] = useState(false);
  const emIframe = typeof window !== 'undefined' && window.parent !== window;

  const entrar = async (fn: () => Promise<void>) => {
    setErro('');
    setAguardando(true);
    try {
      await fn();
      window.location.href = '/';
    } catch (e) {
      setErro((e as Error).message);
      setAguardando(false);
    }
  };

  useEffect(() => {
    setExpirou(new URLSearchParams(window.location.search).get('motivo') === 'expirou');
    fetch('/sessao/dev')
      .then((r) => r.json())
      .then((r) => setDev(!!r?.data?.disponivel))
      .catch(() => {});

    const origemAceita = ORIGEM_SISTEMA || window.location.origin;
    const aoReceber = (ev: MessageEvent) => {
      if (ev.origin !== origemAceita) return;
      if (ev.data?.type === 'financeiro:token' && typeof ev.data.token === 'string') {
        entrar(() => abrir('/sessao', { token: ev.data.token }));
      }
    };
    window.addEventListener('message', aoReceber);
    // Avisa o sistema principal que o módulo está pronto para receber o token.
    if (window.parent !== window) window.parent.postMessage({ type: 'financeiro:pronto' }, origemAceita);
    return () => window.removeEventListener('message', aoReceber);
  }, []);

  const loginDev = (e: FormEvent) => {
    e.preventDefault();
    entrar(() => abrir('/sessao/dev', { tenant_id: tenant, perfil, nome }));
  };

  const loginToken = (e: FormEvent) => {
    e.preventDefault();
    entrar(() => abrir('/sessao', { token: token.trim().replace(/^Bearer\s+/i, '') }));
  };

  return (
    <div className="entrar">
      <div className="cartao">
        <div className="marca" style={{ border: 0, padding: '0 0 14px' }}>
          <span className="marca-icone" aria-hidden>
            ₣
          </span>
          <div>
            <strong>Módulo Financeiro</strong>
            <small>Sistema integrado de gestão empresarial</small>
          </div>
        </div>

        {expirou && <div className="estado-erro estado" style={{ padding: 10, marginBottom: 12 }}>Sessão expirada. Entre novamente.</div>}
        {emIframe && <p className="sutil">Aguardando a autenticação do sistema principal…</p>}

        {dev && (
          <form className="form" onSubmit={loginDev} style={{ gridTemplateColumns: '1fr' }}>
            <p className="sutil" style={{ margin: 0 }}>
              <strong>Modo desenvolvimento:</strong> simula o módulo de Autenticação emitindo um JWT RS256 com a chave de <code>npm run keys</code>.
            </p>
            <Campo rotulo="Nome">
              <input value={nome} onChange={(e) => setNome(e.target.value)} maxLength={60} />
            </Campo>
            <Campo rotulo="Tenant (organização)" dica="Use o mesmo tenant para reencontrar os dados.">
              <div className="linha-input">
                <input value={tenant} onChange={(e) => setTenant(e.target.value.trim())} required />
                <button type="button" className="btn btn-sec" onClick={() => setTenant(crypto.randomUUID())}>
                  Novo
                </button>
              </div>
            </Campo>
            <Campo rotulo="Perfil">
              <select value={perfil} onChange={(e) => setPerfil(e.target.value)}>
                <option value="admin">Administrador (financeiro:admin)</option>
                <option value="operador">Operador (leitura + escrita)</option>
                <option value="leitura">Somente leitura</option>
              </select>
            </Campo>
            <button className="btn" disabled={aguardando}>
              {aguardando ? 'Entrando…' : 'Entrar'}
            </button>
          </form>
        )}

        <details style={{ marginTop: 16 }} open={!dev && !emIframe}>
          <summary className="sutil" style={{ cursor: 'pointer' }}>
            Entrar com um token JWT
          </summary>
          <form className="form" onSubmit={loginToken} style={{ gridTemplateColumns: '1fr', marginTop: 10 }}>
            <Campo rotulo="Token" dica='Gerado pelo módulo de Autenticação (ou "npm run token" na API).'>
              <textarea rows={4} value={token} onChange={(e) => setToken(e.target.value)} required className="mono" />
            </Campo>
            <button className="btn btn-sec" disabled={aguardando}>
              Entrar com token
            </button>
          </form>
        </details>

        {erro && (
          <div className="estado estado-erro" style={{ padding: 10, marginTop: 12 }}>
            {erro}
          </div>
        )}
      </div>
    </div>
  );
}
