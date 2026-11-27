import { useState } from 'react'
import { FileBarChart } from 'lucide-react'
import { cx, FilterBar, Input, PageHeader, Select } from '@/components/ui'
import { BotoesExportar, Conteudo, Indicador, Tabela, Td, Th } from '@/components/modulo'
import { useDados } from '@/lib/hooks'
import { brl, competencia, mesAtual, pct } from '@/lib/formato'

interface Linha { codigo: string; titulo: string; tipo: 'GRUPO' | 'SUBTOTAL' | 'CATEGORIA'; valores: number[]; total: number; percentualDaReceita: number | null }
interface Dre {
  meses: string[]
  linhas: Linha[]
  receitaLiquida: number
  lucroBruto: number
  resultadoOperacional: number
  resultadoLiquido: number
  margemBruta: number | null
  margemLiquida: number | null
}

const menosMeses = (mes: string, n: number) => {
  const [a, m] = mes.split('-').map(Number)
  const d = new Date(a, m - 1 - n, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** §23 DRE gerencial: competência ou caixa, por mês, com categorias abertas dentro de cada grupo. */
export function DreTela(_: { detalhe?: string }) {
  const [f, setF] = useState({ de: menosMeses(mesAtual(), 5), ate: mesAtual(), regime: 'COMPETENCIA' })
  const [abertas, setAbertas] = useState(true)
  const dre = useDados<Dre>('/dre', f)

  return (
    <>
      <PageHeader title="DRE gerencial" subtitle="Visão de gestão a partir dos lançamentos do módulo; o DRE contábil continua com a contabilidade"
        actions={<BotoesExportar caminho="/relatorios/dre/exportar" query={{ de: `${f.de}-01`, ate: `${f.ate}-01`, regime: f.regime }} nome="dre" />} />
      <FilterBar>
        <Input aria-label="De" type="month" className="w-auto" value={f.de} onChange={(e) => e.target.value && setF({ ...f, de: e.target.value })} />
        <Input aria-label="Até" type="month" className="w-auto" value={f.ate} onChange={(e) => e.target.value && setF({ ...f, ate: e.target.value })} />
        <Select aria-label="Regime" className="w-auto" value={f.regime} onChange={(e) => setF({ ...f, regime: e.target.value })}>
          <option value="COMPETENCIA">Regime de competência</option>
          <option value="CAIXA">Regime de caixa</option>
        </Select>
        <label className="ml-auto flex items-center gap-2 text-xs text-texto-2">
          <input type="checkbox" checked={abertas} onChange={(e) => setAbertas(e.target.checked)} className="accent-brand-700" /> Mostrar categorias
        </label>
      </FilterBar>
      <div className="flex-1 space-y-5 p-5">
        <Conteudo carga={dre.carga} recarregar={dre.recarregar} icone={<FileBarChart aria-hidden />}>
          {(d) => (
            <>
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                <Indicador rotulo="Receita líquida" valor={brl(d.receitaLiquida)} />
                <Indicador rotulo="Lucro bruto" valor={brl(d.lucroBruto)} detalhe={`margem bruta ${pct(d.margemBruta)}`} />
                <Indicador rotulo="Resultado operacional" valor={brl(d.resultadoOperacional)} tom={d.resultadoOperacional < 0 ? 'erro' : undefined} />
                <Indicador rotulo="Resultado líquido" valor={brl(d.resultadoLiquido)} detalhe={`margem líquida ${pct(d.margemLiquida)}`}
                  tom={d.resultadoLiquido < 0 ? 'erro' : 'ok'} />
              </div>
              <Tabela atualizando={dre.atualizando} cabecalho={<>
                <Th className="min-w-64">Linha</Th>
                {d.meses.map((m) => <Th key={m} num>{competencia(m)}</Th>)}
                <Th num>Total</Th><Th num>% receita</Th>
              </>}>
                {d.linhas.filter((l) => abertas || l.tipo !== 'CATEGORIA').map((l) => (
                  <tr key={l.codigo} className={cx('border-b border-borda last:border-0',
                    l.tipo === 'SUBTOTAL' && 'bg-brand-50/70 font-semibold text-titulo dark:bg-superficie-2',
                    l.tipo === 'GRUPO' && 'font-medium text-titulo')}>
                    <Td className={cx(l.tipo === 'CATEGORIA' && 'pl-9 text-texto-2')}>{l.titulo}</Td>
                    {l.valores.map((v, i) => <Td key={i} num className={cx(v < 0 && 'text-red-600 dark:text-red-400')}>{brl(v)}</Td>)}
                    <Td num className={cx('font-semibold', l.total < 0 && 'text-red-600 dark:text-red-400')}>{brl(l.total)}</Td>
                    <Td num className="text-texto-3">{pct(l.percentualDaReceita)}</Td>
                  </tr>
                ))}
              </Tabela>
              <p className="text-xs text-texto-3">
                Competência: receitas pela competência da cobrança e despesas pela competência da conta. Caixa: o que foi recebido e pago em cada mês.
                Tarifas e juros vêm do extrato conciliado. Lançamentos sem categoria entram em Receita bruta ou Despesas operacionais.
              </p>
            </>
          )}
        </Conteudo>
      </div>
    </>
  )
}
