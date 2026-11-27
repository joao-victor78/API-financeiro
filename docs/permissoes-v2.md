# Permissões e telas da V2

As permissões abaixo são novas. Para valerem na plataforma, precisam ser cadastradas no catálogo do
identity (`permissoes/financeiro.yaml` do `infra-integrador-2026`) e ligadas aos perfis. As telas novas
precisam entrar no submenu do registro do módulo (`modulos/financeiro.json`). Até lá, só o token de
desenvolvimento (`mvn -q compile exec:java@token`) as carrega.

Cada rota exige **uma** permissão (`hasAuthority`), como nas rotas da Fase 2.

## Permissões

| Permissão | O que libera |
|---|---|
| `financeiro.cadastro.ver` | Ver categorias, centros de custo e contas bancárias (e usá-los nos formulários) |
| `financeiro.cadastro.administrar` | Criar, editar e excluir os cadastros; criar o plano de categorias padrão |
| `financeiro.documento.ver` | Baixar comprovantes, notas e extratos guardados |
| `financeiro.documento.enviar` | Enviar arquivos para a biblioteca de documentos |
| `financeiro.conciliacao.ver` | Ver extratos importados, transações e o resumo da conciliação |
| `financeiro.conciliacao.executar` | Importar OFX, conciliar, ignorar, desfazer e rodar a conciliação automática |
| `financeiro.fluxocaixa.ver` | Fluxo de caixa realizado e previsto |
| `financeiro.dre.ver` | DRE gerencial |
| `financeiro.margem.ver` | Custo dos itens da recorrência, margem por item e margem bruta no dashboard (§3: técnico não vê) |
| `financeiro.comissao.ver` | Ver comissões. Sem `administrar`, só as próprias (filtro pelo usuário do token) |
| `financeiro.comissao.administrar` | Regras de comissão, fechamento da competência e cancelamento |
| `financeiro.notafiscal.ver` | Ver notas fiscais emitidas e recebidas |
| `financeiro.notafiscal.registrar` | Registrar e cancelar notas |
| `financeiro.contabilidade.ver` | Portal da contabilidade, lançamentos, exportações e o fechamento |
| `financeiro.contabilidade.fechar` | Lado do financeiro no fechamento: iniciar, checklist, pronto, enviar |
| `financeiro.contabilidade.analisar` | Lado da contabilidade: em análise, pendência, finalizar |
| `financeiro.webhook.administrar` | Central de webhooks de saída |
| `financeiro.configuracao.administrar` | Juros, multa, carência, tolerância da conciliação e alerta de consumo |
| `financeiro.pagamento.estornar` | Estornar pagamento confirmado (total ou parcial) |
| `financeiro.consumo.registrar` | Envio de consumo por integração (`POST /api/financeiro/consumos`, token de serviço) |

## Perfis de desenvolvimento (`EmitirToken`)

| Perfil | Permissões da V2 |
|---|---|
| ADMINISTRADOR | Todas |
| GESTOR | Todas as de leitura + `margem.ver` |
| FINANCEIRO | Leitura + `cadastro.administrar`, `documento.enviar`, `conciliacao.executar`, `margem.ver`, `comissao.administrar`, `notafiscal.registrar`, `contabilidade.fechar`, `pagamento.estornar` |
| CONTABILIDADE | `contabilidade.ver`, `contabilidade.analisar`, `documento.ver`, `notafiscal.ver`, `dre.ver`, `fluxocaixa.ver`, `conciliacao.ver`, `cadastro.ver` (sem contas a receber/pagar) |
| VENDEDOR | `comissao.ver` (só as próprias) |
| TECNICO | Nenhuma (não vê margem) |

"Leitura" = `cadastro.ver`, `documento.ver`, `conciliacao.ver`, `fluxocaixa.ver`, `dre.ver`, `comissao.ver`,
`notafiscal.ver`, `contabilidade.ver`.

## Submenu (`modulos/financeiro.json`)

Rotas novas, na ordem do menu do front (`frontend/src/App.tsx`). As da Fase 2 continuam iguais.

| Rota | Nome | Permissão |
|---|---|---|
| `/fluxo-caixa` | Fluxo de caixa | `financeiro.fluxocaixa.ver` |
| `/dre` | DRE | `financeiro.dre.ver` |
| `/conciliacao` | Conciliação | `financeiro.conciliacao.ver` |
| `/comissoes` | Comissões | `financeiro.comissao.ver` |
| `/notas-fiscais` | Notas fiscais | `financeiro.notafiscal.ver` |
| `/contabilidade` | Contabilidade | `financeiro.contabilidade.ver` |
| `/cadastros` | Cadastros | `financeiro.cadastro.ver` |
| `/webhooks` | Webhooks | `financeiro.webhook.administrar` |
| `/configuracoes` | Configurações | `financeiro.configuracao.administrar` |

A busca global também devolve notas fiscais, com rota `/notas-fiscais/{id}`.
