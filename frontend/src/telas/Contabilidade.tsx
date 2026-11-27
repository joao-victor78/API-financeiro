import { useState, type FormEvent, type ReactNode } from 'react'
import { BookCheck, CheckCircle2, Circle, Plug } from 'lucide-react'
import { Button, cx, DetailField, DetailSection, FilterBar, FormField, Input, Modal, PageHeader, Tabs, Textarea } from '@/components/ui'
import { BaixarDocumento, BotoesExportar, Conteudo, Indicador, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { brl, competencia, curto, data, dataHora, ITENS_FECHAMENTO, mesAtual, ROTULOS } from '@/lib/formato'
import { api } from '@/plataforma/api'

interface ItemFechamento {
  item: string
  ordem: number
  concluido: boolean
  concluidoPor: string | null
  concluidoEm: string | null
  observacao: string | null
  verificacao: string | null
}
interface Fechamento {
  id: string | null
  competencia: string
  status: string
  pendencias: string | null
  concluidos: number
  total: number
  itens: ItemFechamento[]
  diagnostico: Record<string, number> | null
  enviadoEm: string | null
  enviadoPor: string | null
  finalizadoEm: string | null
  finalizadoPor: string | null
  pacoteDocumentoId: string | null
}
interface Lancamento {
  data: string
  tipo: string
  contaDebito: string | null
  contaCredito: string | null
  valor: number
  historico: string
  documento: string | null
  participante: string | null
  categoria: string | null
  centroCusto: string | null
  origemId: string | null
  origem: string | null
}
interface Integracao { codigo: string; nome: string; disponivel: boolean; configurado: boolean }
type Linha = Record<string, unknown>
interface Portal {
  competencia: string
  fechamento: Fechamento
  totais: Record<string, number>
  receitas: Lancamento[]
  recebimentos: Lancamento[]
  despesas: Lancamento[]
  pagamentos: Lancamento[]
  movimentacoesBancarias: Linha[]
  documentosFiscais: Linha[]
  comprovantes: Linha[]
  contratos: Linha[]
}

const TIPOS_LANCAMENTO: Record<string, string> = {
  RECEITA: 'Receitas', RECEBIMENTO: 'Recebimentos', DESPESA: 'Despesas', PAGAMENTO: 'Pagamentos', TARIFA: 'Tarifas',
  JUROS: 'Juros', ESTORNO: 'Estornos', TRANSFERENCIA: 'Transferências', OUTRO: 'Outros',
}
const EDITAVEL = ['EM_CONFERENCIA', 'COM_PENDENCIA', 'AGUARDANDO_DOCUMENTO']
const BLOQUEADO = ['ENVIADO_PARA_CONTABILIDADE', 'EM_ANALISE', 'FINALIZADO']
/** Mesma matriz de ContabilidadeService (a API confere de novo). */
const DO_FINANCEIRO: Record<string, string[]> = {
  EM_CONFERENCIA: ['COM_PENDENCIA', 'AGUARDANDO_DOCUMENTO', 'PRONTO'],
  COM_PENDENCIA: ['EM_CONFERENCIA', 'AGUARDANDO_DOCUMENTO', 'PRONTO'],
  AGUARDANDO_DOCUMENTO: ['EM_CONFERENCIA', 'COM_PENDENCIA', 'PRONTO'],
  PRONTO: ['EM_CONFERENCIA', 'ENVIADO_PARA_CONTABILIDADE'],
}
const DA_CONTABILIDADE: Record<string, string[]> = {
  ENVIADO_PARA_CONTABILIDADE: ['EM_ANALISE', 'COM_PENDENCIA'],
  EM_ANALISE: ['FINALIZADO', 'COM_PENDENCIA'],
}
const ROTULO_ACAO: Record<string, string> = {
  EM_CONFERENCIA: 'Voltar para conferência', COM_PENDENCIA: 'Registrar pendência', AGUARDANDO_DOCUMENTO: 'Aguardar documento',
  PRONTO: 'Marcar como pronto', ENVIADO_PARA_CONTABILIDADE: 'Enviar para a contabilidade', EM_ANALISE: 'Iniciar análise', FINALIZADO: 'Finalizar',
}
const EXIGE_TEXTO = ['COM_PENDENCIA', 'AGUARDANDO_DOCUMENTO']

/** §34 a §37: fechamento mensal com checklist, portal da contabilidade, lançamentos e exportações. */
export function Contabilidade({ detalhe }: { detalhe?: string }) {
  const inicial = detalhe && /^\d{4}-\d{2}$/.test(detalhe) ? detalhe : mesAtual()
  const [mes, setMes] = useState(inicial)
  const [aba, setAba] = useState(0)
  return (
    <>
      <PageHeader title="Contabilidade" subtitle="Fechamento mensal e o que o escritório contábil recebe"
        actions={<BotoesExportar caminho="/contabilidade/exportar" query={{ competencia: mes }} nome={`contabil-${mes}`} formatos={['csv', 'xlsx', 'pdf', 'ofx', 'zip']} />} />
      <Tabs tabs={['Fechamento', 'Portal', 'Lançamentos', 'Histórico', 'Integrações']} active={aba} onChange={setAba} />
      <FilterBar>
        <Input aria-label="Competência" type="month" className="w-auto" value={mes} max={mesAtual()} onChange={(e) => e.target.value && setMes(e.target.value)} />
        <span className="text-xs text-texto-3">Competência {competencia(mes)}</span>
      </FilterBar>
      <div className="flex-1 space-y-4 p-5">
        {aba === 0 && <PainelFechamento mes={mes} />}
        {aba === 1 && <PainelPortal mes={mes} />}
        {aba === 2 && <PainelLancamentos mes={mes} />}
        {aba === 3 && <Historico aoEscolher={(m) => { setMes(m); setAba(0) }} />}
        {aba === 4 && <Integracoes />}
      </div>
    </>
  )
}

function PainelFechamento({ mes }: { mes: string }) {
  const { tem } = usePermissao()
  const podeFechar = tem('financeiro.contabilidade.fechar')
  const podeAnalisar = tem('financeiro.contabilidade.analisar')
  const f = useDados<Fechamento>(`/contabilidade/fechamentos/${mes}`)
  const { executar, ocupado } = useAcao()
  const [mudanca, setMudanca] = useState<string | null>(null)
  const [observando, setObservando] = useState<ItemFechamento | null>(null)

  const mudar = (status: string, pendencias?: string) =>
    executar(() => api(`/contabilidade/fechamentos/${mes}/status`, { method: 'PATCH', body: { status, pendencias: pendencias ?? null } }), () => {
      setMudanca(null)
      f.recarregar()
    })
  const marcar = (i: ItemFechamento, concluido: boolean, observacao: string | null) =>
    executar(() => api(`/contabilidade/fechamentos/${mes}/itens/${i.item}`, { method: 'PUT', body: { concluido, observacao } }), () => {
      setObservando(null)
      f.recarregar()
    })

  return (
    <Conteudo carga={f.carga} recarregar={f.recarregar} icone={<BookCheck aria-hidden />}>
      {(x) => {
        const acoes = [
          ...(podeFechar ? DO_FINANCEIRO[x.status] ?? [] : []),
          ...(podeAnalisar ? DA_CONTABILIDADE[x.status] ?? [] : []),
        ]
        const editavel = podeFechar && EDITAVEL.includes(x.status)
        const progresso = x.total ? Math.round((x.concluidos / x.total) * 100) : 0
        return (
          <>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <Indicador rotulo="Situação" valor={<Status status={x.status} />} detalhe={BLOQUEADO.includes(x.status) ? 'Competência travada para alterações' : 'Competência aberta'} />
              <Indicador rotulo="Checklist" valor={`${x.concluidos}/${x.total}`} detalhe={`${progresso}% concluído`} tom={x.concluidos === x.total ? 'ok' : undefined} />
              <Indicador rotulo="Enviado" valor={x.enviadoEm ? dataHora(x.enviadoEm) : '—'} detalhe={x.enviadoPor ? `por #${curto(x.enviadoPor)}` : undefined} />
              <Indicador rotulo="Finalizado" valor={x.finalizadoEm ? dataHora(x.finalizadoEm) : '—'} detalhe={x.finalizadoPor ? `por #${curto(x.finalizadoPor)}` : undefined} />
            </div>

            <div className="h-2 overflow-hidden rounded-full bg-superficie-2" role="progressbar" aria-valuenow={progresso} aria-valuemin={0} aria-valuemax={100}>
              <div className="h-full bg-brand-600 transition-all" style={{ width: `${progresso}%` }} />
            </div>

            {x.pendencias && ['COM_PENDENCIA', 'AGUARDANDO_DOCUMENTO'].includes(x.status) && (
              <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300">
                <p className="font-semibold">{ROTULOS[x.status]}</p>
                <p className="mt-1 whitespace-pre-line">{x.pendencias}</p>
              </div>
            )}

            <div className="flex flex-wrap items-center gap-2">
              {x.status === 'NAO_INICIADO' && podeFechar && (
                <Button disabled={ocupado} onClick={() => executar(() => api(`/contabilidade/fechamentos/${mes}/iniciar`, { method: 'POST' }), f.recarregar)}>
                  Iniciar fechamento
                </Button>
              )}
              {acoes.map((s) => (
                <Button key={s} variant={s === 'COM_PENDENCIA' || s === 'EM_CONFERENCIA' ? 'outline' : 'primary'} disabled={ocupado
                  || (s === 'PRONTO' && x.concluidos < x.total)} title={s === 'PRONTO' && x.concluidos < x.total ? 'Conclua todo o checklist antes' : undefined}
                  onClick={() => {
                    if (EXIGE_TEXTO.includes(s)) setMudanca(s)
                    else if (s === 'ENVIADO_PARA_CONTABILIDADE') {
                      if (confirm(`Enviar ${competencia(mes)} para a contabilidade? O pacote é gerado e a competência fica travada para alterações.`)) void mudar(s)
                    } else if (s === 'FINALIZADO') {
                      if (confirm(`Finalizar o fechamento de ${competencia(mes)}? A competência continua travada.`)) void mudar(s)
                    } else void mudar(s)
                  }}>
                  {ROTULO_ACAO[s]}
                </Button>
              ))}
              {x.pacoteDocumentoId && (
                <span className="ml-auto flex items-center gap-1 text-xs text-texto-3">Pacote enviado <BaixarDocumento id={x.pacoteDocumentoId} nome={`contabil-${mes}.zip`} /></span>
              )}
            </div>

            <ul className="divide-y divide-borda overflow-hidden rounded-xl border border-borda bg-superficie shadow-sm">
              {[...x.itens].sort((a, b) => a.ordem - b.ordem).map((i) => (
                <li key={i.item} className="flex items-start gap-3 px-4 py-3">
                  <button type="button" disabled={!editavel || ocupado} aria-pressed={i.concluido} aria-label={`${ITENS_FECHAMENTO[i.item]}: ${i.concluido ? 'concluído' : 'pendente'}`}
                    onClick={() => void marcar(i, !i.concluido, i.observacao)}
                    className={cx('mt-0.5 shrink-0', i.concluido ? 'text-brand-600' : 'text-texto-3', editavel ? 'cursor-pointer hover:text-brand-700' : 'cursor-default')}>
                    {i.concluido ? <CheckCircle2 className="size-5" /> : <Circle className="size-5" />}
                  </button>
                  <div className="min-w-0 flex-1">
                    <p className={cx('text-sm font-medium', i.concluido ? 'text-titulo' : 'text-texto-2')}>{ITENS_FECHAMENTO[i.item] ?? i.item}</p>
                    {i.verificacao && <p className="text-xs text-texto-3">{i.verificacao}</p>}
                    {i.observacao && <p className="mt-1 text-xs text-texto-2 italic">“{i.observacao}”</p>}
                    {i.concluidoEm && <p className="mt-0.5 text-xs text-texto-3">Concluído em {dataHora(i.concluidoEm)}{i.concluidoPor && ` por #${curto(i.concluidoPor)}`}</p>}
                  </div>
                  {editavel && <Button size="sm" variant="ghost" onClick={() => setObservando(i)}>Observação</Button>}
                </li>
              ))}
            </ul>
            <p className="text-xs text-texto-3">
              Depois de enviada, a competência fica travada: cobranças, contas a pagar e conciliações desse mês não podem ser alteradas.
              Se a contabilidade registrar uma pendência, o mês volta a ser editável.
            </p>

            {mudanca && <ModalPendencia status={mudanca} atual={x.pendencias} ocupado={ocupado} aoFechar={() => setMudanca(null)} aoConfirmar={(t) => void mudar(mudanca, t)} />}
            {observando && <ModalObservacao item={observando} ocupado={ocupado} aoFechar={() => setObservando(null)} aoSalvar={(t) => void marcar(observando, observando.concluido, t)} />}
          </>
        )
      }}
    </Conteudo>
  )
}

function ModalPendencia({ status, atual, ocupado, aoFechar, aoConfirmar }: { status: string; atual: string | null; ocupado: boolean; aoFechar: () => void; aoConfirmar: (texto: string) => void }) {
  const [texto, setTexto] = useState(atual ?? '')
  const enviar = (e: FormEvent) => {
    e.preventDefault()
    aoConfirmar(texto.trim())
  }
  return (
    <Modal open onClose={aoFechar} title={ROTULO_ACAO[status]} subtitle="O texto aparece para o financeiro e para a contabilidade"
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-pendencia" loading={ocupado}>Confirmar</Button></>}>
      <form id="form-pendencia" onSubmit={enviar}>
        <FormField label={status === 'AGUARDANDO_DOCUMENTO' ? 'Quais documentos faltam' : 'Pendências'} required>
          <Textarea required minLength={3} maxLength={2000} rows={5} value={texto} onChange={(e) => setTexto(e.target.value)} />
        </FormField>
      </form>
    </Modal>
  )
}

function ModalObservacao({ item, ocupado, aoFechar, aoSalvar }: { item: ItemFechamento; ocupado: boolean; aoFechar: () => void; aoSalvar: (texto: string | null) => void }) {
  const [texto, setTexto] = useState(item.observacao ?? '')
  return (
    <Modal open onClose={aoFechar} title={ITENS_FECHAMENTO[item.item] ?? item.item} subtitle={item.verificacao ?? undefined}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button loading={ocupado} onClick={() => aoSalvar(texto.trim() || null)}>Salvar</Button></>}>
      <FormField label="Observação"><Textarea maxLength={500} rows={4} value={texto} onChange={(e) => setTexto(e.target.value)} /></FormField>
    </Modal>
  )
}

function PainelPortal({ mes }: { mes: string }) {
  const p = useDados<Portal>('/contabilidade/portal', { competencia: mes })
  const [secao, setSecao] = useState(0)
  return (
    <Conteudo carga={p.carga} recarregar={p.recarregar}>
      {(x) => (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            {Object.entries(x.totais).filter(([, v]) => v !== 0).map(([k, v]) => <Indicador key={k} rotulo={TIPOS_LANCAMENTO[k] ?? k} valor={brl(v)} />)}
            {Object.values(x.totais).every((v) => v === 0) && <Indicador rotulo="Lançamentos" valor="Nenhum" />}
          </div>
          <Tabs tabs={[`Receitas (${x.receitas.length})`, `Recebimentos (${x.recebimentos.length})`, `Despesas (${x.despesas.length})`,
            `Pagamentos (${x.pagamentos.length})`, `Banco (${x.movimentacoesBancarias.length})`, `Notas (${x.documentosFiscais.length})`,
            `Comprovantes (${x.comprovantes.length})`, `Contratos (${x.contratos.length})`]} active={secao} onChange={setSecao} />
          {secao === 0 && <TabelaLancamentos l={x.receitas} />}
          {secao === 1 && <TabelaLancamentos l={x.recebimentos} />}
          {secao === 2 && <TabelaLancamentos l={x.despesas} />}
          {secao === 3 && <TabelaLancamentos l={x.pagamentos} />}
          {secao === 4 && (
            <TabelaGenerica linhas={x.movimentacoesBancarias} colunas={[
              ['data', 'Data', (v) => data(String(v))], ['conta', 'Conta'], ['descricao', 'Descrição'], ['documento', 'Documento'],
              ['classificacao', 'Classificação'], ['status', 'Situação', (v) => <Status status={String(v)} />], ['valor', 'Valor', (v) => brl(Number(v)), true],
            ]} />
          )}
          {secao === 5 && (
            <TabelaGenerica linhas={x.documentosFiscais} colunas={[
              ['data_emissao', 'Emissão', (v) => data(String(v))], ['tipo', 'Tipo'], ['modelo', 'Modelo'], ['numero', 'Número'],
              ['participante_nome', 'Participante'], ['status', 'Situação', (v) => <Status status={String(v)} />],
              ['valor_impostos', 'Impostos', (v) => brl(Number(v ?? 0)), true], ['valor', 'Valor', (v) => brl(Number(v)), true],
              ['documento_id', '', (v) => (v ? <BaixarDocumento id={String(v)} /> : null)],
            ]} />
          )}
          {secao === 6 && (
            <TabelaGenerica linhas={x.comprovantes} colunas={[
              ['created_at', 'Enviado', (v) => dataHora(String(v))], ['nome_arquivo', 'Arquivo'], ['categoria', 'Categoria'],
              ['entidade', 'Vinculado a'], ['descricao', 'Descrição'], ['id', '', (v) => <BaixarDocumento id={String(v)} />],
            ]} />
          )}
          {secao === 7 && (
            <TabelaGenerica linhas={x.contratos} colunas={[
              ['contrato_id', 'Contrato', (v) => <span className="font-mono text-xs">#{curto(String(v))}</span>],
              ['empresa_id', 'Empresa', (v) => <span className="font-mono text-xs">#{curto(String(v))}</span>],
              ['cobrancas', 'Cobranças', undefined, true], ['total', 'Total', (v) => brl(Number(v)), true],
            ]} />
          )}
        </>
      )}
    </Conteudo>
  )
}

type Coluna = [string, string, ((v: unknown) => ReactNode)?, boolean?]

function TabelaGenerica({ linhas, colunas }: { linhas: Linha[]; colunas: Coluna[] }) {
  if (linhas.length === 0) return <p className="py-8 text-center text-sm text-texto-3">Nada nesta competência.</p>
  return (
    <Tabela cabecalho={<>{colunas.map(([k, t, , num]) => <Th key={k + t} num={num}>{t}</Th>)}</>}>
      {linhas.map((l, i) => (
        <tr key={String(l.id ?? i)} className={linha}>
          {colunas.map(([k, t, fmt, num]) => <Td key={k + t} num={num}>{fmt ? fmt(l[k]) : String(l[k] ?? '—')}</Td>)}
        </tr>
      ))}
    </Tabela>
  )
}

function TabelaLancamentos({ l }: { l: Lancamento[] }) {
  if (l.length === 0) return <p className="py-8 text-center text-sm text-texto-3">Nada nesta competência.</p>
  return (
    <Tabela cabecalho={<>
      <Th>Data</Th><Th>Tipo</Th><Th>Histórico</Th><Th className="hidden lg:table-cell">Participante</Th><Th className="hidden md:table-cell">Débito</Th>
      <Th className="hidden md:table-cell">Crédito</Th><Th className="hidden lg:table-cell">Categoria</Th><Th className="hidden xl:table-cell">Centro</Th><Th num>Valor</Th>
    </>}>
      {l.map((x, i) => (
        <tr key={`${x.origemId ?? ''}-${x.tipo}-${i}`} className={linha}>
          <Td>{data(x.data)}</Td>
          <Td>{TIPOS_LANCAMENTO[x.tipo]?.replace(/s$/, '') ?? x.tipo}</Td>
          <Td><p className="max-w-80 truncate" title={x.historico}>{x.historico}</p>{x.documento && <p className="text-xs text-texto-3">doc. {x.documento}</p>}</Td>
          <Td className="hidden lg:table-cell">{x.participante ?? '—'}</Td>
          <Td className="hidden font-mono text-xs md:table-cell">{x.contaDebito ?? '—'}</Td>
          <Td className="hidden font-mono text-xs md:table-cell">{x.contaCredito ?? '—'}</Td>
          <Td className="hidden lg:table-cell">{x.categoria ?? '—'}</Td>
          <Td className="hidden xl:table-cell">{x.centroCusto ?? '—'}</Td>
          <Td num className="font-semibold text-titulo">{brl(x.valor)}</Td>
        </tr>
      ))}
    </Tabela>
  )
}

function PainelLancamentos({ mes }: { mes: string }) {
  const l = useDados<Lancamento[]>('/contabilidade/lancamentos', { competencia: mes })
  const [tipo, setTipo] = useState('')
  return (
    <Conteudo carga={l.carga} recarregar={l.recarregar} vazio={(x) => x.length === 0} textoVazio="Nenhum lançamento nesta competência" icone={<BookCheck aria-hidden />}>
      {(x) => {
        const sem = x.filter((y) => !y.contaDebito || !y.contaCredito).length
        return (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <select aria-label="Tipo" value={tipo} onChange={(e) => setTipo(e.target.value)}
                className="h-8 rounded-lg border border-borda bg-superficie px-2 text-xs text-texto">
                <option value="">Todos os tipos ({x.length})</option>
                {Object.entries(TIPOS_LANCAMENTO).filter(([k]) => x.some((y) => y.tipo === k)).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              {sem > 0 && <span className="text-xs text-amber-700 dark:text-amber-400">{sem} lançamento(s) sem conta contábil de débito ou crédito: configure em Cadastros.</span>}
            </div>
            <TabelaLancamentos l={tipo ? x.filter((y) => y.tipo === tipo) : x} />
          </>
        )
      }}
    </Conteudo>
  )
}

function Historico({ aoEscolher }: { aoEscolher: (mes: string) => void }) {
  const l = useDados<Fechamento[]>('/contabilidade/fechamentos')
  return (
    <Conteudo carga={l.carga} recarregar={l.recarregar} vazio={(x) => x.length === 0} textoVazio="Nenhum fechamento iniciado" icone={<BookCheck aria-hidden />}>
      {(x) => (
        <Tabela cabecalho={<><Th>Competência</Th><Th>Situação</Th><Th num>Checklist</Th><Th className="hidden md:table-cell">Enviado</Th><Th className="hidden md:table-cell">Finalizado</Th><Th /></>}>
          {x.map((f) => (
            <tr key={f.id ?? f.competencia} className={linha}>
              <Td className="font-medium text-titulo">{competencia(f.competencia)}</Td>
              <Td><Status status={f.status} /></Td>
              <Td num>{f.concluidos}/{f.total}</Td>
              <Td className="hidden md:table-cell">{dataHora(f.enviadoEm)}</Td>
              <Td className="hidden md:table-cell">{dataHora(f.finalizadoEm)}</Td>
              <Td className="text-right"><Button size="sm" variant="ghost" onClick={() => aoEscolher(f.competencia.slice(0, 7))}>Abrir</Button></Td>
            </tr>
          ))}
        </Tabela>
      )}
    </Conteudo>
  )
}

function Integracoes() {
  const l = useDados<Integracao[]>('/contabilidade/integracoes')
  return (
    <Conteudo carga={l.carga} recarregar={l.recarregar} vazio={(x) => x.length === 0} icone={<Plug aria-hidden />}>
      {(x) => (
        <>
          <DetailSection title="Sistemas contábeis">
            {x.map((i) => (
              <DetailField key={i.codigo} label={i.nome}>
                <Status status={i.disponivel ? (i.configurado ? 'ativa' : 'PENDENTE') : 'inativa'}
                  texto={i.disponivel ? (i.configurado ? 'Disponível' : 'Disponível, sem configuração') : 'Previsto'} />
              </DetailField>
            ))}
          </DetailSection>
          <p className="text-xs text-texto-3">
            Hoje o envio usa o pacote em arquivo (ZIP com lançamentos em CSV, XLSX, PDF e OFX, mais os comprovantes), que qualquer sistema contábil importa.
            Os conectores diretos marcados como previstos dependem do layout de cada fornecedor.
          </p>
        </>
      )}
    </Conteudo>
  )
}
