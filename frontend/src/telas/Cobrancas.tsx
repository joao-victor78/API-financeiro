import { useState, type FormEvent } from 'react'
import { Banknote, CalendarClock, CreditCard, FileDown, FileText, Pencil, Plus, QrCode, Receipt, Trash2, Undo2, Zap } from 'lucide-react'
import { Button, DetailField, DetailSection, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Pagination, Select, Tabs } from '@/components/ui'
import { BaixarDocumento, CabecalhoDeDetalhe, Conteudo, Copiar, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { avisar } from '@/lib/avisos'
import { useCadastros } from '@/lib/cadastros'
import { useEmpresas } from '@/lib/empresas'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { brl, CANAIS, competencia, curto, data, dataHora, etapa, FORMAS, hoje, numero, ROTULOS } from '@/lib/formato'
import { api, baixarArquivo, ErroDaApi, mensagemDe, type Pagina } from '@/plataforma/api'
import { navegar } from '@/plataforma/rotas'
import { NovaNota } from './NotasFiscais'

export interface Cobranca {
  id: string
  clienteId: string
  contratoId: string | null
  recorrenciaId: string | null
  competencia: string
  vencimento: string
  descricao: string | null
  valor: number
  desconto: number
  juros: number
  multa: number
  valorPago: number
  saldo: number
  formaPagamento: string | null
  status: string
  diasAtraso: number
  deletedAt: string | null
  // V2 (§24, §25, §26, §71); opcionais para seguir funcionando com a API anterior
  categoriaId?: string | null
  centroCustoId?: string | null
  vendedorId?: string | null
  sdrId?: string | null
  parceiroId?: string | null
  valorVariavel?: number | null
  encargosManuais?: boolean
  encargosCalculadosEm?: string | null
  agendadaPara?: string | null
}

interface ItemCobranca {
  id: string
  tipo: string
  servicoId: string | null
  fabricante: string | null
  descricao: string
  quantidade: number
  unidade: string | null
  valorUnitario: number
  total: number
}

interface NotaDaCobranca { id: string; modelo: string; numero: string; serie: string | null; dataEmissao: string; valor: number; status: string; documentoId: string | null }

const TIPOS_ITEM: Record<string, string> = { FIXO: 'Mensalidade', EXCEDENTE: 'Excedente de consumo', ADICIONAL: 'Adicional' }

interface Pagamento {
  pagamentoId: string
  meio: string
  status: string
  valor: number
  pixCopiaCola: string | null
  pixQrCode: string | null
  boletoUrl: string | null
  boletoLinhaDigitavel: string | null
  linkPagamentoCartao: string | null
  expiraEm: string | null
  confirmadoEm: string | null
  createdAt: string
  estornadoEm?: string | null
  valorEstornado?: number | null
  motivoEstorno?: string | null
}

interface Disparo { id: string; diasOffset: number; canal: string; situacao: string; disparadoEm: string }
interface Promessa { id: string; dataPrometida: string; valor: number; observacao: string | null; status: string }

const STATUS = ['ABERTO', 'AGENDADO', 'ATRASADO', 'PARCIAL', 'PAGO', 'ESTORNADO', 'CANCELADO']
const PAGAVEIS = ['ABERTO', 'AGENDADO', 'ATRASADO', 'PARCIAL']
const TAMANHO = 20
const pendente = (p: Pagamento) => p.status === 'PENDENTE' || p.status === 'PROCESSANDO'

/** RF-01 (contas a receber), RF-04 (PIX, boleto, cartão), RF-11 (exportação), RF-12 (baixa manual). */
export function Cobrancas({ detalhe }: { detalhe?: string }) {
  const { tem } = usePermissao()
  const [filtro, setFiltro] = useState({ status: '', clienteId: '', vencimentoInicio: '', vencimentoFim: '', ordenar: 'vencimento,asc', pagina: 0 })
  const lista = useDados<Pagina<Cobranca>>('/cobrancas', { ...filtro, tamanho: TAMANHO })
  const empresas = useEmpresas(lista.dados?.itens.map((c) => c.clienteId) ?? [])
  const [formulario, setFormulario] = useState<Cobranca | 'nova' | null>(null)
  const aberto = detalhe ?? null
  const filtrar = (m: Partial<typeof filtro>) => setFiltro({ ...filtro, ...m, pagina: 0 })

  async function exportar(formato: string) {
    try {
      const { pagina: _p, ordenar: _o, ...f } = filtro
      await baixarArquivo('/relatorios/cobrancas/exportar', { formato, ...f }, `cobrancas-${hoje()}.${formato}`)
    } catch (e) {
      avisar('erro', mensagemDe(e))
    }
  }

  return (
    <>
      <PageHeader
        title="Contas a receber"
        subtitle={lista.dados ? `${lista.dados.total} cobrança(s)` : 'Cobranças das empresas clientes'}
        actions={<>
          {tem('financeiro.relatorio.exportar') && ['csv', 'xlsx', 'pdf'].map((f) => (
            <Button key={f} variant="secondary" size="sm" onClick={() => exportar(f)}><FileDown aria-hidden className="size-3.5" /> {f.toUpperCase()}</Button>
          ))}
          {tem('financeiro.contareceber.criar') && <Button size="sm" onClick={() => setFormulario('nova')}><Plus aria-hidden className="size-3.5" /> Nova cobrança</Button>}
        </>}
      />
      <FilterBar>
        <Select aria-label="Status" className="w-auto" value={filtro.status} onChange={(e) => filtrar({ status: e.target.value })}>
          <option value="">Todos os status (sem canceladas)</option>
          {STATUS.map((s) => <option key={s} value={s}>{ROTULOS[s]}</option>)}
        </Select>
        <Input aria-label="Empresa (id do CRM)" placeholder="Empresa (id do CRM)" className="w-72" value={filtro.clienteId} onChange={(e) => filtrar({ clienteId: e.target.value.trim() })} />
        <Input aria-label="Vencimento de" type="date" className="w-auto" value={filtro.vencimentoInicio} onChange={(e) => filtrar({ vencimentoInicio: e.target.value })} />
        <Input aria-label="Vencimento até" type="date" className="w-auto" value={filtro.vencimentoFim} onChange={(e) => filtrar({ vencimentoFim: e.target.value })} />
        <Select aria-label="Ordenar por" className="w-auto" value={filtro.ordenar} onChange={(e) => filtrar({ ordenar: e.target.value })}>
          <option value="vencimento,asc">Vencimento ↑</option>
          <option value="vencimento,desc">Vencimento ↓</option>
          <option value="criadoEm,desc">Mais recentes</option>
        </Select>
      </FilterBar>

      <div className="flex-1 p-5">
        {empresas.indisponivel && <p className="mb-3 text-xs text-texto-3">Dados das empresas indisponíveis no CRM agora; mostrando o identificador.</p>}
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(p) => p.itens.length === 0} icone={<Receipt aria-hidden />}
          textoVazio="Nenhuma cobrança com estes filtros">
          {(p) => (
            <>
              <Tabela atualizando={lista.atualizando} cabecalho={<>
                <Th>Empresa</Th><Th>Descrição</Th><Th className="hidden md:table-cell">Compet.</Th><Th>Vencimento</Th>
                <Th num>Valor</Th><Th num>Saldo</Th><Th>Status</Th>
              </>}>
                {p.itens.map((c) => (
                  <tr key={c.id} onClick={() => navegar(`/cobrancas/${c.id}`)} className={`cursor-pointer ${linha}`}>
                    <Td><span className="font-medium text-titulo" title={c.clienteId}>{empresas.nome(c.clienteId)}</span></Td>
                    <Td>{c.descricao ?? '—'} <span className="font-mono text-xs text-texto-3">#{curto(c.id)}</span></Td>
                    <Td className="hidden md:table-cell">{competencia(c.competencia)}</Td>
                    <Td>{data(c.vencimento)}{c.diasAtraso > 0 && <span className="ml-1 text-xs text-red-600 dark:text-red-400">{c.diasAtraso}d</span>}</Td>
                    <Td num>{brl(c.valor - c.desconto + c.juros + c.multa)}</Td>
                    <Td num>{brl(c.saldo)}</Td>
                    <Td><Status status={c.status} /></Td>
                  </tr>
                ))}
              </Tabela>
              <Pagination pagina={p.pagina} tamanho={p.tamanho} total={p.total} onChange={(n) => setFiltro({ ...filtro, pagina: n })} />
            </>
          )}
        </Conteudo>
      </div>

      <Modal open={aberto !== null} onClose={() => navegar('/cobrancas')} size="xl" noPad title="Detalhe da cobrança">
        {aberto && (
          <Detalhe id={aberto} nomeEmpresa={empresas.nome} aoEditar={(c) => setFormulario(c)} aoMudar={lista.recarregar} />
        )}
      </Modal>

      {formulario && (
        <FormularioDeCobranca atual={formulario === 'nova' ? null : formulario} aoFechar={() => setFormulario(null)} aoSalvar={() => {
          setFormulario(null)
          lista.recarregar()
        }} />
      )}
    </>
  )
}

function FormularioDeCobranca({ atual, aoFechar, aoSalvar }: { atual: Cobranca | null; aoFechar: () => void; aoSalvar: () => void }) {
  const parcial = atual?.status === 'PARCIAL'
  const [f, setF] = useState({
    clienteId: atual?.clienteId ?? '', contratoId: atual?.contratoId ?? '', descricao: atual?.descricao ?? '',
    competencia: (atual?.competencia ?? hoje()).slice(0, 7), vencimento: atual?.vencimento ?? '',
    valor: atual ? String(atual.valor) : '', desconto: atual?.desconto ? String(atual.desconto) : '',
    juros: atual?.juros ? String(atual.juros) : '', multa: atual?.multa ? String(atual.multa) : '', formaPagamento: atual?.formaPagamento ?? '',
    categoriaId: atual?.categoriaId ?? '', centroCustoId: atual?.centroCustoId ?? '',
    vendedorId: atual?.vendedorId ?? '', sdrId: atual?.sdrId ?? '', parceiroId: atual?.parceiroId ?? '',
  })
  const cad = useCadastros()
  const categorias = cad.categorias.filter((c) => c.tipo === 'RECEITA' && (c.ativa || c.id === f.categoriaId))
  const centros = cad.centros.filter((c) => c.ativo || c.id === f.centroCustoId)
  const [erro, setErro] = useState<ErroDaApi | null>(null)
  const [enviando, setEnviando] = useState(false)
  const campo = (k: keyof typeof f) => ({ value: f[k], onChange: (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value }) })

  async function salvar(e: FormEvent) {
    e.preventDefault()
    setEnviando(true)
    setErro(null)
    try {
      const corpo = { ...f, competencia: `${f.competencia}-01` }
      const r = atual ? await api(`/cobrancas/${atual.id}`, { method: 'PUT', body: corpo }) : await api('/cobrancas', { method: 'POST', body: corpo })
      avisar('sucesso', r.message ?? 'Cobrança salva.')
      aoSalvar()
    } catch (x) {
      if (x instanceof ErroDaApi) setErro(x)
      else avisar('erro', mensagemDe(x))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Modal open onClose={aoFechar} title={atual ? 'Editar cobrança' : 'Nova cobrança'} subtitle="Toda cobrança nasce de uma empresa do CRM (RN-03)"
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-cobranca" loading={enviando}>Salvar</Button></>}>
      <form id="form-cobranca" onSubmit={salvar}>
        <FormSection number={1} title="Empresa e contrato">
          <CampoUuid label="Empresa (id do CRM)" required valor={f.clienteId} onChange={(v) => setF({ ...f, clienteId: v })} erro={erro?.doCampo('clienteId')} />
          <CampoUuid label="Contrato (opcional)" valor={f.contratoId} onChange={(v) => setF({ ...f, contratoId: v })} />
          <FormField label="Descrição" colSpan={2}><Input maxLength={200} {...campo('descricao')} /></FormField>
        </FormSection>
        <FormSection number={2} title="Valores e vencimento">
          <FormField label="Competência" required><Input type="month" required {...campo('competencia')} /></FormField>
          <FormField label="Vencimento" required error={erro?.doCampo('vencimento')}><Input type="date" required {...campo('vencimento')} /></FormField>
          <FormField label="Valor (R$)" required error={erro?.doCampo('valor')} hint={parcial ? 'Com pagamento parcial, o valor original não muda (RN-05).' : 'Até 2 casas decimais.'}>
            <Input type="number" step="0.01" min="0.01" required disabled={parcial} {...campo('valor')} />
          </FormField>
          <FormField label="Forma de pagamento">
            <Select {...campo('formaPagamento')}>
              <option value="">—</option>
              {Object.entries(FORMAS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
          </FormField>
          <FormField label="Desconto (R$)" error={erro?.doCampo('desconto')}><Input type="number" step="0.01" min="0" {...campo('desconto')} /></FormField>
          <FormField label="Juros (R$)"><Input type="number" step="0.01" min="0" {...campo('juros')} /></FormField>
          <FormField label="Multa (R$)" hint="Juros ou multa digitados aqui não são recalculados automaticamente"><Input type="number" step="0.01" min="0" {...campo('multa')} /></FormField>
        </FormSection>
        {(categorias.length > 0 || centros.length > 0) && (
          <FormSection number={3} title="Classificação">
            {categorias.length > 0 && (
              <FormField label="Categoria" error={erro?.doCampo('categoriaId')} hint="Define a linha do DRE">
                <Select {...campo('categoriaId')}>
                  <option value="">Sem categoria</option>
                  {categorias.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
                </Select>
              </FormField>
            )}
            {centros.length > 0 && (
              <FormField label="Centro de custo" error={erro?.doCampo('centroCustoId')}>
                <Select {...campo('centroCustoId')}>
                  <option value="">Sem centro de custo</option>
                  {centros.map((c) => <option key={c.id} value={c.id}>{c.codigo} · {c.nome}</option>)}
                </Select>
              </FormField>
            )}
          </FormSection>
        )}
        <FormSection number={categorias.length > 0 || centros.length > 0 ? 4 : 3} title="Comissão (opcional)">
          <CampoUuid label="Vendedor (id do usuário)" valor={f.vendedorId} onChange={(v) => setF({ ...f, vendedorId: v })} erro={erro?.doCampo('vendedorId')} />
          <CampoUuid label="SDR" valor={f.sdrId} onChange={(v) => setF({ ...f, sdrId: v })} erro={erro?.doCampo('sdrId')} />
          <CampoUuid label="Parceiro" valor={f.parceiroId} onChange={(v) => setF({ ...f, parceiroId: v })} erro={erro?.doCampo('parceiroId')} />
        </FormSection>
        {erro && !erro.erros.some((x) => x.campo) && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{erro.detalhe}</p>}
      </form>
    </Modal>
  )
}

/** UUID de outro módulo. Sem o módulo no ar, permite gerar um para testes. */
export function CampoUuid({ label, valor, onChange, required, erro }: { label: string; valor: string; onChange: (v: string) => void; required?: boolean; erro?: string | null }) {
  return (
    <FormField label={label} required={required} error={erro}>
      <div className="flex gap-2">
        <Input required={required} value={valor} onChange={(e) => onChange(e.target.value.trim())} pattern="[0-9a-fA-F-]{36}" placeholder="00000000-0000-0000-0000-000000000000" className="flex-1 font-mono text-xs" />
        <Button variant="outline" size="md" onClick={() => onChange(crypto.randomUUID())}>Gerar</Button>
      </div>
    </FormField>
  )
}

function Detalhe({ id, nomeEmpresa, aoEditar, aoMudar }: { id: string; nomeEmpresa: (id: string) => string; aoEditar: (c: Cobranca) => void; aoMudar: () => void }) {
  const { tem } = usePermissao()
  const [aba, setAba] = useState(0)
  const cobranca = useDados<Cobranca>(`/cobrancas/${id}`)
  const pagamentos = useDados<Pagamento[]>(`/cobrancas/${id}/pagamentos`)
  const disparos = useDados<Disparo[]>(`/cobrancas/${id}/disparos`)
  const promessas = useDados<Promessa[]>(`/cobrancas/${id}/promessas`)
  const itens = useDados<ItemCobranca[]>(`/cobrancas/${id}/itens`)
  const veNotas = tem('financeiro.notafiscal.ver')
  const notas = useDados<Pagina<NotaDaCobranca>>(veNotas ? '/notas-fiscais' : null, { cobrancaId: id, tamanho: 50 })
  const cad = useCadastros()
  const { executar, ocupado } = useAcao()
  const [estornando, setEstornando] = useState<Pagamento | null>(null)
  const [novaNota, setNovaNota] = useState(false)
  const [agendarPara, setAgendarPara] = useState('')
  const c = cobranca.dados
  const edita = tem('financeiro.contareceber.editar')

  const recarregar = () => {
    cobranca.recarregar()
    pagamentos.recarregar()
    promessas.recarregar()
    aoMudar()
  }
  const agendar = (e: FormEvent) => {
    e.preventDefault()
    void executar(() => api(`/cobrancas/${id}/agendamento`, { method: 'POST', body: { data: agendarPara } }), () => {
      setAgendarPara('')
      recarregar()
    })
  }
  const desagendar = () => executar(() => api(`/cobrancas/${id}/agendamento`, { method: 'DELETE' }), recarregar)
  const pedir = (meio: string) => executar(() => api(`/cobrancas/${id}/pagamento/${meio}`, { method: 'POST' }), () => {
    setAba(1)
    recarregar()
  })
  const simular = (p: Pagamento) => executar(async () => ({
    ...(await api(`/pagamentos/${p.pagamentoId}/simular-confirmacao`, { method: 'POST' })),
    message: 'O provedor simulado confirmou o pagamento (webhook assinado).',
  }), recarregar)
  const cancelar = () => confirm('Cancelar esta cobrança? Ela sai da listagem padrão e o histórico permanece.')
    && executar(() => api(`/cobrancas/${id}`, { method: 'DELETE' }), () => {
      aoMudar()
      navegar('/cobrancas')
    })

  if (!c) return <div className="p-6"><Conteudo carga={cobranca.carga}>{() => null}</Conteudo></div>
  const pagavel = !c.deletedAt && PAGAVEIS.includes(c.status) && c.saldo > 0

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      <CabecalhoDeDetalhe
        titulo={c.descricao ?? `Cobrança #${curto(c.id)}`}
        subtitulo={`${nomeEmpresa(c.clienteId)} · vence ${data(c.vencimento)}`}
        status={<Status status={c.status} />}
        acoes={<>
          {edita && !c.deletedAt && ['ABERTO', 'AGENDADO', 'PARCIAL'].includes(c.status) && (
            <Button variant="secondary" size="sm" onClick={() => aoEditar(c)}><Pencil aria-hidden className="size-3.5" /> Editar</Button>
          )}
          {tem('financeiro.contareceber.excluir') && !c.deletedAt && ['ABERTO', 'AGENDADO', 'ATRASADO'].includes(c.status) && c.valorPago === 0 && (
            <Button variant="danger" size="sm" disabled={ocupado} onClick={cancelar}><Trash2 aria-hidden className="size-3.5" /> Cancelar</Button>
          )}
        </>}
      />
      <Tabs tabs={['Resumo', 'Pagamentos', 'Promessas', 'Régua', 'Composição', ...(veNotas ? ['Notas fiscais'] : [])]} active={aba} onChange={setAba} />
      <div className="min-h-0 flex-1 overflow-y-auto p-6">
        {aba === 0 && (
          <>
            <DetailSection number={1} title="Valores">
              <DetailField label="Valor">{brl(c.valor)}</DetailField>
              <DetailField label="Desconto · juros · multa">{brl(c.desconto)} · {brl(c.juros)} · {brl(c.multa)}</DetailField>
              <DetailField label="Pago">{brl(c.valorPago)}</DetailField>
              <DetailField label="Saldo"><strong className="text-titulo">{brl(c.saldo)}</strong></DetailField>
            </DetailSection>
            <DetailSection number={2} title="Origem">
              <DetailField label="Competência">{competencia(c.competencia)}</DetailField>
              <DetailField label="Vencimento">{data(c.vencimento)}{c.diasAtraso > 0 && ` · ${c.diasAtraso} dia(s) de atraso`}</DetailField>
              <DetailField label="Forma de pagamento">{c.formaPagamento ? FORMAS[c.formaPagamento] : null}</DetailField>
              <DetailField label="Recorrência">{c.recorrenciaId ? `#${curto(c.recorrenciaId)}` : 'Avulsa'}</DetailField>
              <DetailField label="Empresa (id do CRM)" colSpan={2}><span className="font-mono text-xs">{c.clienteId}</span></DetailField>
              {c.agendadaPara && <DetailField label="Pagamento agendado para">{data(c.agendadaPara)}</DetailField>}
              {c.encargosCalculadosEm && <DetailField label="Encargos calculados em">{data(c.encargosCalculadosEm)}</DetailField>}
              {c.encargosManuais && <DetailField label="Encargos">Digitados à mão (sem recálculo automático)</DetailField>}
            </DetailSection>
            {(c.categoriaId || c.centroCustoId || c.vendedorId || c.sdrId || c.parceiroId || !!c.valorVariavel) && (
              <DetailSection number={3} title="Classificação e comissão">
                {c.categoriaId && <DetailField label="Categoria">{cad.nomeCategoria(c.categoriaId)}</DetailField>}
                {c.centroCustoId && <DetailField label="Centro de custo">{cad.centros.find((x) => x.id === c.centroCustoId)?.nome ?? `#${curto(c.centroCustoId)}`}</DetailField>}
                {c.vendedorId && <DetailField label="Vendedor"><span className="font-mono text-xs">#{curto(c.vendedorId)}</span></DetailField>}
                {c.sdrId && <DetailField label="SDR"><span className="font-mono text-xs">#{curto(c.sdrId)}</span></DetailField>}
                {c.parceiroId && <DetailField label="Parceiro"><span className="font-mono text-xs">#{curto(c.parceiroId)}</span></DetailField>}
                {!!c.valorVariavel && <DetailField label="Parte variável (consumo e adicionais)">{brl(c.valorVariavel)}</DetailField>}
              </DetailSection>
            )}
            {edita && pagavel && (
              <DetailSection number={4} title="Receber">
                <div className="flex flex-wrap gap-2 sm:col-span-2">
                  <Button size="sm" loading={ocupado} onClick={() => pedir('pix')}><QrCode aria-hidden className="size-3.5" /> Gerar PIX</Button>
                  <Button size="sm" variant="outline" disabled={ocupado} onClick={() => pedir('boleto')}><FileText aria-hidden className="size-3.5" /> Gerar boleto</Button>
                  <Button size="sm" variant="outline" disabled={ocupado} onClick={() => pedir('cartao')}><CreditCard aria-hidden className="size-3.5" /> Link de cartão</Button>
                </div>
                <form onSubmit={agendar} className="flex flex-wrap items-end gap-2 sm:col-span-2">
                  <FormField label={c.status === 'AGENDADO' ? 'Reagendar pagamento para' : 'Agendar pagamento para'} hint="Débito agendado ou data combinada com o cliente">
                    <Input type="date" min={hoje()} required value={agendarPara} onChange={(e) => setAgendarPara(e.target.value)} className="w-auto" />
                  </FormField>
                  <Button type="submit" size="sm" variant="outline" disabled={ocupado || !agendarPara}><CalendarClock aria-hidden className="size-3.5" /> Agendar</Button>
                  {c.status === 'AGENDADO' && <Button size="sm" variant="ghost" disabled={ocupado} onClick={desagendar}>Desfazer agendamento</Button>}
                </form>
              </DetailSection>
            )}
          </>
        )}

        {aba === 1 && (
          <>
            <Conteudo carga={pagamentos.carga} vazio={(l) => l.length === 0} textoVazio="Nenhum pedido de pagamento ainda" icone={<Banknote aria-hidden />}>
              {(lista) => (
                <div className="space-y-3">
                  {lista.map((p) => (
                    <div key={p.pagamentoId} className="rounded-xl border border-borda p-4">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold text-titulo">{FORMAS[p.meio] ?? p.meio}</span>
                        <Status status={p.status} />
                        <span className="tabular-nums">{brl(p.valor)}</span>
                        <span className="text-xs text-texto-3">
                          {p.confirmadoEm ? `confirmado ${dataHora(p.confirmadoEm)}` : p.expiraEm ? `expira ${dataHora(p.expiraEm)}` : dataHora(p.createdAt)}
                        </span>
                        {pendente(p) && edita && (
                          <Button size="sm" variant="secondary" className="ml-auto" disabled={ocupado} onClick={() => simular(p)} title="Faz o papel do provedor e envia o webhook assinado">
                            <Zap aria-hidden className="size-3.5" /> Simular pagamento
                          </Button>
                        )}
                        {p.status === 'CONFIRMADO' && tem('financeiro.pagamento.estornar') && (
                          <Button size="sm" variant="outline" className="ml-auto" disabled={ocupado} onClick={() => setEstornando(p)}>
                            <Undo2 aria-hidden className="size-3.5" /> Estornar
                          </Button>
                        )}
                      </div>
                      {p.estornadoEm && (
                        <p className="mt-2 text-xs text-red-600 dark:text-red-400">
                          Estornado {brl(p.valorEstornado)} em {dataHora(p.estornadoEm)}{p.motivoEstorno && ` · ${p.motivoEstorno}`}
                        </p>
                      )}
                      {p.pixQrCode && <img src={p.pixQrCode} alt="QR code do PIX" width={176} height={176} className="mt-3 rounded-lg border border-borda bg-white p-1" />}
                      {pendente(p) && (p.pixCopiaCola ?? p.boletoLinhaDigitavel) && (
                        <div className="mt-3 flex gap-2">
                          <Input readOnly value={p.pixCopiaCola ?? p.boletoLinhaDigitavel ?? ''} className="flex-1 font-mono text-xs" aria-label="Código para pagamento" />
                          <Copiar texto={p.pixCopiaCola ?? p.boletoLinhaDigitavel ?? ''} />
                        </div>
                      )}
                      {p.linkPagamentoCartao && pendente(p) && <p className="mt-2 font-mono text-xs break-all text-texto-3">{p.linkPagamentoCartao}</p>}
                    </div>
                  ))}
                </div>
              )}
            </Conteudo>
            {edita && pagavel && <BaixaManual id={id} saldo={c.saldo} aoBaixar={recarregar} />}
          </>
        )}

        {aba === 2 && (
          <>
            <Conteudo carga={promessas.carga} vazio={(l) => l.length === 0} textoVazio="Nenhuma promessa registrada">
              {(lista) => (
                <Tabela cabecalho={<><Th>Data prometida</Th><Th num>Valor</Th><Th>Observação</Th><Th>Status</Th></>}>
                  {lista.map((p) => (
                    <tr key={p.id} className={linha}><Td>{data(p.dataPrometida)}</Td><Td num>{brl(p.valor)}</Td><Td>{p.observacao}</Td><Td><Status status={p.status} /></Td></tr>
                  ))}
                </Tabela>
              )}
            </Conteudo>
            {edita && pagavel && <NovaPromessa id={id} saldo={c.saldo} aoSalvar={promessas.recarregar} />}
          </>
        )}

        {aba === 3 && (
          <Conteudo carga={disparos.carga} vazio={(l) => l.length === 0} textoVazio="Nenhum aviso da régua para esta cobrança">
            {(lista) => (
              <Tabela cabecalho={<><Th>Etapa</Th><Th>Canal</Th><Th>Situação</Th><Th>Quando</Th></>}>
                {lista.map((d) => (
                  <tr key={d.id} className={linha}><Td>{etapa(d.diasOffset)}</Td><Td>{CANAIS[d.canal] ?? d.canal}</Td><Td><Status status={d.situacao} /></Td><Td>{dataHora(d.disparadoEm)}</Td></tr>
                ))}
              </Tabela>
            )}
          </Conteudo>
        )}

        {aba === 4 && (
          <Conteudo carga={itens.carga} vazio={(l) => l.length === 0} textoVazio={c.recorrenciaId ? 'Cobrança gerada antes da composição por itens' : 'Cobrança avulsa: valor único, sem itens'}>
            {(lista) => (
              <>
                <Tabela cabecalho={<><Th>Item</Th><Th>Tipo</Th><Th num>Qtd.</Th><Th num>Unitário</Th><Th num>Total</Th></>}>
                  {lista.map((i) => (
                    <tr key={i.id} className={linha}>
                      <Td>
                        <p className="font-medium text-titulo">{i.descricao}</p>
                        {i.fabricante && <p className="text-xs text-texto-3">{i.fabricante}</p>}
                      </Td>
                      <Td>{TIPOS_ITEM[i.tipo] ?? i.tipo}</Td>
                      <Td num>{numero(i.quantidade)}{i.unidade && ` ${i.unidade}`}</Td>
                      <Td num>{brl(i.valorUnitario)}</Td>
                      <Td num className="font-semibold text-titulo">{brl(i.total)}</Td>
                    </tr>
                  ))}
                </Tabela>
                <p className="mt-2 text-right text-sm text-texto-2">Soma dos itens: <strong className="text-titulo">{brl(lista.reduce((s, i) => s + i.total, 0))}</strong></p>
              </>
            )}
          </Conteudo>
        )}

        {aba === 5 && veNotas && (
          <>
            <Conteudo carga={notas.carga} vazio={(p) => p.itens.length === 0} textoVazio="Nenhuma nota registrada para esta cobrança">
              {(p) => (
                <Tabela cabecalho={<><Th>Nota</Th><Th>Emissão</Th><Th num>Valor</Th><Th>Situação</Th><Th /></>}>
                  {p.itens.map((n) => (
                    <tr key={n.id} className={linha}>
                      <Td>{n.modelo} nº {n.numero}{n.serie && ` série ${n.serie}`}</Td>
                      <Td>{data(n.dataEmissao)}</Td>
                      <Td num>{brl(n.valor)}</Td>
                      <Td><Status status={n.status} /></Td>
                      <Td className="text-right">{n.documentoId && tem('financeiro.documento.ver') && <BaixarDocumento id={n.documentoId} />}</Td>
                    </tr>
                  ))}
                </Tabela>
              )}
            </Conteudo>
            {tem('financeiro.notafiscal.registrar') && !c.deletedAt && (
              <div className="mt-4 flex justify-end"><Button size="sm" variant="outline" onClick={() => setNovaNota(true)}><Plus aria-hidden className="size-3.5" /> Registrar nota emitida</Button></div>
            )}
          </>
        )}
      </div>
      {estornando && <Estorno pagamento={estornando} aoFechar={() => setEstornando(null)} aoEstornar={() => { setEstornando(null); recarregar() }} />}
      {novaNota && <NovaNota cobrancaId={id} aoFechar={() => setNovaNota(false)} aoSalvar={() => { setNovaNota(false); notas.recarregar() }} />}
    </div>
  )
}

/** §28 refund(): total ou parcial; pelo provedor quando o pagamento veio dele. */
function Estorno({ pagamento, aoFechar, aoEstornar }: { pagamento: Pagamento; aoFechar: () => void; aoEstornar: () => void }) {
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ valor: String(pagamento.valor), motivo: '' })
  return (
    <Modal open onClose={aoFechar} size="md" title="Estornar pagamento" subtitle={`${FORMAS[pagamento.meio] ?? pagamento.meio} · ${brl(pagamento.valor)} · confirmado ${dataHora(pagamento.confirmadoEm)}`}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" variant="danger" form="form-estorno" loading={ocupado}>Estornar</Button></>}>
      <form id="form-estorno" onSubmit={(e) => {
        e.preventDefault()
        void executar(() => api(`/pagamentos/${pagamento.pagamentoId}/estorno`, { method: 'POST', body: { valor: Number(f.valor), motivo: f.motivo.trim() } }), aoEstornar)
      }}>
        <FormSection title="Estorno">
          <FormField label="Valor (R$)" required hint="Menor que o pago = estorno parcial. O saldo volta para a cobrança.">
            <Input type="number" step="0.01" min="0.01" max={pagamento.valor} required value={f.valor} onChange={(e) => setF({ ...f, valor: e.target.value })} />
          </FormField>
          <FormField label="Motivo" required><Input required maxLength={300} value={f.motivo} onChange={(e) => setF({ ...f, motivo: e.target.value })} /></FormField>
        </FormSection>
      </form>
    </Modal>
  )
}

function BaixaManual({ id, saldo, aoBaixar }: { id: string; saldo: number; aoBaixar: () => void }) {
  const [f, setF] = useState({ valor: '', dataPagamento: hoje() })
  const { executar, ocupado } = useAcao()
  return (
    <form className="mt-6" onSubmit={(e) => {
      e.preventDefault()
      void executar(() => api(`/cobrancas/${id}/baixas`, { method: 'POST', body: f }), () => {
        setF({ valor: '', dataPagamento: hoje() })
        aoBaixar()
      })
    }}>
      <FormSection title="Baixa manual (transferência)">
        <FormField label="Valor recebido (R$)" required hint={`Saldo ${brl(saldo)}. Menor que o saldo deixa a cobrança parcial.`}>
          <Input type="number" step="0.01" min="0.01" max={saldo} required value={f.valor} onChange={(e) => setF({ ...f, valor: e.target.value })} />
        </FormField>
        <FormField label="Data do pagamento" required>
          <Input type="date" max={hoje()} required value={f.dataPagamento} onChange={(e) => setF({ ...f, dataPagamento: e.target.value })} />
        </FormField>
      </FormSection>
      <div className="flex justify-end"><Button type="submit" loading={ocupado}>Registrar baixa</Button></div>
    </form>
  )
}

function NovaPromessa({ id, saldo, aoSalvar }: { id: string; saldo: number; aoSalvar: () => void }) {
  const [f, setF] = useState({ dataPrometida: '', valor: '', observacao: '' })
  const { executar, ocupado } = useAcao()
  return (
    <form className="mt-6" onSubmit={(e) => {
      e.preventDefault()
      void executar(() => api(`/cobrancas/${id}/promessas`, { method: 'POST', body: f }), () => {
        setF({ dataPrometida: '', valor: '', observacao: '' })
        aoSalvar()
      })
    }}>
      <FormSection title="Registrar promessa de pagamento">
        <FormField label="Data prometida" required><Input type="date" min={hoje()} required value={f.dataPrometida} onChange={(e) => setF({ ...f, dataPrometida: e.target.value })} /></FormField>
        <FormField label="Valor (R$)" required><Input type="number" step="0.01" min="0.01" max={saldo} required value={f.valor} onChange={(e) => setF({ ...f, valor: e.target.value })} /></FormField>
        <FormField label="Observação" colSpan={2}><Input maxLength={500} value={f.observacao} onChange={(e) => setF({ ...f, observacao: e.target.value })} /></FormField>
      </FormSection>
      <div className="flex justify-end"><Button type="submit" variant="outline" loading={ocupado}>Registrar promessa</Button></div>
    </form>
  )
}
