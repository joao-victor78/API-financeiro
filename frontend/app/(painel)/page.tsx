'use client';

import { useState } from 'react';
import { Badge, Cabecalho, Estado, Kpi, useAcao, useDados, usePermissao } from '@/components/ui';
import { api } from '@/lib/api';
import { brl, dataHora, FAIXAS_AGING, ROTULOS } from '@/lib/formato';

interface Resumo {
  data_referencia: string;
  total_em_aberto: number;
  total_vencido: number;
  total_vencido_atualizado: number;
  taxa_inadimplencia_percentual: number;
  clientes_inadimplentes: number;
  aging: Record<string, { quantidade: number; valor: number }>;
}
interface Execucao {
  inicio: string;
  duracao_ms: number;
  ok: boolean;
  resultado: unknown;
}
interface StatusAutomacao {
  agendamento_ativo: boolean;
  jobs: Record<string, { agenda: string; ultima: Execucao | null }>;
  eventos_por_status: { status: string; total: number }[];
}
interface Health {
  status: string;
  versao: string;
  uptime_s: number;
  dependencias: { banco: { tipo: string; status: string; latencia_ms: number } };
}

const JOBS: Record<string, { nome: string; descricao: string }> = {
  recorrencias: { nome: 'Recorrências', descricao: 'Emite e aprova as cobranças cujo ciclo chegou, com Pix e conta a receber.' },
  vencimentos: { nome: 'Vencimentos', descricao: 'Marca títulos vencidos e avisa os outros módulos.' },
  promessas: { nome: 'Promessas', descricao: 'Marca promessas de pagamento não cumpridas.' },
  eventos: { nome: 'Eventos', descricao: 'Entrega os eventos pendentes aos módulos inscritos (HMAC).' },
};

const uptime = (s: number) => (s < 3600 ? `${Math.round(s / 60)} min` : `${Math.floor(s / 3600)} h ${Math.round((s % 3600) / 60)} min`);

export default function Painel() {
  const { admin } = usePermissao();
  const health = useDados<Health>('/health');
  const resumo = useDados<Resumo>('/inadimplencia/resumo');
  const auto = useDados<StatusAutomacao>('/automacao/status');
  const { executar, ocupado } = useAcao();
  const [resultado, setResultado] = useState<{ job: string; r: unknown } | null>(null);

  const rodar = (job: string) =>
    executar(
      () => api<{ resultado: unknown }>(`/automacao/executar/${job}`, { method: 'POST' }),
      (d) => {
        setResultado({ job, r: d?.resultado });
        auto.recarregar();
        resumo.recarregar();
      },
    );

  const r = resumo.dados;
  const maxAging = r ? Math.max(1, ...Object.values(r.aging).map((a) => a.valor)) : 1;

  return (
    <>
      <Cabecalho titulo="Painel" descricao="Visão geral da carteira e das automações que operam sem intervenção humana.">
        {health.dados && <Badge status={health.dados.status} texto={`API ${ROTULOS[health.dados.status] ?? health.dados.status}`} />}
      </Cabecalho>

      <Estado carregando={resumo.carregando} erro={resumo.erro} vazio={!r ? undefined : false}>
        {r && (
          <div className="kpis">
            <Kpi rotulo="Total em aberto" valor={brl(r.total_em_aberto)} />
            <Kpi rotulo="Total vencido" valor={brl(r.total_vencido)} tom={r.total_vencido ? 'erro' : undefined} />
            <Kpi rotulo="Vencido com encargos" valor={brl(r.total_vencido_atualizado)} detalhe="multa 2% + juros 1% a.m." />
            <Kpi rotulo="Inadimplência" valor={`${r.taxa_inadimplencia_percentual.toLocaleString('pt-BR')}%`} detalhe={`${r.clientes_inadimplentes} cliente(s)`} />
          </div>
        )}
      </Estado>

      <div className="grade">
        <section className="cartao">
          <h2>Aging da carteira</h2>
          {r &&
            Object.entries(r.aging).map(([faixa, a]) => (
              <div className="barra" key={faixa}>
                <span className="sutil">{FAIXAS_AGING[faixa] ?? faixa}</span>
                <div className="barra-trilho">
                  <div className={`barra-valor ${faixa === 'a_vencer' ? '' : 'atraso'}`} style={{ width: `${(a.valor / maxAging) * 100}%` }} />
                </div>
                <span className="num">
                  {brl(a.valor)} <small className="sutil">({a.quantidade})</small>
                </span>
              </div>
            ))}
        </section>

        <section className="cartao">
          <h2>Saúde do módulo</h2>
          <Estado carregando={health.carregando} erro={health.erro} vazio={!health.dados ? undefined : false}>
            {health.dados && (
              <dl className="lista-def">
                <dt>Status</dt>
                <dd>
                  <Badge status={health.dados.status} />
                </dd>
                <dt>Versão</dt>
                <dd>{health.dados.versao}</dd>
                <dt>Banco</dt>
                <dd>
                  {health.dados.dependencias.banco.tipo} · {health.dados.dependencias.banco.status} · {health.dados.dependencias.banco.latencia_ms} ms
                </dd>
                <dt>No ar há</dt>
                <dd>{uptime(health.dados.uptime_s)}</dd>
                <dt>Eventos</dt>
                <dd>
                  {auto.dados?.eventos_por_status.length
                    ? auto.dados.eventos_por_status.map((e) => (
                        <span key={e.status} style={{ marginRight: 6 }}>
                          <Badge status={e.status} texto={`${ROTULOS[e.status] ?? e.status}: ${e.total}`} />
                        </span>
                      ))
                    : '—'}
                </dd>
              </dl>
            )}
          </Estado>
        </section>
      </div>

      <section className="cartao">
        <header>
          <div>
            <h2>Automações (agendador)</h2>
            <p className="sutil" style={{ margin: '4px 0 0' }}>
              {auto.dados?.agendamento_ativo ? 'Agendamento ativo — os jobs rodam sozinhos nos horários abaixo.' : 'Agendamento desligado (JOBS_ENABLED=false).'}
            </p>
          </div>
        </header>
        <Estado carregando={auto.carregando} erro={auto.erro} vazio={!auto.dados ? undefined : false}>
          <div className="tabela-scroll">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Job</th>
                  <th>Quando</th>
                  <th>Última execução</th>
                  <th>Resultado</th>
                  {admin && <th />}
                </tr>
              </thead>
              <tbody>
                {auto.dados &&
                  Object.entries(auto.dados.jobs).map(([job, j]) => (
                    <tr key={job}>
                      <td>
                        <strong>{JOBS[job]?.nome ?? job}</strong>
                        <br />
                        <small className="sutil">{JOBS[job]?.descricao}</small>
                      </td>
                      <td>{j.agenda}</td>
                      <td>{j.ultima ? `${dataHora(j.ultima.inicio)} · ${j.ultima.duracao_ms} ms` : <span className="sutil">ainda não rodou</span>}</td>
                      <td>{j.ultima ? <Badge status={j.ultima.ok ? 'ok' : 'falha'} texto={j.ultima.ok ? 'Sucesso' : 'Falhou'} /> : '—'}</td>
                      {admin && (
                        <td className="acoes-linha">
                          <button className="btn btn-sec btn-mini" disabled={ocupado} onClick={() => rodar(job)}>
                            Executar agora
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
          {resultado && (
            <div style={{ marginTop: 12 }}>
              <strong>Resultado de “{JOBS[resultado.job]?.nome}”</strong>
              <pre className="json">{JSON.stringify(resultado.r, null, 2)}</pre>
            </div>
          )}
        </Estado>
      </section>
    </>
  );
}
