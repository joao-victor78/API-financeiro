import { useDados, usePermissao } from '@/lib/hooks'

export interface Categoria { id: string; nome: string; tipo: 'RECEITA' | 'DESPESA'; grupoDre: string; contaContabil: string | null; ativa: boolean }
export interface CentroCusto { id: string; codigo: string; nome: string; ativo: boolean }
export interface ContaBancaria {
  id: string
  nome: string
  bancoCodigo: string | null
  agencia: string | null
  numero: string | null
  tipo: string
  saldoInicial: number
  dataSaldoInicial: string
  contaContabil: string | null
  padraoRecebimento: boolean
  ativa: boolean
}

/**
 * Cadastros para os selects das telas (§25). Sem financeiro.cadastro.ver, as listas vêm vazias e os
 * formulários continuam com os campos de texto livre de antes.
 */
export function useCadastros() {
  const { tem } = usePermissao()
  const pode = tem('financeiro.cadastro.ver')
  const categorias = useDados<Categoria[]>(pode ? '/cadastros/categorias' : null)
  const centros = useDados<CentroCusto[]>(pode ? '/cadastros/centros-custo' : null)
  const contas = useDados<ContaBancaria[]>(pode ? '/cadastros/contas-bancarias' : null)
  return {
    pode,
    categorias: categorias.dados ?? [],
    centros: centros.dados ?? [],
    contas: contas.dados ?? [],
    nomeCategoria: (id: string | null | undefined) => (id ? categorias.dados?.find((c) => c.id === id)?.nome ?? '—' : '—'),
    nomeConta: (id: string | null | undefined) => (id ? contas.dados?.find((c) => c.id === id)?.nome ?? '—' : '—'),
    recarregar: () => {
      categorias.recarregar()
      centros.recarregar()
      contas.recarregar()
    },
  }
}
