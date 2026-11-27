import { useState, type FormEvent } from 'react'
import { Landmark, Pencil, Plus, Tags, Trash2, Wallet } from 'lucide-react'
import { Button, Checkbox, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Select, Tabs } from '@/components/ui'
import { Conteudo, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { brl, CONTAS_TIPO, data, GRUPOS_DRE, hoje } from '@/lib/formato'
import type { Categoria, CentroCusto, ContaBancaria } from '@/lib/cadastros'
import { api } from '@/plataforma/api'

/** §25: plano de categorias (ligado ao DRE), centros de custo e contas bancárias do tenant. */
export function Cadastros(_: { detalhe?: string }) {
  const { tem } = usePermissao()
  const administra = tem('financeiro.cadastro.administrar')
  const [aba, setAba] = useState(0)
  return (
    <>
      <PageHeader title="Cadastros financeiros" subtitle="Categorias, centros de custo e contas bancárias usados nas cobranças, despesas, DRE e conciliação" />
      <Tabs tabs={['Categorias', 'Centros de custo', 'Contas bancárias']} active={aba} onChange={setAba} />
      {aba === 0 && <Categorias administra={administra} />}
      {aba === 1 && <Centros administra={administra} />}
      {aba === 2 && <Contas administra={administra} />}
    </>
  )
}

const Acoes = ({ administra, ocupado, aoEditar, aoExcluir }: { administra: boolean; ocupado: boolean; aoEditar: () => void; aoExcluir: () => void }) =>
  administra ? (
    <Td className="text-right whitespace-nowrap">
      <Button size="sm" variant="ghost" aria-label="Editar" onClick={aoEditar}><Pencil aria-hidden className="size-3.5" /></Button>
      <Button size="sm" variant="ghost" aria-label="Excluir" disabled={ocupado} onClick={aoExcluir}><Trash2 aria-hidden className="size-3.5" /></Button>
    </Td>
  ) : <Td />

function Categorias({ administra }: { administra: boolean }) {
  const lista = useDados<Categoria[]>('/cadastros/categorias')
  const { executar, ocupado } = useAcao()
  const [tipo, setTipo] = useState('')
  const [form, setForm] = useState<Categoria | 'nova' | null>(null)
  const planoPadrao = () => confirm('Criar o plano de categorias padrão (receitas e despesas de um MSP)? Categorias com o mesmo nome não são duplicadas.')
    && executar(() => api('/cadastros/categorias/plano-padrao', { method: 'POST' }), lista.recarregar)
  return (
    <>
      <FilterBar>
        <Select aria-label="Tipo" className="w-auto" value={tipo} onChange={(e) => setTipo(e.target.value)}>
          <option value="">Receitas e despesas</option><option value="RECEITA">Receitas</option><option value="DESPESA">Despesas</option>
        </Select>
        {administra && (
          <div className="ml-auto flex gap-2">
            <Button size="sm" variant="outline" disabled={ocupado} onClick={planoPadrao}>Criar plano padrão</Button>
            <Button size="sm" onClick={() => setForm('nova')}><Plus aria-hidden className="size-3.5" /> Nova categoria</Button>
          </div>
        )}
      </FilterBar>
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} icone={<Tags aria-hidden />}
          textoVazio={administra ? 'Nenhuma categoria. Comece pelo plano padrão.' : 'Nenhuma categoria cadastrada'}>
          {(l) => (
            <Tabela cabecalho={<><Th>Categoria</Th><Th>Tipo</Th><Th>Linha do DRE</Th><Th className="hidden md:table-cell">Conta contábil</Th><Th>Situação</Th><Th /></>}>
              {l.filter((c) => !tipo || c.tipo === tipo).map((c) => (
                <tr key={c.id} className={linha}>
                  <Td className="font-medium text-titulo">{c.nome}</Td>
                  <Td>{c.tipo === 'RECEITA' ? 'Receita' : 'Despesa'}</Td>
                  <Td>{GRUPOS_DRE[c.grupoDre] ?? c.grupoDre}</Td>
                  <Td className="hidden font-mono text-xs md:table-cell">{c.contaContabil ?? '—'}</Td>
                  <Td><Status status={c.ativa ? 'ativa' : 'inativa'} texto={c.ativa ? 'Ativa' : 'Inativa'} /></Td>
                  <Acoes administra={administra} ocupado={ocupado} aoEditar={() => setForm(c)}
                    aoExcluir={() => confirm(`Excluir a categoria "${c.nome}"? Os lançamentos que já a usam continuam com ela.`)
                      && executar(() => api(`/cadastros/categorias/${c.id}`, { method: 'DELETE' }), lista.recarregar)} />
                </tr>
              ))}
            </Tabela>
          )}
        </Conteudo>
      </div>
      {form && <FormCategoria atual={form === 'nova' ? null : form} aoFechar={() => setForm(null)} aoSalvar={() => { setForm(null); lista.recarregar() }} />}
    </>
  )
}

function FormCategoria({ atual, aoFechar, aoSalvar }: { atual: Categoria | null; aoFechar: () => void; aoSalvar: () => void }) {
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({
    nome: atual?.nome ?? '', tipo: atual?.tipo ?? 'DESPESA', grupoDre: atual?.grupoDre ?? 'DESPESAS_OPERACIONAIS',
    contaContabil: atual?.contaContabil ?? '', ativa: atual?.ativa ?? true,
  })
  const salvar = (e: FormEvent) => {
    e.preventDefault()
    void executar(() => api(atual ? `/cadastros/categorias/${atual.id}` : '/cadastros/categorias', {
      method: atual ? 'PUT' : 'POST', body: { ...f, contaContabil: f.contaContabil || null },
    }), aoSalvar)
  }
  return (
    <Modal open onClose={aoFechar} size="lg" title={atual ? 'Editar categoria' : 'Nova categoria'}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-categoria" loading={ocupado}>Salvar</Button></>}>
      <form id="form-categoria" onSubmit={salvar}>
        <FormSection title="Categoria">
          <FormField label="Nome" required colSpan={2}><Input required maxLength={80} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></FormField>
          <FormField label="Tipo" required>
            <Select value={f.tipo} onChange={(e) => {
              const tipo = e.target.value as 'RECEITA' | 'DESPESA'
              setF({ ...f, tipo, grupoDre: tipo === 'RECEITA' ? 'RECEITA_BRUTA' : 'DESPESAS_OPERACIONAIS' })
            }}>
              <option value="RECEITA">Receita</option><option value="DESPESA">Despesa</option>
            </Select>
          </FormField>
          <FormField label="Linha do DRE" required>
            <Select value={f.grupoDre} onChange={(e) => setF({ ...f, grupoDre: e.target.value })}>
              {Object.entries(GRUPOS_DRE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </Select>
          </FormField>
          <FormField label="Conta contábil" hint="Código no plano de contas do escritório (usado na exportação)">
            <Input maxLength={30} value={f.contaContabil} onChange={(e) => setF({ ...f, contaContabil: e.target.value })} />
          </FormField>
          <label className="flex items-center gap-2 self-end pb-2 text-sm text-texto-2"><Checkbox checked={f.ativa} onChange={(e) => setF({ ...f, ativa: e.target.checked })} /> Ativa</label>
        </FormSection>
      </form>
    </Modal>
  )
}

function Centros({ administra }: { administra: boolean }) {
  const lista = useDados<CentroCusto[]>('/cadastros/centros-custo')
  const { executar, ocupado } = useAcao()
  const [form, setForm] = useState<CentroCusto | 'novo' | null>(null)
  return (
    <>
      {administra && (
        <FilterBar>
          <Button size="sm" className="ml-auto" onClick={() => setForm('novo')}><Plus aria-hidden className="size-3.5" /> Novo centro de custo</Button>
        </FilterBar>
      )}
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} textoVazio="Nenhum centro de custo" icone={<Wallet aria-hidden />}>
          {(l) => (
            <Tabela cabecalho={<><Th>Código</Th><Th>Nome</Th><Th>Situação</Th><Th /></>}>
              {l.map((c) => (
                <tr key={c.id} className={linha}>
                  <Td className="font-mono text-xs">{c.codigo}</Td>
                  <Td className="font-medium text-titulo">{c.nome}</Td>
                  <Td><Status status={c.ativo ? 'ativa' : 'inativa'} texto={c.ativo ? 'Ativo' : 'Inativo'} /></Td>
                  <Acoes administra={administra} ocupado={ocupado} aoEditar={() => setForm(c)}
                    aoExcluir={() => confirm(`Excluir o centro de custo "${c.nome}"?`) && executar(() => api(`/cadastros/centros-custo/${c.id}`, { method: 'DELETE' }), lista.recarregar)} />
                </tr>
              ))}
            </Tabela>
          )}
        </Conteudo>
      </div>
      {form && <FormCentro atual={form === 'novo' ? null : form} aoFechar={() => setForm(null)} aoSalvar={() => { setForm(null); lista.recarregar() }} />}
    </>
  )
}

function FormCentro({ atual, aoFechar, aoSalvar }: { atual: CentroCusto | null; aoFechar: () => void; aoSalvar: () => void }) {
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ codigo: atual?.codigo ?? '', nome: atual?.nome ?? '', ativo: atual?.ativo ?? true })
  const salvar = (e: FormEvent) => {
    e.preventDefault()
    void executar(() => api(atual ? `/cadastros/centros-custo/${atual.id}` : '/cadastros/centros-custo', { method: atual ? 'PUT' : 'POST', body: f }), aoSalvar)
  }
  return (
    <Modal open onClose={aoFechar} size="lg" title={atual ? 'Editar centro de custo' : 'Novo centro de custo'}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-centro" loading={ocupado}>Salvar</Button></>}>
      <form id="form-centro" onSubmit={salvar}>
        <FormSection title="Centro de custo">
          <FormField label="Código" required><Input required maxLength={20} placeholder="SUP, PROJ, ADM…" value={f.codigo} onChange={(e) => setF({ ...f, codigo: e.target.value })} /></FormField>
          <FormField label="Nome" required><Input required maxLength={80} value={f.nome} onChange={(e) => setF({ ...f, nome: e.target.value })} /></FormField>
          <label className="flex items-center gap-2 text-sm text-texto-2"><Checkbox checked={f.ativo} onChange={(e) => setF({ ...f, ativo: e.target.checked })} /> Ativo</label>
        </FormSection>
      </form>
    </Modal>
  )
}

function Contas({ administra }: { administra: boolean }) {
  const lista = useDados<ContaBancaria[]>('/cadastros/contas-bancarias')
  const { executar, ocupado } = useAcao()
  const [form, setForm] = useState<ContaBancaria | 'nova' | null>(null)
  return (
    <>
      {administra && (
        <FilterBar>
          <Button size="sm" className="ml-auto" onClick={() => setForm('nova')}><Plus aria-hidden className="size-3.5" /> Nova conta</Button>
        </FilterBar>
      )}
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(l) => l.length === 0} textoVazio="Nenhuma conta bancária" icone={<Landmark aria-hidden />}>
          {(l) => (
            <Tabela cabecalho={<><Th>Conta</Th><Th>Tipo</Th><Th className="hidden md:table-cell">Banco / agência / número</Th><Th num>Saldo inicial</Th><Th className="hidden md:table-cell">Conta contábil</Th><Th>Situação</Th><Th /></>}>
              {l.map((c) => (
                <tr key={c.id} className={linha}>
                  <Td>
                    <p className="font-medium text-titulo">{c.nome}</p>
                    {c.padraoRecebimento && <p className="text-xs text-brand-700 dark:text-brand-400">Padrão para recebimentos</p>}
                  </Td>
                  <Td>{CONTAS_TIPO[c.tipo] ?? c.tipo}</Td>
                  <Td className="hidden font-mono text-xs md:table-cell">{[c.bancoCodigo, c.agencia, c.numero].filter(Boolean).join(' / ') || '—'}</Td>
                  <Td num>{brl(c.saldoInicial)}<p className="text-xs text-texto-3">em {data(c.dataSaldoInicial)}</p></Td>
                  <Td className="hidden font-mono text-xs md:table-cell">{c.contaContabil ?? '—'}</Td>
                  <Td><Status status={c.ativa ? 'ativa' : 'inativa'} texto={c.ativa ? 'Ativa' : 'Inativa'} /></Td>
                  <Acoes administra={administra} ocupado={ocupado} aoEditar={() => setForm(c)}
                    aoExcluir={() => confirm(`Excluir a conta "${c.nome}"? Extratos já importados continuam guardados.`)
                      && executar(() => api(`/cadastros/contas-bancarias/${c.id}`, { method: 'DELETE' }), lista.recarregar)} />
                </tr>
              ))}
            </Tabela>
          )}
        </Conteudo>
      </div>
      {form && <FormConta atual={form === 'nova' ? null : form} aoFechar={() => setForm(null)} aoSalvar={() => { setForm(null); lista.recarregar() }} />}
    </>
  )
}

function FormConta({ atual, aoFechar, aoSalvar }: { atual: ContaBancaria | null; aoFechar: () => void; aoSalvar: () => void }) {
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({
    nome: atual?.nome ?? '', bancoCodigo: atual?.bancoCodigo ?? '', agencia: atual?.agencia ?? '', numero: atual?.numero ?? '',
    tipo: atual?.tipo ?? 'CORRENTE', saldoInicial: atual ? String(atual.saldoInicial) : '0', dataSaldoInicial: atual?.dataSaldoInicial ?? hoje(),
    contaContabil: atual?.contaContabil ?? '', padraoRecebimento: atual?.padraoRecebimento ?? false, ativa: atual?.ativa ?? true,
  })
  const campo = (k: 'nome' | 'bancoCodigo' | 'agencia' | 'numero' | 'saldoInicial' | 'dataSaldoInicial' | 'contaContabil') =>
    ({ value: f[k], onChange: (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value }) })
  const salvar = (e: FormEvent) => {
    e.preventDefault()
    const vazioNulo = (s: string) => s.trim() || null
    void executar(() => api(atual ? `/cadastros/contas-bancarias/${atual.id}` : '/cadastros/contas-bancarias', {
      method: atual ? 'PUT' : 'POST',
      body: {
        ...f, bancoCodigo: vazioNulo(f.bancoCodigo), agencia: vazioNulo(f.agencia), numero: vazioNulo(f.numero),
        contaContabil: vazioNulo(f.contaContabil), saldoInicial: Number(f.saldoInicial || 0),
      },
    }), aoSalvar)
  }
  return (
    <Modal open onClose={aoFechar} title={atual ? 'Editar conta bancária' : 'Nova conta bancária'}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-conta" loading={ocupado}>Salvar</Button></>}>
      <form id="form-conta" onSubmit={salvar}>
        <FormSection number={1} title="Identificação">
          <FormField label="Nome" required colSpan={2}><Input required maxLength={80} placeholder="Itaú principal, Caixa da empresa…" {...campo('nome')} /></FormField>
          <FormField label="Tipo" required>
            <Select value={f.tipo} onChange={(e) => setF({ ...f, tipo: e.target.value })}>{Object.entries(CONTAS_TIPO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select>
          </FormField>
          <FormField label="Código do banco" hint="3 a 5 dígitos (ex.: 341)"><Input inputMode="numeric" pattern="\d{3,5}" maxLength={5} {...campo('bancoCodigo')} /></FormField>
          <FormField label="Agência"><Input maxLength={10} {...campo('agencia')} /></FormField>
          <FormField label="Número da conta"><Input maxLength={20} {...campo('numero')} /></FormField>
        </FormSection>
        <FormSection number={2} title="Saldo e contabilidade">
          <FormField label="Saldo inicial (R$)" required><Input type="number" step="0.01" required {...campo('saldoInicial')} /></FormField>
          <FormField label="Data do saldo inicial" required><Input type="date" required {...campo('dataSaldoInicial')} /></FormField>
          <FormField label="Conta contábil"><Input maxLength={30} {...campo('contaContabil')} /></FormField>
          <div className="flex flex-col gap-2 text-sm text-texto-2">
            <label className="flex items-center gap-2"><Checkbox checked={f.padraoRecebimento} onChange={(e) => setF({ ...f, padraoRecebimento: e.target.checked })} /> Padrão para recebimentos</label>
            <label className="flex items-center gap-2"><Checkbox checked={f.ativa} onChange={(e) => setF({ ...f, ativa: e.target.checked })} /> Ativa</label>
          </div>
        </FormSection>
      </form>
    </Modal>
  )
}
