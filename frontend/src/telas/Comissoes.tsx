import { useState, type FormEvent } from 'react'
import { BadgePercent, Pencil, Plus, Trash2 } from 'lucide-react'
import { Button, Checkbox, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Pagination, Select, Tabs } from '@/components/ui'
import { BotoesExportar, Conteudo, Indicador, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { BASES_COMISSAO, BENEFICIARIOS, brl, competencia, curto, data, mesAtual, pct } from '@/lib/formato'
import { api, type Pagina } from '@/plataforma/api'
import { CampoUuid } from './Cobrancas'

interface Comissao {
  id: string
  regra: string | null
  beneficiarioTipo: string
  beneficiarioId: string
  cobrancaId: string
  competencia: string
  baseCalculo: number
  percentual: number | null
  valor: number
  status: string
  contaPagarId: string | null
}
interface Regra {
  id: string
  nome: string
  beneficiarioTipo: string
  beneficiarioId: string | null
  servicoId: string | null
  fabricante: string | null
  base: string
  valor: number
  mesesRecorrencia: number | null
  prioridade: number
  ativa: boolean
  vigenciaInicio: string | null
  vigenciaFim: string | null
}

/** §71: comissão de vendedor, SDR e parceiro, apurada no pagamento e lançada em contas a pagar no fechamento. */
export function Comissoes(_: { detalhe?: string }) {
  const { tem } = usePermissao()
  const administra = tem('financeiro.comissao.administrar')
  const [aba, setAba] = useState(0)
  return (
    <>
      <PageHeader title="Comissões" subtitle={administra ? 'Apuradas automaticamente quando o pagamento é confirmado' : 'Suas comissões'} />
      {administra && <Tabs tabs={['Comissões', 'Regras']} active={aba} onChange={setAba} />}
      {aba === 0 ? <ListaDeComissoes administra={administra} /> : <Regras />}
    </>
  )
}

function ListaDeComissoes({ administra }: { administra: boolean }) {
  const [filtro, setFiltro] = useState({ competencia: mesAtual(), status: '', beneficiarioTipo: '', pagina: 0 })
  const query = { ...filtro, competencia: filtro.competencia ? `${filtro.competencia}-01` : '', tamanho: 30 }
  const lista = useDados<Pagina<Comissao>>('/comissoes', query)
  const { executar, ocupado } = useAcao()
  const total = lista.dados?.itens.reduce((s, c) => s + (c.status === 'CANCELADA' ? 0 : c.valor), 0) ?? 0
  const aPagar = lista.dados?.itens.filter((c) => c.status === 'A_PAGAR').reduce((s, c) => s + c.valor, 0) ?? 0
  const fechar = () => confirm(`Lançar as comissões a pagar de ${competencia(filtro.competencia)} em contas a pagar (uma por beneficiário)? Elas passam pela aprovação normal.`)
    && executar(() => api('/comissoes/fechamento', { method: 'POST', query: { competencia: `${filtro.competencia}-01` } }), lista.recarregar)
  return (
    <>
      <FilterBar>
        <Input aria-label="Competência" type="month" className="w-auto" value={filtro.competencia} onChange={(e) => setFiltro({ ...filtro, competencia: e.target.value, pagina: 0 })} />
        <Select aria-label="Situação" className="w-auto" value={filtro.status} onChange={(e) => setFiltro({ ...filtro, status: e.target.value, pagina: 0 })}>
          <option value="">Todas</option><option value="A_PAGAR">A pagar</option><option value="LANCADA">Lançadas</option><option value="CANCELADA">Canceladas</option>
        </Select>
        {administra && (
          <Select aria-label="Tipo" className="w-auto" value={filtro.beneficiarioTipo} onChange={(e) => setFiltro({ ...filtro, beneficiarioTipo: e.target.value, pagina: 0 })}>
            <option value="">Vendedor, SDR e parceiro</option>
            {Object.entries(BENEFICIARIOS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </Select>
        )}
        <div className="ml-auto flex gap-2">
          <BotoesExportar caminho="/relatorios/comissoes/exportar" query={{ competencia: query.competencia, status: filtro.status }} nome="comissoes" />
          {administra && filtro.competencia && <Button size="sm" disabled={ocupado || aPagar === 0} onClick={fechar}>Fechar competência</Button>}
        </div>
      </FilterBar>
      <div className="flex-1 space-y-4 p-5">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
          <Indicador rotulo="Total no filtro" valor={brl(total)} />
          <Indicador rotulo="A pagar" valor={brl(aPagar)} tom={aPagar ? 'ok' : undefined} />
          <Indicador rotulo="Comissões" valor={lista.dados?.total ?? '—'} />
        </div>
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(p) => p.itens.length === 0} icone={<BadgePercent aria-hidden />} textoVazio="Nenhuma comissão neste filtro">
          {(p) => (
            <>
              <Tabela atualizando={lista.atualizando} cabecalho={<>
                <Th>Beneficiário</Th><Th>Regra</Th><Th className="hidden md:table-cell">Cobrança</Th><Th>Compet.</Th><Th num>Base</Th><Th num>%</Th><Th num>Valor</Th><Th>Status</Th><Th />
              </>}>
                {p.itens.map((c) => (
                  <tr key={c.id} className={linha}>
                    <Td>{BENEFICIARIOS[c.beneficiarioTipo]} <span className="font-mono text-xs text-texto-3">#{curto(c.beneficiarioId)}</span></Td>
                    <Td>{c.regra ?? '—'}</Td>
                    <Td className="hidden md:table-cell"><span className="font-mono text-xs">#{curto(c.cobrancaId)}</span></Td>
                    <Td>{competencia(c.competencia)}</Td>
                    <Td num>{brl(c.baseCalculo)}</Td>
                    <Td num>{c.percentual === null ? '—' : pct(c.percentual)}</Td>
                    <Td num className="font-semibold text-titulo">{brl(c.valor)}</Td>
                    <Td><Status status={c.status} /></Td>
                    <Td className="text-right">
                      {administra && c.status === 'A_PAGAR' && (
                        <Button size="sm" variant="ghost" disabled={ocupado} onClick={() => confirm('Cancelar esta comissão?') && executar(() => api(`/comissoes/${c.id}/cancelar`, { method: 'POST' }), lista.recarregar)}>Cancelar</Button>
                      )}
                    </Td>
                  </tr>
                ))}
              </Tabela>
              <Pagination pagina={p.pagina} tamanho={p.tamanho} total={p.total} onChange={(n) => setFiltro({ ...filtro, pagina: n })} />
            </>
          )}
        </Conteudo>
      </div>
    </>
  )
}

function Regras() {
  const lista = useDados<Regra[]>('/comissoes/regras')
  const { executar, ocupado } = useAcao()
  const [formulario, setFormulario] = useState<Regra | 'nova' | null>(null)
  return (
    <>
      <FilterBar>
        <p className="text-xs text-texto-3">Para cada beneficiário vale a regra mais específica (beneficiário &gt; serviço &gt; fabricante); empate pela menor prioridade.</p>
        <Button size="sm" className="ml-auto" onClick={() => setFormulario('nova')}><Plus aria-hidden className="size-3.5" /> Nova regra</Button>
      </FilterBar>
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} textoVazio="Nenhuma regra de comissão" icone={<BadgePercent aria-hidden />}>
          {(l) => (
            <Tabela cabecalho={<><Th>Regra</Th><Th>Para</Th><Th>Base</Th><Th num>Valor</Th><Th className="hidden md:table-cell">Filtro</Th><Th className="hidden md:table-cell">Vigência</Th><Th>Situação</Th><Th /></>}>
              {l.map((r) => (
                <tr key={r.id} className={linha}>
                  <Td><p className="font-medium text-titulo">{r.nome}</p><p className="text-xs text-texto-3">prioridade {r.prioridade}</p></Td>
                  <Td>{BENEFICIARIOS[r.beneficiarioTipo]}{r.beneficiarioId && <span className="ml-1 font-mono text-xs text-texto-3">#{curto(r.beneficiarioId)}</span>}</Td>
                  <Td>{BASES_COMISSAO[r.base]}{r.mesesRecorrencia && <p className="text-xs text-texto-3">{r.mesesRecorrencia} meses</p>}</Td>
                  <Td num>{r.base === 'FIXA' ? brl(r.valor) : pct(r.valor)}</Td>
                  <Td className="hidden md:table-cell">{[r.servicoId && `serviço #${curto(r.servicoId)}`, r.fabricante].filter(Boolean).join(' · ') || 'Toda a cobrança'}</Td>
                  <Td className="hidden md:table-cell">{r.vigenciaInicio || r.vigenciaFim ? `${r.vigenciaInicio ? data(r.vigenciaInicio) : '…'} a ${r.vigenciaFim ? data(r.vigenciaFim) : '…'}` : 'Sempre'}</Td>
                  <Td><Status status={r.ativa ? 'ativa' : 'inativa'} texto={r.ativa ? 'Ativa' : 'Inativa'} /></Td>
                  <Td className="text-right whitespace-nowrap">
                    <Button size="sm" variant="ghost" onClick={() => setFormulario(r)}><Pencil aria-hidden className="size-3.5" /></Button>
                    <Button size="sm" variant="ghost" disabled={ocupado} aria-label="Excluir" onClick={() => confirm('Excluir a regra? As comissões já apuradas continuam.') && executar(() => api(`/comissoes/regras/${r.id}`, { method: 'DELETE' }), lista.recarregar)}>
                      <Trash2 aria-hidden className="size-3.5" />
                    </Button>
                  </Td>
                </tr>
              ))}
            </Tabela>
          )}
        </Conteudo>
      </div>
      {formulario && <FormularioRegra atual={formulario === 'nova' ? null : formulario} aoFechar={() => setFormulario(null)} aoSalvar={() => { setFormulario(null); lista.recarregar() }} />}
    </>
  )
}

function FormularioRegra({ atual, aoFechar, aoSalvar }: { atual: Regra | null; aoFechar: () => void; aoSalvar: () => void }) {
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({
    nome: atual?.nome ?? '', beneficiarioTipo: atual?.beneficiarioTipo ?? 'VENDEDOR', beneficiarioId: atual?.beneficiarioId ?? '',
    servicoId: atual?.servicoId ?? '', fabricante: atual?.fabricante ?? '', base: atual?.base ?? 'RECORRENTE', valor: atual ? String(atual.valor) : '',
    mesesRecorrencia: atual?.mesesRecorrencia ? String(atual.mesesRecorrencia) : '', prioridade: String(atual?.prioridade ?? 100), ativa: atual?.ativa ?? true,
    vigenciaInicio: atual?.vigenciaInicio ?? '', vigenciaFim: atual?.vigenciaFim ?? '',
  })
  const campo = (k: keyof typeof f) => ({ value: String(f[k]), onChange: (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value }) })
  const salvar = (e: FormEvent) => {
    e.preventDefault()
    const corpo = { ...f, valor: Number(f.valor), prioridade: Number(f.prioridade), mesesRecorrencia: f.mesesRecorrencia ? Number(f.mesesRecorrencia) : '' }
    void executar(() => api(atual ? `/comissoes/regras/${atual.id}` : '/comissoes/regras', { method: atual ? 'PUT' : 'POST', body: corpo }), aoSalvar)
  }
  return (
    <Modal open onClose={aoFechar} title={atual ? 'Editar regra de comissão' : 'Nova regra de comissão'}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-regra-com" loading={ocupado}>Salvar</Button></>}>
      <form id="form-regra-com" onSubmit={salvar}>
        <FormSection number={1} title="Quem recebe">
          <FormField label="Nome da regra" required colSpan={2}><Input required maxLength={120} {...campo('nome')} /></FormField>
          <FormField label="Tipo" required>
            <Select {...campo('beneficiarioTipo')}>{Object.entries(BENEFICIARIOS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select>
          </FormField>
          <CampoUuid label="Beneficiário específico (opcional)" valor={f.beneficiarioId} onChange={(v) => setF({ ...f, beneficiarioId: v })} />
        </FormSection>
        <FormSection number={2} title="Como calcula">
          <FormField label="Base" required><Select {...campo('base')}>{Object.entries(BASES_COMISSAO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></FormField>
          <FormField label={f.base === 'FIXA' ? 'Valor (R$)' : 'Percentual (%)'} required>
            <Input type="number" step="0.0001" min="0.0001" max={f.base === 'FIXA' ? undefined : 100} required {...campo('valor')} />
          </FormField>
          {f.base === 'RECORRENTE' && <FormField label="Por quantos meses" hint="Vazio = enquanto o contrato durar"><Input type="number" min={1} max={120} {...campo('mesesRecorrencia')} /></FormField>}
          <FormField label="Prioridade" hint="Menor vale primeiro no empate"><Input type="number" min={0} max={9999} {...campo('prioridade')} /></FormField>
        </FormSection>
        <FormSection number={3} title="Filtro e vigência (opcional)">
          <CampoUuid label="Só do serviço" valor={f.servicoId} onChange={(v) => setF({ ...f, servicoId: v })} />
          <FormField label="Só do fabricante"><Input maxLength={80} placeholder="Microsoft, Acronis…" {...campo('fabricante')} /></FormField>
          <FormField label="Vigente de"><Input type="date" {...campo('vigenciaInicio')} /></FormField>
          <FormField label="Vigente até"><Input type="date" {...campo('vigenciaFim')} /></FormField>
          <label className="flex items-center gap-2 text-sm text-texto-2"><Checkbox checked={f.ativa} onChange={(e) => setF({ ...f, ativa: e.target.checked })} /> Regra ativa</label>
        </FormSection>
      </form>
    </Modal>
  )
}
