'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import { Badge, Cabecalho, Campo, Estado, Kpi, Modal, Paginacao, useAcao, useAvisos, useDados, usePermissao } from '@/components/ui';
import { api, ErroApi, Paginado } from '@/lib/api';
import { brl, data, dataHora, ROTULOS } from '@/lib/formato';

interface Conciliacao {
  id: string;
  arquivo_nome: string;
  banco_id: string | null;
  conta_id: string | null;
  periodo_inicio: string | null;
  periodo_fim: string | null;
  total_transacoes: number;
  conciliadas_automaticamente: number;
  pendentes: number;
  ignoradas_duplicadas: number;
  created_at: string;
  taxa_automatica_percentual?: number;
}
interface Transacao {
  id: string;
  fitid: string;
  data: string;
  valor: number;
  tipo: 'credito' | 'debito';
  descricao: string | null;
  status: string;
  conta_receber_id: string | null;
  conta_pagar_id: string | null;
  score: number | null;
  criterio: string | null;
}
interface Sugestao {
  id: string;
  score: number;
  criterio: string;
  tipo: 'receber' | 'pagar';
}

const CRITERIOS: Record<string, string> = {
  txid_pix: 'txid do Pix na descrição',
  txid_pix_ja_baixado: 'Pix já baixado pelo webhook',
  valor_exato: 'Valor idêntico',
  manual: 'Conciliação manual',
};
const criterio = (c: string | null) => (c ? CRITERIOS[c] ?? c.replace('valor_exato+data', 'Valor idêntico + data ') : '—');

export default function Conciliacoes() {
  const { escrita } = usePermissao();
  const [page, setPage] = useState(1);
  const lista = useDados<Paginado<Conciliacao>>('/conciliacoes', { page, limit: 20 });
  const [aberta, setAberta] = useState<string | null>(null);
  const [ultima, setUltima] = useState<Conciliacao | null>(null);

  return (
    <>
      <Cabecalho
        titulo="Conciliação bancária"
        descricao="Importe o extrato OFX (1.x ou 2.x): o motor baixa sozinho só o que é inequívoco (≥ 75 pontos e candidato único) e deixa o resto para você decidir."
      />

      {escrita && (
        <Importar
          onImportado={(c) => {
            setUltima(c);
            setPage(1);
            lista.recarregar();
          }}
        />
      )}

      {ultima && (
        <div className="kpis">
          <Kpi rotulo="Transações no extrato" valor={ultima.total_transacoes} detalhe={ultima.arquivo_nome} />
          <Kpi rotulo="Baixadas automaticamente" valor={ultima.conciliadas_automaticamente} detalhe={`${(ultima.taxa_automatica_percentual ?? 0).toLocaleString('pt-BR')}% do extrato`} tom="ok" />
          <Kpi rotulo="Pendentes para revisão" valor={ultima.pendentes} />
          <Kpi rotulo="Já importadas (FITID)" valor={ultima.ignoradas_duplicadas} detalhe="reimportação não duplica" />
        </div>
      )}

      <section className="cartao">
        <h2>Extratos importados</h2>
        <Estado carregando={lista.carregando} erro={lista.erro} vazio={lista.dados ? lista.dados.items.length === 0 : undefined}>
          <div className="tabela-scroll">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Arquivo</th>
                  <th>Período</th>
                  <th className="num">Transações</th>
                  <th className="num">Automáticas</th>
                  <th className="num">Pendentes</th>
                  <th className="num">Duplicadas</th>
                  <th>Importado em</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {lista.dados?.items.map((c) => (
                  <tr key={c.id}>
                    <td>{c.arquivo_nome}</td>
                    <td>{c.periodo_inicio ? `${data(c.periodo_inicio)} a ${data(c.periodo_fim)}` : '—'}</td>
                    <td className="num">{c.total_transacoes}</td>
                    <td className="num">{c.conciliadas_automaticamente}</td>
                    <td className="num">{c.pendentes ? <Badge status="pendente" texto={String(c.pendentes)} /> : 0}</td>
                    <td className="num">{c.ignoradas_duplicadas}</td>
                    <td>{dataHora(c.created_at)}</td>
                    <td className="acoes-linha">
                      <button className="btn btn-sec btn-mini" onClick={() => setAberta(c.id)}>
                        Transações
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Paginacao meta={lista.dados?.meta} onPagina={setPage} />
        </Estado>
      </section>

      <Modal titulo="Transações do extrato" aberto={!!aberta} onFechar={() => setAberta(null)} largo>
        {aberta && <Detalhe id={aberta} onAlterou={lista.recarregar} />}
      </Modal>
    </>
  );
}

function Importar({ onImportado }: { onImportado: (c: Conciliacao) => void }) {
  const { executar, ocupado } = useAcao();
  const arquivo = useRef<HTMLInputElement>(null);
  const [nome, setNome] = useState('');
  const enviar = (e: FormEvent) => {
    e.preventDefault();
    const f = arquivo.current?.files?.[0];
    if (!f) return;
    const form = new FormData();
    form.append('arquivo', f);
    executar(
      () => api<Conciliacao>('/conciliacoes/importar', { method: 'POST', form }),
      (c) => {
        onImportado(c);
        if (arquivo.current) arquivo.current.value = '';
        setNome('');
      },
    );
  };
  return (
    <section className="cartao">
      <form className="filtros" style={{ margin: 0 }} onSubmit={enviar}>
        <Campo rotulo="Extrato OFX (até 5 MB)">
          <input ref={arquivo} type="file" accept=".ofx,.OFX,application/x-ofx,text/plain" required onChange={(e) => setNome(e.target.files?.[0]?.name ?? '')} />
        </Campo>
        <button className="btn" disabled={ocupado || !nome}>
          {ocupado ? 'Conciliando…' : 'Importar e conciliar'}
        </button>
      </form>
    </section>
  );
}

function Detalhe({ id, onAlterou }: { id: string; onAlterou: () => void }) {
  const { escrita } = usePermissao();
  const [status, setStatus] = useState('');
  const c = useDados<Conciliacao & { transacoes: Transacao[] }>(`/conciliacoes/${id}`, { status });
  const { executar, ocupado } = useAcao();
  const [sugerir, setSugerir] = useState<Transacao | null>(null);

  const recarregar = () => {
    c.recarregar();
    onAlterou();
  };
  const ignorar = (t: Transacao) => executar(() => api(`/conciliacoes/${id}/transacoes/${t.id}/ignorar`, { method: 'POST' }), recarregar);

  if (sugerir) {
    return (
      <Sugestoes
        conciliacao={id}
        transacao={sugerir}
        onVoltar={() => setSugerir(null)}
        onConciliado={() => {
          setSugerir(null);
          recarregar();
        }}
      />
    );
  }

  return (
    <>
      <div className="filtros">
        <Campo rotulo="Status">
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Todas</option>
            {['pendente', 'conciliada', 'ignorada'].map((s) => (
              <option key={s} value={s}>
                {ROTULOS[s]}
              </option>
            ))}
          </select>
        </Campo>
      </div>
      <Estado carregando={c.carregando} erro={c.erro} vazio={c.dados ? c.dados.transacoes.length === 0 : undefined}>
        <div className="tabela-scroll">
          <table className="tabela tabela-compacta">
            <thead>
              <tr>
                <th>Data</th>
                <th>Descrição</th>
                <th className="num">Valor</th>
                <th>Status</th>
                <th>Critério</th>
                <th className="num">Pontos</th>
                {escrita && <th />}
              </tr>
            </thead>
            <tbody>
              {c.dados?.transacoes.map((t) => (
                <tr key={t.id}>
                  <td>{data(t.data)}</td>
                  <td>
                    {t.descricao ?? '—'}
                    <br />
                    <small className="sutil mono">FITID {t.fitid}</small>
                  </td>
                  <td className="num" style={{ color: t.tipo === 'debito' ? 'var(--erro)' : undefined }}>
                    {t.tipo === 'debito' ? '−' : ''}
                    {brl(Math.abs(t.valor))}
                  </td>
                  <td>
                    <Badge status={t.status} />
                  </td>
                  <td>{criterio(t.criterio)}</td>
                  <td className="num">{t.score ?? '—'}</td>
                  {escrita && (
                    <td className="acoes-linha">
                      {t.status === 'pendente' && (
                        <>
                          <button className="btn btn-mini" onClick={() => setSugerir(t)}>
                            Conciliar…
                          </button>
                          <button className="btn btn-sec btn-mini" disabled={ocupado} onClick={() => ignorar(t)}>
                            Ignorar
                          </button>
                        </>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Estado>
    </>
  );
}

interface Titulo {
  id: string;
  descricao: string;
  data_vencimento: string;
  saldo: number;
  status: string;
}

function Sugestoes({ conciliacao, transacao, onVoltar, onConciliado }: { conciliacao: string; transacao: Transacao; onVoltar: () => void; onConciliado: () => void }) {
  const sug = useDados<Sugestao[]>(`/conciliacoes/${conciliacao}/transacoes/${transacao.id}/sugestoes`);
  const [titulos, setTitulos] = useState<Record<string, Titulo>>({});
  const [manual, setManual] = useState('');
  const { executar, ocupado } = useAcao();
  const avisos = useAvisos();
  const tipo = transacao.tipo === 'credito' ? 'receber' : 'pagar';

  // Busca descrição/vencimento/saldo dos títulos sugeridos para o operador decidir.
  useEffect(() => {
    if (!sug.dados) return;
    Promise.all(
      sug.dados.map((s) =>
        api<Titulo>(`/contas-${s.tipo === 'receber' ? 'receber' : 'pagar'}/${s.id}`)
          .then((r) => [s.id, r.data] as const)
          .catch(() => null),
      ),
    ).then((pares) => setTitulos(Object.fromEntries(pares.filter(Boolean) as [string, Titulo][])));
  }, [sug.dados]);

  const conciliar = (contaId: string) =>
    executar(
      () =>
        api(`/conciliacoes/${conciliacao}/transacoes/${transacao.id}/conciliar`, {
          method: 'POST',
          body: tipo === 'receber' ? { conta_receber_id: contaId } : { conta_pagar_id: contaId },
        }),
      onConciliado,
    );

  return (
    <>
      <div className="destaque">
        <strong>
          {transacao.tipo === 'credito' ? 'Crédito' : 'Débito'} de {brl(Math.abs(transacao.valor))} em {data(transacao.data)}
        </strong>
        <br />
        <span className="sutil">{transacao.descricao}</span>
      </div>

      <h3 className="secao-titulo">Candidatos do motor de conciliação</h3>
      <Estado carregando={sug.carregando} erro={sug.erro} vazio={sug.dados ? sug.dados.length === 0 : undefined}>
        <table className="tabela tabela-compacta">
          <thead>
            <tr>
              <th>Título</th>
              <th>Vencimento</th>
              <th className="num">Saldo</th>
              <th>Critério</th>
              <th className="num">Pontos</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {sug.dados?.map((s) => (
              <tr key={s.id}>
                <td>{titulos[s.id]?.descricao ?? <span className="mono">{s.id.slice(0, 8)}</span>}</td>
                <td>{data(titulos[s.id]?.data_vencimento)}</td>
                <td className="num">{titulos[s.id] ? brl(titulos[s.id].saldo) : '—'}</td>
                <td>{criterio(s.criterio)}</td>
                <td className="num">{s.score}</td>
                <td className="acoes-linha">
                  <button className="btn btn-mini" disabled={ocupado} onClick={() => conciliar(s.id)}>
                    Conciliar com este
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Estado>

      <form
        className="filtros"
        style={{ marginTop: 16 }}
        onSubmit={(e) => {
          e.preventDefault();
          if (!/^[0-9a-f-]{36}$/i.test(manual)) return avisos.erro(new ErroApi(422, 'Informe o UUID do título'));
          conciliar(manual);
        }}
      >
        <Campo rotulo={`Ou informe o ID da conta a ${tipo}`}>
          <input value={manual} onChange={(e) => setManual(e.target.value.trim())} placeholder="UUID" style={{ minWidth: 320 }} />
        </Campo>
        <button className="btn btn-sec" disabled={ocupado || !manual}>
          Conciliar
        </button>
        <button type="button" className="btn btn-sec" onClick={onVoltar} style={{ marginLeft: 'auto' }}>
          ← Voltar
        </button>
      </form>
    </>
  );
}
