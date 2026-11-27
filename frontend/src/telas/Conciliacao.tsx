import { useState } from 'react'
import { Landmark, Link2, Plus, Undo2, Upload, Wand2, X } from 'lucide-react'
import { Button, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Pagination, Select, Spinner } from '@/components/ui'
import { BotoesExportar, Conteudo, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { avisar } from '@/lib/avisos'
import { useCadastros } from '@/lib/cadastros'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { brl, CLASSIFICACOES, data, dataHora, hoje, ROTULOS } from '@/lib/formato'
import { api, enviarArquivo, mensagemDe, type Pagina } from '@/plataforma/api'

interface Transacao {
  id: string
  contaBancariaId: string
  data: string
  valor: number
  descricao: string | null
  documento: string | null
  classificacao: string | null
  status: string
  modo: string | null
  pagamentoId: string | null
  contaPagarId: string | null
  categoriaId: string | null
  conciliadoEm: string | null
}
interface Resumo {
  contaBancariaId: string
  conta: string
  pendentes: number
  conciliadas: number
  ignoradas: number
  saldoPeloExtrato: number
  saldoPeloSistema: number
  diferenca: number
  ultimoExtrato: string | null
}
interface Candidato { tipo: string; id: string; titulo: string; valor: number; data: string; diasDeDiferenca: number }
interface Sugestoes { classificacaoSugerida: string; candidatos: Candidato[] }

const TIPO_CANDIDATO: Record<string, string> = {
  PAGAMENTO: 'Pagamento recebido', COBRANCA_EM_ABERTO: 'Baixar cobrança', CONTA_PAGA: 'Conta paga', CONTA_APROVADA: 'Pagar conta aprovada',
}

/** §32: importação OFX, conciliação automática e manual; tarifa, juros, transferência e estorno identificados. */
export function Conciliacao(_: { detalhe?: string }) {
  const { tem } = usePermissao()
  const executa = tem('financeiro.conciliacao.executar')
  const cadastros = useCadastros()
  const [filtro, setFiltro] = useState({ contaBancariaId: '', status: 'PENDENTE', de: '', ate: '', pagina: 0 })
  const resumo = useDados<Resumo[]>('/conciliacao/resumo')
  const lista = useDados<Pagina<Transacao>>('/conciliacao/transacoes', { ...filtro, tamanho: 30 })
  const { executar, ocupado } = useAcao()
  const [importar, setImportar] = useState(false)
  const [lancar, setLancar] = useState(false)
  const [conciliando, setConciliando] = useState<Transacao | null>(null)
  const recarregar = () => {
    resumo.recarregar()
    lista.recarregar()
  }
  const filtrar = (m: Partial<typeof filtro>) => setFiltro({ ...filtro, ...m, pagina: 0 })

  return (
    <>
      <PageHeader title="Conciliação bancária" subtitle="Extrato do banco × recebimentos e pagamentos do sistema"
        actions={<>
          <BotoesExportar caminho="/relatorios/conciliacao/exportar" query={{ contaBancariaId: filtro.contaBancariaId, status: filtro.status, de: filtro.de, ate: filtro.ate }} nome="conciliacao" />
          {executa && <Button variant="secondary" size="sm" disabled={ocupado} onClick={() => executar(() => api('/conciliacao/automatica', { method: 'POST', query: { contaBancariaId: filtro.contaBancariaId } }), recarregar)}><Wand2 aria-hidden className="size-3.5" /> Conciliar automaticamente</Button>}
          {executa && <Button variant="secondary" size="sm" onClick={() => setLancar(true)}><Plus aria-hidden className="size-3.5" /> Lançamento</Button>}
          {executa && <Button size="sm" onClick={() => setImportar(true)}><Upload aria-hidden className="size-3.5" /> Importar OFX</Button>}
        </>} />
      <FilterBar>
        <Select aria-label="Conta" className="w-auto" value={filtro.contaBancariaId} onChange={(e) => filtrar({ contaBancariaId: e.target.value })}>
          <option value="">Todas as contas</option>
          {cadastros.contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
        </Select>
        <Select aria-label="Situação" className="w-auto" value={filtro.status} onChange={(e) => filtrar({ status: e.target.value })}>
          <option value="">Todas</option>
          <option value="PENDENTE">Pendentes</option>
          <option value="CONCILIADA">Conciliadas</option>
          <option value="IGNORADA">Ignoradas</option>
        </Select>
        <Input aria-label="De" type="date" className="w-auto" value={filtro.de} onChange={(e) => filtrar({ de: e.target.value })} />
        <Input aria-label="Até" type="date" className="w-auto" value={filtro.ate} onChange={(e) => filtrar({ ate: e.target.value })} />
      </FilterBar>
      <div className="flex-1 space-y-5 p-5">
        {cadastros.pode && cadastros.contas.length === 0 && (
          <p className="text-sm text-texto-3">Cadastre as contas bancárias em <strong>Cadastros</strong> para importar extratos.</p>
        )}
        <Conteudo carga={resumo.carga} recarregar={resumo.recarregar} vazio={(r) => r.length === 0} textoVazio="Nenhuma conta bancária ativa" icone={<Landmark aria-hidden />}>
          {(r) => (
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              {r.map((c) => (
                <div key={c.contaBancariaId} className="rounded-xl border border-borda bg-superficie p-4 shadow-sm">
                  <div className="flex items-center justify-between">
                    <p className="font-semibold text-titulo">{c.conta}</p>
                    <span className="text-xs text-texto-3">{c.ultimoExtrato ? `extrato até ${data(c.ultimoExtrato)}` : 'sem extrato'}</span>
                  </div>
                  <div className="mt-3 grid grid-cols-3 gap-2 text-center text-xs">
                    <div><p className="text-lg font-bold text-titulo tabular-nums">{c.pendentes}</p>pendentes</div>
                    <div><p className="text-lg font-bold text-brand-700 tabular-nums dark:text-brand-400">{c.conciliadas}</p>conciliadas</div>
                    <div><p className="text-lg font-bold text-texto-3 tabular-nums">{c.ignoradas}</p>ignoradas</div>
                  </div>
                  <dl className="mt-3 space-y-1 text-sm">
                    <div className="flex justify-between"><dt className="text-texto-3">Saldo pelo extrato</dt><dd className="tabular-nums">{brl(c.saldoPeloExtrato)}</dd></div>
                    <div className="flex justify-between"><dt className="text-texto-3">Saldo pelo sistema</dt><dd className="tabular-nums">{brl(c.saldoPeloSistema)}</dd></div>
                    <div className="flex justify-between font-semibold"><dt>Diferença</dt>
                      <dd className={c.diferenca === 0 ? 'text-brand-700 tabular-nums dark:text-brand-400' : 'text-red-600 tabular-nums dark:text-red-400'}>{brl(c.diferenca)}</dd></div>
                  </dl>
                </div>
              ))}
            </div>
          )}
        </Conteudo>

        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(p) => p.itens.length === 0} textoVazio="Nenhuma transação com estes filtros" icone={<Landmark aria-hidden />}>
          {(p) => (
            <>
              <Tabela atualizando={lista.atualizando} cabecalho={<>
                <Th>Data</Th><Th>Descrição</Th><Th className="hidden md:table-cell">Conta</Th><Th num>Valor</Th><Th>Identificação</Th><Th>Situação</Th><Th />
              </>}>
                {p.itens.map((t) => (
                  <tr key={t.id} className={linha}>
                    <Td>{data(t.data)}</Td>
                    <Td><p className="text-titulo">{t.descricao ?? '—'}</p>{t.documento && <p className="text-xs text-texto-3">doc. {t.documento}</p>}</Td>
                    <Td className="hidden md:table-cell">{cadastros.nomeConta(t.contaBancariaId)}</Td>
                    <Td num className={t.valor < 0 ? 'text-red-600 dark:text-red-400' : 'text-brand-700 dark:text-brand-400'}>{brl(t.valor)}</Td>
                    <Td>{t.classificacao ? CLASSIFICACOES[t.classificacao] : '—'}{t.status === 'PENDENTE' && t.classificacao && <span className="ml-1 text-xs text-texto-3">(sugerida)</span>}</Td>
                    <Td><Status status={t.status} />{t.modo && <p className="text-xs text-texto-3">{ROTULOS[t.modo]} · {dataHora(t.conciliadoEm)}</p>}</Td>
                    <Td className="text-right whitespace-nowrap">
                      {executa && t.status === 'PENDENTE' && (
                        <div className="flex justify-end gap-1.5">
                          <Button size="sm" onClick={() => setConciliando(t)}><Link2 aria-hidden className="size-3.5" /> Conciliar</Button>
                          <Button size="sm" variant="ghost" disabled={ocupado} title="Ignorar (não é movimento da empresa)"
                            onClick={() => executar(() => api(`/conciliacao/transacoes/${t.id}/ignorar`, { method: 'POST' }), recarregar)}><X aria-hidden className="size-3.5" /></Button>
                        </div>
                      )}
                      {executa && t.status !== 'PENDENTE' && (
                        <Button size="sm" variant="ghost" disabled={ocupado} onClick={() => confirm('Desfazer a conciliação? Baixas e pagamentos já registrados continuam valendo.')
                          && executar(() => api(`/conciliacao/transacoes/${t.id}/desfazer`, { method: 'POST' }), recarregar)}><Undo2 aria-hidden className="size-3.5" /> Desfazer</Button>
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

      {importar && <ImportarOfx contas={cadastros.contas} aoFechar={() => setImportar(false)} aoImportar={() => { setImportar(false); recarregar() }} />}
      {lancar && <NovoLancamento contas={cadastros.contas} aoFechar={() => setLancar(false)} aoSalvar={() => { setLancar(false); recarregar() }} />}
      {conciliando && <ConciliarTransacao t={conciliando} aoFechar={() => setConciliando(null)} aoConciliar={() => { setConciliando(null); recarregar() }} />}
    </>
  )
}

function ImportarOfx({ contas, aoFechar, aoImportar }: { contas: { id: string; nome: string }[]; aoFechar: () => void; aoImportar: () => void }) {
  const [conta, setConta] = useState(contas[0]?.id ?? '')
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [enviando, setEnviando] = useState(false)
  async function enviar() {
    if (!arquivo || !conta) return
    const form = new FormData()
    form.append('arquivo', arquivo)
    setEnviando(true)
    try {
      const r = await enviarArquivo('/conciliacao/extratos', form, { contaBancariaId: conta })
      avisar('sucesso', r.message ?? 'Extrato importado.')
      aoImportar()
    } catch (e) {
      avisar('erro', mensagemDe(e))
    } finally {
      setEnviando(false)
    }
  }
  return (
    <Modal open onClose={aoFechar} size="md" title="Importar extrato OFX" subtitle="O mesmo arquivo não entra duas vezes; lançamentos repetidos são ignorados."
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button loading={enviando} disabled={!arquivo || !conta} onClick={enviar}>Importar</Button></>}>
      <FormSection title="Extrato">
        <FormField label="Conta bancária" required colSpan={2}>
          <Select value={conta} onChange={(e) => setConta(e.target.value)}>
            {contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </Select>
        </FormField>
        <FormField label="Arquivo .ofx" required colSpan={2} hint="Exportado do internet banking (OFX 1.x ou 2.x).">
          <Input type="file" accept=".ofx" onChange={(e) => setArquivo(e.target.files?.[0] ?? null)} />
        </FormField>
      </FormSection>
    </Modal>
  )
}

function NovoLancamento({ contas, aoFechar, aoSalvar }: { contas: { id: string; nome: string }[]; aoFechar: () => void; aoSalvar: () => void }) {
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ contaBancariaId: contas[0]?.id ?? '', data: hoje(), valor: '', descricao: '', documento: '' })
  return (
    <Modal open onClose={aoFechar} size="md" title="Lançamento bancário" subtitle="Movimento sem arquivo: caixa, ajuste ou integração manual. Valor negativo = saída."
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-lanc" loading={ocupado}>Registrar</Button></>}>
      <form id="form-lanc" onSubmit={(e) => { e.preventDefault(); void executar(() => api('/conciliacao/transacoes', { method: 'POST', body: f }), aoSalvar) }}>
        <FormSection title="Movimento">
          <FormField label="Conta" required colSpan={2}>
            <Select value={f.contaBancariaId} onChange={(e) => setF({ ...f, contaBancariaId: e.target.value })}>{contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}</Select>
          </FormField>
          <FormField label="Data" required><Input type="date" required value={f.data} onChange={(e) => setF({ ...f, data: e.target.value })} /></FormField>
          <FormField label="Valor (R$)" required hint="Negativo para saída"><Input type="number" step="0.01" required value={f.valor} onChange={(e) => setF({ ...f, valor: e.target.value })} /></FormField>
          <FormField label="Descrição" colSpan={2}><Input maxLength={300} value={f.descricao} onChange={(e) => setF({ ...f, descricao: e.target.value })} /></FormField>
          <FormField label="Documento"><Input maxLength={60} value={f.documento} onChange={(e) => setF({ ...f, documento: e.target.value })} /></FormField>
        </FormSection>
      </form>
    </Modal>
  )
}

function ConciliarTransacao({ t, aoFechar, aoConciliar }: { t: Transacao; aoFechar: () => void; aoConciliar: () => void }) {
  const sugestoes = useDados<Sugestoes>(`/conciliacao/transacoes/${t.id}/sugestoes`)
  const cadastros = useCadastros()
  const { executar, ocupado } = useAcao()
  const [classificacao, setClassificacao] = useState('')
  const [categoria, setCategoria] = useState('')
  const vincular = (c: Candidato) => {
    const corpo = c.tipo === 'PAGAMENTO' ? { pagamentoId: c.id } : c.tipo === 'COBRANCA_EM_ABERTO' ? { cobrancaId: c.id } : { contaPagarId: c.id }
    void executar(() => api(`/conciliacao/transacoes/${t.id}/conciliar`, { method: 'POST', body: corpo }), aoConciliar)
  }
  const classes = ['TARIFA', 'JUROS', 'TRANSFERENCIA', 'ESTORNO', 'OUTRO']
  return (
    <Modal open onClose={aoFechar} size="lg" title="Conciliar transação" subtitle={`${data(t.data)} · ${brl(t.valor)} · ${t.descricao ?? ''}`}
      footer={<Button variant="outline" onClick={aoFechar}>Fechar</Button>}>
      <FormSection number={1} title="Documento no sistema">
        <div className="md:col-span-2">
          {!sugestoes.dados ? <Spinner /> : sugestoes.dados.candidatos.length === 0 ? (
            <p className="text-sm text-texto-3">Nenhum recebimento, cobrança ou conta compatível. Classifique abaixo se for tarifa, juros ou transferência.</p>
          ) : (
            <div className="space-y-2">
              {sugestoes.dados.candidatos.map((c) => (
                <div key={c.tipo + c.id} className="flex flex-wrap items-center gap-3 rounded-lg border border-borda p-3">
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-semibold text-texto-3 uppercase">{TIPO_CANDIDATO[c.tipo] ?? c.tipo}</p>
                    <p className="truncate text-sm text-titulo">{c.titulo}</p>
                    <p className="text-xs text-texto-3">{data(c.data)} · {c.diasDeDiferenca} dia(s) de diferença</p>
                  </div>
                  <span className="tabular-nums">{brl(c.valor)}</span>
                  <Button size="sm" disabled={ocupado} onClick={() => vincular(c)}>{c.tipo === 'COBRANCA_EM_ABERTO' ? 'Baixar e conciliar' : c.tipo === 'CONTA_APROVADA' ? 'Pagar e conciliar' : 'Conciliar'}</Button>
                </div>
              ))}
            </div>
          )}
        </div>
      </FormSection>
      <FormSection number={2} title="Sem documento no sistema">
        <FormField label="Identificação" hint={sugestoes.dados ? `Sugestão pela descrição: ${CLASSIFICACOES[sugestoes.dados.classificacaoSugerida]}` : undefined}>
          <Select value={classificacao} onChange={(e) => setClassificacao(e.target.value)}>
            <option value="">Escolha…</option>
            {classes.map((c) => <option key={c} value={c}>{CLASSIFICACOES[c]}</option>)}
          </Select>
        </FormField>
        <FormField label="Categoria (DRE)" hint="Opcional: sem categoria, tarifa e juros vão para despesas/receitas financeiras.">
          <Select value={categoria} onChange={(e) => setCategoria(e.target.value)}>
            <option value="">—</option>
            {cadastros.categorias.filter((c) => c.ativa).map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </Select>
        </FormField>
        <div className="flex justify-end md:col-span-2">
          <Button variant="outline" disabled={!classificacao || ocupado}
            onClick={() => executar(() => api(`/conciliacao/transacoes/${t.id}/conciliar`, { method: 'POST', body: { classificacao, categoriaId: categoria } }), aoConciliar)}>
            Classificar e conciliar
          </Button>
        </div>
      </FormSection>
    </Modal>
  )
}
