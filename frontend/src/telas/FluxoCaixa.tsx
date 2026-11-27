import { useState } from 'react'
import { ArrowDownRight, ArrowUpRight, LineChart } from 'lucide-react'
import { cx, FilterBar, Input, PageHeader, Select } from '@/components/ui'
import { BotoesExportar, Conteudo, Indicador, linha, Tabela, Td, Th } from '@/components/modulo'
import { useCadastros } from '@/lib/cadastros'
import { useDados } from '@/lib/hooks'
import { brl, data, hoje } from '@/lib/formato'

interface Periodo {
  periodo: string
  inicio: string
  fim: string
  entradasRealizadas: number
  saidasRealizadas: number
  entradasPrevistas: number
  saidasPrevistas: number
  saldoDoPeriodo: number
  saldoAcumulado: number
}

interface Fluxo {
  saldoInicial: number
  periodos: Periodo[]
  totalEntradasRealizadas: number
  totalSaidasRealizadas: number
  totalEntradasPrevistas: number
  totalSaidasPrevistas: number
  saldoFinal: number
  vencidoAReceber: number
  vencidoAPagar: number
  recorrenciasProjetadas: number
}

const inicioDoMes = () => `${hoje().slice(0, 7)}-01`
const maisMeses = (iso: string, n: number) => {
  const [a, m] = iso.split('-').map(Number)
  const d = new Date(a, m - 1 + n, 0)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** §23 e §74: fluxo de caixa realizado e previsto, com saldo acumulado a partir das contas bancárias. */
export function FluxoCaixa(_: { detalhe?: string }) {
  const cadastros = useCadastros()
  const [f, setF] = useState({ de: inicioDoMes(), ate: maisMeses(inicioDoMes(), 3), agrupamento: 'MES', contaBancariaId: '' })
  const fluxo = useDados<Fluxo>('/fluxo-caixa', f)
  const rotulo = (p: Periodo) => (f.agrupamento === 'MES' ? p.periodo.slice(5, 7) + '/' + p.periodo.slice(0, 4) : f.agrupamento === 'DIA' ? data(p.periodo) : `${data(p.inicio)} a ${data(p.fim)}`)

  return (
    <>
      <PageHeader title="Fluxo de caixa" subtitle="Realizado (recebido e pago) e previsto (a receber, a pagar e próximas recorrências)"
        actions={<BotoesExportar caminho="/relatorios/fluxo-caixa/exportar" query={f} nome="fluxo-de-caixa" />} />
      <FilterBar>
        <Input aria-label="De" type="date" className="w-auto" value={f.de} onChange={(e) => e.target.value && setF({ ...f, de: e.target.value })} />
        <Input aria-label="Até" type="date" className="w-auto" value={f.ate} onChange={(e) => e.target.value && setF({ ...f, ate: e.target.value })} />
        <Select aria-label="Agrupar por" className="w-auto" value={f.agrupamento} onChange={(e) => setF({ ...f, agrupamento: e.target.value })}>
          <option value="DIA">Por dia</option>
          <option value="SEMANA">Por semana</option>
          <option value="MES">Por mês</option>
        </Select>
        {cadastros.contas.length > 0 && (
          <Select aria-label="Conta bancária" className="w-auto" value={f.contaBancariaId} onChange={(e) => setF({ ...f, contaBancariaId: e.target.value })}>
            <option value="">Todas as contas</option>
            {cadastros.contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </Select>
        )}
      </FilterBar>
      <div className="flex-1 space-y-5 p-5">
        <Conteudo carga={fluxo.carga} recarregar={fluxo.recarregar} vazio={(d) => d.periodos.length === 0} icone={<LineChart aria-hidden />}>
          {(d) => (
            <>
              <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
                <Indicador rotulo="Saldo inicial" valor={brl(d.saldoInicial)} detalhe={cadastros.contas.length === 0 ? 'cadastre as contas bancárias' : undefined} />
                <Indicador rotulo="Entradas realizadas" valor={brl(d.totalEntradasRealizadas)} tom="ok" />
                <Indicador rotulo="Saídas realizadas" valor={brl(d.totalSaidasRealizadas)} />
                <Indicador rotulo="A receber no período" valor={brl(d.totalEntradasPrevistas)} detalhe={`${brl(d.recorrenciasProjetadas)} de recorrências futuras`} />
                <Indicador rotulo="A pagar no período" valor={brl(d.totalSaidasPrevistas)} />
                <Indicador rotulo="Saldo final projetado" valor={brl(d.saldoFinal)} tom={d.saldoFinal < 0 ? 'erro' : 'ok'} />
              </div>
              {(d.vencidoAReceber > 0 || d.vencidoAPagar > 0) && (
                <p className="rounded-lg border border-yellow-200 bg-yellow-50 px-4 py-2 text-sm text-yellow-800 dark:border-yellow-500/30 dark:bg-yellow-500/10 dark:text-yellow-300">
                  Fora do saldo projetado: {brl(d.vencidoAReceber)} vencidos a receber e {brl(d.vencidoAPagar)} vencidos a pagar.
                </p>
              )}
              <Tabela atualizando={fluxo.atualizando} cabecalho={<>
                <Th>Período</Th><Th num>Entradas</Th><Th num>Saídas</Th><Th num className="hidden md:table-cell">A receber</Th>
                <Th num className="hidden md:table-cell">A pagar</Th><Th num>Saldo do período</Th><Th num>Saldo acumulado</Th>
              </>}>
                {d.periodos.map((p) => (
                  <tr key={p.inicio} className={linha}>
                    <Td>{rotulo(p)}</Td>
                    <Td num><span className="inline-flex items-center gap-1 text-brand-700 dark:text-brand-400"><ArrowUpRight aria-hidden className="size-3.5" />{brl(p.entradasRealizadas)}</span></Td>
                    <Td num><span className="inline-flex items-center gap-1 text-red-600 dark:text-red-400"><ArrowDownRight aria-hidden className="size-3.5" />{brl(p.saidasRealizadas)}</span></Td>
                    <Td num className="hidden text-texto-2 md:table-cell">{brl(p.entradasPrevistas)}</Td>
                    <Td num className="hidden text-texto-2 md:table-cell">{brl(p.saidasPrevistas)}</Td>
                    <Td num className={cx(p.saldoDoPeriodo < 0 && 'text-red-600 dark:text-red-400')}>{brl(p.saldoDoPeriodo)}</Td>
                    <Td num className={cx('font-semibold text-titulo', p.saldoAcumulado < 0 && 'text-red-600 dark:text-red-400')}>{brl(p.saldoAcumulado)}</Td>
                  </tr>
                ))}
              </Tabela>
            </>
          )}
        </Conteudo>
      </div>
    </>
  )
}
