# Módulo Financeiro — Grupo 8

API e front do módulo Financeiro do Projeto Integrador 2026, conforme a **EOR v1.0 (Fase 2)** e o
**Contrato de Integração** da plataforma (`infra-integrador-2026`). Código do módulo: `financeiro`.

| | |
|---|---|
| API | Java 21 · Spring Boot 3.5 · porta **8085** · rotas em `/api/financeiro` e `/public/financeiro` |
| Front | React + Vite + Tailwind v4 (Design System da plataforma) · porta **3005** · servido em `/modulos/financeiro/` e embutido na casca por iframe |
| Banco | PostgreSQL, schema `financeiro`; migrations com `own_financeiro`, aplicação com `usr_financeiro` |
| Mensageria | RabbitMQ: publica em `financeiro.eventos`, pede timeline/notificação/e-mail em `identity.entrada` |
| Contratos | `infra-integrador-2026/contratos/financeiro.yaml` (OpenAPI 0.4.0), `financeiro.asyncapi.yaml`, `financeiro.views.md` |

## O que está implementado

| RF | Requisito | Onde |
|---|---|---|
| RF-01 | Contas a receber: lançar, consultar, editar (RN-05), cancelar (exclusão lógica) | `cobrancas/` |
| RF-02 | Contas a pagar: lançar, aprovar (permissão distinta), registrar pagamento só se aprovada | `contaspagar/` |
| RF-03 | Recorrência: valor sempre dos itens, uma cobrança por competência, aditivo só para frente, job diário, consumidor de `contratos.contrato.assinado`/`cancelado` | `recorrencias/`, `eventos/ProcessadorContratos` |
| RF-04 | PIX (BR Code + QR), boleto e link de cartão por um adaptador de provedor (simulado) | `pagamentos/`, `pagamentos/provedor/` |
| RF-05 | Webhook público com assinatura HMAC, limite por IP (429) e idempotência por evento | `webhooks/` |
| RF-06 | Régua configurável, job diário (atraso + disparos), um disparo por cobrança e etapa | `regua/` |
| RF-07 | Inadimplência com aging, histórico da empresa e promessas de pagamento | `inadimplencia/` |
| RF-08 | MRR decomposto (Novo, Expansion, Contraction, Churned, Net New), ARR, churn, home | `indicadores/` |
| RF-09 | Busca global (até 5, filtrada por permissão) | `busca/` |
| RF-10 | Status financeiro da empresa, individual e em lote (até 100), sem valores | `integracoes/` |
| RF-11 | Exportação CSV, XLSX e PDF | `relatorios/` |
| RF-12 | Baixa manual (transferência), total ou parcial | `cobrancas/CobrancaService#baixaManual` |
| RF-13 | Auditoria só de inserção (gatilho no banco + `REVOKE`) e tela de consulta | `auditoria/`, migration |

### V2: o que faltava em relação ao Prompt Mestre

Tudo é aditivo: a migration `V2__financeiro_completo.sql` só cria tabelas e colunas opcionais, as rotas e
respostas da Fase 2 continuam iguais (campos novos só no fim), e os 56 testes da Fase 2 seguem passando.

| § | Requisito | Onde (API) | Tela |
|---|---|---|---|
| 23, 25 | Plano de categorias ligado ao DRE, centros de custo, contas bancárias (com plano padrão de MSP) | `cadastros/` | Cadastros |
| 23 | DRE gerencial por competência ou caixa, com margem bruta e líquida | `gerencial/DreService` | DRE |
| 23, 74 | Fluxo de caixa realizado e previsto (dia, semana, mês), saldo por conta | `gerencial/FluxoCaixaService` | Fluxo de caixa |
| 24 | Status `AGENDADO` (agendar/desfazer), juros e multa automáticos por tenant (desligados por padrão) | `cobrancas/EncargosService`, `configuracoes/` | Contas a receber, Configurações |
| 26 | Composição da cobrança por itens: mensalidade, excedente de consumo e adicionais | `cobrancas/CobrancaItem`, `recorrencias/RecorrenciaService#gerarCiclo` | Contas a receber › Composição |
| 26 | Consumo variável com franquia, alerta de uso, envio por integração (RMM) | `recorrencias/RecorrenciaExtrasService`, `POST /api/financeiro/consumos` | Recorrência › Consumo |
| 26 | Adicionais por competência e reajuste anual por índice (IPCA, IGP-M, fixo) | idem | Recorrência › Adicionais, Reajustes |
| 27, 28 | Cartão salvo (token no provedor) e cobrança automática no ciclo; estorno total ou parcial | `pagamentos/`, `recorrencias/` | Recorrência › Cartão, Contas a receber › Pagamentos |
| 28 | Adaptador de provedor real **Asaas** (`PROVEDOR_PAGAMENTO=asaas`) e webhook `/public/financeiro/webhooks/asaas` | `pagamentos/provedor/ProvedorAsaas` | — |
| 29 | Conciliação bancária: importação OFX, sugestões, conciliação manual e automática, ignorar, desfazer | `conciliacao/` | Conciliação |
| 34 a 37 | Fechamento contábil mensal (checklist com diagnóstico, status, trava da competência), portal da contabilidade, lançamentos com débito/crédito, exportação CSV/XLSX/PDF/OFX/ZIP | `contabilidade/` | Contabilidade |
| 35 | Notas fiscais emitidas e recebidas, ligadas à cobrança ou à conta a pagar, com XML/PDF | `fiscal/` | Notas fiscais |
| 69 | Custo e margem por item da recorrência, visíveis só com `financeiro.margem.ver` | `recorrencias/RecorrenciaDtos` | Recorrência |
| 71 | Comissões de vendedor, SDR e parceiro: regras (fixa, %, 1ª mensalidade, MRR, margem, recorrente por N meses), apuração no pagamento, cancelamento no estorno, fechamento para contas a pagar | `comissoes/` | Comissões |
| 74, 111 | Dashboard com resultado do mês, saldo bancário, previsão de 30 dias, conciliação pendente, comissões e margem | `indicadores/` | Dashboard |
| 78 | Exportação de contas a pagar, inadimplência, MRR, fluxo de caixa, DRE, comissões, conciliação e notas | `relatorios/RelatoriosGerenciaisController` | Botões CSV/XLSX/PDF |
| 85 | Segredos no banco cifrados com AES-256-GCM | `seguranca/Cifra` | — |
| 91 | Webhooks de saída assinados (HMAC-SHA256), fila com novas tentativas, reenvio, bloqueio de SSRF | `webhooks/saida/` | Webhooks |
| 95, 96 | Biblioteca de documentos em disco local ou S3 (AWS SDK v2; MinIO/Wasabi por endpoint), anexos em contas a pagar | `documentos/` | Contas a pagar |
| 98 | Actuator (health, métricas, Prometheus) numa porta interna separada | `application.yml` (`management`) | — |
| 99 | Backup do schema com retenção e teste de restauração | `scripts/backup.sh`, `scripts/backup.ps1`, [docs/backup.md](docs/backup.md) | — |

Permissões novas, perfis e rotas do submenu: [docs/permissoes-v2.md](docs/permissoes-v2.md).

> **Asaas:** o adaptador segue a API v3 documentada, mas **ainda não foi validado no sandbox**. Antes de
> usar em produção, teste PIX, boleto, cartão salvo, estorno e o webhook com uma conta de sandbox.
> Sem `PROVEDOR_PAGAMENTO`, continua o provedor simulado.

Fora do escopo deste módulo: os demais módulos da plataforma (CRM, contratos, service desk etc.) e a
emissão da nota na prefeitura/SEFAZ (o Financeiro registra a nota emitida pelo sistema fiscal).
Conectores diretos para Alterdata, Domínio e similares aparecem como "previstos": o envio hoje é pelo
pacote em arquivo (`ConectorArquivo`), que esses sistemas importam.

## Rodar na máquina

Pré-requisitos: JDK 21, Maven 3.9+, Node 22 e PostgreSQL (ou `docker compose up -d`, que sobe
PostgreSQL com os bancos `erp`/`erp_test` e um RabbitMQ).

```bash
cp .env.example .env
mvn -q compile exec:java@chaves     # par RSA de desenvolvimento em keys/ (faz o papel do identity)
mvn spring-boot:run                 # aplica a migration e sobe em http://localhost:8085
cd frontend && cp .env.example .env && npm install && npm run dev   # http://localhost:3005/modulos/financeiro/
```

Sem o identity no ar, gere um token com `mvn -q compile exec:java@token -Dexec.args="<tenant> ADMINISTRADOR"` e cole na
tela de entrada do front (perfis: ADMINISTRADOR, GESTOR, FINANCEIRO, CONTABILIDADE, VENDEDOR, TECNICO). O token é
assinado com `keys/auth-private.pem`, que faz o papel do identity.
Com o identity no ar, deixe `JWT_PUBLIC_KEY_PATH` vazio: a API valida pelo JWKS.

Sem RabbitMQ, a API funciona normalmente: os eventos ficam `PENDENTE` no outbox e são publicados quando
o broker voltar (três tentativas, com espera de 1, 5 e 25 minutos).

> **Banco criado pela versão anterior (v0.1):** a estrutura mudou por completo para seguir o DER. Apague
> o schema antigo antes de subir: `psql -U financeiro -d erp -c "DROP SCHEMA financeiro CASCADE"`.

Variáveis novas da V2 (todas com padrão que mantém o comportamento anterior): `ARMAZENAMENTO_*`, `S3_*`,
`FINANCEIRO_CHAVE_CRIPTOGRAFIA`, `PROVEDOR_PAGAMENTO`, `ASAAS_*`, `WEBHOOKS_SAIDA_*`, `JOB_ENCARGOS_CRON` e
`MANAGEMENT_PORT`. Estão comentadas no `.env.example`. Em produção, `FINANCEIRO_CHAVE_CRIPTOGRAFIA` é
obrigatória (`openssl rand -base64 32`); sem ela a API usa uma chave de desenvolvimento e avisa no log.

Saúde e métricas: `http://localhost:9085/actuator/health` e `/actuator/prometheus` (porta
`MANAGEMENT_PORT`, só para a rede interna).

## Rodar na plataforma

No `infra-integrador-2026`: `docker compose --profile plataforma --profile modulos up -d financeiro financeiro-front`.
As imagens `ghcr.io/joao-victor78/financeiro` e `financeiro-front` são publicadas a cada push na `main`
(`.github/workflows/ci.yml`).

## Testes

```bash
mvn verify    # 74 testes: cada critério de aceite da EOR (DADO QUE / QUANDO / ENTÃO), 401, 403, isolamento entre tenants, e os fluxos da V2
```

O JaCoCo do `mvn verify` precisa do JDK 21 (o da CI). Em JDK mais novo, rode `mvn test -Djacoco.skip=true`.

Os testes usam PostgreSQL real (`TEST_DB_URL`, padrão `jdbc:postgresql://localhost:5432/erp_test`) e
**apagam o schema `financeiro` desse banco** — por segurança, só aceitam banco terminado em `_test`.
RabbitMQ e CRM são simulados; nenhum outro módulo precisa estar no ar (Contrato §14.5).

## Decisões tomadas onde a documentação deixa em aberto

| Ponto | Decisão |
|---|---|
| PD-8 juros e multa | Campos editáveis na cobrança. V2: cálculo automático opcional por tenant (multa uma vez + juros simples pró-rata dia, após a carência), desligado por padrão; valores digitados à mão nunca são recalculados |
| RN-63 permissão da baixa manual | Reaproveita `financeiro.contareceber.editar` |
| RF-13 permissão da auditoria | Nova: `financeiro.auditoria.ver` (ADMINISTRADOR) |
| Vencimento da cobrança recorrente | Dia de geração + `diasParaVencimento` (padrão 10) |
| Destinatário do e-mail da régua | Primeiro contato com e-mail da empresa no CRM, com token de serviço; sem ele, o disparo fica `SEM_DESTINATARIO` e só vai para a timeline |
| Categoria e centro de custo | Fase 2: texto livre (PD-5). V2: cadastros opcionais; com eles, o formulário mostra a lista e o texto é preenchido com o nome |
| Comissão com mais de uma regra | Por beneficiário vale a mais específica (beneficiário > serviço > fabricante), empate pela menor prioridade. Apurada na confirmação do pagamento; erro na comissão nunca desfaz o pagamento |
| Trava da competência | Em `ENVIADO_PARA_CONTABILIDADE`, `EM_ANALISE` e `FINALIZADO`, cobranças, contas a pagar e conciliações do mês não mudam. Pendência registrada pela contabilidade destrava |
| Excedente de consumo | Permitido = franquia (ou a quantidade do item, sem franquia). O excedente e os adicionais entram na próxima cobrança gerada e ficam fora do MRR |
| Assinatura dos webhooks de saída | `X-Financeiro-Assinatura: t=<epoch>,v1=<HMAC-SHA256 de "t.corpo">`, no estilo Stripe; só HTTPS e endereço público |
| Layout dos sistemas contábeis | Não inventamos layouts proprietários: pacote ZIP com CSV, XLSX, PDF e OFX, mais os comprovantes |
| Tabela de idempotência | `eventos_processados`, como manda o Contrato §9.7 (o DER chama de `eventos_consumidos`) |
