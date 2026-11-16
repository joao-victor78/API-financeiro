'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Sessao, SessaoCtx } from '@/components/ui';
import { curto } from '@/lib/formato';

const MENU = [
  { href: '/', rotulo: 'Painel' },
  { href: '/contas-receber', rotulo: 'Contas a receber' },
  { href: '/contas-pagar', rotulo: 'Contas a pagar' },
  { href: '/cobrancas', rotulo: 'Cobranças' },
  { href: '/recorrencias', rotulo: 'Recorrências' },
  { href: '/inadimplencia', rotulo: 'Inadimplência' },
  { href: '/conciliacoes', rotulo: 'Conciliação' },
  { href: '/eventos', rotulo: 'Eventos' },
];

export default function PainelLayout({ children }: { children: React.ReactNode }) {
  const caminho = usePathname();
  const [sessao, setSessao] = useState<Sessao | null>(null);
  const [menuAberto, setMenuAberto] = useState(false);

  useEffect(() => {
    fetch('/sessao', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : Promise.reject()))
      .then((r) => setSessao(r.data))
      .catch(() => (window.location.href = '/entrar'));
  }, []);

  useEffect(() => setMenuAberto(false), [caminho]);

  const sair = async () => {
    await fetch('/sessao', { method: 'DELETE' });
    window.location.href = '/entrar';
  };

  const ativo = (href: string) => (href === '/' ? caminho === '/' : caminho.startsWith(href));

  return (
    <SessaoCtx.Provider value={sessao}>
      <div className="app">
        <aside className={`lateral ${menuAberto ? 'aberta' : ''}`}>
          <div className="marca">
            <span className="marca-icone" aria-hidden>
              ₣
            </span>
            <div>
              <strong>Financeiro</strong>
              <small>/api/financeiro · v0.1</small>
            </div>
            <button className="btn-icone menu-botao" aria-label="Abrir menu" onClick={() => setMenuAberto((v) => !v)}>
              ☰
            </button>
          </div>
          <nav>
            {MENU.map((m) => (
              <Link key={m.href} href={m.href} className={ativo(m.href) ? 'ativo' : ''}>
                {m.rotulo}
              </Link>
            ))}
            <a href="/api/financeiro/docs" target="_blank" rel="noreferrer">
              Documentação da API ↗
            </a>
          </nav>
          {sessao && (
            <div className="usuario">
              <strong>{sessao.nome ?? 'Usuário'}</strong>
              <small title={sessao.tenant_id}>Tenant {curto(sessao.tenant_id)}</small>
              <small>{sessao.permissoes.map((p) => p.replace('financeiro:', '')).join(', ') || 'sem permissões'}</small>
              <button className="btn btn-sec" onClick={sair}>
                Sair
              </button>
            </div>
          )}
        </aside>
        <main className="conteudo">{sessao ? children : <div className="estado sutil">Carregando sessão…</div>}</main>
      </div>
    </SessaoCtx.Provider>
  );
}
