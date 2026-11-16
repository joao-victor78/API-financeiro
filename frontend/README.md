# Frontend do Módulo Financeiro

Interface web da API do módulo Financeiro, em Next.js 15 (App Router) e React 19. Foi feita para ser
incorporada ao sistema principal por **iframe**.

## Como rodar

```bash
# 1. API (na pasta raiz do projeto)
npm run keys && npm run dev          # http://localhost:3008/api/financeiro

# 2. Frontend (nesta pasta)
cp .env.example .env
npm install
npm run dev                          # http://localhost:3000
```

Em `npm run dev`, a tela **Entrar** oferece o login de desenvolvimento. Ele faz o papel do módulo de
Autenticação e emite um JWT RS256 com a chave criada por `npm run keys`. Use sempre o mesmo tenant
para reencontrar os dados. Em produção (`npm run build && npm start`) esse login e a simulação de Pix
ficam desativados.

## Telas

| Tela | O que faz |
|---|---|
| Painel | Saúde da API, totais da carteira, aging, status dos jobs do agendador e execução manual (admin) |
| Contas a receber | Lista com filtros, criação, recebimento total ou parcial, estorno e exclusão lógica |
| Contas a pagar | Lista, criação por fornecedor/centro de custo/categoria, pagamento e exclusão lógica |
| Cobranças | Rascunho, aprovação, cancelamento com motivo, QR Code Pix, copia e cola e **simulação do banco avisando o Pix pago** (dev) |
| Recorrências | Criação com itens, previsão dos próximos ciclos, execuções, gerar agora, pausar e reativar |
| Inadimplência | Títulos vencidos com multa e juros, faixas de atraso e promessas de pagamento |
| Conciliação | Upload do OFX, resultado da conciliação automática, transações pendentes com as sugestões do motor, conciliação manual e opção de ignorar |
| Eventos | Outbox de eventos (status, tentativas, payload) e assinaturas de webhook dos outros módulos (admin) |

Os botões de escrita só aparecem para quem tem `financeiro:escrita` ou `financeiro:admin` no token.

## Segurança e integração

- **O token não fica em localStorage.** O sistema principal envia o JWT ao iframe por `postMessage`
  (`{ type: 'financeiro:token', token }`), depois de receber `{ type: 'financeiro:pronto' }`. Somente
  mensagens da origem `NEXT_PUBLIC_ORIGEM_SISTEMA` são aceitas.
- A rota `/sessao` valida o token na própria API e o grava em um cookie **httpOnly + SameSite=Strict**
  (`financeiro_token`), que o JavaScript da página não consegue ler.
- O navegador fala só com o servidor do frontend. As chamadas a `/api/financeiro/*` são repassadas
  (rewrite) para a API, levando o cookie, e o `JwtGuard` da API o aceita como alternativa ao
  cabeçalho `Bearer`. O tenant continua vindo exclusivamente do token.
- O cabeçalho `Content-Security-Policy: frame-ancestors` restringe quem pode incorporar a tela
  (`FRAME_ANCESTORS`).

Exemplo no sistema principal:

```js
const iframe = document.querySelector('#financeiro');
window.addEventListener('message', (e) => {
  if (e.origin === 'https://financeiro.exemplo.com.br' && e.data?.type === 'financeiro:pronto') {
    iframe.contentWindow.postMessage({ type: 'financeiro:token', token: jwtDoUsuario }, e.origin);
  }
});
```

## Variáveis (`.env`)

| Variável | Padrão | Uso |
|---|---|---|
| `FINANCEIRO_API_URL` | `http://localhost:3008` | Endereço da API |
| `FRAME_ANCESTORS` | `'self'` | Quem pode incorporar o frontend em iframe |
| `NEXT_PUBLIC_ORIGEM_SISTEMA` | (própria origem) | Origem aceita no `postMessage` do token |
| `DEV_CHAVE_PRIVADA` | `../keys/auth-private.pem` | Login de desenvolvimento (só em `npm run dev`) |
| `DEV_PIX_WEBHOOK_SECRET` | `troque-este-segredo` | Mesmo valor de `PIX_WEBHOOK_SECRET` da API, para simular o Pix pago |
