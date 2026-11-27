import { useState } from 'react'
import { FilterBar, FormField, Input, PageHeader } from '@/components/ui'
import { Conteudo, Indicador, linha, Tabela, Td, Th } from '@/components/modulo'
import { useDados } from '@/lib/hooks'
import { brl, competencia, mesAtual, pct as fmtPct } from '@/lib/formato'

interface Painel {
  aReceberHoje: number
  atrasados: number
  pagamentosRecebidosMes: number
  contasAPagarMes: number
  inadimplenciaTotal: number
  cobrancasEmAberto: number
  cobrancasAtrasadas: number
  mrrAtual: number
  arr: number
  // V2 (§74, §111); campos novos podem faltar numa API antiga
  receitaMes?: number
  despesasMes?: number
  resultadoMes?: number
  margemMes?: number | null
  pagoMes?: number
  entradasPrevistas30d?: number
  saidasPrevistas30d?: number
  saldoProjetado30d?: number
  saldoBancario?: number | null
  conciliacoesPendentes?: number
  comissoesAPagar?: number
  reajustesDoMes?: number
  custoServicosMes?: number | null
  margemBrutaMes?: number | null
}

interface Mrr {
  periodo: string
  mrrNovo: number
  mrrExpansion: number
  mrrContraction: number
  mrrChurned: number
  mrrNetNew: number
  mrrTotal: number
  arr: number
  empresasAtivas: number
  logoChurn: number
  mrrChurn: number
  revenueChurn: number
}

const pct = (v: number) => `${Number(v).toLocaleString('pt-BR', { minimumFractionDigits: 2 })}%`

/** Home do financeiro (RN-46) e MRR decomposto (RN-43, RN-44, RN-47, RN-48). */
export function Dashboard(_: { detalhe?: string }) {
  const [ate, setAte] = useState(mesAtual())
  const painel = useDados<Painel>('/dashboard')
  const historico = useDados<Mrr[]>('/mrr/historico', { ate, meses: 6 })
  const atual = historico.dados?.[historico.dados.length - 1]

  return (
    <>
      <PageHeader title="Dashboard financeiro" subtitle="A receber, atrasados, recebimentos e contas do mês · MRR por período" />
      <div className="flex-1 space-y-5 p-5">
        <Conteudo carga={painel.carga} recarregar={painel.recarregar}>
          {(d) => (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
              <Indicador rotulo="A receber hoje" valor={brl(d.aReceberHoje)} />
              <Indicador rotulo="Atrasados" valor={brl(d.atrasados)} detalhe={`${d.cobrancasAtrasadas} cobrança(s)`} tom={d.atrasados ? 'erro' : undefined} />
              <Indicador rotulo="Recebido no mês" valor={brl(d.pagamentosRecebidosMes)} tom="ok" />
              <Indicador rotulo="Contas a pagar no mês" valor={brl(d.contasAPagarMes)} />
              <Indicador rotulo="Inadimplência" valor={brl(d.inadimplenciaTotal)} detalhe="saldo vencido em aberto" />
              <Indicador rotulo="MRR" valor={brl(d.mrrAtual)} detalhe={`ARR ${brl(d.arr)}`} />
            </div>
          )}
        </Conteudo>
        <Conteudo carga={painel.carga}>
          {(d) => d.receitaMes === undefined ? null : (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
              <Indicador rotulo="Resultado do mês" valor={brl(d.resultadoMes)} tom={(d.resultadoMes ?? 0) < 0 ? 'erro' : 'ok'}
                detalhe={`receita ${brl(d.receitaMes)} · despesas ${brl(d.despesasMes)}${d.margemMes != null ? ` · margem ${fmtPct(d.margemMes)}` : ''}`} />
              <Indicador rotulo="Pago no mês" valor={brl(d.pagoMes)} detalhe="contas a pagar quitadas" />
              <Indicador rotulo="Saldo bancário" valor={d.saldoBancario == null ? '—' : brl(d.saldoBancario)}
                detalhe={d.saldoBancario == null ? 'cadastre as contas bancárias' : 'saldo inicial + movimentação'} tom={(d.saldoBancario ?? 0) < 0 ? 'erro' : undefined} />
              <Indicador rotulo="Próximos 30 dias" valor={brl(d.saldoProjetado30d)} tom={(d.saldoProjetado30d ?? 0) < 0 ? 'erro' : undefined}
                detalhe={`entra ${brl(d.entradasPrevistas30d)} · sai ${brl(d.saidasPrevistas30d)}`} />
              <Indicador rotulo="Conciliação pendente" valor={d.conciliacoesPendentes ?? 0} detalhe="transações do extrato" />
              <Indicador rotulo="Comissões a pagar" valor={brl(d.comissoesAPagar)} detalhe={d.reajustesDoMes ? `${d.reajustesDoMes} reajuste(s) previsto(s) no mês` : undefined} />
              {d.margemBrutaMes !== undefined && d.custoServicosMes != null && (
                <Indicador rotulo="Margem bruta (recorrência)" valor={fmtPct(d.margemBrutaMes)} detalhe={`custo dos serviços ${brl(d.custoServicosMes)}`} />
              )}
            </div>
          )}
        </Conteudo>
      </div>

      <FilterBar>
        <span className="text-sm font-semibold text-titulo">MRR por período</span>
        <div className="ml-auto w-44">
          <FormField label="Até">
            <Input type="month" value={ate} onChange={(e) => e.target.value && setAte(e.target.value)} />
          </FormField>
        </div>
      </FilterBar>
      <div className="space-y-4 p-5">
        {atual && (
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Indicador rotulo={`Net New MRR · ${competencia(atual.periodo)}`} valor={brl(atual.mrrNetNew)} tom={atual.mrrNetNew < 0 ? 'erro' : 'ok'} />
            <Indicador rotulo="Logo churn" valor={pct(atual.logoChurn)} detalhe={`${atual.empresasAtivas} empresa(s) ativa(s)`} />
            <Indicador rotulo="MRR churn" valor={pct(atual.mrrChurn)} />
            <Indicador rotulo="Revenue churn" valor={pct(atual.revenueChurn)} detalhe="churn + contraction" />
          </div>
        )}
        <Conteudo carga={historico.carga} recarregar={historico.recarregar}>
          {(lista) => (
            <Tabela atualizando={historico.atualizando} cabecalho={<>
              <Th>Período</Th><Th num>Novo</Th><Th num>Expansion</Th><Th num>Contraction</Th><Th num>Churned</Th>
              <Th num>Net New</Th><Th num>MRR</Th><Th num>ARR</Th>
            </>}>
              {lista.map((m) => (
                <tr key={m.periodo} className={linha}>
                  <Td>{competencia(m.periodo)}</Td>
                  <Td num>{brl(m.mrrNovo)}</Td>
                  <Td num>{brl(m.mrrExpansion)}</Td>
                  <Td num>{brl(m.mrrContraction)}</Td>
                  <Td num>{brl(m.mrrChurned)}</Td>
                  <Td num className="font-semibold text-titulo">{brl(m.mrrNetNew)}</Td>
                  <Td num>{brl(m.mrrTotal)}</Td>
                  <Td num>{brl(m.arr)}</Td>
                </tr>
              ))}
            </Tabela>
          )}
        </Conteudo>
      </div>
    </>
  )
}
