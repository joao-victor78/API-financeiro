import { useState, type FormEvent } from 'react'
import { KeyRound, Pencil, Plus, Send, Trash2, Webhook } from 'lucide-react'
import { Button, Checkbox, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Select, Tabs } from '@/components/ui'
import { Conteudo, Copiar, Indicador, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { useAcao, useDados } from '@/lib/hooks'
import { curto, dataHora } from '@/lib/formato'
import { api } from '@/plataforma/api'

interface Assinatura {
  id: string
  descricao: string
  url: string
  eventos: string[]
  ativa: boolean
  segredo: string | null
  createdAt: string
  entregues: number
  pendentes: number
  falhas: number
}
interface Entrega {
  id: string
  assinaturaId: string
  eventoId: string | null
  tipo: string
  status: string
  tentativas: number
  ultimoStatusHttp: number | null
  ultimoErro: string | null
  proximaTentativa: string | null
  entregueEm: string | null
  criadoEm: string
}
interface Evento { tipo: string; apelido: string }

/** §91: central de webhooks de saída, assinados com HMAC-SHA256, com fila, novas tentativas e reenvio. */
export function Webhooks(_: { detalhe?: string }) {
  const [aba, setAba] = useState(0)
  const [filtroEntregas, setFiltroEntregas] = useState('')
  return (
    <>
      <PageHeader title="Webhooks" subtitle="Avise outros sistemas quando algo acontece no financeiro" />
      <Tabs tabs={['Destinos', 'Entregas']} active={aba} onChange={setAba} />
      {aba === 0
        ? <Destinos verEntregas={(id) => { setFiltroEntregas(id); setAba(1) }} />
        : <Entregas assinaturaId={filtroEntregas} setAssinaturaId={setFiltroEntregas} />}
    </>
  )
}

function Destinos({ verEntregas }: { verEntregas: (id: string) => void }) {
  const lista = useDados<Assinatura[]>('/webhooks')
  const { executar, ocupado } = useAcao()
  const [form, setForm] = useState<Assinatura | 'novo' | null>(null)
  const [segredo, setSegredo] = useState<string | null>(null)

  const comSegredo = (a: Assinatura) => {
    lista.recarregar()
    if (a.segredo) setSegredo(a.segredo)
  }

  return (
    <>
      <FilterBar>
        <p className="text-xs text-texto-3">
          Cada envio leva o cabeçalho <code className="font-mono">X-Financeiro-Assinatura: t=&lt;epoch&gt;,v1=&lt;HMAC-SHA256 de "t.corpo"&gt;</code>. Falhas são tentadas de novo com espera crescente.
        </p>
        <Button size="sm" className="ml-auto" onClick={() => setForm('novo')}><Plus aria-hidden className="size-3.5" /> Novo webhook</Button>
      </FilterBar>
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} textoVazio="Nenhum webhook cadastrado" icone={<Webhook aria-hidden />}>
          {(l) => (
            <Tabela atualizando={lista.atualizando} cabecalho={<><Th>Destino</Th><Th className="hidden md:table-cell">Eventos</Th><Th num>Entregues</Th><Th num>Pendentes</Th><Th num>Falhas</Th><Th>Situação</Th><Th /></>}>
              {l.map((a) => (
                <tr key={a.id} className={linha}>
                  <Td>
                    <p className="font-medium text-titulo">{a.descricao}</p>
                    <p className="max-w-72 truncate font-mono text-xs text-texto-3" title={a.url}>{a.url}</p>
                  </Td>
                  <Td className="hidden md:table-cell">
                    <p className="max-w-64 truncate text-xs" title={a.eventos.join(', ')}>{a.eventos.join(', ')}</p>
                  </Td>
                  <Td num>{a.entregues}</Td>
                  <Td num>{a.pendentes}</Td>
                  <Td num className={a.falhas ? 'text-red-600 dark:text-red-400' : undefined}>{a.falhas}</Td>
                  <Td><Status status={a.ativa ? 'ativa' : 'inativa'} texto={a.ativa ? 'Ativo' : 'Pausado'} /></Td>
                  <Td className="text-right whitespace-nowrap">
                    <Button size="sm" variant="ghost" disabled={ocupado} title="Enviar um evento de teste" onClick={() => executar(() => api(`/webhooks/${a.id}/teste`, { method: 'POST' }), lista.recarregar)}>
                      <Send aria-hidden className="size-3.5" /> Testar
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => verEntregas(a.id)}>Entregas</Button>
                    <Button size="sm" variant="ghost" aria-label="Trocar segredo" title="Trocar segredo" disabled={ocupado}
                      onClick={() => confirm('Gerar um novo segredo? O atual deixa de valer imediatamente.')
                        && executar(() => api<Assinatura>(`/webhooks/${a.id}/segredo`, { method: 'POST' }), comSegredo)}>
                      <KeyRound aria-hidden className="size-3.5" />
                    </Button>
                    <Button size="sm" variant="ghost" aria-label="Editar" onClick={() => setForm(a)}><Pencil aria-hidden className="size-3.5" /></Button>
                    <Button size="sm" variant="ghost" aria-label="Excluir" disabled={ocupado}
                      onClick={() => confirm(`Excluir o webhook "${a.descricao}"? As entregas pendentes deixam de ser enviadas.`)
                        && executar(() => api(`/webhooks/${a.id}`, { method: 'DELETE' }), lista.recarregar)}>
                      <Trash2 aria-hidden className="size-3.5" />
                    </Button>
                  </Td>
                </tr>
              ))}
            </Tabela>
          )}
        </Conteudo>
      </div>
      {form && (
        <FormWebhook atual={form === 'novo' ? null : form} aoFechar={() => setForm(null)}
          aoSalvar={(a) => { setForm(null); comSegredo(a) }} />
      )}
      {segredo && (
        <Modal open onClose={() => setSegredo(null)} size="md" title="Segredo do webhook" subtitle="Ele não será mostrado de novo"
          footer={<Button onClick={() => setSegredo(null)}>Já guardei</Button>}>
          <p className="mb-3 text-sm text-texto-2">Use este valor no destino para conferir a assinatura HMAC-SHA256 de cada envio.</p>
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate rounded-lg bg-superficie-2 px-3 py-2 font-mono text-xs">{segredo}</code>
            <Copiar texto={segredo} />
          </div>
        </Modal>
      )}
    </>
  )
}

function FormWebhook({ atual, aoFechar, aoSalvar }: { atual: Assinatura | null; aoFechar: () => void; aoSalvar: (a: Assinatura) => void }) {
  const eventos = useDados<Evento[]>('/webhooks/eventos')
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ descricao: atual?.descricao ?? '', url: atual?.url ?? 'https://', eventos: atual?.eventos ?? [], ativa: atual?.ativa ?? true })
  const alternar = (tipo: string) => setF({ ...f, eventos: f.eventos.includes(tipo) ? f.eventos.filter((e) => e !== tipo) : [...f.eventos, tipo] })
  const salvar = (e: FormEvent) => {
    e.preventDefault()
    void executar(() => api<Assinatura>(atual ? `/webhooks/${atual.id}` : '/webhooks', { method: atual ? 'PUT' : 'POST', body: f }), aoSalvar)
  }
  return (
    <Modal open onClose={aoFechar} title={atual ? 'Editar webhook' : 'Novo webhook'}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-webhook" loading={ocupado} disabled={f.eventos.length === 0}>Salvar</Button></>}>
      <form id="form-webhook" onSubmit={salvar}>
        <FormSection number={1} title="Destino">
          <FormField label="Descrição" required><Input required maxLength={120} value={f.descricao} onChange={(e) => setF({ ...f, descricao: e.target.value })} /></FormField>
          <FormField label="URL" required hint="Somente HTTPS e endereço público">
            <Input type="url" required maxLength={500} pattern="https://.+" value={f.url} onChange={(e) => setF({ ...f, url: e.target.value })} />
          </FormField>
          <label className="flex items-center gap-2 text-sm text-texto-2"><Checkbox checked={f.ativa} onChange={(e) => setF({ ...f, ativa: e.target.checked })} /> Ativo</label>
        </FormSection>
        <FormSection number={2} title="Eventos">
          <Conteudo carga={eventos.carga} recarregar={eventos.recarregar}>
            {(l) => (
              <div className="grid gap-2 sm:grid-cols-2 md:col-span-2">
                {l.map((ev) => (
                  <label key={ev.tipo} className="flex items-start gap-2 text-sm text-texto-2">
                    <Checkbox checked={f.eventos.includes(ev.tipo)} onChange={() => alternar(ev.tipo)} className="mt-0.5" />
                    <span><span className="font-medium text-titulo">{ev.apelido}</span><span className="block font-mono text-xs text-texto-3">{ev.tipo}</span></span>
                  </label>
                ))}
              </div>
            )}
          </Conteudo>
        </FormSection>
      </form>
    </Modal>
  )
}

function Entregas({ assinaturaId, setAssinaturaId }: { assinaturaId: string; setAssinaturaId: (id: string) => void }) {
  const [status, setStatus] = useState('')
  const destinos = useDados<Assinatura[]>('/webhooks')
  const lista = useDados<Entrega[]>('/webhooks/entregas', { assinaturaId, status })
  const { executar, ocupado } = useAcao()
  const nome = (id: string) => destinos.dados?.find((d) => d.id === id)?.descricao ?? `#${curto(id)}`
  const falhas = lista.dados?.filter((e) => e.status === 'FALHOU').length ?? 0
  return (
    <>
      <FilterBar>
        <Select aria-label="Destino" className="w-auto" value={assinaturaId} onChange={(e) => setAssinaturaId(e.target.value)}>
          <option value="">Todos os destinos</option>
          {destinos.dados?.map((d) => <option key={d.id} value={d.id}>{d.descricao}</option>)}
        </Select>
        <Select aria-label="Situação" className="w-auto" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">Todas</option><option value="PENDENTE">Pendentes</option><option value="ENTREGUE">Entregues</option><option value="FALHOU">Falharam</option>
        </Select>
        <Button size="sm" variant="outline" className="ml-auto" onClick={lista.recarregar}>Atualizar</Button>
      </FilterBar>
      <div className="flex-1 space-y-4 p-5">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
          <Indicador rotulo="Entregas listadas" valor={lista.dados?.length ?? '—'} detalhe="as 200 mais recentes" />
          <Indicador rotulo="Falharam" valor={falhas} tom={falhas ? 'erro' : undefined} />
        </div>
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} textoVazio="Nenhuma entrega neste filtro" icone={<Webhook aria-hidden />}>
          {(l) => (
            <Tabela atualizando={lista.atualizando} cabecalho={<><Th>Criada</Th><Th>Destino</Th><Th>Evento</Th><Th num>Tentativas</Th><Th className="hidden md:table-cell">Último retorno</Th><Th>Situação</Th><Th /></>}>
              {l.map((e) => (
                <tr key={e.id} className={linha}>
                  <Td className="whitespace-nowrap">{dataHora(e.criadoEm)}</Td>
                  <Td>{nome(e.assinaturaId)}</Td>
                  <Td className="font-mono text-xs">{e.tipo}</Td>
                  <Td num>{e.tentativas}</Td>
                  <Td className="hidden md:table-cell">
                    {e.ultimoStatusHttp && <span className="font-mono text-xs">HTTP {e.ultimoStatusHttp}</span>}
                    {e.ultimoErro && <p className="max-w-72 truncate text-xs text-red-600 dark:text-red-400" title={e.ultimoErro}>{e.ultimoErro}</p>}
                    {e.status === 'PENDENTE' && e.proximaTentativa && <p className="text-xs text-texto-3">próxima: {dataHora(e.proximaTentativa)}</p>}
                    {e.entregueEm && <p className="text-xs text-texto-3">entregue {dataHora(e.entregueEm)}</p>}
                  </Td>
                  <Td><Status status={e.status} /></Td>
                  <Td className="text-right">
                    {e.status === 'FALHOU' && (
                      <Button size="sm" variant="ghost" disabled={ocupado} onClick={() => executar(() => api(`/webhooks/entregas/${e.id}/reenviar`, { method: 'POST' }), lista.recarregar)}>
                        Reenviar
                      </Button>
                    )}
                  </Td>
                </tr>
              ))}
            </Tabela>
          )}
        </Conteudo>
      </div>
    </>
  )
}
