'use client';

import { FormEvent, useState } from 'react';
import { Badge, Cabecalho, Campo, Estado, Kpi, Modal, Paginacao, useAcao, useDados, usePermissao } from '@/components/ui';
import { api, Paginado } from '@/lib/api';
import { brl, competencia, curto, data, FAIXAS_AGING, hoje, ROTULOS } from '@/lib/formato';

interface Atraso {
  conta_receber_id: string;
  cliente_id: string;
  descricao: string;
  competencia: string;
  data_vencimento: string;
  status: string;
  saldo: number;
  dias_atraso: number;
  faixa_aging: string;
  multa: number;
  juros: number;
  valor_atualizado: number;
}
interface Promessa {
  id: string;
  conta_receber_id: string;
  data_prometida: string;
  valor: number;
  observacao: string | null;
  status: string;
  created_at: string;
}
interface Resumo {
  total_vencido: number;
  total_vencido_atualizado: number;
  taxa_inadimplencia_percentual: number;
  clientes_inadimplentes: number;
  data_referencia: string;
}

export default function Inadimplencia() {
  const { escrita } = usePermissao();
  const [filtro, setFiltro] = useState({ dias_min: '', page: 1 });
  const [filtroP, setFiltroP] = useState({ status: '', page: 1 });
  const resumo = useDados<Resumo>('/inadimplencia/resumo');
  const lista = useDados<Paginado<Atraso>>('/inadimplencia', { ...filtro, limit: 20 });
  const promessas = useDados<Paginado<Promessa>>('/inadimplencia/promessas', { ...filtroP, limit: 20 });
  const [prometer, setPrometer] = useState<Atraso | null>(null);

  return (
    <>
      <Cabecalho
        titulo="Inadimplência"
        descricao="Títulos vencidos com encargos calculados na consulta: multa de 2% (teto do CDC, art. 52 §1º) e juros de mora de 1% a.m. pro rata."
      />

      {resumo.dados && (
        <div className="kpis">
          <Kpi rotulo="Vencido" valor={brl(resumo.dados.total_vencido)} tom={resumo.dados.total_vencido ? 'erro' : undefined} />
          <Kpi rotulo="Vencido com encargos" valor={brl(resumo.dados.total_vencido_atualizado)} />
          <Kpi rotulo="Taxa de inadimplência" valor={`${resumo.dados.taxa_inadimplencia_percentual.toLocaleString('pt-BR')}%`} />
          <Kpi rotulo="Clientes inadimplentes" valor={resumo.dados.clientes_inadimplentes} detalhe={`referência ${data(resumo.dados.data_referencia)}`} />
        </div>
      )}

      <section className="cartao">
        <header>
          <h2>Títulos em atraso</h2>
          <div className="filtros" style={{ margin: 0 }}>
            <Campo rotulo="Atraso mínimo (dias)">
              <input type="number" min={1} value={filtro.dias_min} onChange={(e) => setFiltro({ dias_min: e.target.value, page: 1 })} placeholder="Todos" />
            </Campo>
          </div>
        </header>
        <Estado carregando={lista.carregando} erro={lista.erro} vazio={lista.dados ? lista.dados.items.length === 0 : undefined}>
          <div className="tabela-scroll">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Descrição</th>
                  <th>Cliente</th>
                  <th>Vencimento</th>
                  <th className="num">Atraso</th>
                  <th>Faixa</th>
                  <th className="num">Saldo</th>
                  <th className="num">Multa</th>
                  <th className="num">Juros</th>
                  <th className="num">Atualizado</th>
                  {escrita && <th />}
                </tr>
              </thead>
              <tbody>
                {lista.dados?.items.map((a) => (
                  <tr key={a.conta_receber_id}>
                    <td>
                      {a.descricao}
                      <br />
                      <small className="sutil">{competencia(a.competencia)}</small>
                    </td>
                    <td className="mono" title={a.cliente_id}>
                      {curto(a.cliente_id)}
                    </td>
                    <td>{data(a.data_vencimento)}</td>
                    <td className="num">{a.dias_atraso} d</td>
                    <td>
                      <Badge status="vencida" texto={FAIXAS_AGING[a.faixa_aging] ?? a.faixa_aging} />
                    </td>
                    <td className="num">{brl(a.saldo)}</td>
                    <td className="num">{brl(a.multa)}</td>
                    <td className="num">{brl(a.juros)}</td>
                    <td className="num">
                      <strong>{brl(a.valor_atualizado)}</strong>
                    </td>
                    {escrita && (
                      <td className="acoes-linha">
                        <button className="btn btn-sec btn-mini" onClick={() => setPrometer(a)}>
                          Promessa
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Paginacao meta={lista.dados?.meta} onPagina={(page) => setFiltro({ ...filtro, page })} />
        </Estado>
      </section>

      <section className="cartao">
        <header>
          <div>
            <h2>Promessas de pagamento</h2>
            <p className="sutil" style={{ margin: '4px 0 0' }}>
              Cumpridas automaticamente quando o pagamento chega; marcadas como quebradas pelo agendador se a data passar.
            </p>
          </div>
          <div className="filtros" style={{ margin: 0 }}>
            <Campo rotulo="Status">
              <select value={filtroP.status} onChange={(e) => setFiltroP({ status: e.target.value, page: 1 })}>
                <option value="">Todas</option>
                {['pendente', 'cumprida', 'quebrada'].map((s) => (
                  <option key={s} value={s}>
                    {ROTULOS[s]}
                  </option>
                ))}
              </select>
            </Campo>
          </div>
        </header>
        <Estado carregando={promessas.carregando} erro={promessas.erro} vazio={promessas.dados ? promessas.dados.items.length === 0 : undefined}>
          <div className="tabela-scroll">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Conta</th>
                  <th>Data prometida</th>
                  <th className="num">Valor</th>
                  <th>Observação</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {promessas.dados?.items.map((p) => (
                  <tr key={p.id}>
                    <td className="mono" title={p.conta_receber_id}>
                      {curto(p.conta_receber_id)}
                    </td>
                    <td>{data(p.data_prometida)}</td>
                    <td className="num">{brl(p.valor)}</td>
                    <td>{p.observacao ?? '—'}</td>
                    <td>
                      <Badge status={p.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Paginacao meta={promessas.dados?.meta} onPagina={(page) => setFiltroP({ ...filtroP, page })} />
        </Estado>
      </section>

      <Modal titulo="Registrar promessa de pagamento" aberto={!!prometer} onFechar={() => setPrometer(null)}>
        {prometer && (
          <NovaPromessa
            atraso={prometer}
            onSalvo={() => {
              setPrometer(null);
              promessas.recarregar();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function NovaPromessa({ atraso, onSalvo }: { atraso: Atraso; onSalvo: () => void }) {
  const { executar, ocupado } = useAcao();
  const [f, setF] = useState({ data_prometida: hoje(), valor: String(atraso.saldo), observacao: '' });
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(() => api('/inadimplencia/promessas', { method: 'POST', body: { ...f, valor: Number(f.valor), conta_receber_id: atraso.conta_receber_id } }), onSalvo);
  };
  return (
    <form className="form" onSubmit={salvar}>
      <div className="destaque campo-largo">
        <strong>{atraso.descricao}</strong> — {atraso.dias_atraso} dias em atraso, saldo de {brl(atraso.saldo)}.
      </div>
      <Campo rotulo="Data prometida">
        <input type="date" min={hoje()} value={f.data_prometida} onChange={(e) => setF({ ...f, data_prometida: e.target.value })} required />
      </Campo>
      <Campo rotulo="Valor (R$)">
        <input type="number" min="0.01" step="0.01" max={atraso.saldo} value={f.valor} onChange={(e) => setF({ ...f, valor: e.target.value })} required />
      </Campo>
      <Campo rotulo="Observação" largo>
        <textarea rows={2} maxLength={500} value={f.observacao} onChange={(e) => setF({ ...f, observacao: e.target.value })} />
      </Campo>
      <div className="form-rodape">
        <button className="btn" disabled={ocupado}>
          Registrar
        </button>
      </div>
    </form>
  );
}
