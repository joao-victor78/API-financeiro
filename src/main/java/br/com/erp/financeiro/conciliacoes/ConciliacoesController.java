package br.com.erp.financeiro.conciliacoes;

import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.Permissao;
import br.com.erp.financeiro.comum.auth.Permissoes;
import br.com.erp.financeiro.comum.http.ApiException;
import br.com.erp.financeiro.comum.http.Mensagem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Conciliação bancária")
@RestController
@RequestMapping("/conciliacoes")
public class ConciliacoesController {

  private static final int MAX_OFX = 5 * 1024 * 1024;

  private final ConciliacoesService svc;

  public ConciliacoesController(ConciliacoesService svc) {
    this.svc = svc;
  }

  public record ImportarJson(@Size(max = 200) String nomeArquivo, String conteudo) {}

  public record ConciliarManual(UUID contaReceberId, UUID contaPagarId) {}

  public record FiltroTransacoes(
      @Pattern(regexp = "pendente|conciliada|ignorada", message = "status deve ser pendente, conciliada ou ignorada") String status) {}

  public record Paginacao(
      @Min(1) Integer page,
      @Min(1) @Max(value = Paginado.LIMITE_MAXIMO, message = Paginado.MSG_LIMITE) Integer limit) implements Paginado.Paginacao {}

  @Operation(summary = "Importa o extrato OFX enviado no campo \"arquivo\" (multipart)")
  @PostMapping(value = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Extrato importado e conciliado")
  public ConciliacoesService.Resultado importarArquivo(AuthUser u, @RequestPart(value = "arquivo", required = false) MultipartFile arquivo)
      throws IOException {
    if (arquivo == null || arquivo.isEmpty()) {
      throw ApiException.requisicaoInvalida("Envie o arquivo OFX no campo \"arquivo\" (multipart) ou em \"conteudo\" (JSON)");
    }
    String nome = arquivo.getOriginalFilename() == null || arquivo.getOriginalFilename().isBlank() ? "extrato.ofx" : arquivo.getOriginalFilename();
    return svc.importar(u, nome, arquivo.getBytes());
  }

  @Operation(summary = "Importa o extrato OFX enviado como texto em \"conteudo\" (JSON)")
  @PostMapping(value = "/importar", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Extrato importado e conciliado")
  public ConciliacoesService.Resultado importarTexto(AuthUser u, @Valid @RequestBody ImportarJson body) {
    if (body.conteudo() == null || body.conteudo().isEmpty()) {
      throw ApiException.requisicaoInvalida("Envie o arquivo OFX no campo \"arquivo\" (multipart) ou em \"conteudo\" (JSON)");
    }
    if (body.conteudo().length() > MAX_OFX) throw ApiException.requisicaoInvalida("Arquivo maior que 5 MB");
    return svc.importar(u, body.nomeArquivo() == null ? "extrato.ofx" : body.nomeArquivo(), body.conteudo().getBytes(StandardCharsets.UTF_8));
  }

  @GetMapping
  @Permissoes(Permissao.LEITURA)
  public Paginado<Conciliacao> listar(AuthUser u, @Valid Paginacao p) {
    return svc.listar(u, p);
  }

  @GetMapping("/{id}")
  @Permissoes(Permissao.LEITURA)
  public ConciliacoesService.Detalhe obter(AuthUser u, @PathVariable UUID id, @Valid FiltroTransacoes f) {
    return svc.obter(u, id, f.status() == null || f.status().isBlank() ? null : f.status());
  }

  @GetMapping("/{id}/transacoes/{transacaoId}/sugestoes")
  @Permissoes(Permissao.LEITURA)
  public List<ConciliacoesService.Sugestao> sugestoes(AuthUser u, @PathVariable UUID id, @PathVariable UUID transacaoId) {
    return svc.sugestoes(u, id, transacaoId);
  }

  @PostMapping("/{id}/transacoes/{transacaoId}/conciliar")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Transação conciliada manualmente")
  public ExtratoTransacao conciliar(AuthUser u, @PathVariable UUID id, @PathVariable UUID transacaoId, @RequestBody ConciliarManual dto) {
    return svc.conciliarManual(u, id, transacaoId, dto.contaReceberId(), dto.contaPagarId());
  }

  @PostMapping("/{id}/transacoes/{transacaoId}/ignorar")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Transação marcada como ignorada")
  public ExtratoTransacao ignorar(AuthUser u, @PathVariable UUID id, @PathVariable UUID transacaoId) {
    return svc.ignorar(u, id, transacaoId);
  }
}
