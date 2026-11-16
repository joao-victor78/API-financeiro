'use client';

import { FormEvent, useEffect, useState } from 'react';
import { ItensEditor, ItensResumo, Item, itemVazio, totalItens } from '@/components/ItensEditor';
import { Badge, Cabecalho, Campo, CampoUuid, Copiar, Estado, Modal, Paginacao, useAcao, useAvisos, useDados, usePermissao } from '@/components/ui';
import { api, Paginado } from '@/lib/api';
import { brl, competencia, competenciaAtual, curto, data, dataHora, FORMAS, ROTULOS } from '@/lib/formato';

interface Cobranca {
  id: string;
  cliente_id: string;
  contrato_id: string | null;
  recorrencia_id: string | null;
  competencia: string;
  descricao: string;
  itens: Item[];
  valor_total: number;
  data_vencimento: string;
  forma_pagamento: string;
  status: string;
  pix_txid: string | null;
  pix_copia_cola: string | null;
  aprovada_em: string | null;
  cancelada_em: string | null;
  motivo_cancelamento: string | null;
  created_at: string;
}

const STATUS = ['rascunho', 'aprovada', 'paga', 'cancelada'];

/** Verifica se a simulação do PSP (somente desenvolvimento) está disponível. */
function useDev() {
  const [dev, setDev] = useState(false);
  useEffect(() => {
    fetch('/sessao/dev')
      .then((r) => r.json())
      .then((r) => setDev(!!r?.data?.disponivel))
      .catch(() => {});
  }, []);
  return dev;
}

export default function Cobrancas() {
  const { escrita } = usePermissao();
  const [filtro, setFiltro] = useState({ status: '', competencia: '', page: 1 });
  const lista = useDados<Paginado<Cobranca>>('/cobrancas', { ...filtro, limit: 20 });
  const { executar, ocupado } = useAcao();
  const [nova, setNova] = useState(false);
  const [aberta, setAberta] = useState<string | null>(null);
  const [cancelar, setCancelar] = useState<Cobranca | null>(null);

  const aprovar = (c: Cobranca) => executar(() => api(`/cobrancas/${c.id}/aprovar`, { method: 'POST' }), lista.recarregar);

  return (
    <>
      <Cabecalho titulo="Cobranças" descricao="Rascunho → aprovação (gera conta a receber e Pix) → pagamento. Cancelamento em vez de exclusão.">
        {escrita && (
          <button className="btn" onClick={() => setNova(true)}>
            Nova cobrança
          </button>
        )}
      </Cabecalho>

      <section className="cartao">
        <div className="filtros">
          <Campo rotulo="Status">
            <select value={filtro.status} onChange={(e) => setFiltro({ ...filtro, status: e.target.value, page: 1 })}>
              <option value="">Todos</option>
              {STATUS.map((s) => (
                <option key={s} value={s}>
                  {ROTULOS[s]}
                </option>
              ))}
            </select>
          </Campo>
          <Campo rotulo="Competência">
            <input type="month" value={filtro.competencia} onChange={(e) => setFiltro({ ...filtro, competencia: e.target.value, page: 1 })} />
          </Campo>
        </div>

        <Estado carregando={lista.carregando} erro={lista.erro} vazio={lista.dados ? lista.dados.items.length === 0 : undefined}>
          <div className="tabela-scroll">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Descrição</th>
                  <th>Compet.</th>
                  <th>Vencimento</th>
                  <th className="num">Total</th>
                  <th>Forma</th>
                  <th>Origem</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {lista.dados?.items.map((c) => (
                  <tr key={c.id}>
                    <td>{c.descricao}</td>
                    <td>{competencia(c.competencia)}</td>
                    <td>{data(c.data_vencimento)}</td>
                    <td className="num">{brl(c.valor_total)}</td>
                    <td>{FORMAS[c.forma_pagamento]}</td>
                    <td>{c.recorrencia_id ? <Badge status="em_aberto" texto="Recorrência" /> : <span className="sutil">Manual</span>}</td>
                    <td>
                      <Badge status={c.status} />
                    </td>
                    <td className="acoes-linha">
                      <button className="btn btn-sec btn-mini" onClick={() => setAberta(c.id)}>
                        Detalhes
                      </button>
                      {escrita && c.status === 'rascunho' && (
                        <button className="btn btn-mini" disabled={ocupado} onClick={() => aprovar(c)}>
                          Aprovar
                        </button>
                      )}
                      {escrita && (c.status === 'rascunho' || c.status === 'aprovada') && (
                        <button className="btn btn-perigo btn-mini" onClick={() => setCancelar(c)}>
                          Cancelar
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Paginacao meta={lista.dados?.meta} onPagina={(page) => setFiltro({ ...filtro, page })} />
        </Estado>
      </section>

      <Modal titulo="Nova cobrança" aberto={nova} onFechar={() => setNova(false)} largo>
        <NovaCobranca
          onSalvo={() => {
            setNova(false);
            lista.recarregar();
          }}
        />
      </Modal>

      <Modal titulo="Cobrança" aberto={!!aberta} onFechar={() => setAberta(null)} largo>
        {aberta && <Detalhe id={aberta} onAlterou={lista.recarregar} />}
      </Modal>

      <Modal titulo="Cancelar cobrança" aberto={!!cancelar} onFechar={() => setCancelar(null)}>
        {cancelar && (
          <Cancelamento
            cobranca={cancelar}
            onSalvo={() => {
              setCancelar(null);
              lista.recarregar();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function NovaCobranca({ onSalvo }: { onSalvo: () => void }) {
  const { executar, ocupado } = useAcao();
  const [f, setF] = useState({ cliente_id: '', contrato_id: '', competencia: competenciaAtual(), descricao: '', data_vencimento: '', forma_pagamento: 'pix' });
  const [itens, setItens] = useState<Item[]>([itemVazio()]);
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(() => api('/cobrancas', { method: 'POST', body: { ...f, itens } }), onSalvo);
  };
  return (
    <form className="form" onSubmit={salvar}>
      <CampoUuid rotulo="Cliente" valor={f.cliente_id} onChange={set('cliente_id')} obrigatorio />
      <CampoUuid rotulo="Contrato (opcional)" valor={f.contrato_id} onChange={set('contrato_id')} />
      <Campo rotulo="Descrição" largo>
        <input value={f.descricao} onChange={(e) => set('descricao')(e.target.value)} required maxLength={200} placeholder="Fatura outubro/2026" />
      </Campo>
      <Campo rotulo="Competência">
        <input type="month" value={f.competencia} onChange={(e) => set('competencia')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Vencimento">
        <input type="date" value={f.data_vencimento} onChange={(e) => set('data_vencimento')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Forma de pagamento">
        <select value={f.forma_pagamento} onChange={(e) => set('forma_pagamento')(e.target.value)}>
          {Object.entries(FORMAS).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </select>
      </Campo>
      <ItensEditor itens={itens} onChange={setItens} />
      <div className="form-rodape">
        <span className="sutil" style={{ marginRight: 'auto' }}>
          A cobrança nasce em rascunho. Ao aprovar, o sistema cria a conta a receber e o Pix.
        </span>
        <button className="btn" disabled={ocupado || totalItens(itens) <= 0}>
          Salvar rascunho
        </button>
      </div>
    </form>
  );
}

function Detalhe({ id, onAlterou }: { id: string; onAlterou: () => void }) {
  const c = useDados<Cobranca>(`/cobrancas/${id}`);
  const dev = useDev();
  const avisos = useAvisos();
  const { escrita } = usePermissao();
  const [simulando, setSimulando] = useState(false);

  const simularPix = async () => {
    if (!c.dados?.pix_txid) return;
    setSimulando(true);
    try {
      const r = await fetch('/dev/pix', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ txid: c.dados.pix_txid, valor: c.dados.valor_total }),
      });
      const j = await r.json();
      const res = j?.data?.resultados?.[0]?.resultado;
      if (!r.ok || res !== 'baixado') throw new Error(j?.message ?? `Resultado: ${res ?? r.status}`);
      avisos.ok('O banco notificou o Pix (webhook HMAC) e o sistema baixou o título sozinho.');
      c.recarregar();
      onAlterou();
    } catch (e) {
      avisos.erro(e);
    } finally {
      setSimulando(false);
    }
  };

  return (
    <Estado carregando={c.carregando} erro={c.erro} vazio={!c.dados ? undefined : false}>
      {c.dados && (
        <>
          <dl className="lista-def">
            <dt>Descrição</dt>
            <dd>{c.dados.descricao}</dd>
            <dt>Status</dt>
            <dd>
              <Badge status={c.dados.status} />
            </dd>
            <dt>Competência</dt>
            <dd>{competencia(c.dados.competencia)}</dd>
            <dt>Vencimento</dt>
            <dd>{data(c.dados.data_vencimento)}</dd>
            <dt>Cliente</dt>
            <dd className="mono">{c.dados.cliente_id}</dd>
            {c.dados.recorrencia_id && (
              <>
                <dt>Recorrência</dt>
                <dd className="mono">{curto(c.dados.recorrencia_id)} (gerada automaticamente)</dd>
              </>
            )}
            <dt>Aprovada em</dt>
            <dd>{dataHora(c.dados.aprovada_em)}</dd>
            {c.dados.cancelada_em && (
              <>
                <dt>Cancelada em</dt>
                <dd>
                  {dataHora(c.dados.cancelada_em)} — {c.dados.motivo_cancelamento}
                </dd>
              </>
            )}
          </dl>

          <h3 className="secao-titulo">Itens · {brl(c.dados.valor_total)}</h3>
          <ItensResumo itens={c.dados.itens} />

          {c.dados.pix_copia_cola && (
            <div className="pix">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={`/api/financeiro/cobrancas/${c.dados.id}/qrcode?tamanho=360`} alt="QR Code Pix da cobrança" />
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
                <strong>Pix copia e cola (BR Code)</strong>
                <small className="sutil mono">txid {c.dados.pix_txid}</small>
                <textarea readOnly rows={4} value={c.dados.pix_copia_cola} onFocus={(e) => e.target.select()} />
                <div className="acoes">
                  <Copiar texto={c.dados.pix_copia_cola} rotulo="Copiar código" />
                  <a className="btn btn-sec" href={`/api/financeiro/cobrancas/${c.dados.id}/qrcode?formato=svg`} target="_blank" rel="noreferrer">
                    Abrir SVG
                  </a>
                  {dev && escrita && c.dados.status === 'aprovada' && (
                    <button className="btn" disabled={simulando} onClick={simularPix} title="Somente desenvolvimento: simula o PSP chamando o webhook assinado">
                      {simulando ? 'Notificando…' : 'Simular Pix pago pelo banco'}
                    </button>
                  )}
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </Estado>
  );
}

function Cancelamento({ cobranca, onSalvo }: { cobranca: Cobranca; onSalvo: () => void }) {
  const { executar, ocupado } = useAcao();
  const [motivo, setMotivo] = useState('');
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(() => api(`/cobrancas/${cobranca.id}/cancelar`, { method: 'POST', body: { motivo } }), onSalvo);
  };
  return (
    <form className="form" onSubmit={salvar} style={{ gridTemplateColumns: '1fr' }}>
      <p className="sutil" style={{ margin: 0 }}>
        “{cobranca.descricao}” ({brl(cobranca.valor_total)}). A conta a receber vinculada também será cancelada.
      </p>
      <Campo rotulo="Motivo">
        <textarea rows={3} value={motivo} onChange={(e) => setMotivo(e.target.value)} required minLength={3} maxLength={300} />
      </Campo>
      <div className="form-rodape">
        <button className="btn btn-perigo" disabled={ocupado}>
          Cancelar cobrança
        </button>
      </div>
    </form>
  );
}
