import { useState, type FormEvent } from 'react'
import { CreditCard, Gauge, Plus, Repeat, Trash2, TrendingUp } from 'lucide-react'
import { Button, Checkbox, DetailField, DetailSection, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Pagination, Select, Tabs, Textarea } from '@/components/ui'
import { CabecalhoDeDetalhe, Conteudo, Copiar, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { useCadastros } from '@/lib/cadastros'
import { useEmpresas } from '@/lib/empresas'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { brl, competencia, curto, data, dataHora, FORMAS, INDICES, mesAtual, numero, PERIODICIDADES, pct } from '@/lib/formato'
import { api, type Pagina } from '@/plataforma/api'
import { CampoUuid } from './Cobrancas'

interface Item {
  servicoId: string
  descricao: string
  quantidade: number | string
  unidade: string
  valorUnitario: number | string
  franquia?: number | string | null
  // V2 (§26, §69)
  valorExcedente?: number | string | null
  custoUnitario?: number | string | null
  fabricante?: string | null
  margemPercentual?: number | null
  total?: number
}

interface Regra {
  id: string
  contratoId: string | null
  clienteId: string
  descricao: string | null
  periodicidade: string
  diaGeracao: number
  diasParaVencimento: number
  formaPagamento: string | null
  proximaCompetencia: string
  valorProximoCiclo: number
  itens: Item[]
  ativa: boolean
  vigenciaFim: string | null
  // V2
  vendedorId?: string | null
  sdrId?: string | null
  parceiroId?: string | null
  categoriaId?: string | null
  centroCustoId?: string | null
  indiceReajuste?: string | null
  mesReajuste?: number | null
  cobrancaAutomatica?: boolean
  cartaoFinal?: string | null
  cartaoBandeira?: string | null
  mrr?: number
}

interface Consumo {
  id: string
  servicoId: string
  descricao: string | null
  unidade: string | null
  competencia: string
  quantidade: number
  permitido: number | null
  excedente: number
  valorExcedente: number
  percentualUso: number | null
  origem: string
  observacao: string | null
  cobrancaId: string | null
  alertadoEm: string | null
  sugestao: string | null
}
interface Adicional { id: string; competencia: string; descricao: string; valor: number; cobrancaId: string | null; createdAt: string }
interface Reajuste {
  id: string
  indice: string
  percentual: number
  competenciaAplicacao: string
  valorAnterior: number
  valorNovo: number
  observacao: string | null
  createdAt: string
}

const MESES = ['Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho', 'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro']
const itemVazio = (): Item => ({ servicoId: '', descricao: '', quantidade: 1, unidade: 'UN', valorUnitario: '', valorExcedente: '', custoUnitario: '', fabricante: '' })
const vazioNulo = (v: unknown) => (v === '' || v === undefined ? null : v)

/** RF-03: valor do ciclo sempre dos itens (RN-14); um aditivo vale da próxima geração (RN-17). */
export function Recorrencias(_: { detalhe?: string }) {
  const { tem } = usePermissao()
  const administra = tem('financeiro.recorrencia.administrar')
  const [filtro, setFiltro] = useState({ ativa: '', pagina: 0 })
  const lista = useDados<Pagina<Regra>>('/recorrencias', { ...filtro, tamanho: 20 })
  const pendentes = useDados<Regra[]>('/recorrencias/reajustes-pendentes')
  const empresas = useEmpresas(lista.dados?.itens.map((r) => r.clienteId) ?? [])
  const { executar, ocupado } = useAcao()
  const [formulario, setFormulario] = useState<Regra | 'nova' | null>(null)
  const [aberta, setAberta] = useState<string | null>(null)

  const gerar = (r: Regra) => executar(() => api(`/recorrencias/${r.id}/gerar-cobranca`, { method: 'POST' }), lista.recarregar)

  return (
    <>
      <PageHeader title="Recorrência" subtitle="Regras que geram a cobrança de cada ciclo no dia configurado"
        actions={administra && <Button size="sm" onClick={() => setFormulario('nova')}><Plus aria-hidden className="size-3.5" /> Nova regra</Button>} />
      <FilterBar>
        <Select aria-label="Situação" className="w-auto" value={filtro.ativa} onChange={(e) => setFiltro({ ativa: e.target.value, pagina: 0 })}>
          <option value="">Todas</option>
          <option value="true">Ativas</option>
          <option value="false">Encerradas</option>
        </Select>
        {!!pendentes.dados?.length && (
          <span className="ml-auto flex items-center gap-1.5 text-xs text-amber-700 dark:text-amber-400">
            <TrendingUp aria-hidden className="size-3.5" /> {pendentes.dados.length} regra(s) com reajuste previsto para este mês ainda não aplicado
          </span>
        )}
      </FilterBar>
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(p) => p.itens.length === 0} icone={<Repeat aria-hidden />}
          textoVazio="Nenhuma regra de recorrência">
          {(p) => (
            <>
              <Tabela atualizando={lista.atualizando} cabecalho={<>
                <Th>Empresa</Th><Th>Regra</Th><Th className="hidden md:table-cell">Periodicidade</Th><Th>Próximo ciclo</Th>
                <Th num>Valor do ciclo</Th><Th>Situação</Th><Th />
              </>}>
                {p.itens.map((r) => (
                  <tr key={r.id} className={linha}>
                    <Td><span className="font-medium text-titulo" title={r.clienteId}>{empresas.nome(r.clienteId)}</span></Td>
                    <Td>
                      {r.descricao ?? 'Mensalidade'}
                      <p className="text-xs text-texto-3">
                        {r.itens.length} item(ns)
                        {r.cartaoFinal && ` · cartão ${r.cartaoBandeira ?? ''} final ${r.cartaoFinal}${r.cobrancaAutomatica ? ' (automático)' : ''}`}
                      </p>
                    </Td>
                    <Td className="hidden md:table-cell">
                      {PERIODICIDADES[r.periodicidade]} · dia {r.diaGeracao}
                      {r.indiceReajuste && r.mesReajuste && <p className="text-xs text-texto-3">reajuste {INDICES[r.indiceReajuste]} em {MESES[r.mesReajuste - 1].toLowerCase()}</p>}
                    </Td>
                    <Td>{competencia(r.proximaCompetencia)}</Td>
                    <Td num>{brl(r.valorProximoCiclo)}{r.mrr !== undefined && r.periodicidade !== 'MENSAL' && <p className="text-xs text-texto-3">MRR {brl(r.mrr)}</p>}</Td>
                    <Td><Status status={r.ativa ? 'ativa' : 'inativa'} />{r.vigenciaFim && <p className="text-xs text-texto-3">até {data(r.vigenciaFim)}</p>}</Td>
                    <Td className="text-right whitespace-nowrap">
                      <div className="flex justify-end gap-1.5">
                        <Button size="sm" variant="ghost" onClick={() => setAberta(r.id)}>Detalhes</Button>
                        {administra && (
                          <>
                            <Button size="sm" variant="outline" onClick={() => setFormulario(r)}>Editar</Button>
                            {r.ativa && <Button size="sm" disabled={ocupado} onClick={() => gerar(r)} title="Gera a cobrança da competência atual (uma por ciclo)">Gerar ciclo</Button>}
                          </>
                        )}
                      </div>
                    </Td>
                  </tr>
                ))}
              </Tabela>
              <Pagination pagina={p.pagina} tamanho={p.tamanho} total={p.total} onChange={(n) => setFiltro({ ...filtro, pagina: n })} />
            </>
          )}
        </Conteudo>
      </div>

      {formulario && (
        <FormularioDeRegra atual={formulario === 'nova' ? null : formulario} aoFechar={() => setFormulario(null)}
          aoSalvar={() => { setFormulario(null); lista.recarregar() }} />
      )}
      <Modal open={aberta !== null} onClose={() => setAberta(null)} size="xl" noPad title="Detalhe da recorrência">
        {aberta && <Detalhe id={aberta} nomeEmpresa={empresas.nome} aoMudar={() => { lista.recarregar(); pendentes.recarregar() }} />}
      </Modal>
    </>
  )
}

function FormularioDeRegra({ atual, aoFechar, aoSalvar }: { atual: Regra | null; aoFechar: () => void; aoSalvar: () => void }) {
  const { tem } = usePermissao()
  const veMargem = tem('financeiro.margem.ver')
  const cad = useCadastros()
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({
    clienteId: atual?.clienteId ?? '', contratoId: atual?.contratoId ?? '', descricao: atual?.descricao ?? '',
    periodicidade: atual?.periodicidade ?? 'MENSAL', diaGeracao: String(atual?.diaGeracao ?? 5),
    diasParaVencimento: String(atual?.diasParaVencimento ?? 10), formaPagamento: atual?.formaPagamento ?? 'PIX', ativa: atual?.ativa ?? true,
    vendedorId: atual?.vendedorId ?? '', sdrId: atual?.sdrId ?? '', parceiroId: atual?.parceiroId ?? '',
    categoriaId: atual?.categoriaId ?? '', centroCustoId: atual?.centroCustoId ?? '',
    indiceReajuste: atual?.indiceReajuste ?? '', mesReajuste: atual?.mesReajuste ? String(atual.mesReajuste) : '',
  })
  const [itens, setItens] = useState<Item[]>(atual?.itens.length
    ? atual.itens.map((i) => ({
      servicoId: i.servicoId, descricao: i.descricao, quantidade: i.quantidade, unidade: i.unidade, valorUnitario: i.valorUnitario,
      franquia: i.franquia ?? '', valorExcedente: i.valorExcedente ?? '', custoUnitario: i.custoUnitario ?? '', fabricante: i.fabricante ?? '',
    }))
    : [itemVazio()])
  const total = itens.reduce((s, i) => s + Number(i.quantidade || 0) * Number(i.valorUnitario || 0), 0)
  const custo = itens.reduce((s, i) => s + Number(i.quantidade || 0) * Number(i.custoUnitario || 0), 0)
  const alterar = (idx: number, campo: keyof Item, valor: string) => setItens(itens.map((it, i) => (i === idx ? { ...it, [campo]: valor } : it)))
  const categorias = cad.categorias.filter((c) => c.tipo === 'RECEITA' && (c.ativa || c.id === f.categoriaId))
  const centros = cad.centros.filter((c) => c.ativo || c.id === f.centroCustoId)

  const salvar = (e: FormEvent) => {
    e.preventDefault()
    const corpo = {
      ...f, diaGeracao: Number(f.diaGeracao), diasParaVencimento: Number(f.diasParaVencimento),
      indiceReajuste: vazioNulo(f.indiceReajuste), mesReajuste: f.mesReajuste ? Number(f.mesReajuste) : null,
      itens: itens.map((i) => ({
        ...i, franquia: vazioNulo(i.franquia), valorExcedente: vazioNulo(i.valorExcedente), fabricante: vazioNulo(i.fabricante),
        // Sem permissão de margem o custo vai nulo e a API mantém o já gravado
        custoUnitario: veMargem ? vazioNulo(i.custoUnitario) : null,
      })),
    }
    void executar(() => (atual ? api(`/recorrencias/${atual.id}`, { method: 'PUT', body: corpo }) : api('/recorrencias', { method: 'POST', body: corpo })), aoSalvar)
  }

  return (
    <Modal open onClose={aoFechar} size="full" title={atual ? 'Editar regra' : 'Nova regra de recorrência'}
      subtitle={atual ? 'A alteração vale a partir do próximo ciclo; as cobranças já geradas não mudam.' : 'A primeira competência é a do mês atual.'}
      footer={<><span className="mr-auto text-sm text-texto-2">Valor por ciclo: <strong className="text-titulo">{brl(total)}</strong>
        {veMargem && custo > 0 && <span className="ml-3 text-texto-3">custo {brl(custo)} · margem {pct(total ? ((total - custo) / total) * 100 : null)}</span>}</span>
        <Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-regra" loading={ocupado}>Salvar regra</Button></>}>
      <form id="form-regra" onSubmit={salvar}>
        <FormSection number={1} title="Empresa e contrato">
          <CampoUuid label="Empresa (id do CRM)" required valor={f.clienteId} onChange={(v) => setF({ ...f, clienteId: v })} />
          <CampoUuid label="Contrato (opcional)" valor={f.contratoId} onChange={(v) => setF({ ...f, contratoId: v })} />
          <FormField label="Descrição" colSpan={2}><Input maxLength={200} placeholder="Plano gerenciado" value={f.descricao} onChange={(e) => setF({ ...f, descricao: e.target.value })} /></FormField>
        </FormSection>
        <FormSection number={2} title="Ciclo">
          <FormField label="Periodicidade" required>
            <Select value={f.periodicidade} onChange={(e) => setF({ ...f, periodicidade: e.target.value })}>
              {Object.entries(PERIODICIDADES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
          </FormField>
          <FormField label="Forma de pagamento">
            <Select value={f.formaPagamento} onChange={(e) => setF({ ...f, formaPagamento: e.target.value })}>
              {Object.entries(FORMAS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
          </FormField>
          <FormField label="Dia de geração" required hint="1 a 28"><Input type="number" min={1} max={28} required value={f.diaGeracao} onChange={(e) => setF({ ...f, diaGeracao: e.target.value })} /></FormField>
          <FormField label="Vence em (dias)" required hint="Depois do dia de geração"><Input type="number" min={0} max={60} required value={f.diasParaVencimento} onChange={(e) => setF({ ...f, diasParaVencimento: e.target.value })} /></FormField>
          {atual && (
            <label className="flex items-center gap-2 text-sm text-texto-2"><Checkbox checked={f.ativa} onChange={(e) => setF({ ...f, ativa: e.target.checked })} /> Regra ativa</label>
          )}
        </FormSection>
        <FormSection number={3} title="Itens (serviço × quantidade × valor unitário)">
          <div className="space-y-2 md:col-span-2">
            {itens.map((it, i) => (
              <div key={i} className="rounded-lg border border-borda p-3">
                <div className="grid grid-cols-2 items-end gap-2 md:grid-cols-[2fr_2fr_1fr_1fr_1fr_1fr_auto]">
                  <FormField label="Serviço (id)" required>
                    <div className="flex gap-1">
                      <Input required value={it.servicoId} onChange={(e) => alterar(i, 'servicoId', e.target.value.trim())} className="flex-1 font-mono text-xs" placeholder="UUID de Produtos e Serviços" />
                      <Button variant="ghost" size="sm" onClick={() => alterar(i, 'servicoId', crypto.randomUUID())} title="Gerar id">⟳</Button>
                    </div>
                  </FormField>
                  <FormField label="Descrição" required><Input required maxLength={200} value={it.descricao} onChange={(e) => alterar(i, 'descricao', e.target.value)} /></FormField>
                  <FormField label="Qtd." required><Input type="number" step="0.001" min="0.001" required value={it.quantidade} onChange={(e) => alterar(i, 'quantidade', e.target.value)} /></FormField>
                  <FormField label="Unid." required><Input required maxLength={20} value={it.unidade} onChange={(e) => alterar(i, 'unidade', e.target.value)} /></FormField>
                  <FormField label="Unitário" required><Input type="number" step="0.01" min="0" required value={it.valorUnitario} onChange={(e) => alterar(i, 'valorUnitario', e.target.value)} /></FormField>
                  <FormField label="Franquia"><Input type="number" step="0.001" min="0" value={it.franquia ?? ''} onChange={(e) => alterar(i, 'franquia', e.target.value)} /></FormField>
                  <Button variant="ghost" size="md" aria-label="Remover item" disabled={itens.length === 1} onClick={() => setItens(itens.filter((_, j) => j !== i))}>
                    <Trash2 aria-hidden className="size-4" />
                  </Button>
                </div>
                <div className="mt-2 grid grid-cols-2 items-end gap-2 md:grid-cols-[2fr_1fr_1fr_3fr]">
                  <FormField label="Fabricante"><Input maxLength={80} placeholder="Microsoft, Acronis…" value={it.fabricante ?? ''} onChange={(e) => alterar(i, 'fabricante', e.target.value)} /></FormField>
                  <FormField label="Preço do excedente" hint="Por unidade acima da franquia">
                    <Input type="number" step="0.01" min="0" value={it.valorExcedente ?? ''} onChange={(e) => alterar(i, 'valorExcedente', e.target.value)} />
                  </FormField>
                  {veMargem ? (
                    <FormField label="Custo unitário" hint="Só quem vê margem">
                      <Input type="number" step="0.01" min="0" value={it.custoUnitario ?? ''} onChange={(e) => alterar(i, 'custoUnitario', e.target.value)} />
                    </FormField>
                  ) : <span />}
                  <p className="pb-2 text-xs text-texto-3">
                    Total {brl(Number(it.quantidade || 0) * Number(it.valorUnitario || 0))}
                    {veMargem && Number(it.custoUnitario) > 0 && Number(it.valorUnitario) > 0 && ` · margem ${pct(((Number(it.valorUnitario) - Number(it.custoUnitario)) / Number(it.valorUnitario)) * 100)}`}
                  </p>
                </div>
              </div>
            ))}
            <Button variant="outline" size="sm" onClick={() => setItens([...itens, itemVazio()])}><Plus aria-hidden className="size-3.5" /> Item</Button>
          </div>
        </FormSection>
        <FormSection number={4} title="Reajuste anual (opcional)">
          <FormField label="Índice">
            <Select value={f.indiceReajuste} onChange={(e) => setF({ ...f, indiceReajuste: e.target.value })}>
              <option value="">Sem reajuste previsto</option>
              {Object.entries(INDICES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
          </FormField>
          <FormField label="Mês do reajuste" hint="A regra aparece como pendente neste mês">
            <Select value={f.mesReajuste} disabled={!f.indiceReajuste} onChange={(e) => setF({ ...f, mesReajuste: e.target.value })}>
              <option value="">—</option>
              {MESES.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
            </Select>
          </FormField>
        </FormSection>
        {(categorias.length > 0 || centros.length > 0) && (
          <FormSection number={5} title="Classificação das cobranças geradas">
            {categorias.length > 0 && (
              <FormField label="Categoria">
                <Select value={f.categoriaId} onChange={(e) => setF({ ...f, categoriaId: e.target.value })}>
                  <option value="">Sem categoria</option>
                  {categorias.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
                </Select>
              </FormField>
            )}
            {centros.length > 0 && (
              <FormField label="Centro de custo">
                <Select value={f.centroCustoId} onChange={(e) => setF({ ...f, centroCustoId: e.target.value })}>
                  <option value="">Sem centro de custo</option>
                  {centros.map((c) => <option key={c.id} value={c.id}>{c.codigo} · {c.nome}</option>)}
                </Select>
              </FormField>
            )}
          </FormSection>
        )}
        <FormSection number={categorias.length > 0 || centros.length > 0 ? 6 : 5} title="Comissão (opcional)">
          <CampoUuid label="Vendedor (id do usuário)" valor={f.vendedorId} onChange={(v) => setF({ ...f, vendedorId: v })} />
          <CampoUuid label="SDR" valor={f.sdrId} onChange={(v) => setF({ ...f, sdrId: v })} />
          <CampoUuid label="Parceiro" valor={f.parceiroId} onChange={(v) => setF({ ...f, parceiroId: v })} />
        </FormSection>
      </form>
    </Modal>
  )
}

function Detalhe({ id, nomeEmpresa, aoMudar }: { id: string; nomeEmpresa: (id: string) => string; aoMudar: () => void }) {
  const { tem } = usePermissao()
  const administra = tem('financeiro.recorrencia.administrar')
  const veMargem = tem('financeiro.margem.ver')
  const regra = useDados<Regra>(`/recorrencias/${id}`)
  const [aba, setAba] = useState(0)
  const r = regra.dados
  const recarregar = () => {
    regra.recarregar()
    aoMudar()
  }
  if (!r) return <div className="p-6"><Conteudo carga={regra.carga}>{() => null}</Conteudo></div>
  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      <CabecalhoDeDetalhe titulo={r.descricao ?? 'Mensalidade'} subtitulo={`${nomeEmpresa(r.clienteId)} · ${PERIODICIDADES[r.periodicidade]} · próximo ciclo ${competencia(r.proximaCompetencia)}`}
        status={<Status status={r.ativa ? 'ativa' : 'inativa'} />} />
      <Tabs tabs={['Itens', 'Consumo', 'Adicionais', 'Reajustes', 'Cartão']} active={aba} onChange={setAba} />
      <div className="min-h-0 flex-1 overflow-y-auto p-6">
        {aba === 0 && (
          <>
            <Tabela cabecalho={<><Th>Item</Th><Th num>Qtd.</Th><Th num>Unitário</Th><Th num>Franquia</Th><Th num>Excedente</Th>{veMargem && <Th num>Custo · margem</Th>}<Th num>Total</Th></>}>
              {r.itens.map((i, k) => (
                <tr key={`${i.servicoId}-${k}`} className={linha}>
                  <Td><p className="font-medium text-titulo">{i.descricao}</p><p className="text-xs text-texto-3">{i.fabricante ?? `serviço #${curto(i.servicoId)}`}</p></Td>
                  <Td num>{numero(Number(i.quantidade))} {i.unidade}</Td>
                  <Td num>{brl(Number(i.valorUnitario))}</Td>
                  <Td num>{i.franquia == null || i.franquia === '' ? '—' : numero(Number(i.franquia))}</Td>
                  <Td num>{i.valorExcedente == null || i.valorExcedente === '' ? '—' : brl(Number(i.valorExcedente))}</Td>
                  {veMargem && <Td num>{i.custoUnitario == null || i.custoUnitario === '' ? '—' : `${brl(Number(i.custoUnitario))} · ${pct(i.margemPercentual)}`}</Td>}
                  <Td num className="font-semibold text-titulo">{brl(i.total ?? Number(i.quantidade) * Number(i.valorUnitario))}</Td>
                </tr>
              ))}
            </Tabela>
            <DetailSection title="Resumo">
              <DetailField label="Valor do próximo ciclo">{brl(r.valorProximoCiclo)}</DetailField>
              {r.mrr !== undefined && <DetailField label="MRR (parte fixa)">{brl(r.mrr)}</DetailField>}
              {r.indiceReajuste && <DetailField label="Reajuste">{INDICES[r.indiceReajuste]}{r.mesReajuste && ` em ${MESES[r.mesReajuste - 1].toLowerCase()}`}</DetailField>}
              {r.vendedorId && <DetailField label="Vendedor"><span className="font-mono text-xs">#{curto(r.vendedorId)}</span></DetailField>}
            </DetailSection>
          </>
        )}
        {aba === 1 && <AbaConsumo regra={r} administra={administra} />}
        {aba === 2 && <AbaAdicionais regra={r} administra={administra} />}
        {aba === 3 && <AbaReajustes regra={r} administra={administra} aoReajustar={recarregar} />}
        {aba === 4 && <AbaCartao regra={r} administra={administra} aoMudar={recarregar} />}
      </div>
    </div>
  )
}

/** §26: consumo variável por competência; o que passa da franquia entra na próxima cobrança gerada. */
function AbaConsumo({ regra, administra }: { regra: Regra; administra: boolean }) {
  const lista = useDados<Consumo[]>(`/recorrencias/${regra.id}/consumos`)
  const { executar, ocupado } = useAcao()
  const comFranquia = regra.itens.filter((i) => i.franquia != null && i.franquia !== '')
  const [f, setF] = useState({ servicoId: regra.itens[0]?.servicoId ?? '', competencia: mesAtual(), quantidade: '', observacao: '' })
  const registrar = (e: FormEvent) => {
    e.preventDefault()
    void executar(() => api(`/recorrencias/${regra.id}/consumos`, {
      method: 'PUT', body: { ...f, competencia: `${f.competencia}-01`, quantidade: Number(f.quantidade), observacao: f.observacao || null },
    }), () => {
      setF({ ...f, quantidade: '', observacao: '' })
      lista.recarregar()
    })
  }
  return (
    <>
      <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} icone={<Gauge aria-hidden />}
        textoVazio={comFranquia.length ? 'Nenhum consumo informado ainda' : 'Nenhum consumo informado (sem franquia, a quantidade do item é o permitido)'}>
        {(l) => (
          <Tabela cabecalho={<><Th>Compet.</Th><Th>Serviço</Th><Th num>Usado</Th><Th num>Permitido</Th><Th num>Uso</Th><Th num>Excedente</Th><Th>Faturado</Th></>}>
            {l.map((c) => (
              <tr key={c.id} className={linha}>
                <Td>{competencia(c.competencia)}</Td>
                <Td>
                  <p className="font-medium text-titulo">{c.descricao ?? `#${curto(c.servicoId)}`}</p>
                  <p className="text-xs text-texto-3">{c.origem === 'INTEGRACAO' ? 'integração' : 'manual'}{c.observacao && ` · ${c.observacao}`}</p>
                  {c.sugestao && <p className="text-xs text-amber-700 dark:text-amber-400">{c.sugestao}</p>}
                </Td>
                <Td num>{numero(c.quantidade)} {c.unidade}</Td>
                <Td num>{numero(c.permitido)}</Td>
                <Td num className={(c.percentualUso ?? 0) > 100 ? 'font-semibold text-red-600 dark:text-red-400' : undefined}>{pct(c.percentualUso)}</Td>
                <Td num>{c.excedente > 0 ? <>{numero(c.excedente)}<p className="text-xs text-texto-3">{brl(c.valorExcedente)}</p></> : '—'}</Td>
                <Td>{c.cobrancaId ? <span className="font-mono text-xs">#{curto(c.cobrancaId)}</span> : c.excedente > 0 ? 'na próxima cobrança' : '—'}</Td>
              </tr>
            ))}
          </Tabela>
        )}
      </Conteudo>
      {administra && regra.ativa && (
        <form className="mt-6" onSubmit={registrar}>
          <FormSection title="Informar consumo do mês">
            <FormField label="Serviço" required>
              <Select required value={f.servicoId} onChange={(e) => setF({ ...f, servicoId: e.target.value })}>
                {regra.itens.map((i) => <option key={i.servicoId} value={i.servicoId}>{i.descricao}</option>)}
              </Select>
            </FormField>
            <FormField label="Competência" required><Input type="month" required value={f.competencia} onChange={(e) => setF({ ...f, competencia: e.target.value })} /></FormField>
            <FormField label="Quantidade usada" required hint="Substitui o valor já informado para o mês">
              <Input type="number" step="0.001" min="0" required value={f.quantidade} onChange={(e) => setF({ ...f, quantidade: e.target.value })} />
            </FormField>
            <FormField label="Observação"><Input maxLength={300} value={f.observacao} onChange={(e) => setF({ ...f, observacao: e.target.value })} /></FormField>
          </FormSection>
          <div className="flex justify-end"><Button type="submit" loading={ocupado}>Registrar consumo</Button></div>
        </form>
      )}
    </>
  )
}

/** §26: cobranças extras de uma competência (projeto, hora técnica), somadas à próxima cobrança gerada. */
function AbaAdicionais({ regra, administra }: { regra: Regra; administra: boolean }) {
  const lista = useDados<Adicional[]>(`/recorrencias/${regra.id}/adicionais`)
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ competencia: mesAtual(), descricao: '', valor: '' })
  return (
    <>
      <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} textoVazio="Nenhum adicional lançado">
        {(l) => (
          <Tabela cabecalho={<><Th>Compet.</Th><Th>Descrição</Th><Th num>Valor</Th><Th>Faturado</Th><Th /></>}>
            {l.map((a) => (
              <tr key={a.id} className={linha}>
                <Td>{competencia(a.competencia)}</Td>
                <Td>{a.descricao}<p className="text-xs text-texto-3">lançado {dataHora(a.createdAt)}</p></Td>
                <Td num>{brl(a.valor)}</Td>
                <Td>{a.cobrancaId ? <span className="font-mono text-xs">#{curto(a.cobrancaId)}</span> : 'na próxima cobrança'}</Td>
                <Td className="text-right">
                  {administra && !a.cobrancaId && (
                    <Button size="sm" variant="ghost" aria-label="Excluir" disabled={ocupado}
                      onClick={() => confirm('Excluir este adicional?') && executar(() => api(`/recorrencias/${regra.id}/adicionais/${a.id}`, { method: 'DELETE' }), lista.recarregar)}>
                      <Trash2 aria-hidden className="size-3.5" />
                    </Button>
                  )}
                </Td>
              </tr>
            ))}
          </Tabela>
        )}
      </Conteudo>
      {administra && regra.ativa && (
        <form className="mt-6" onSubmit={(e) => {
          e.preventDefault()
          void executar(() => api(`/recorrencias/${regra.id}/adicionais`, { method: 'POST', body: { ...f, competencia: `${f.competencia}-01`, valor: Number(f.valor) } }), () => {
            setF({ ...f, descricao: '', valor: '' })
            lista.recarregar()
          })
        }}>
          <FormSection title="Novo adicional">
            <FormField label="Competência" required><Input type="month" required value={f.competencia} onChange={(e) => setF({ ...f, competencia: e.target.value })} /></FormField>
            <FormField label="Valor (R$)" required><Input type="number" step="0.01" min="0.01" required value={f.valor} onChange={(e) => setF({ ...f, valor: e.target.value })} /></FormField>
            <FormField label="Descrição" required colSpan={2}><Input required maxLength={200} placeholder="Horas técnicas extras, projeto…" value={f.descricao} onChange={(e) => setF({ ...f, descricao: e.target.value })} /></FormField>
          </FormSection>
          <div className="flex justify-end"><Button type="submit" loading={ocupado}>Lançar adicional</Button></div>
        </form>
      )}
    </>
  )
}

/** §26: reajuste por índice; vale a partir da próxima geração, como um aditivo (RN-17). */
function AbaReajustes({ regra, administra, aoReajustar }: { regra: Regra; administra: boolean; aoReajustar: () => void }) {
  const lista = useDados<Reajuste[]>(`/recorrencias/${regra.id}/reajustes`)
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ indice: regra.indiceReajuste ?? 'IPCA', percentual: '', competenciaAplicacao: regra.proximaCompetencia.slice(0, 7), observacao: '' })
  const novo = regra.valorProximoCiclo * (1 + Number(f.percentual || 0) / 100)
  return (
    <>
      <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} textoVazio="Nenhum reajuste aplicado" icone={<TrendingUp aria-hidden />}>
        {(l) => (
          <Tabela cabecalho={<><Th>Desde</Th><Th>Índice</Th><Th num>%</Th><Th num>Antes</Th><Th num>Depois</Th><Th>Aplicado</Th></>}>
            {l.map((x) => (
              <tr key={x.id} className={linha}>
                <Td>{competencia(x.competenciaAplicacao)}</Td>
                <Td>{INDICES[x.indice] ?? x.indice}{x.observacao && <p className="text-xs text-texto-3">{x.observacao}</p>}</Td>
                <Td num>{pct(x.percentual)}</Td>
                <Td num>{brl(x.valorAnterior)}</Td>
                <Td num className="font-semibold text-titulo">{brl(x.valorNovo)}</Td>
                <Td>{dataHora(x.createdAt)}</Td>
              </tr>
            ))}
          </Tabela>
        )}
      </Conteudo>
      {administra && regra.ativa && (
        <form className="mt-6" onSubmit={(e) => {
          e.preventDefault()
          if (!confirm(`Aplicar ${f.percentual}% (${INDICES[f.indice]}) a partir de ${competencia(f.competenciaAplicacao)}? O valor do ciclo passa de ${brl(regra.valorProximoCiclo)} para cerca de ${brl(novo)}.`)) return
          void executar(() => api(`/recorrencias/${regra.id}/reajustes`, {
            method: 'POST', body: { ...f, percentual: Number(f.percentual), competenciaAplicacao: `${f.competenciaAplicacao}-01`, observacao: f.observacao || null },
          }), () => {
            setF({ ...f, percentual: '', observacao: '' })
            lista.recarregar()
            aoReajustar()
          })
        }}>
          <FormSection title="Aplicar reajuste">
            <FormField label="Índice" required>
              <Select value={f.indice} onChange={(e) => setF({ ...f, indice: e.target.value })}>{Object.entries(INDICES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select>
            </FormField>
            <FormField label="Percentual (%)" required hint="Do índice acumulado em 12 meses; negativo reduz">
              <Input type="number" step="0.0001" min="-99.9999" max="100" required value={f.percentual} onChange={(e) => setF({ ...f, percentual: e.target.value })} />
            </FormField>
            <FormField label="A partir de" required hint={`Próxima geração: ${competencia(regra.proximaCompetencia)}`}>
              <Input type="month" required min={regra.proximaCompetencia.slice(0, 7)} value={f.competenciaAplicacao} onChange={(e) => setF({ ...f, competenciaAplicacao: e.target.value })} />
            </FormField>
            <FormField label="Observação"><Textarea maxLength={300} rows={2} value={f.observacao} onChange={(e) => setF({ ...f, observacao: e.target.value })} /></FormField>
          </FormSection>
          <div className="flex items-center justify-end gap-3">
            {f.percentual && <span className="text-sm text-texto-2">Novo valor do ciclo: <strong className="text-titulo">{brl(novo)}</strong></span>}
            <Button type="submit" loading={ocupado}>Aplicar reajuste</Button>
          </div>
        </form>
      )}
    </>
  )
}

/** §28 chargeSavedCard(): cartão tokenizado no provedor; o número nunca passa pelo Financeiro (RN-22). */
function AbaCartao({ regra, administra, aoMudar }: { regra: Regra; administra: boolean; aoMudar: () => void }) {
  const { executar, ocupado } = useAcao()
  const [link, setLink] = useState<string | null>(null)
  return (
    <>
      <DetailSection title="Cartão salvo">
        <DetailField label="Cartão">{regra.cartaoFinal ? `${regra.cartaoBandeira ?? 'Cartão'} final ${regra.cartaoFinal}` : 'Nenhum cartão vinculado'}</DetailField>
        <DetailField label="Cobrança automática">
          {regra.cobrancaAutomatica ? 'Ligada: cada ciclo é cobrado no cartão e a cobrança fica agendada até a confirmação' : 'Desligada'}
        </DetailField>
      </DetailSection>
      {administra && (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" variant="outline" disabled={ocupado} onClick={() => executar(() => api<{ link: string }>(`/recorrencias/${regra.id}/cartao/link`), (d) => setLink(d.link))}>
            <CreditCard aria-hidden className="size-3.5" /> Gerar link para o cliente cadastrar o cartão
          </Button>
          <Button size="sm" variant="ghost" disabled={ocupado} title="Só com o provedor simulado" onClick={() => executar(() => api(`/recorrencias/${regra.id}/cartao/teste`, { method: 'POST' }), aoMudar)}>
            Vincular cartão de teste
          </Button>
          {regra.cartaoFinal && (
            <Button size="sm" variant="danger" disabled={ocupado} onClick={() => confirm('Remover o cartão salvo? A cobrança automática é desligada.')
              && executar(() => api(`/recorrencias/${regra.id}/cartao`, { method: 'DELETE' }), aoMudar)}>
              Remover cartão
            </Button>
          )}
        </div>
      )}
      {link && (
        <div className="mt-4 flex items-center gap-2">
          <Input readOnly value={link} className="flex-1 font-mono text-xs" aria-label="Link de cadastro do cartão" />
          <Copiar texto={link} />
        </div>
      )}
      <p className="mt-4 text-xs text-texto-3">O cliente digita o cartão na página do provedor; aqui ficam só o token, o final e a bandeira.</p>
    </>
  )
}
