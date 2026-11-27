import { useState, type FormEvent } from 'react'
import { Check, Plus, Wallet } from 'lucide-react'
import { Button, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Pagination, Select } from '@/components/ui'
import { BaixarDocumento, BotoesExportar, Conteudo, EnviarDocumento, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { useCadastros } from '@/lib/cadastros'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { brl, competencia, curto, data, dataHora, hoje } from '@/lib/formato'
import { api, type Pagina } from '@/plataforma/api'

interface ContaPagar {
  id: string
  fornecedorNome: string
  fornecedorDocumento: string | null
  categoria: string | null
  centroCusto: string | null
  competencia: string
  vencimento: string
  valor: number
  documento: string | null
  status: string
  aprovadoPor: string | null
  aprovadoEm: string | null
  dataPagamento: string | null
  valorPago: number | null
  // V2 (§25, §96)
  categoriaId?: string | null
  centroCustoId?: string | null
  contaBancariaId?: string | null
  documentoId?: string | null
}

/** RF-02: quem lança não é quem aprova (RN-11); pagamento só depois de aprovada (RN-12). */
export function ContasPagar(_: { detalhe?: string }) {
  const { tem } = usePermissao()
  const cad = useCadastros()
  const [filtro, setFiltro] = useState({ aprovado: '', ordenar: 'vencimento,asc', pagina: 0, categoriaId: '', centroCustoId: '', competencia: '' })
  const query = { ...filtro, competencia: filtro.competencia ? `${filtro.competencia}-01` : '', tamanho: 20 }
  const lista = useDados<Pagina<ContaPagar>>('/contas-pagar', query)
  const { executar, ocupado } = useAcao()
  const [nova, setNova] = useState(false)
  const [pagando, setPagando] = useState<ContaPagar | null>(null)
  const podeAnexar = tem('financeiro.contapagar.criar') && tem('financeiro.documento.enviar')

  const aprovar = (c: ContaPagar) => confirm(`Aprovar o pagamento de ${brl(c.valor)} a ${c.fornecedorNome}? A aprovação fica registrada com seu usuário.`)
    && executar(() => api(`/contas-pagar/${c.id}/aprovar`, { method: 'PATCH' }), lista.recarregar)
  const anexar = (c: ContaPagar, documentoId: string) =>
    executar(() => api(`/contas-pagar/${c.id}/documento`, { method: 'PUT', body: { documentoId } }), lista.recarregar)

  return (
    <>
      <PageHeader title="Contas a pagar" subtitle={lista.dados ? `${lista.dados.total} conta(s)` : 'Despesas com fornecedores'}
        actions={<>
          <BotoesExportar caminho="/relatorios/contas-pagar/exportar" query={{ competencia: query.competencia }} nome="contas-pagar" />
          {tem('financeiro.contapagar.criar') && <Button size="sm" onClick={() => setNova(true)}><Plus aria-hidden className="size-3.5" /> Nova conta</Button>}
        </>} />
      <FilterBar>
        <Select aria-label="Situação" className="w-auto" value={filtro.aprovado} onChange={(e) => setFiltro({ ...filtro, aprovado: e.target.value, pagina: 0 })}>
          <option value="">Todas</option>
          <option value="false">Pendentes de aprovação</option>
          <option value="true">Aprovadas e pagas</option>
        </Select>
        <Select aria-label="Ordenar por" className="w-auto" value={filtro.ordenar} onChange={(e) => setFiltro({ ...filtro, ordenar: e.target.value, pagina: 0 })}>
          <option value="vencimento,asc">Vencimento ↑</option>
          <option value="vencimento,desc">Vencimento ↓</option>
          <option value="criadoEm,desc">Mais recentes</option>
        </Select>
        <Input aria-label="Competência" type="month" className="w-auto" value={filtro.competencia} onChange={(e) => setFiltro({ ...filtro, competencia: e.target.value, pagina: 0 })} />
        {cad.categorias.length > 0 && (
          <Select aria-label="Categoria" className="w-auto" value={filtro.categoriaId} onChange={(e) => setFiltro({ ...filtro, categoriaId: e.target.value, pagina: 0 })}>
            <option value="">Todas as categorias</option>
            {cad.categorias.filter((c) => c.tipo === 'DESPESA').map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </Select>
        )}
        {cad.centros.length > 0 && (
          <Select aria-label="Centro de custo" className="w-auto" value={filtro.centroCustoId} onChange={(e) => setFiltro({ ...filtro, centroCustoId: e.target.value, pagina: 0 })}>
            <option value="">Todos os centros</option>
            {cad.centros.map((c) => <option key={c.id} value={c.id}>{c.codigo} · {c.nome}</option>)}
          </Select>
        )}
      </FilterBar>
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(p) => p.itens.length === 0} icone={<Wallet aria-hidden />} textoVazio="Nenhuma conta a pagar">
          {(p) => (
            <>
              <Tabela atualizando={lista.atualizando} cabecalho={<>
                <Th>Fornecedor</Th><Th className="hidden md:table-cell">Categoria · centro</Th><Th className="hidden md:table-cell">Compet.</Th>
                <Th>Vencimento</Th><Th num>Valor</Th><Th>Status</Th><Th />
              </>}>
                {p.itens.map((c) => (
                  <tr key={c.id} className={linha}>
                    <Td>
                      <p className="font-medium text-titulo">{c.fornecedorNome}</p>
                      {(c.documento || c.fornecedorDocumento) && <p className="text-xs text-texto-3">{[c.documento, c.fornecedorDocumento].filter(Boolean).join(' · ')}</p>}
                    </Td>
                    <Td className="hidden md:table-cell">
                      {[c.categoria, c.centroCusto].filter(Boolean).join(' · ') || '—'}
                      {c.contaBancariaId && <p className="text-xs text-texto-3">{cad.nomeConta(c.contaBancariaId)}</p>}
                    </Td>
                    <Td className="hidden md:table-cell">{competencia(c.competencia)}</Td>
                    <Td>{data(c.vencimento)}</Td>
                    <Td num>{brl(c.valor)}</Td>
                    <Td>
                      <Status status={c.status} />
                      {c.aprovadoEm && <p className="mt-0.5 text-xs text-texto-3" title={c.aprovadoPor ?? ''}>por {curto(c.aprovadoPor)} em {dataHora(c.aprovadoEm)}</p>}
                      {c.dataPagamento && <p className="text-xs text-texto-3">pago {data(c.dataPagamento)} · {brl(c.valorPago)}</p>}
                    </Td>
                    <Td className="text-right whitespace-nowrap">
                      <div className="flex items-center justify-end gap-1.5">
                        {c.documentoId && tem('financeiro.documento.ver') && <BaixarDocumento id={c.documentoId} />}
                        {!c.documentoId && podeAnexar && c.status !== 'CANCELADA' && (
                          <EnviarDocumento categoria="COMPROVANTE" rotulo="Anexar" aoEnviar={(id) => void anexar(c, id)} />
                        )}
                        {c.status === 'PENDENTE' && tem('financeiro.contapagar.aprovar') && (
                          <Button size="sm" disabled={ocupado} onClick={() => aprovar(c)}><Check aria-hidden className="size-3.5" /> Aprovar</Button>
                        )}
                        {c.status === 'APROVADA' && tem('financeiro.contapagar.criar') && (
                          <Button size="sm" variant="outline" onClick={() => setPagando(c)}>Registrar pagamento</Button>
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

      {nova && <NovaConta aoFechar={() => setNova(false)} aoSalvar={() => { setNova(false); lista.recarregar() }} />}
      {pagando && <RegistrarPagamento conta={pagando} aoFechar={() => setPagando(null)} aoSalvar={() => { setPagando(null); lista.recarregar() }} />}
    </>
  )
}

function NovaConta({ aoFechar, aoSalvar }: { aoFechar: () => void; aoSalvar: () => void }) {
  const { tem } = usePermissao()
  const cad = useCadastros()
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ fornecedorNome: '', fornecedorDocumento: '', categoria: '', centroCusto: '', competencia: hoje().slice(0, 7),
    vencimento: '', valor: '', documento: '', observacoes: '', categoriaId: '', centroCustoId: '', contaBancariaId: '', documentoId: '' })
  const campo = (k: keyof typeof f) => ({ value: f[k], onChange: (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value }) })
  const categorias = cad.categorias.filter((c) => c.tipo === 'DESPESA' && c.ativa)
  const centros = cad.centros.filter((c) => c.ativo)
  const contas = cad.contas.filter((c) => c.ativa)
  const salvar = (e: FormEvent) => {
    e.preventDefault()
    const nulo = (s: string) => s || null
    void executar(() => api('/contas-pagar', {
      method: 'POST',
      body: {
        ...f, competencia: `${f.competencia}-01`, categoriaId: nulo(f.categoriaId), centroCustoId: nulo(f.centroCustoId),
        contaBancariaId: nulo(f.contaBancariaId), documentoId: nulo(f.documentoId),
      },
    }), aoSalvar)
  }
  return (
    <Modal open onClose={aoFechar} title="Nova conta a pagar"
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-conta" loading={ocupado}>Lançar</Button></>}>
      <form id="form-conta" onSubmit={salvar}>
        <FormSection number={1} title="Fornecedor">
          <FormField label="Nome" required colSpan={2}><Input required maxLength={150} {...campo('fornecedorNome')} /></FormField>
          <FormField label="CNPJ/CPF"><Input maxLength={18} {...campo('fornecedorDocumento')} /></FormField>
          <FormField label="Documento (NF)"><Input maxLength={60} {...campo('documento')} /></FormField>
        </FormSection>
        <FormSection number={2} title="Valores">
          <FormField label="Competência" required><Input type="month" required {...campo('competencia')} /></FormField>
          <FormField label="Vencimento" required><Input type="date" required {...campo('vencimento')} /></FormField>
          <FormField label="Valor (R$)" required><Input type="number" step="0.01" min="0.01" required {...campo('valor')} /></FormField>
          {categorias.length > 0 ? (
            <FormField label="Categoria" hint="Define a linha do DRE">
              <Select {...campo('categoriaId')}>
                <option value="">Sem categoria</option>
                {categorias.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
              </Select>
            </FormField>
          ) : (
            <FormField label="Categoria" hint="Texto livre até haver cadastro (PD-5)"><Input maxLength={60} {...campo('categoria')} /></FormField>
          )}
          {centros.length > 0 ? (
            <FormField label="Centro de custo">
              <Select {...campo('centroCustoId')}>
                <option value="">Sem centro de custo</option>
                {centros.map((c) => <option key={c.id} value={c.id}>{c.codigo} · {c.nome}</option>)}
              </Select>
            </FormField>
          ) : (
            <FormField label="Centro de custo"><Input maxLength={60} {...campo('centroCusto')} /></FormField>
          )}
          {contas.length > 0 && (
            <FormField label="Pagar pela conta">
              <Select {...campo('contaBancariaId')}>
                <option value="">A definir</option>
                {contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
              </Select>
            </FormField>
          )}
          <FormField label="Observações" colSpan={2}><Input maxLength={500} {...campo('observacoes')} /></FormField>
        </FormSection>
        {tem('financeiro.documento.enviar') && (
          <FormSection number={3} title="Arquivo (opcional)">
            <div className="flex items-center gap-2 md:col-span-2">
              <EnviarDocumento categoria="NOTA_FISCAL" rotulo={f.documentoId ? 'Trocar arquivo' : 'Anexar nota ou boleto'} aoEnviar={(id) => setF({ ...f, documentoId: id })} />
              {f.documentoId && <span className="text-xs text-texto-3">Arquivo #{curto(f.documentoId)} será vinculado à conta.</span>}
            </div>
          </FormSection>
        )}
      </form>
    </Modal>
  )
}

function RegistrarPagamento({ conta, aoFechar, aoSalvar }: { conta: ContaPagar; aoFechar: () => void; aoSalvar: () => void }) {
  const cad = useCadastros()
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({ valorPago: String(conta.valor), dataPagamento: hoje(), contaBancariaId: conta.contaBancariaId ?? '' })
  const contas = cad.contas.filter((c) => c.ativa)
  return (
    <Modal open onClose={aoFechar} size="md" title="Registrar pagamento" subtitle={`${conta.fornecedorNome} · vence ${data(conta.vencimento)}`}
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-pagamento" loading={ocupado}>Registrar</Button></>}>
      <form id="form-pagamento" onSubmit={(e) => {
        e.preventDefault()
        void executar(() => api(`/contas-pagar/${conta.id}/pagamento`, { method: 'POST', body: { ...f, contaBancariaId: f.contaBancariaId || null } }), aoSalvar)
      }}>
        <FormSection title="Pagamento">
          <FormField label="Valor pago (R$)" required><Input type="number" step="0.01" min="0.01" required value={f.valorPago} onChange={(e) => setF({ ...f, valorPago: e.target.value })} /></FormField>
          <FormField label="Data" required><Input type="date" max={hoje()} required value={f.dataPagamento} onChange={(e) => setF({ ...f, dataPagamento: e.target.value })} /></FormField>
          {contas.length > 0 && (
            <FormField label="Conta de saída" colSpan={2}>
              <Select value={f.contaBancariaId} onChange={(e) => setF({ ...f, contaBancariaId: e.target.value })}>
                <option value="">Não informar</option>
                {contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
              </Select>
            </FormField>
          )}
        </FormSection>
      </form>
    </Modal>
  )
}
