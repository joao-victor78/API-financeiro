import { useState, type ReactNode } from 'react'
import { Check, Copy, Download, FileDown, Paperclip, RotateCw, SearchX } from 'lucide-react'
import { Badge, Button, cx, EmptyState, Spinner } from '@/components/ui'
import { avisar } from '@/lib/avisos'
import { usePermissao, type Carga } from '@/lib/hooks'
import { corDoStatus, hoje, ROTULOS } from '@/lib/formato'
import { api, baixarArquivo, enviarArquivo, mensagemDe } from '@/plataforma/api'

/** Componentes do Financeiro montados sobre os do design system (components/ui, copiado da casca). */

export function Status({ status, texto }: { status: string; texto?: string }) {
  return <Badge variant={corDoStatus(status)}>{texto ?? ROTULOS[status] ?? status}</Badge>
}

/** Estados de interface (RNF-08): carregando, erro com "tentar de novo" e vazio. */
export function Conteudo<T>({ carga, vazio, textoVazio, icone, recarregar, children }: {
  carga: Carga<T>
  vazio?: (dados: T) => boolean
  textoVazio?: string
  icone?: ReactNode
  recarregar?: () => void
  children: (dados: T) => ReactNode
}) {
  if (carga.tipo === 'carregando') return <Spinner />
  if (carga.tipo === 'erro') {
    return (
      <EmptyState
        title={carga.status === 403 ? 'Sem permissão para ver esta tela' : 'Não foi possível carregar'}
        action={recarregar && carga.status !== 403 && <Button variant="outline" onClick={recarregar}><RotateCw aria-hidden className="size-4" /> Tentar de novo</Button>}
      >
        {carga.mensagem}
      </EmptyState>
    )
  }
  if (vazio?.(carga.dados)) return <EmptyState icon={icone ?? <SearchX aria-hidden />} title={textoVazio ?? 'Nenhum registro encontrado'} />
  return <>{children(carga.dados)}</>
}

/** Tabela no padrão da casca: cartão com borda, cabeçalho em caixa alta e linhas com hover. */
export function Tabela({ cabecalho, children, atualizando }: { cabecalho: ReactNode; children: ReactNode; atualizando?: boolean }) {
  return (
    <div className={cx('overflow-hidden rounded-xl border border-borda bg-superficie shadow-sm transition-opacity', atualizando && 'opacity-60')}>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="border-b border-borda bg-superficie-2 text-left text-xs font-semibold tracking-wide text-texto-3 uppercase">
            <tr>{cabecalho}</tr>
          </thead>
          <tbody>{children}</tbody>
        </table>
      </div>
    </div>
  )
}

export const Th = ({ children, num, className }: { children?: ReactNode; num?: boolean; className?: string }) => (
  <th scope="col" className={cx('px-4 py-2.5 whitespace-nowrap', num && 'text-right', className)}>{children}</th>
)

export const Td = ({ children, num, className }: { children?: ReactNode; num?: boolean; className?: string }) => (
  <td className={cx('px-4 py-3 align-middle', num && 'text-right tabular-nums whitespace-nowrap', className)}>{children}</td>
)

export const linha = 'border-b border-borda last:border-0 hover:bg-brand-50/60 dark:hover:bg-superficie-2'

/** Cartão de indicador (home do financeiro). */
export function Indicador({ rotulo, valor, detalhe, tom }: { rotulo: string; valor: ReactNode; detalhe?: ReactNode; tom?: 'ok' | 'erro' }) {
  return (
    <div className="rounded-xl border border-borda bg-superficie p-4 shadow-sm">
      <p className="text-xs font-medium text-texto-3">{rotulo}</p>
      <p className={cx('mt-1 text-xl font-bold tabular-nums text-titulo', tom === 'erro' && 'text-red-600 dark:text-red-400', tom === 'ok' && 'text-brand-700 dark:text-brand-400')}>{valor}</p>
      {detalhe && <p className="mt-0.5 text-xs text-texto-3">{detalhe}</p>}
    </div>
  )
}

export function Copiar({ texto }: { texto: string }) {
  const [ok, setOk] = useState(false)
  return (
    <Button variant="outline" size="sm" onClick={async () => {
      await navigator.clipboard.writeText(texto)
      setOk(true)
      setTimeout(() => setOk(false), 1500)
    }}>
      {ok ? <Check aria-hidden className="size-3.5" /> : <Copy aria-hidden className="size-3.5" />} {ok ? 'Copiado' : 'Copiar'}
    </Button>
  )
}

/** CSV, XLSX e PDF de um relatório (§78), no cabeçalho da página, como em contas a receber. */
export function BotoesExportar({ caminho, query, nome, formatos = ['csv', 'xlsx', 'pdf'] }: {
  caminho: string
  query?: Record<string, unknown>
  nome: string
  formatos?: string[]
}) {
  const { tem } = usePermissao()
  if (!tem('financeiro.relatorio.exportar') && !caminho.startsWith('/contabilidade')) return null
  return (
    <>
      {formatos.map((f) => (
        <Button key={f} variant="secondary" size="sm" onClick={async () => {
          try {
            await baixarArquivo(caminho, { ...(query ?? {}), formato: f }, `${nome}-${hoje()}.${f}`)
          } catch (e) {
            avisar('erro', mensagemDe(e))
          }
        }}>
          <FileDown aria-hidden className="size-3.5" /> {f.toUpperCase()}
        </Button>
      ))}
    </>
  )
}

/** Escolha de arquivo + envio para /documentos (§96). Devolve o id do documento criado. */
export function EnviarDocumento({ categoria, entidade, registroId, rotulo = 'Anexar arquivo', aoEnviar }: {
  categoria: string
  entidade?: string
  registroId?: string
  rotulo?: string
  aoEnviar: (documentoId: string) => void
}) {
  const [enviando, setEnviando] = useState(false)
  return (
    <label className={cx('inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-lg border border-brand-200 bg-superficie px-3 text-xs font-semibold text-titulo hover:bg-brand-50 dark:border-borda-forte dark:hover:bg-superficie-2', enviando && 'pointer-events-none opacity-55')}>
      <Paperclip aria-hidden className="size-3.5" /> {enviando ? 'Enviando…' : rotulo}
      <input type="file" className="sr-only" accept=".pdf,.xml,.png,.jpg,.jpeg,.csv,.txt,.xlsx,.zip,.ofx" onChange={async (e) => {
        const arquivo = e.target.files?.[0]
        e.target.value = ''
        if (!arquivo) return
        const form = new FormData()
        form.append('arquivo', arquivo)
        setEnviando(true)
        try {
          const r = await enviarArquivo<{ id: string }>('/documentos', form, { categoria, entidade, registroId })
          avisar('sucesso', r.message ?? 'Arquivo enviado.')
          aoEnviar(r.data.id)
        } catch (x) {
          avisar('erro', mensagemDe(x))
        } finally {
          setEnviando(false)
        }
      }} />
    </label>
  )
}

/** Link para baixar um documento da biblioteca (resposta sem envelope, token no cabeçalho). */
export function BaixarDocumento({ id, nome = 'documento' }: { id: string; nome?: string }) {
  return (
    <Button variant="ghost" size="sm" onClick={async () => {
      try {
        const info = await api<{ nomeArquivo: string }>(`/documentos/${id}`)
        await baixarArquivo(`/documentos/${id}/arquivo`, {}, info.data.nomeArquivo ?? nome)
      } catch (e) {
        avisar('erro', mensagemDe(e))
      }
    }}>
      <Download aria-hidden className="size-3.5" /> Baixar
    </Button>
  )
}

/** Cabeçalho do painel de detalhe (modal noPad, §5.2): fundo brand-950. */
export function CabecalhoDeDetalhe({ titulo, subtitulo, status, acoes }: { titulo: string; subtitulo?: ReactNode; status?: ReactNode; acoes?: ReactNode }) {
  return (
    <div className="shrink-0 bg-brand-950 px-5 pt-5 pb-4 text-white">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate text-lg font-bold">{titulo}</h2>
          {subtitulo && <p className="truncate text-sm text-brand-300">{subtitulo}</p>}
          {status && <div className="mt-1.5">{status}</div>}
        </div>
        {acoes && <div className="flex shrink-0 gap-1.5">{acoes}</div>}
      </div>
    </div>
  )
}
