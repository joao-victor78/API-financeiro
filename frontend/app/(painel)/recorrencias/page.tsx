'use client';

import { FormEvent, useState } from 'react';
import { ItensEditor, ItensResumo, Item, itemVazio, totalItens } from '@/components/ItensEditor';
import { Badge, Cabecalho, Campo, CampoUuid, Estado, Modal, Paginacao, useAcao, useDados, usePermissao } from '@/components/ui';
import { api, Paginado } from '@/lib/api';
import { brl, competencia, curto, data, dataHora, FORMAS, hoje, PERIODICIDADES } from '@/lib/formato';

interface Ciclo {
  competencia: string;
  data_vencimento: string;
  data_geracao: string;
}
interface Recorrencia {
  id: string;
  cliente_id: string;
  contrato_id: string;
  descricao: string;
  periodicidade: string;
  dia_vencimento: number;
  dias_antecedencia: number;
  data_inicio: string;
  data_fim: string | null;
  proxima_competencia: string;
  forma_pagamento: string;
  aprovacao_automatica: boolean;
  ativa: boolean;
  itens: Item[];
  valor_ciclo: number;
  proximo_ciclo: Ciclo | null;
}
interface Detalhe extends Recorrencia {
  execucoes: { id: string; competencia: string; cobranca_id: string; origem: string; executada_em: string }[];
}

export default function Recorrencias() {
  const { escrita } = usePermissao();
  const [filtro, setFiltro] = useState({ ativa: '', page: 1 });
  const lista = useDados<Paginado<Recorrencia>>('/recorrencias', { ...filtro, limit: 20 });
  const { executar, ocupado } = useAcao();
  const [nova, setNova] = useState(false);
  const [aberta, setAberta] = useState<string | null>(null);

  const alternar = (r: Recorrencia) => executar(() => api(`/recorrencias/${r.id}/${r.ativa ? 'pausar' : 'reativar'}`, { method: 'POST' }), lista.recarregar);
  const gerar = (r: Recorrencia) =>
    confirm(`Gerar agora a cobrança da competência ${competencia(r.proxima_competencia)}, sem esperar o agendador?`) &&
    executar(() => api(`/recorrencias/${r.id}/gerar`, { method: 'POST' }), lista.recarregar);
  const excluir = (r: Recorrencia) =>
    confirm(`Excluir a recorrência "${r.descricao}"? As cobranças já emitidas são mantidas.`) &&
    executar(async () => (await api(`/recorrencias/${r.id}`, { method: 'DELETE' }), { data: null, message: 'Recorrência excluída' }), lista.recarregar);

  return (
    <>
      <Cabecalho
        titulo="Recorrências"
        descricao="Configurada a regra, o agendador emite as cobranças sozinho a cada ciclo — sem duplicar e recuperando ciclos perdidos."
      >
        {escrita && (
          <button className="btn" onClick={() => setNova(true)}>
            Nova recorrência
          </button>
        )}
      </Cabecalho>

      <section className="cartao">
        <div className="filtros">
          <Campo rotulo="Situação">
            <select value={filtro.ativa} onChange={(e) => setFiltro({ ativa: e.target.value, page: 1 })}>
              <option value="">Todas</option>
              <option value="true">Ativas</option>
              <option value="false">Pausadas</option>
            </select>
          </Campo>
        </div>

        <Estado carregando={lista.carregando} erro={lista.erro} vazio={lista.dados ? lista.dados.items.length === 0 : undefined}>
          <div className="tabela-scroll">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Descrição</th>
                  <th>Ciclo</th>
                  <th className="num">Valor</th>
                  <th>Próxima competência</th>
                  <th>Gera em</th>
                  <th>Aprovação</th>
                  <th>Situação</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {lista.dados?.items.map((r) => (
                  <tr key={r.id}>
                    <td>
                      {r.descricao}
                      <br />
                      <small className="sutil mono">contrato {curto(r.contrato_id)}</small>
                    </td>
                    <td>
                      {PERIODICIDADES[r.periodicidade]} · dia {r.dia_vencimento}
                    </td>
                    <td className="num">{brl(r.valor_ciclo)}</td>
                    <td>{r.proximo_ciclo ? `${competencia(r.proximo_ciclo.competencia)} (vence ${data(r.proximo_ciclo.data_vencimento)})` : '—'}</td>
                    <td>{r.proximo_ciclo ? data(r.proximo_ciclo.data_geracao) : '—'}</td>
                    <td>{r.aprovacao_automatica ? 'Automática' : 'Manual'}</td>
                    <td>
                      <Badge status={r.ativa ? 'ativa' : 'pausada'} />
                    </td>
                    <td className="acoes-linha">
                      <button className="btn btn-sec btn-mini" onClick={() => setAberta(r.id)}>
                        Detalhes
                      </button>
                      {escrita && r.ativa && (
                        <button className="btn btn-sec btn-mini" disabled={ocupado} onClick={() => gerar(r)}>
                          Gerar agora
                        </button>
                      )}
                      {escrita && (
                        <button className="btn btn-sec btn-mini" disabled={ocupado} onClick={() => alternar(r)}>
                          {r.ativa ? 'Pausar' : 'Reativar'}
                        </button>
                      )}
                      {escrita && (
                        <button className="btn btn-perigo btn-mini" disabled={ocupado} onClick={() => excluir(r)}>
                          Excluir
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

      <Modal titulo="Nova recorrência" aberto={nova} onFechar={() => setNova(false)} largo>
        <NovaRecorrencia
          onSalvo={() => {
            setNova(false);
            lista.recarregar();
          }}
        />
      </Modal>

      <Modal titulo="Recorrência" aberto={!!aberta} onFechar={() => setAberta(null)} largo>
        {aberta && <DetalheRecorrencia id={aberta} />}
      </Modal>
    </>
  );
}

function NovaRecorrencia({ onSalvo }: { onSalvo: () => void }) {
  const { executar, ocupado } = useAcao();
  const [f, setF] = useState({
    cliente_id: '',
    contrato_id: '',
    descricao: '',
    periodicidade: 'mensal',
    dia_vencimento: '10',
    dias_antecedencia: '10',
    data_inicio: hoje(),
    data_fim: '',
    forma_pagamento: 'pix',
  });
  const [aprovacao, setAprovacao] = useState(true);
  const [itens, setItens] = useState<Item[]>([itemVazio()]);
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(
      () =>
        api('/recorrencias', {
          method: 'POST',
          body: { ...f, dia_vencimento: Number(f.dia_vencimento), dias_antecedencia: Number(f.dias_antecedencia), aprovacao_automatica: aprovacao, itens },
        }),
      onSalvo,
    );
  };
  return (
    <form className="form" onSubmit={salvar}>
      <CampoUuid rotulo="Cliente" valor={f.cliente_id} onChange={set('cliente_id')} obrigatorio />
      <CampoUuid rotulo="Contrato" valor={f.contrato_id} onChange={set('contrato_id')} obrigatorio />
      <Campo rotulo="Descrição" largo>
        <input value={f.descricao} onChange={(e) => set('descricao')(e.target.value)} required maxLength={200} placeholder="Assinatura plano Pro" />
      </Campo>
      <Campo rotulo="Periodicidade">
        <select value={f.periodicidade} onChange={(e) => set('periodicidade')(e.target.value)}>
          {Object.entries(PERIODICIDADES).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </select>
      </Campo>
      <Campo rotulo="Dia do vencimento" dica="Em meses mais curtos, usa o último dia.">
        <input type="number" min={1} max={31} value={f.dia_vencimento} onChange={(e) => set('dia_vencimento')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Gerar com antecedência (dias)" dica="Padrão: 10 dias antes do vencimento.">
        <input type="number" min={0} max={60} value={f.dias_antecedencia} onChange={(e) => set('dias_antecedencia')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Início">
        <input type="date" value={f.data_inicio} onChange={(e) => set('data_inicio')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Fim (opcional)">
        <input type="date" value={f.data_fim} onChange={(e) => set('data_fim')(e.target.value)} />
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
      <label className="check">
        <input type="checkbox" checked={aprovacao} onChange={(e) => setAprovacao(e.target.checked)} />
        Aprovar automaticamente (emite conta a receber e Pix sem intervenção humana)
      </label>
      <ItensEditor itens={itens} onChange={setItens} />
      <div className="form-rodape">
        <button className="btn" disabled={ocupado || totalItens(itens) <= 0}>
          Salvar
        </button>
      </div>
    </form>
  );
}

function DetalheRecorrencia({ id }: { id: string }) {
  const r = useDados<Detalhe>(`/recorrencias/${id}`);
  const prev = useDados<(Ciclo & { valor: number })[]>(`/recorrencias/${id}/previsao`, { ciclos: 6 });
  return (
    <Estado carregando={r.carregando} erro={r.erro} vazio={!r.dados ? undefined : false}>
      {r.dados && (
        <>
          <dl className="lista-def">
            <dt>Descrição</dt>
            <dd>{r.dados.descricao}</dd>
            <dt>Situação</dt>
            <dd>
              <Badge status={r.dados.ativa ? 'ativa' : 'pausada'} />
            </dd>
            <dt>Regra</dt>
            <dd>
              {PERIODICIDADES[r.dados.periodicidade]}, vencimento dia {r.dados.dia_vencimento}, gerada {r.dados.dias_antecedencia} dias antes
            </dd>
            <dt>Vigência</dt>
            <dd>
              {data(r.dados.data_inicio)} até {r.dados.data_fim ? data(r.dados.data_fim) : 'indeterminado'}
            </dd>
            <dt>Cliente / contrato</dt>
            <dd className="mono">
              {r.dados.cliente_id} / {r.dados.contrato_id}
            </dd>
          </dl>

          <h3 className="secao-titulo">Itens · {brl(r.dados.valor_ciclo)} por ciclo</h3>
          <ItensResumo itens={r.dados.itens} />

          <div className="grade" style={{ marginTop: 6 }}>
            <div>
              <h3 className="secao-titulo">Próximos ciclos</h3>
              <Estado carregando={prev.carregando} erro={prev.erro} vazio={prev.dados ? prev.dados.length === 0 : undefined}>
                <table className="tabela tabela-compacta">
                  <thead>
                    <tr>
                      <th>Compet.</th>
                      <th>Gera em</th>
                      <th>Vence em</th>
                      <th className="num">Valor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {prev.dados?.map((c) => (
                      <tr key={c.competencia}>
                        <td>{competencia(c.competencia)}</td>
                        <td>{data(c.data_geracao)}</td>
                        <td>{data(c.data_vencimento)}</td>
                        <td className="num">{brl(c.valor)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Estado>
            </div>
            <div>
              <h3 className="secao-titulo">Execuções</h3>
              {r.dados.execucoes.length === 0 ? (
                <p className="sutil">Nenhuma cobrança emitida ainda.</p>
              ) : (
                <table className="tabela tabela-compacta">
                  <thead>
                    <tr>
                      <th>Compet.</th>
                      <th>Origem</th>
                      <th>Quando</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.dados.execucoes.map((e) => (
                      <tr key={e.id}>
                        <td>{competencia(e.competencia)}</td>
                        <td>
                          <Badge status={e.origem === 'agendador' ? 'ok' : 'neutro'} texto={e.origem === 'agendador' ? 'Agendador' : 'Manual'} />
                        </td>
                        <td>{dataHora(e.executada_em)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </>
      )}
    </Estado>
  );
}
