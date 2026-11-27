-- V2: o que faltava no módulo Financeiro em relação ao Prompt Mestre (§23 a §37, §41, §69, §71, §74, §91, §95, §96).
-- Só acrescenta: nenhuma tabela ou coluna da V1 muda de tipo, de nome ou de regra. Toda coluna nova em
-- tabela existente é opcional (NULL) ou tem padrão que reproduz o comportamento anterior.
-- Toda tabela de negócio nova tem as sete colunas da §7.2 (id, tenant_id, created_at, updated_at,
-- deleted_at, created_by, updated_by).

-- ================================================================== cadastros (§25, §33)

-- Plano de categorias: tipo (receita/despesa) e grupo do DRE gerencial (§23). conta_contabil é o código
-- do plano de contas do escritório, usado na exportação contábil (§37).
CREATE TABLE financeiro.categorias_financeiras (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  nome            varchar(80) NOT NULL,
  tipo            varchar(7) NOT NULL CHECK (tipo IN ('RECEITA','DESPESA')),
  grupo_dre       varchar(30) NOT NULL CHECK (grupo_dre IN
                    ('RECEITA_BRUTA','DEDUCOES','CUSTOS','DESPESAS_OPERACIONAIS','DESPESAS_ADMINISTRATIVAS',
                     'DESPESAS_COMERCIAIS','RECEITAS_FINANCEIRAS','DESPESAS_FINANCEIRAS','IMPOSTOS_SOBRE_LUCRO','OUTRAS')),
  conta_contabil  varchar(30),
  ativa           boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,
  deleted_at      timestamptz,
  created_by      uuid,
  updated_by      uuid
);
CREATE UNIQUE INDEX ux_categorias_nome ON financeiro.categorias_financeiras (tenant_id, tipo, lower(nome)) WHERE deleted_at IS NULL;

CREATE TABLE financeiro.centros_custo (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  codigo          varchar(20) NOT NULL,
  nome            varchar(80) NOT NULL,
  ativo           boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,
  deleted_at      timestamptz,
  created_by      uuid,
  updated_by      uuid
);
CREATE UNIQUE INDEX ux_centros_custo_codigo ON financeiro.centros_custo (tenant_id, lower(codigo)) WHERE deleted_at IS NULL;

CREATE TABLE financeiro.contas_bancarias (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  nome                varchar(80) NOT NULL,
  banco_codigo        varchar(5),
  agencia             varchar(10),
  numero              varchar(20),
  tipo                varchar(10) NOT NULL CHECK (tipo IN ('CORRENTE','POUPANCA','PAGAMENTO','CAIXA')),
  saldo_inicial       numeric(15,2) NOT NULL DEFAULT 0,
  data_saldo_inicial  date NOT NULL,
  conta_contabil      varchar(30),
  padrao_recebimento  boolean NOT NULL DEFAULT false,
  ativa               boolean NOT NULL DEFAULT true,
  created_at          timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL,
  deleted_at          timestamptz,
  created_by          uuid,
  updated_by          uuid
);
CREATE INDEX idx_contas_bancarias_tenant ON financeiro.contas_bancarias (tenant_id) WHERE deleted_at IS NULL;

-- ================================================================== documentos (§95, §96)

-- O arquivo fica no armazenamento compatível com S3 (ou disco, em desenvolvimento); o banco guarda só
-- a chave, o tamanho e o SHA-256 (nunca o conteúdo).
CREATE TABLE financeiro.documentos (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  nome_arquivo          varchar(200) NOT NULL,
  tipo_conteudo         varchar(100) NOT NULL,
  tamanho_bytes         bigint NOT NULL CHECK (tamanho_bytes > 0),
  sha256                char(64) NOT NULL,
  chave_armazenamento   varchar(300) NOT NULL,
  categoria             varchar(20) NOT NULL CHECK (categoria IN
                          ('COMPROVANTE','NOTA_FISCAL','BOLETO','EXTRATO','CONTRATO','RECIBO','RELATORIO','OUTRO')),
  entidade              varchar(40),
  registro_id           uuid,
  descricao             varchar(300),
  created_at            timestamptz NOT NULL,
  updated_at            timestamptz NOT NULL,
  deleted_at            timestamptz,
  created_by            uuid,
  updated_by            uuid
);
CREATE INDEX idx_documentos_registro ON financeiro.documentos (tenant_id, entidade, registro_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_documentos_data ON financeiro.documentos (tenant_id, created_at DESC) WHERE deleted_at IS NULL;

-- ================================================================== colunas novas (opcionais)

ALTER TABLE financeiro.contas_pagar
  ADD COLUMN categoria_id      uuid REFERENCES financeiro.categorias_financeiras (id),
  ADD COLUMN centro_custo_id   uuid REFERENCES financeiro.centros_custo (id),
  ADD COLUMN conta_bancaria_id uuid REFERENCES financeiro.contas_bancarias (id),
  ADD COLUMN documento_id      uuid REFERENCES financeiro.documentos (id);

-- encargos_manuais: juros/multa digitados à mão não são recalculados pelo job (§24).
-- agendada_para: data do débito agendado (status AGENDADO, §24). valor_variavel: parte do valor que é
-- consumo excedente ou adicional (§26) — não entra no MRR.
ALTER TABLE financeiro.cobrancas
  ADD COLUMN categoria_id      uuid REFERENCES financeiro.categorias_financeiras (id),
  ADD COLUMN centro_custo_id   uuid REFERENCES financeiro.centros_custo (id),
  ADD COLUMN vendedor_id       uuid,
  ADD COLUMN sdr_id            uuid,
  ADD COLUMN parceiro_id       uuid,
  ADD COLUMN valor_variavel    numeric(15,2) NOT NULL DEFAULT 0 CHECK (valor_variavel >= 0),
  ADD COLUMN encargos_manuais  boolean NOT NULL DEFAULT false,
  ADD COLUMN encargos_calculados_em date,
  ADD COLUMN agendada_para     date;

ALTER TABLE financeiro.pagamentos
  ADD COLUMN conta_bancaria_id uuid REFERENCES financeiro.contas_bancarias (id),
  ADD COLUMN estornado_em      timestamptz,
  ADD COLUMN valor_estornado   numeric(15,2) CHECK (valor_estornado > 0),
  ADD COLUMN motivo_estorno    varchar(300);

-- Cartão recorrente (§27): só a referência do cartão tokenizado no provedor, nunca o número (RN-22).
ALTER TABLE financeiro.recorrencias
  ADD COLUMN vendedor_id         uuid,
  ADD COLUMN sdr_id              uuid,
  ADD COLUMN parceiro_id         uuid,
  ADD COLUMN categoria_id        uuid REFERENCES financeiro.categorias_financeiras (id),
  ADD COLUMN centro_custo_id     uuid REFERENCES financeiro.centros_custo (id),
  ADD COLUMN cobranca_automatica boolean NOT NULL DEFAULT false,
  ADD COLUMN cartao_token_ref    varchar(120),
  ADD COLUMN cartao_final        varchar(4),
  ADD COLUMN cartao_bandeira     varchar(20),
  ADD COLUMN indice_reajuste     varchar(16) CHECK (indice_reajuste IN ('IPCA','IGPM','PERCENTUAL_FIXO','OUTRO')),
  ADD COLUMN mes_reajuste        smallint CHECK (mes_reajuste BETWEEN 1 AND 12);

-- valor_excedente: preço por unidade acima da franquia (nulo = o próprio valor unitário).
-- custo_unitario e fabricante alimentam margem (§17, §39) e comissão sobre margem/fabricante (§71).
ALTER TABLE financeiro.recorrencia_itens
  ADD COLUMN valor_excedente numeric(15,2) CHECK (valor_excedente >= 0),
  ADD COLUMN custo_unitario  numeric(15,2) CHECK (custo_unitario >= 0),
  ADD COLUMN fabricante      varchar(80);

-- ================================================================== composição da cobrança (§26)

CREATE TABLE financeiro.cobranca_itens (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  cobranca_id     uuid NOT NULL REFERENCES financeiro.cobrancas (id),
  tipo            varchar(10) NOT NULL CHECK (tipo IN ('FIXO','EXCEDENTE','ADICIONAL')),
  servico_id      uuid,
  fabricante      varchar(80),
  descricao       varchar(250) NOT NULL,
  quantidade      numeric(14,3) NOT NULL CHECK (quantidade > 0),
  unidade         varchar(20),
  valor_unitario  numeric(15,2) NOT NULL CHECK (valor_unitario >= 0),
  total           numeric(15,2) NOT NULL CHECK (total >= 0),
  custo_total     numeric(15,2),
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,
  deleted_at      timestamptz,
  created_by      uuid,
  updated_by      uuid
);
CREATE INDEX idx_cobranca_itens_cobranca ON financeiro.cobranca_itens (tenant_id, cobranca_id);

-- ================================================================== consumo, adicionais, reajuste (§26, §41, §69)

CREATE TABLE financeiro.recorrencia_consumos (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  recorrencia_id  uuid NOT NULL REFERENCES financeiro.recorrencias (id),
  servico_id      uuid NOT NULL,
  competencia     char(7) NOT NULL,
  quantidade      numeric(14,3) NOT NULL CHECK (quantidade >= 0),
  origem          varchar(10) NOT NULL DEFAULT 'MANUAL' CHECK (origem IN ('MANUAL','INTEGRACAO')),
  observacao      varchar(300),
  cobranca_id     uuid REFERENCES financeiro.cobrancas (id),
  alertado_em     timestamptz,
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,
  deleted_at      timestamptz,
  created_by      uuid,
  updated_by      uuid
);
CREATE UNIQUE INDEX ux_consumo_competencia ON financeiro.recorrencia_consumos (recorrencia_id, servico_id, competencia)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_consumos_tenant ON financeiro.recorrencia_consumos (tenant_id, competencia);

CREATE TABLE financeiro.recorrencia_adicionais (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  recorrencia_id  uuid NOT NULL REFERENCES financeiro.recorrencias (id),
  competencia     char(7) NOT NULL,
  descricao       varchar(200) NOT NULL,
  valor           numeric(15,2) NOT NULL CHECK (valor > 0),
  cobranca_id     uuid REFERENCES financeiro.cobrancas (id),
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,
  deleted_at      timestamptz,
  created_by      uuid,
  updated_by      uuid
);
CREATE INDEX idx_adicionais_regra ON financeiro.recorrencia_adicionais (tenant_id, recorrencia_id, competencia) WHERE deleted_at IS NULL;

CREATE TABLE financeiro.reajustes (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL,
  recorrencia_id         uuid NOT NULL REFERENCES financeiro.recorrencias (id),
  indice                 varchar(16) NOT NULL CHECK (indice IN ('IPCA','IGPM','PERCENTUAL_FIXO','OUTRO')),
  percentual             numeric(7,4) NOT NULL CHECK (percentual > -100 AND percentual <= 100),
  competencia_aplicacao  char(7) NOT NULL,
  valor_anterior         numeric(15,2) NOT NULL,
  valor_novo             numeric(15,2) NOT NULL,
  observacao             varchar(300),
  created_at             timestamptz NOT NULL,
  updated_at             timestamptz NOT NULL,
  deleted_at             timestamptz,
  created_by             uuid,
  updated_by             uuid
);
CREATE INDEX idx_reajustes_regra ON financeiro.reajustes (tenant_id, recorrencia_id, created_at DESC);

-- ================================================================== configuração por tenant (§24)

-- Uma linha por tenant. Encargos automáticos nascem desligados: sem configuração, juros e multa
-- continuam editáveis à mão, como antes (PD-8).
CREATE TABLE financeiro.configuracoes (
  tenant_id                   uuid PRIMARY KEY,
  encargos_automaticos        boolean NOT NULL DEFAULT false,
  multa_percentual            numeric(5,2) NOT NULL DEFAULT 2.00 CHECK (multa_percentual BETWEEN 0 AND 20),
  juros_mensal_percentual     numeric(5,2) NOT NULL DEFAULT 1.00 CHECK (juros_mensal_percentual BETWEEN 0 AND 20),
  dias_carencia               smallint NOT NULL DEFAULT 0 CHECK (dias_carencia BETWEEN 0 AND 30),
  conciliacao_tolerancia_dias smallint NOT NULL DEFAULT 3 CHECK (conciliacao_tolerancia_dias BETWEEN 0 AND 15),
  alerta_consumo_percentual   numeric(6,2) NOT NULL DEFAULT 100.00 CHECK (alerta_consumo_percentual BETWEEN 50 AND 500),
  created_at                  timestamptz NOT NULL,
  updated_at                  timestamptz NOT NULL,
  updated_by                  uuid
);

-- ================================================================== conciliação (§32)

CREATE TABLE financeiro.extratos_importados (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  conta_bancaria_id  uuid NOT NULL REFERENCES financeiro.contas_bancarias (id),
  nome_arquivo       varchar(200) NOT NULL,
  sha256             char(64) NOT NULL,
  documento_id       uuid REFERENCES financeiro.documentos (id),
  periodo_inicio     date,
  periodo_fim        date,
  saldo_final        numeric(15,2),
  lancamentos        int NOT NULL DEFAULT 0,
  novos              int NOT NULL DEFAULT 0,
  conciliados_auto   int NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  deleted_at         timestamptz,
  created_by         uuid,
  updated_by         uuid
);
CREATE UNIQUE INDEX ux_extrato_arquivo ON financeiro.extratos_importados (tenant_id, conta_bancaria_id, sha256) WHERE deleted_at IS NULL;

CREATE TABLE financeiro.transacoes_bancarias (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  conta_bancaria_id  uuid NOT NULL REFERENCES financeiro.contas_bancarias (id),
  extrato_id         uuid REFERENCES financeiro.extratos_importados (id),
  identificador      varchar(120) NOT NULL,                  -- FITID do OFX (ou gerado)
  data               date NOT NULL,
  valor              numeric(15,2) NOT NULL CHECK (valor <> 0), -- positivo = crédito, negativo = débito
  descricao          varchar(300),
  documento          varchar(60),
  classificacao      varchar(20) CHECK (classificacao IN
                       ('PAGAMENTO_RECEBIDO','PAGAMENTO_EFETUADO','TRANSFERENCIA','TARIFA','JUROS','ESTORNO','OUTRO')),
  status             varchar(10) NOT NULL DEFAULT 'PENDENTE' CHECK (status IN ('PENDENTE','CONCILIADA','IGNORADA')),
  modo               varchar(10) CHECK (modo IN ('AUTOMATICA','MANUAL')),
  pagamento_id       uuid REFERENCES financeiro.pagamentos (id),
  conta_pagar_id     uuid REFERENCES financeiro.contas_pagar (id),
  categoria_id       uuid REFERENCES financeiro.categorias_financeiras (id),
  conciliado_por     uuid,
  conciliado_em      timestamptz,
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  deleted_at         timestamptz,
  created_by         uuid,
  updated_by         uuid
);
CREATE UNIQUE INDEX ux_transacao_identificador ON financeiro.transacoes_bancarias (conta_bancaria_id, identificador) WHERE deleted_at IS NULL;
CREATE INDEX idx_transacoes_tenant ON financeiro.transacoes_bancarias (tenant_id, status, data) WHERE deleted_at IS NULL;
-- Um pagamento ou conta a pagar concilia com no máximo uma transação.
CREATE UNIQUE INDEX ux_transacao_pagamento ON financeiro.transacoes_bancarias (pagamento_id) WHERE pagamento_id IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX ux_transacao_conta_pagar ON financeiro.transacoes_bancarias (conta_pagar_id) WHERE conta_pagar_id IS NOT NULL AND deleted_at IS NULL;

-- ================================================================== comissões (§71)

CREATE TABLE financeiro.regras_comissao (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  nome               varchar(120) NOT NULL,
  beneficiario_tipo  varchar(10) NOT NULL CHECK (beneficiario_tipo IN ('VENDEDOR','SDR','PARCEIRO')),
  beneficiario_id    uuid,                                  -- nulo = qualquer beneficiário do tipo
  servico_id         uuid,
  fabricante         varchar(80),
  base               varchar(26) NOT NULL CHECK (base IN
                       ('FIXA','PERCENTUAL','SOBRE_PRIMEIRA_MENSALIDADE','SOBRE_MRR','SOBRE_MARGEM','RECORRENTE')),
  valor              numeric(15,4) NOT NULL CHECK (valor > 0),  -- R$ na FIXA; % nas demais
  meses_recorrencia  smallint CHECK (meses_recorrencia BETWEEN 1 AND 120),
  prioridade         smallint NOT NULL DEFAULT 100,
  ativa              boolean NOT NULL DEFAULT true,
  vigencia_inicio    date,
  vigencia_fim       date,
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  deleted_at         timestamptz,
  created_by         uuid,
  updated_by         uuid
);
CREATE INDEX idx_regras_comissao_tenant ON financeiro.regras_comissao (tenant_id, ativa) WHERE deleted_at IS NULL;

CREATE TABLE financeiro.comissoes (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  regra_id           uuid NOT NULL REFERENCES financeiro.regras_comissao (id),
  beneficiario_tipo  varchar(10) NOT NULL,
  beneficiario_id    uuid NOT NULL,
  cobranca_id        uuid NOT NULL REFERENCES financeiro.cobrancas (id),
  pagamento_id       uuid NOT NULL REFERENCES financeiro.pagamentos (id),
  empresa_id         uuid NOT NULL,
  competencia        char(7) NOT NULL,
  base_calculo       numeric(15,2) NOT NULL,
  percentual         numeric(9,4),
  valor              numeric(15,2) NOT NULL CHECK (valor >= 0),
  status             varchar(10) NOT NULL CHECK (status IN ('A_PAGAR','LANCADA','CANCELADA')),
  conta_pagar_id     uuid REFERENCES financeiro.contas_pagar (id),
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  deleted_at         timestamptz,
  created_by         uuid,
  updated_by         uuid,
  CONSTRAINT ux_comissao_pagamento UNIQUE (regra_id, pagamento_id, beneficiario_id)
);
CREATE INDEX idx_comissoes_tenant ON financeiro.comissoes (tenant_id, competencia, status);

-- ================================================================== notas fiscais (§23 faturamento, §35)

-- O módulo registra e guarda a nota (XML/PDF); a emissão fiscal continua no sistema fiscal (§33).
CREATE TABLE financeiro.notas_fiscais (
  id                      uuid PRIMARY KEY,
  tenant_id               uuid NOT NULL,
  tipo                    varchar(8) NOT NULL CHECK (tipo IN ('EMITIDA','RECEBIDA')),
  modelo                  varchar(6) NOT NULL DEFAULT 'NFSE' CHECK (modelo IN ('NFSE','NFE','NFCE','OUTRO')),
  numero                  varchar(20) NOT NULL,
  serie                   varchar(5),
  chave_acesso            varchar(50),
  data_emissao            date NOT NULL,
  competencia             char(7) NOT NULL,
  valor                   numeric(15,2) NOT NULL CHECK (valor > 0),
  valor_impostos          numeric(15,2) NOT NULL DEFAULT 0 CHECK (valor_impostos >= 0),
  empresa_id              uuid,
  participante_nome       varchar(150),
  participante_documento  varchar(18),
  cobranca_id             uuid REFERENCES financeiro.cobrancas (id),
  conta_pagar_id          uuid REFERENCES financeiro.contas_pagar (id),
  documento_id            uuid REFERENCES financeiro.documentos (id),
  status                  varchar(10) NOT NULL DEFAULT 'AUTORIZADA' CHECK (status IN ('AUTORIZADA','CANCELADA')),
  observacao              varchar(300),
  created_at              timestamptz NOT NULL,
  updated_at              timestamptz NOT NULL,
  deleted_at              timestamptz,
  created_by              uuid,
  updated_by              uuid
);
CREATE UNIQUE INDEX ux_notas_numero ON financeiro.notas_fiscais
  (tenant_id, tipo, modelo, coalesce(serie, ''), numero, coalesce(participante_documento, '')) WHERE deleted_at IS NULL;
CREATE INDEX idx_notas_competencia ON financeiro.notas_fiscais (tenant_id, competencia) WHERE deleted_at IS NULL;

-- ================================================================== fechamento contábil (§35, §36)

CREATE TABLE financeiro.fechamentos_contabeis (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  competencia      char(7) NOT NULL,
  status           varchar(26) NOT NULL CHECK (status IN
                     ('NAO_INICIADO','EM_CONFERENCIA','COM_PENDENCIA','AGUARDANDO_DOCUMENTO','PRONTO',
                      'ENVIADO_PARA_CONTABILIDADE','EM_ANALISE','FINALIZADO')),
  pendencias       varchar(1000),
  enviado_em       timestamptz,
  enviado_por      uuid,
  finalizado_em    timestamptz,
  finalizado_por   uuid,
  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL,
  deleted_at       timestamptz,
  created_by       uuid,
  updated_by       uuid
);
CREATE UNIQUE INDEX ux_fechamento_competencia ON financeiro.fechamentos_contabeis (tenant_id, competencia) WHERE deleted_at IS NULL;

CREATE TABLE financeiro.fechamento_itens (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  fechamento_id    uuid NOT NULL REFERENCES financeiro.fechamentos_contabeis (id),
  item             varchar(26) NOT NULL CHECK (item IN
                     ('RECEITAS_CONFERIDAS','DESPESAS_CONFERIDAS','EXTRATOS_CONFERIDOS','NOTAS_EMITIDAS','NOTAS_RECEBIDAS',
                      'COMPROVANTES','COMISSOES','IMPOSTOS','LANCAMENTOS_CLASSIFICADOS','DOCUMENTACAO_COMPLETA')),
  ordem            smallint NOT NULL,
  concluido        boolean NOT NULL DEFAULT false,
  concluido_por    uuid,
  concluido_em     timestamptz,
  observacao       varchar(500),
  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL,
  deleted_at       timestamptz,
  created_by       uuid,
  updated_by       uuid,
  CONSTRAINT ux_fechamento_item UNIQUE (fechamento_id, item)
);

-- ================================================================== webhooks de saída (§91)

-- O segredo de assinatura é guardado cifrado (AES-GCM, chave em variável de ambiente; §85).
CREATE TABLE financeiro.webhook_assinaturas (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  descricao        varchar(120) NOT NULL,
  url              varchar(500) NOT NULL,
  eventos          varchar(600) NOT NULL,                   -- lista separada por vírgula; "*" = todos
  segredo_cifrado  varchar(300) NOT NULL,
  ativa            boolean NOT NULL DEFAULT true,
  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL,
  deleted_at       timestamptz,
  created_by       uuid,
  updated_by       uuid
);
CREATE INDEX idx_webhook_assinaturas_tenant ON financeiro.webhook_assinaturas (tenant_id, ativa) WHERE deleted_at IS NULL;

CREATE TABLE financeiro.webhook_entregas (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  assinatura_id      uuid NOT NULL REFERENCES financeiro.webhook_assinaturas (id),
  evento_id          uuid NOT NULL,
  tipo               varchar(80) NOT NULL,
  payload            jsonb NOT NULL,
  status             varchar(10) NOT NULL DEFAULT 'PENDENTE' CHECK (status IN ('PENDENTE','ENTREGUE','FALHOU')),
  tentativas         smallint NOT NULL DEFAULT 0,
  ultimo_status_http smallint,
  ultimo_erro        varchar(500),
  proxima_tentativa  timestamptz NOT NULL,
  entregue_em        timestamptz,
  criado_em          timestamptz NOT NULL,
  CONSTRAINT ux_webhook_entrega UNIQUE (assinatura_id, evento_id)
);
CREATE INDEX idx_webhook_entregas_pendentes ON financeiro.webhook_entregas (proxima_tentativa) WHERE status = 'PENDENTE';
CREATE INDEX idx_webhook_entregas_tenant ON financeiro.webhook_entregas (tenant_id, criado_em DESC);

-- Cliente do provedor de pagamento real por empresa (o Asaas exige um "customer" antes da cobrança).
CREATE TABLE financeiro.provedor_clientes (
  tenant_id     uuid NOT NULL,
  provedor      varchar(40) NOT NULL,
  empresa_id    uuid NOT NULL,
  referencia    varchar(100) NOT NULL,
  criado_em     timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, provedor, empresa_id)
);

-- ================================================================== view pública ampliada

-- Para o motor de relatórios do Grupo 3: as colunas da vw_pub_cobrancas, mais categoria e vendedor.
CREATE VIEW financeiro.vw_pub_cobrancas_v2 AS
  SELECT c.id, c.tenant_id, c.empresa_id, c.contrato_id, c.competencia, c.data_vencimento,
         c.valor, c.desconto, c.juros, c.multa, c.valor_pago, c.forma_pagamento, c.status, c.created_at,
         c.categoria_id, cat.nome AS categoria, c.centro_custo_id, c.vendedor_id, c.parceiro_id, c.valor_variavel
    FROM financeiro.cobrancas c
    LEFT JOIN financeiro.categorias_financeiras cat ON cat.id = c.categoria_id
   WHERE c.deleted_at IS NULL;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'usr_chamados') THEN
    GRANT SELECT ON financeiro.vw_pub_cobrancas_v2 TO usr_chamados;
  END IF;
END $$;
