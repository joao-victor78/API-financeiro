'use client';

import { FormEvent, useState } from 'react';
import { Badge, Cabecalho, Campo, CampoUuid, Estado, Modal, Paginacao, useAcao, useDados, usePermissao } from '@/components/ui';
import { api, Paginado } from '@/lib/api';
import { brl, competencia, competenciaAtual, data, FORMAS, hoje, ROTULOS } from '@/lib/formato';

export interface ContaReceber {
  id: string;
  cliente_id: string;
  contrato_id: string | null;
  cobranca_id: string | null;
  descricao: string;
  competencia: string;
  data_vencimento: string;
  valor: number;
  desconto: number;
  juros: number;
  multa: number;
  valor_pago: number;
  saldo: number;
  data_pagamento: string | null;
  forma_pagamento: string | null;
  status: string;
  pix_txid: string | null;
}

const STATUS = ['em_aberto', 'agendada', 'parcialmente_paga', 'vencida', 'paga', 'cancelada', 'estornada'];
const ABERTOS = ['em_aberto', 'agendada', 'vencida', 'parcialmente_paga'];

export default function ContasReceber() {
  const { escrita } = usePermissao();
  const [filtro, setFiltro] = useState({ status: '', competencia: '', page: 1 });
  const lista = useDados<Paginado<ContaReceber>>('/contas-receber', { ...filtro, limit: 20 });
  const { executar, ocupado } = useAcao();
  const [nova, setNova] = useState(false);
  const [baixa, setBaixa] = useState<ContaReceber | null>(null);

  const estornar = (c: ContaReceber) =>
    confirm(`Estornar o recebimento de ${brl(c.valor_pago)}?`) &&
    executar(() => api(`/contas-receber/${c.id}/estorno`, { method: 'POST' }), lista.recarregar);

  const excluir = (c: ContaReceber) =>
    confirm(`Excluir "${c.descricao}"? (exclusão lógica, fica registrada na auditoria)`) &&
    executar(async () => (await api(`/contas-receber/${c.id}`, { method: 'DELETE' }), { data: null, message: 'Conta excluída' }), lista.recarregar);

  return (
    <>
      <Cabecalho titulo="Contas a receber" descricao="Títulos a receber de clientes, com ciclo de vida controlado pelo próprio sistema.">
        {escrita && (
          <button className="btn" onClick={() => setNova(true)}>
            Nova conta
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
                  <th className="num">Valor</th>
                  <th className="num">Recebido</th>
                  <th className="num">Saldo</th>
                  <th>Forma</th>
                  <th>Status</th>
                  {escrita && <th />}
                </tr>
              </thead>
              <tbody>
                {lista.dados?.items.map((c) => (
                  <tr key={c.id}>
                    <td>
                      {c.descricao}
                      {c.pix_txid && (
                        <>
                          <br />
                          <small className="sutil mono">txid {c.pix_txid}</small>
                        </>
                      )}
                    </td>
                    <td>{competencia(c.competencia)}</td>
                    <td>{data(c.data_vencimento)}</td>
                    <td className="num">{brl(c.valor - c.desconto)}</td>
                    <td className="num">{c.valor_pago ? brl(c.valor_pago) : '—'}</td>
                    <td className="num">{brl(c.saldo)}</td>
                    <td>{c.forma_pagamento ? FORMAS[c.forma_pagamento] : '—'}</td>
                    <td>
                      <Badge status={c.status} />
                    </td>
                    {escrita && (
                      <td className="acoes-linha">
                        {ABERTOS.includes(c.status) && (
                          <button className="btn btn-mini" onClick={() => setBaixa(c)}>
                            Receber
                          </button>
                        )}
                        {c.valor_pago > 0 && c.status !== 'estornada' && (
                          <button className="btn btn-sec btn-mini" disabled={ocupado} onClick={() => estornar(c)}>
                            Estornar
                          </button>
                        )}
                        {c.valor_pago === 0 && !c.cobranca_id && (
                          <button className="btn btn-perigo btn-mini" disabled={ocupado} onClick={() => excluir(c)}>
                            Excluir
                          </button>
                        )}
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

      <Modal titulo="Nova conta a receber" aberto={nova} onFechar={() => setNova(false)}>
        <NovaConta
          onSalvo={() => {
            setNova(false);
            lista.recarregar();
          }}
        />
      </Modal>

      <Modal titulo="Registrar recebimento" aberto={!!baixa} onFechar={() => setBaixa(null)}>
        {baixa && (
          <Baixa
            conta={baixa}
            onSalvo={() => {
              setBaixa(null);
              lista.recarregar();
            }}
          />
        )}
      </Modal>
    </>
  );
}

function NovaConta({ onSalvo }: { onSalvo: () => void }) {
  const { executar, ocupado } = useAcao();
  const [f, setF] = useState({
    cliente_id: '',
    contrato_id: '',
    descricao: '',
    competencia: competenciaAtual(),
    data_vencimento: '',
    valor: '',
    desconto: '',
    forma_pagamento: 'pix',
  });
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(
      () => api('/contas-receber', { method: 'POST', body: { ...f, valor: Number(f.valor), desconto: f.desconto ? Number(f.desconto) : undefined } }),
      onSalvo,
    );
  };
  return (
    <form className="form" onSubmit={salvar}>
      <CampoUuid rotulo="Cliente" valor={f.cliente_id} onChange={set('cliente_id')} obrigatorio />
      <CampoUuid rotulo="Contrato (opcional)" valor={f.contrato_id} onChange={set('contrato_id')} />
      <Campo rotulo="Descrição" largo>
        <input value={f.descricao} onChange={(e) => set('descricao')(e.target.value)} required maxLength={200} />
      </Campo>
      <Campo rotulo="Competência">
        <input type="month" value={f.competencia} onChange={(e) => set('competencia')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Vencimento">
        <input type="date" value={f.data_vencimento} onChange={(e) => set('data_vencimento')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Valor (R$)">
        <input type="number" min="0.01" step="0.01" value={f.valor} onChange={(e) => set('valor')(e.target.value)} required />
      </Campo>
      <Campo rotulo="Desconto (R$)">
        <input type="number" min="0" step="0.01" value={f.desconto} onChange={(e) => set('desconto')(e.target.value)} />
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
      <div className="form-rodape">
        <button className="btn" disabled={ocupado}>
          Salvar
        </button>
      </div>
    </form>
  );
}

function Baixa({ conta, onSalvo }: { conta: ContaReceber; onSalvo: () => void }) {
  const { executar, ocupado } = useAcao();
  const [f, setF] = useState({ valor: String(conta.saldo), data_pagamento: hoje(), forma_pagamento: conta.forma_pagamento ?? 'pix' });
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(() => api(`/contas-receber/${conta.id}/baixa`, { method: 'POST', body: { ...f, valor: Number(f.valor) } }), onSalvo);
  };
  return (
    <form className="form" onSubmit={salvar}>
      <div className="destaque campo-largo">
        <strong>{conta.descricao}</strong> — saldo de {brl(conta.saldo)}. Um valor menor que o saldo gera baixa parcial.
      </div>
      <Campo rotulo="Valor recebido (R$)">
        <input type="number" min="0.01" step="0.01" max={conta.saldo} value={f.valor} onChange={(e) => setF({ ...f, valor: e.target.value })} required />
      </Campo>
      <Campo rotulo="Data do pagamento">
        <input type="date" value={f.data_pagamento} onChange={(e) => setF({ ...f, data_pagamento: e.target.value })} required />
      </Campo>
      <Campo rotulo="Forma">
        <select value={f.forma_pagamento} onChange={(e) => setF({ ...f, forma_pagamento: e.target.value })}>
          {Object.entries(FORMAS).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </select>
      </Campo>
      <div className="form-rodape">
        <button className="btn" disabled={ocupado}>
          Registrar recebimento
        </button>
      </div>
    </form>
  );
}
