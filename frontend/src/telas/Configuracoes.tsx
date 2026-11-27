import { useEffect, useState, type FormEvent } from 'react'
import { Settings } from 'lucide-react'
import { Button, Checkbox, FormField, FormSection, Input, PageHeader } from '@/components/ui'
import { Conteudo } from '@/components/modulo'
import { useAcao, useDados } from '@/lib/hooks'
import { api } from '@/plataforma/api'

interface Configuracao {
  encargosAutomaticos: boolean
  multaPercentual: number
  jurosMensalPercentual: number
  diasCarencia: number
  conciliacaoToleranciaDias: number
  alertaConsumoPercentual: number
}

/** §113: parâmetros financeiros do tenant, sem código. Sem nada salvo valem os padrões (encargos desligados). */
export function Configuracoes(_: { detalhe?: string }) {
  const atual = useDados<Configuracao>('/configuracoes')
  return (
    <>
      <PageHeader title="Configurações do financeiro" subtitle="Encargos por atraso, conciliação e alertas de consumo" />
      <div className="flex-1 p-5">
        <Conteudo carga={atual.carga} recarregar={atual.recarregar} icone={<Settings aria-hidden />}>
          {(c) => <Formulario inicial={c} aoSalvar={atual.recarregar} />}
        </Conteudo>
      </div>
    </>
  )
}

function Formulario({ inicial, aoSalvar }: { inicial: Configuracao; aoSalvar: () => void }) {
  const { executar, ocupado } = useAcao()
  const texto = (c: Configuracao) => ({
    encargosAutomaticos: c.encargosAutomaticos, multaPercentual: String(c.multaPercentual), jurosMensalPercentual: String(c.jurosMensalPercentual),
    diasCarencia: String(c.diasCarencia), conciliacaoToleranciaDias: String(c.conciliacaoToleranciaDias), alertaConsumoPercentual: String(c.alertaConsumoPercentual),
  })
  const [f, setF] = useState(texto(inicial))
  useEffect(() => setF(texto(inicial)), [inicial])
  const campo = (k: Exclude<keyof typeof f, 'encargosAutomaticos'>) => ({ value: f[k], onChange: (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value }) })

  const salvar = (e: FormEvent) => {
    e.preventDefault()
    void executar(() => api('/configuracoes', {
      method: 'PUT',
      body: {
        encargosAutomaticos: f.encargosAutomaticos, multaPercentual: Number(f.multaPercentual), jurosMensalPercentual: Number(f.jurosMensalPercentual),
        diasCarencia: Number(f.diasCarencia), conciliacaoToleranciaDias: Number(f.conciliacaoToleranciaDias), alertaConsumoPercentual: Number(f.alertaConsumoPercentual),
      },
    }), aoSalvar)
  }
  const recalcular = () => confirm('Recalcular agora juros e multa de todas as cobranças atrasadas? Valores digitados à mão não são alterados.')
    && executar(() => api('/configuracoes/encargos/recalcular', { method: 'POST' }))

  const exemplo = (() => {
    // Mesma regra de EncargosService.calcular: dias contados depois da carência
    const v = 1000
    const dias = Math.max(0, 30 - Number(f.diasCarencia || 0))
    const multa = dias > 0 ? (v * Number(f.multaPercentual || 0)) / 100 : 0
    const juros = ((v * Number(f.jurosMensalPercentual || 0)) / 100 / 30) * dias
    return { multa, juros, total: v + multa + juros }
  })()

  return (
    <form onSubmit={salvar} className="max-w-3xl rounded-xl border border-borda bg-superficie p-5 shadow-sm">
      <FormSection number={1} title="Juros e multa por atraso">
        <label className="flex items-start gap-2 text-sm text-texto-2 md:col-span-2">
          <Checkbox className="mt-0.5" checked={f.encargosAutomaticos} onChange={(e) => setF({ ...f, encargosAutomaticos: e.target.checked })} />
          <span>
            <span className="font-medium text-titulo">Calcular juros e multa automaticamente</span>
            <span className="block text-xs text-texto-3">
              Desligado, as cobranças só têm os encargos digitados à mão (comportamento anterior). Ligado, o cálculo roda todo dia nas cobranças atrasadas;
              juros e multa digitados à mão em uma cobrança nunca são sobrescritos.
            </span>
          </span>
        </label>
        <FormField label="Multa (%)" required hint="Uma vez, sobre o valor (CDC: até 2%)"><Input type="number" step="0.01" min={0} max={20} required {...campo('multaPercentual')} /></FormField>
        <FormField label="Juros ao mês (%)" required hint="Pró-rata por dia de atraso"><Input type="number" step="0.01" min={0} max={20} required {...campo('jurosMensalPercentual')} /></FormField>
        <FormField label="Dias de carência" required hint="Sem encargos até N dias após o vencimento"><Input type="number" min={0} max={30} required {...campo('diasCarencia')} /></FormField>
        <div className="rounded-lg bg-superficie-2 p-3 text-xs text-texto-2">
          Exemplo: R$ 1.000,00 com 30 dias de atraso → multa {exemplo.multa.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })} + juros{' '}
          {exemplo.juros.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })} = <strong>{exemplo.total.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })}</strong>
        </div>
      </FormSection>
      <FormSection number={2} title="Conciliação bancária">
        <FormField label="Tolerância de datas (dias)" required hint="Diferença máxima entre o extrato e o lançamento para sugerir o par">
          <Input type="number" min={0} max={15} required {...campo('conciliacaoToleranciaDias')} />
        </FormField>
      </FormSection>
      <FormSection number={3} title="Consumo variável">
        <FormField label="Alerta de consumo (% da franquia)" required hint="Avisa quando o consumo passa deste percentual">
          <Input type="number" step="0.01" min={50} max={500} required {...campo('alertaConsumoPercentual')} />
        </FormField>
      </FormSection>
      <div className="flex flex-wrap justify-end gap-2 border-t border-borda pt-4">
        <Button type="button" variant="outline" disabled={ocupado || !inicial.encargosAutomaticos} title={!inicial.encargosAutomaticos ? 'Ligue e salve o cálculo automático antes' : undefined} onClick={recalcular}>
          Recalcular encargos agora
        </Button>
        <Button type="submit" loading={ocupado}>Salvar</Button>
      </div>
    </form>
  )
}
