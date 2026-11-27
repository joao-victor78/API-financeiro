import { useState, type FormEvent } from 'react'
import { FileCheck2, Plus } from 'lucide-react'
import { Button, FilterBar, FormField, FormSection, Input, Modal, PageHeader, Pagination, Select } from '@/components/ui'
import { BaixarDocumento, BotoesExportar, Conteudo, EnviarDocumento, linha, Status, Tabela, Td, Th } from '@/components/modulo'
import { useEmpresas } from '@/lib/empresas'
import { useAcao, useDados, usePermissao } from '@/lib/hooks'
import { brl, competencia, curto, data, hoje, mesAtual } from '@/lib/formato'
import { api, type Pagina } from '@/plataforma/api'
import { CampoUuid } from './Cobrancas'

interface Nota {
  id: string
  tipo: string
  modelo: string
  numero: string
  serie: string | null
  chaveAcesso: string | null
  dataEmissao: string
  competencia: string
  valor: number
  valorImpostos: number
  empresaId: string | null
  participanteNome: string | null
  participanteDocumento: string | null
  cobrancaId: string | null
  contaPagarId: string | null
  documentoId: string | null
  status: string
  observacao: string | null
}

/**
 * Faturamento (§23) e notas recebidas (§35): o registro da nota emitida no sistema fiscal, ligado à
 * cobrança, e das notas de fornecedores, ligadas às contas a pagar, com o XML/PDF guardado.
 */
export function NotasFiscais({ detalhe }: { detalhe?: string }) {
  const { tem } = usePermissao()
  const registra = tem('financeiro.notafiscal.registrar')
  const [filtro, setFiltro] = useState({ tipo: '', competencia: mesAtual(), status: '', pagina: 0 })
  const query = { ...filtro, competencia: filtro.competencia ? `${filtro.competencia}-01` : '', tamanho: 30 }
  const lista = useDados<Pagina<Nota>>('/notas-fiscais', query)
  const empresas = useEmpresas(lista.dados?.itens.map((n) => n.empresaId) ?? [])
  const { executar, ocupado } = useAcao()
  const [nova, setNova] = useState(false)
  const emitidas = lista.dados?.itens.filter((n) => n.tipo === 'EMITIDA' && n.status === 'AUTORIZADA').reduce((s, n) => s + n.valor, 0) ?? 0

  const cancelar = (n: Nota) => {
    const motivo = prompt(`Motivo do cancelamento da nota ${n.numero} (já cancelada no sistema fiscal):`)
    if (motivo) void executar(() => api(`/notas-fiscais/${n.id}/cancelar`, { method: 'POST', body: { motivo } }), lista.recarregar)
  }

  return (
    <>
      <PageHeader title="Notas fiscais" subtitle={lista.dados ? `${lista.dados.total} nota(s) · ${brl(emitidas)} emitidos na página` : 'Emitidas e recebidas'}
        actions={<>
          <BotoesExportar caminho="/relatorios/notas-fiscais/exportar" query={{ competencia: query.competencia }} nome="notas-fiscais" />
          {registra && <Button size="sm" onClick={() => setNova(true)}><Plus aria-hidden className="size-3.5" /> Registrar nota</Button>}
        </>} />
      <FilterBar>
        <Select aria-label="Tipo" className="w-auto" value={filtro.tipo} onChange={(e) => setFiltro({ ...filtro, tipo: e.target.value, pagina: 0 })}>
          <option value="">Emitidas e recebidas</option><option value="EMITIDA">Emitidas</option><option value="RECEBIDA">Recebidas</option>
        </Select>
        <Input aria-label="Competência" type="month" className="w-auto" value={filtro.competencia} onChange={(e) => setFiltro({ ...filtro, competencia: e.target.value, pagina: 0 })} />
        <Select aria-label="Situação" className="w-auto" value={filtro.status} onChange={(e) => setFiltro({ ...filtro, status: e.target.value, pagina: 0 })}>
          <option value="">Todas</option><option value="AUTORIZADA">Autorizadas</option><option value="CANCELADA">Canceladas</option>
        </Select>
      </FilterBar>
      <div className="flex-1 p-5">
        <Conteudo carga={lista.carga} recarregar={lista.recarregar} vazio={(p) => p.itens.length === 0} icone={<FileCheck2 aria-hidden />} textoVazio="Nenhuma nota neste filtro">
          {(p) => (
            <>
              <Tabela atualizando={lista.atualizando} cabecalho={<>
                <Th>Nota</Th><Th>Participante</Th><Th>Emissão</Th><Th className="hidden md:table-cell">Compet.</Th><Th num>Valor</Th><Th num className="hidden md:table-cell">Impostos</Th><Th>Status</Th><Th />
              </>}>
                {p.itens.map((n) => (
                  <tr key={n.id} className={`${linha} ${detalhe === n.id ? 'bg-brand-50/60' : ''}`}>
                    <Td>
                      <p className="font-medium text-titulo">{n.modelo} nº {n.numero}{n.serie && ` · série ${n.serie}`}</p>
                      <p className="text-xs text-texto-3">{n.tipo === 'EMITIDA' ? 'Emitida' : 'Recebida'}{n.cobrancaId && ` · cobrança #${curto(n.cobrancaId)}`}{n.contaPagarId && ` · conta #${curto(n.contaPagarId)}`}</p>
                    </Td>
                    <Td>{n.participanteNome ?? (n.empresaId ? empresas.nome(n.empresaId) : '—')}{n.participanteDocumento && <p className="text-xs text-texto-3">{n.participanteDocumento}</p>}</Td>
                    <Td>{data(n.dataEmissao)}</Td>
                    <Td className="hidden md:table-cell">{competencia(n.competencia)}</Td>
                    <Td num>{brl(n.valor)}</Td>
                    <Td num className="hidden md:table-cell">{brl(n.valorImpostos)}</Td>
                    <Td><Status status={n.status} /></Td>
                    <Td className="text-right whitespace-nowrap">
                      <div className="flex justify-end gap-1.5">
                        {n.documentoId ? <BaixarDocumento id={n.documentoId} /> : registra && n.status === 'AUTORIZADA' && (
                          <EnviarDocumento categoria="NOTA_FISCAL" entidade="nota_fiscal" registroId={n.id} rotulo="XML/PDF"
                            aoEnviar={(doc) => void executar(() => api(`/notas-fiscais/${n.id}/documento`, { method: 'PUT', body: { documentoId: doc } }), lista.recarregar)} />
                        )}
                        {registra && n.status === 'AUTORIZADA' && <Button size="sm" variant="ghost" disabled={ocupado} onClick={() => cancelar(n)}>Cancelar</Button>}
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
      {nova && <NovaNota aoFechar={() => setNova(false)} aoSalvar={() => { setNova(false); lista.recarregar() }} />}
    </>
  )
}

export function NovaNota({ aoFechar, aoSalvar, cobrancaId, contaPagarId }: { aoFechar: () => void; aoSalvar: () => void; cobrancaId?: string; contaPagarId?: string }) {
  const { executar, ocupado } = useAcao()
  const [f, setF] = useState({
    tipo: contaPagarId ? 'RECEBIDA' : 'EMITIDA', modelo: 'NFSE', numero: '', serie: '', chaveAcesso: '', dataEmissao: hoje(), competencia: '',
    valor: '', valorImpostos: '', empresaId: '', participanteNome: '', participanteDocumento: '', cobrancaId: cobrancaId ?? '', contaPagarId: contaPagarId ?? '',
    documentoId: '', observacao: '',
  })
  const campo = (k: keyof typeof f) => ({ value: f[k], onChange: (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value }) })
  const salvar = (e: FormEvent) => {
    e.preventDefault()
    void executar(() => api('/notas-fiscais', { method: 'POST', body: { ...f, competencia: f.competencia ? `${f.competencia}-01` : '' } }), aoSalvar)
  }
  const emitida = f.tipo === 'EMITIDA'
  return (
    <Modal open onClose={aoFechar} title="Registrar nota fiscal" subtitle="A emissão é feita no sistema fiscal; aqui fica o registro, o vínculo e o arquivo."
      footer={<><Button variant="outline" onClick={aoFechar}>Cancelar</Button><Button type="submit" form="form-nota" loading={ocupado}>Registrar</Button></>}>
      <form id="form-nota" onSubmit={salvar}>
        <FormSection number={1} title="Nota">
          <FormField label="Tipo" required>
            <Select {...campo('tipo')} disabled={!!cobrancaId || !!contaPagarId}><option value="EMITIDA">Emitida (faturamento)</option><option value="RECEBIDA">Recebida (fornecedor)</option></Select>
          </FormField>
          <FormField label="Modelo" required>
            <Select {...campo('modelo')}><option value="NFSE">NFS-e</option><option value="NFE">NF-e</option><option value="NFCE">NFC-e</option><option value="OUTRO">Outro</option></Select>
          </FormField>
          <FormField label="Número" required><Input required maxLength={20} {...campo('numero')} /></FormField>
          <FormField label="Série"><Input maxLength={5} {...campo('serie')} /></FormField>
          {(f.modelo === 'NFE' || f.modelo === 'NFCE') && (
            <FormField label="Chave de acesso" required colSpan={2}><Input required pattern="\d{44}" maxLength={44} className="font-mono text-xs" {...campo('chaveAcesso')} /></FormField>
          )}
          <FormField label="Emissão" required><Input type="date" required {...campo('dataEmissao')} /></FormField>
          <FormField label="Competência" hint="Vazio: a da cobrança/conta ou da emissão"><Input type="month" {...campo('competencia')} /></FormField>
          <FormField label="Valor (R$)" required><Input type="number" step="0.01" min="0.01" required {...campo('valor')} /></FormField>
          <FormField label="Impostos retidos/destacados (R$)"><Input type="number" step="0.01" min="0" {...campo('valorImpostos')} /></FormField>
        </FormSection>
        <FormSection number={2} title={emitida ? 'Cliente e cobrança' : 'Fornecedor e conta a pagar'}>
          {emitida ? (
            <>
              <CampoUuid label="Cobrança faturada" valor={f.cobrancaId} onChange={(v) => setF({ ...f, cobrancaId: v })} />
              <CampoUuid label="Empresa (id do CRM)" valor={f.empresaId} onChange={(v) => setF({ ...f, empresaId: v })} />
            </>
          ) : (
            <>
              <CampoUuid label="Conta a pagar" valor={f.contaPagarId} onChange={(v) => setF({ ...f, contaPagarId: v })} />
              <FormField label="Fornecedor"><Input maxLength={150} {...campo('participanteNome')} /></FormField>
              <FormField label="CNPJ/CPF"><Input maxLength={18} {...campo('participanteDocumento')} /></FormField>
            </>
          )}
          <FormField label="Arquivo (XML/PDF)" colSpan={2} hint={f.documentoId ? 'Arquivo enviado.' : 'Opcional; pode anexar depois.'}>
            <div><EnviarDocumento categoria="NOTA_FISCAL" rotulo={f.documentoId ? 'Trocar arquivo' : 'Escolher arquivo'} aoEnviar={(id) => setF({ ...f, documentoId: id })} /></div>
          </FormField>
          <FormField label="Observação" colSpan={2}><Input maxLength={300} {...campo('observacao')} /></FormField>
        </FormSection>
      </form>
    </Modal>
  )
}
