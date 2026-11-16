'use client';

import { brl } from '@/lib/formato';

export interface Item {
  tipo: 'servico' | 'consumo' | 'extra';
  descricao: string;
  quantidade: number;
  valor_unitario: number;
}

export const itemVazio = (): Item => ({ tipo: 'servico', descricao: '', quantidade: 1, valor_unitario: 0 });
export const totalItens = (itens: Item[]) => Math.round(itens.reduce((s, i) => s + i.quantidade * i.valor_unitario * 100, 0)) / 100;

const TIPOS = { servico: 'Serviço', consumo: 'Consumo', extra: 'Extra' };

/** Serviços, consumo e itens extras de uma cobrança ou recorrência. */
export function ItensEditor({ itens, onChange }: { itens: Item[]; onChange: (i: Item[]) => void }) {
  const alterar = (idx: number, campo: keyof Item, valor: string) =>
    onChange(itens.map((it, i) => (i === idx ? { ...it, [campo]: campo === 'quantidade' || campo === 'valor_unitario' ? Number(valor) : valor } : it)));

  return (
    <fieldset className="itens">
      <legend>Itens</legend>
      <table className="tabela tabela-compacta">
        <thead>
          <tr>
            <th>Tipo</th>
            <th>Descrição</th>
            <th className="num">Qtd.</th>
            <th className="num">Valor unit.</th>
            <th className="num">Subtotal</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {itens.map((it, i) => (
            <tr key={i}>
              <td>
                <select value={it.tipo} onChange={(e) => alterar(i, 'tipo', e.target.value)} aria-label="Tipo do item">
                  {Object.entries(TIPOS).map(([k, v]) => (
                    <option key={k} value={k}>
                      {v}
                    </option>
                  ))}
                </select>
              </td>
              <td>
                <input value={it.descricao} required maxLength={200} onChange={(e) => alterar(i, 'descricao', e.target.value)} aria-label="Descrição do item" />
              </td>
              <td>
                <input type="number" min="0.001" step="0.001" required value={it.quantidade} onChange={(e) => alterar(i, 'quantidade', e.target.value)} aria-label="Quantidade" className="num" />
              </td>
              <td>
                <input type="number" min="0.01" step="0.01" required value={it.valor_unitario || ''} onChange={(e) => alterar(i, 'valor_unitario', e.target.value)} aria-label="Valor unitário" className="num" />
              </td>
              <td className="num">{brl(it.quantidade * it.valor_unitario)}</td>
              <td>
                <button type="button" className="btn-icone" aria-label="Remover item" disabled={itens.length === 1} onClick={() => onChange(itens.filter((_, j) => j !== i))}>
                  ×
                </button>
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td colSpan={4}>
              <button type="button" className="btn btn-sec" onClick={() => onChange([...itens, itemVazio()])}>
                + Adicionar item
              </button>
            </td>
            <td className="num">
              <strong>{brl(totalItens(itens))}</strong>
            </td>
            <td />
          </tr>
        </tfoot>
      </table>
    </fieldset>
  );
}

export function ItensResumo({ itens }: { itens: Item[] }) {
  return (
    <table className="tabela tabela-compacta">
      <thead>
        <tr>
          <th>Tipo</th>
          <th>Descrição</th>
          <th className="num">Qtd.</th>
          <th className="num">Unit.</th>
          <th className="num">Subtotal</th>
        </tr>
      </thead>
      <tbody>
        {itens.map((it, i) => (
          <tr key={i}>
            <td>{TIPOS[it.tipo]}</td>
            <td>{it.descricao}</td>
            <td className="num">{it.quantidade}</td>
            <td className="num">{brl(it.valor_unitario)}</td>
            <td className="num">{brl(it.quantidade * it.valor_unitario)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
