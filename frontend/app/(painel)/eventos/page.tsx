'use client';

import { FormEvent, Fragment, useState } from 'react';
import { Badge, Cabecalho, Campo, Copiar, Estado, Modal, Paginacao, useAcao, useDados, usePermissao } from '@/components/ui';
import { api, Paginado } from '@/lib/api';
import { curto, dataHora, EVENTOS } from '@/lib/formato';

interface Evento {
  id: string;
  tipo: string;
  agregado_id: string;
  payload: Record<string, unknown>;
  status: string;
  tentativas: number;
  proxima_tentativa: string;
  ultimo_erro: string | null;
  entregue_em: string | null;
  created_at: string;
}
interface Assinatura {
  id: string;
  modulo: string;
  url: string;
  eventos: string[];
  ativa: boolean;
  created_at: string;
  segredo?: string;
}

export default function Eventos() {
  const { admin } = usePermissao();
  const [filtro, setFiltro] = useState({ tipo: '', page: 1 });
  const lista = useDados<Paginado<Evento>>('/eventos', { ...filtro, limit: 20 });
  const [aberto, setAberto] = useState<string | null>(null);

  return (
    <>
      <Cabecalho
        titulo="Eventos"
        descricao="Toda mudança relevante vira um evento na mesma transação (transactional outbox) e é entregue aos módulos inscritos, com assinatura HMAC e novas tentativas."
      >
        <button className="btn btn-sec" onClick={lista.recarregar}>
          Atualizar
        </button>
      </Cabecalho>

      <section className="cartao">
        <div className="filtros">
          <Campo rotulo="Tipo">
            <select value={filtro.tipo} onChange={(e) => setFiltro({ tipo: e.target.value, page: 1 })}>
              <option value="">Todos</option>
              {EVENTOS.map((e) => (
                <option key={e} value={e}>
                  {e}
                </option>
              ))}
            </select>
          </Campo>
        </div>
        <Estado carregando={lista.carregando} erro={lista.erro} vazio={lista.dados ? lista.dados.items.length === 0 : undefined}>
          <div className="tabela-scroll">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Evento</th>
                  <th>Registro</th>
                  <th>Criado</th>
                  <th>Status</th>
                  <th className="num">Tentativas</th>
                  <th>Entregue / próxima tentativa</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {lista.dados?.items.map((e) => (
                  <Fragment key={e.id}>
                    <tr>
                      <td className="mono">{e.tipo}</td>
                      <td className="mono" title={e.agregado_id}>
                        {curto(e.agregado_id)}
                      </td>
                      <td>{dataHora(e.created_at)}</td>
                      <td>
                        <Badge status={e.status} />
                      </td>
                      <td className="num">{e.tentativas}</td>
                      <td>
                        {e.entregue_em ? dataHora(e.entregue_em) : e.status === 'pendente' ? dataHora(e.proxima_tentativa) : '—'}
                        {e.ultimo_erro && (
                          <>
                            <br />
                            <small style={{ color: 'var(--erro)' }}>{e.ultimo_erro}</small>
                          </>
                        )}
                      </td>
                      <td className="acoes-linha">
                        <button className="btn btn-sec btn-mini" onClick={() => setAberto(aberto === e.id ? null : e.id)}>
                          {aberto === e.id ? 'Ocultar' : 'Payload'}
                        </button>
                      </td>
                    </tr>
                    {aberto === e.id && (
                      <tr>
                        <td colSpan={7}>
                          <pre className="json">{JSON.stringify(e.payload, null, 2)}</pre>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <Paginacao meta={lista.dados?.meta} onPagina={(page) => setFiltro({ ...filtro, page })} />
        </Estado>
      </section>

      {admin && <Assinaturas />}
    </>
  );
}

function Assinaturas() {
  const lista = useDados<Assinatura[]>('/eventos/assinaturas');
  const { executar, ocupado } = useAcao();
  const [nova, setNova] = useState(false);
  const [criada, setCriada] = useState<Assinatura | null>(null);

  const remover = (a: Assinatura) =>
    confirm(`Remover a assinatura do módulo "${a.modulo}"?`) &&
    executar(async () => (await api(`/eventos/assinaturas/${a.id}`, { method: 'DELETE' }), { data: null, message: 'Assinatura removida' }), lista.recarregar);

  return (
    <section className="cartao">
      <header>
        <div>
          <h2>Módulos inscritos (webhooks)</h2>
          <p className="sutil" style={{ margin: '4px 0 0' }}>
            Ex.: o Core se inscreve em <code>conta_receber.vencida</code> para suspender o acesso do cliente automaticamente.
          </p>
        </div>
        <button className="btn" onClick={() => setNova(true)}>
          Nova assinatura
        </button>
      </header>
      <Estado carregando={lista.carregando} erro={lista.erro} vazio={lista.dados ? lista.dados.length === 0 : undefined}>
        <div className="tabela-scroll">
          <table className="tabela">
            <thead>
              <tr>
                <th>Módulo</th>
                <th>URL</th>
                <th>Eventos</th>
                <th>Criada</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {lista.dados?.map((a) => (
                <tr key={a.id}>
                  <td>
                    <strong>{a.modulo}</strong>
                  </td>
                  <td className="mono">{a.url}</td>
                  <td>
                    {a.eventos.map((e) => (
                      <span key={e} style={{ marginRight: 4, display: 'inline-block', marginBottom: 2 }}>
                        <Badge status="em_aberto" texto={e} />
                      </span>
                    ))}
                  </td>
                  <td>{dataHora(a.created_at)}</td>
                  <td className="acoes-linha">
                    <button className="btn btn-perigo btn-mini" disabled={ocupado} onClick={() => remover(a)}>
                      Remover
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Estado>

      <Modal titulo="Nova assinatura de eventos" aberto={nova} onFechar={() => setNova(false)}>
        <NovaAssinatura
          onSalvo={(a) => {
            setNova(false);
            setCriada(a);
            lista.recarregar();
          }}
        />
      </Modal>

      <Modal titulo="Assinatura criada" aberto={!!criada} onFechar={() => setCriada(null)}>
        {criada?.segredo && (
          <>
            <p>
              Guarde o segredo abaixo no módulo <strong>{criada.modulo}</strong>: ele valida o cabeçalho <code>x-financeiro-assinatura</code> (HMAC-SHA256) de cada entrega.{' '}
              <strong>Ele não será exibido novamente.</strong>
            </p>
            <div className="linha-input">
              <input readOnly value={criada.segredo} className="mono" onFocus={(e) => e.target.select()} />
              <Copiar texto={criada.segredo} />
            </div>
          </>
        )}
      </Modal>
    </section>
  );
}

function NovaAssinatura({ onSalvo }: { onSalvo: (a: Assinatura) => void }) {
  const { executar, ocupado } = useAcao();
  const [modulo, setModulo] = useState('core');
  const [url, setUrl] = useState('');
  const [eventos, setEventos] = useState<string[]>(['cobranca.paga', 'conta_receber.vencida']);
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(() => api<Assinatura>('/eventos/assinaturas', { method: 'POST', body: { modulo, url, eventos } }), onSalvo);
  };
  return (
    <form className="form" onSubmit={salvar}>
      <Campo rotulo="Módulo">
        <input value={modulo} onChange={(e) => setModulo(e.target.value)} required maxLength={60} />
      </Campo>
      <Campo rotulo="URL do webhook" largo>
        <input type="url" value={url} onChange={(e) => setUrl(e.target.value)} required placeholder="http://core:3001/api/core/webhooks/financeiro" />
      </Campo>
      <fieldset className="itens">
        <legend>Eventos</legend>
        {EVENTOS.map((ev) => (
          <label key={ev} className="check" style={{ gridColumn: 'auto' }}>
            <input type="checkbox" checked={eventos.includes(ev)} onChange={(e) => setEventos(e.target.checked ? [...eventos, ev] : eventos.filter((x) => x !== ev))} />
            <code>{ev}</code>
          </label>
        ))}
      </fieldset>
      <div className="form-rodape">
        <button className="btn" disabled={ocupado || eventos.length === 0}>
          Criar assinatura
        </button>
      </div>
    </form>
  );
}
