'use client';

import { FormEvent, useState } from 'react';
import { Badge, Cabecalho, Campo, CampoUuid, Estado, Modal, Paginacao, useAcao, useDados, usePermissao } from '@/components/ui';
import { api, Paginado } from '@/lib/api';
import { brl, competencia, competenciaAtual, curto, data, FORMAS, hoje, ROTULOS } from '@/lib/formato';

interface ContaPagar {
  id: string;
  fornecedor_id: string;
  centro_custo_id: string;
  categoria_id: string;
  descricao: string;
  documento: string | null;
  competencia: string;
  data_vencimento: string;
  valor: number;
  desconto: number;
  valor_pago: number;
  saldo: number;
  data_pagamento: string | null;
  forma_pagamento: string | null;
  status: string;
}

const STATUS = ['em_aberto', 'agendada', 'parcialmente_paga', 'vencida', 'paga', 'cancelada'];
const ABERTOS = ['em_aberto', 'agendada', 'vencida', 'parcialmente_paga'];

export default function ContasPagar() {
  const { escrita } = usePermissao();
  const [filtro, setFiltro] = useState({ status: '', competencia: '', page: 1 });
  const lista = useDados<Paginado<ContaPagar>>('/contas-pagar', { ...filtro, limit: 20 });
  const { executar, ocupado } = useAcao();
  const [nova, setNova] = useState(false);
  const [pagar, setPagar] = useState<ContaPagar | null>(null);

  const excluir = (c: ContaPagar) =>
    confirm(`Excluir "${c.descricao}"? (exclusão lógica)`) &&
    executar(async () => (await api(`/contas-pagar/${c.id}`, { method: 'DELETE' }), { data: null, message: 'Conta excluída' }), lista.recarregar);

  return (
    <>
      <Cabecalho titulo="Contas a pagar" descricao="Lançamentos devidos a fornecedores, por centro de custo e categoria.">
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
                  <th>Fornecedor</th>
                  <th>Compet.</th>
                  <th>Vencimento</th>
                  <th className="num">Valor</th>
                  <th className="num">Pago</th>
                  <th className="num">Saldo</th>
                  <th>Status</th>
                  {escrita && <th />}
                </tr>
              </thead>
              <tbody>
                {lista.dados?.items.map((c) => (
                  <tr key={c.id}>
                    <td>
                      {c.descricao}
                      {c.documento && (
                        <>
                          <br />
                          <small className="sutil">{c.documento}</small>
                        </>
                      )}
                    </td>
                    <td className="mono" title={c.fornecedor_id}>
                      {curto(c.fornecedor_id)}
                    </td>
                    <td>{competencia(c.competencia)}</td>
                    <td>{data(c.data_vencimento)}</td>
                    <td className="num">{brl(c.valor - c.desconto)}</td>
                    <td className="num">{c.valor_pago ? brl(c.valor_pago) : '—'}</td>
                    <td className="num">{brl(c.saldo)}</td>
                    <td>
                      <Badge status={c.status} />
                    </td>
                    {escrita && (
                      <td className="acoes-linha">
                        {ABERTOS.includes(c.status) && (
                          <button className="btn btn-mini" onClick={() => setPagar(c)}>
                            Pagar
                          </button>
                        )}
                        {c.valor_pago === 0 && (
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

      <Modal titulo="Nova conta a pagar" aberto={nova} onFechar={() => setNova(false)}>
        <NovaConta
          onSalvo={() => {
            setNova(false);
            lista.recarregar();
          }}
        />
      </Modal>

      <Modal titulo="Registrar pagamento" aberto={!!pagar} onFechar={() => setPagar(null)}>
        {pagar && (
          <Pagamento
            conta={pagar}
            onSalvo={() => {
              setPagar(null);
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
    fornecedor_id: '',
    centro_custo_id: '',
    categoria_id: '',
    descricao: '',
    documento: '',
    competencia: competenciaAtual(),
    data_vencimento: '',
    valor: '',
    desconto: '',
    forma_pagamento: 'boleto',
  });
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(
      () => api('/contas-pagar', { method: 'POST', body: { ...f, valor: Number(f.valor), desconto: f.desconto ? Number(f.desconto) : undefined } }),
      onSalvo,
    );
  };
  return (
    <form className="form" onSubmit={salvar}>
      <CampoUuid rotulo="Fornecedor" valor={f.fornecedor_id} onChange={set('fornecedor_id')} obrigatorio />
      <CampoUuid rotulo="Centro de custo" valor={f.centro_custo_id} onChange={set('centro_custo_id')} obrigatorio />
      <CampoUuid rotulo="Categoria" valor={f.categoria_id} onChange={set('categoria_id')} obrigatorio />
      <Campo rotulo="Documento (opcional)">
        <input value={f.documento} onChange={(e) => set('documento')(e.target.value)} maxLength={60} placeholder="NF 1234" />
      </Campo>
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

function Pagamento({ conta, onSalvo }: { conta: ContaPagar; onSalvo: () => void }) {
  const { executar, ocupado } = useAcao();
  const [f, setF] = useState({ valor: String(conta.saldo), data_pagamento: hoje(), forma_pagamento: conta.forma_pagamento ?? 'boleto' });
  const salvar = (e: FormEvent) => {
    e.preventDefault();
    executar(() => api(`/contas-pagar/${conta.id}/pagamento`, { method: 'POST', body: { ...f, valor: Number(f.valor) } }), onSalvo);
  };
  return (
    <form className="form" onSubmit={salvar}>
      <div className="destaque campo-largo">
        <strong>{conta.descricao}</strong> — saldo de {brl(conta.saldo)}.
      </div>
      <Campo rotulo="Valor pago (R$)">
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
          Registrar pagamento
        </button>
      </div>
    </form>
  );
}
