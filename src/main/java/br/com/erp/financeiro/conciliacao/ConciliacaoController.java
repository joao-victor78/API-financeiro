package br.com.erp.financeiro.conciliacao;

import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.ConciliacaoEntrada;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.ExtratoSaida;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.Importacao;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.ResumoConta;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.Sugestoes;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.TransacaoEntrada;
import br.com.erp.financeiro.conciliacao.ConciliacaoDtos.TransacaoSaida;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Conciliação financeira (§32). Ver: financeiro.conciliacao.ver; importar e conciliar: financeiro.conciliacao.executar. */
@Tag(name = "conciliacao")
@RestController
@RequestMapping("/api/financeiro/conciliacao")
public class ConciliacaoController {

  private final ConciliacaoService conciliacao;

  public ConciliacaoController(ConciliacaoService conciliacao) {
    this.conciliacao = conciliacao;
  }

  @Operation(summary = "Importar extrato OFX (concilia automaticamente o que bater)")
  @PostMapping(value = "/extratos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('financeiro.conciliacao.executar')")
  public Resposta<Importacao> importar(@RequestParam UUID contaBancariaId, @RequestPart("arquivo") MultipartFile arquivo) {
    try {
      Importacao i = conciliacao.importarOfx(contaBancariaId, arquivo.getOriginalFilename(), arquivo.getBytes());
      return Resposta.ok(i, i.novos() + " lançamento(s) novo(s); " + i.conciliadosAutomaticamente() + " conciliado(s) automaticamente.");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @GetMapping("/extratos")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.ver')")
  public Resposta<List<ExtratoSaida>> extratos() {
    return Resposta.ok(conciliacao.extratos().stream().map(ExtratoSaida::de).toList());
  }

  @Operation(summary = "Registrar lançamento bancário sem arquivo (API do banco ou caixa)")
  @PostMapping("/transacoes")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.executar')")
  public Resposta<TransacaoSaida> registrar(@Valid @RequestBody TransacaoEntrada e) {
    return Resposta.ok(TransacaoSaida.de(conciliacao.registrar(e)), "Lançamento registrado.");
  }

  @GetMapping("/transacoes")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.ver')")
  public Resposta<Pagina<TransacaoSaida>> listar(
      @RequestParam(required = false) UUID contaBancariaId,
      @RequestParam(required = false) TransacaoBancaria.Status status,
      @RequestParam(required = false) LocalDate de,
      @RequestParam(required = false) LocalDate ate,
      @RequestParam(required = false) UUID extratoId,
      @RequestParam(required = false) Integer pagina,
      @RequestParam(required = false) Integer tamanho) {
    return Resposta.ok(conciliacao.listar(new ConciliacaoService.Filtro(contaBancariaId, status, de, ate, extratoId),
        Pagina.pedido(pagina, tamanho, Sort.by("data").descending().and(Sort.by("id")))));
  }

  @GetMapping("/transacoes/{id}/sugestoes")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.ver')")
  public Resposta<Sugestoes> sugestoes(@PathVariable UUID id) {
    return Resposta.ok(conciliacao.sugestoes(id));
  }

  @PostMapping("/transacoes/{id}/conciliar")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.executar')")
  public Resposta<TransacaoSaida> conciliar(@PathVariable UUID id, @RequestBody ConciliacaoEntrada e) {
    return Resposta.ok(TransacaoSaida.de(conciliacao.conciliar(id, e)), "Transação conciliada.");
  }

  @PostMapping("/transacoes/{id}/ignorar")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.executar')")
  public Resposta<TransacaoSaida> ignorar(@PathVariable UUID id) {
    return Resposta.ok(TransacaoSaida.de(conciliacao.ignorar(id)), "Transação ignorada.");
  }

  @PostMapping("/transacoes/{id}/desfazer")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.executar')")
  public Resposta<TransacaoSaida> desfazer(@PathVariable UUID id) {
    return Resposta.ok(TransacaoSaida.de(conciliacao.desfazer(id)), "Conciliação desfeita.");
  }

  @Operation(summary = "Rodar de novo a conciliação automática nas pendentes")
  @PostMapping("/automatica")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.executar')")
  public Resposta<Map<String, Integer>> automatica(@RequestParam(required = false) UUID contaBancariaId) {
    int n = conciliacao.conciliarPendentes(contaBancariaId);
    return Resposta.ok(Map.of("conciliadas", n), n + " transação(ões) conciliada(s).");
  }

  @GetMapping("/resumo")
  @PreAuthorize("hasAuthority('financeiro.conciliacao.ver')")
  public Resposta<List<ResumoConta>> resumo() {
    return Resposta.ok(conciliacao.resumo());
  }
}
