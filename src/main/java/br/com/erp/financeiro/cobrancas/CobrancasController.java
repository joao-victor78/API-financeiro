package br.com.erp.financeiro.cobrancas;

import br.com.erp.financeiro.cobrancas.CobrancasDtos.Aprovada;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.Atualizar;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.Cancelar;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.Criar;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.Filtro;
import br.com.erp.financeiro.cobrancas.CobrancasDtos.FiltroQrCode;
import br.com.erp.financeiro.comum.Paginado;
import br.com.erp.financeiro.comum.auth.AuthUser;
import br.com.erp.financeiro.comum.auth.Permissao;
import br.com.erp.financeiro.comum.auth.Permissoes;
import br.com.erp.financeiro.comum.http.Mensagem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cobranças")
@RestController
@RequestMapping("/cobrancas")
public class CobrancasController {

  private final CobrancasService svc;

  public CobrancasController(CobrancasService svc) {
    this.svc = svc;
  }

  @GetMapping
  @Permissoes(Permissao.LEITURA)
  public Paginado<Cobranca> listar(AuthUser u, @Valid Filtro f) {
    return svc.listar(u, f);
  }

  @GetMapping("/{id}")
  @Permissoes(Permissao.LEITURA)
  public Cobranca obter(AuthUser u, @PathVariable UUID id) {
    return svc.obter(u, id);
  }

  @Operation(summary = "Imagem do QR Code Pix para exibir na tela, no e-mail ou na fatura (devolve a imagem, não JSON)")
  // Sem "produces": os erros desta rota continuam saindo em JSON no formato padrão.
  @GetMapping("/{id}/qrcode")
  @Permissoes(Permissao.LEITURA)
  public ResponseEntity<byte[]> qrCode(AuthUser u, @PathVariable UUID id, @Valid FiltroQrCode q) {
    var img = svc.qrCode(u, id, q.formato() == null ? "png" : q.formato(), q.tamanho() == null ? 320 : q.tamanho());
    return ResponseEntity.ok()
        .contentType(img.tipo())
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
        .body(img.corpo());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Cobrança criada em rascunho")
  public Cobranca criar(AuthUser u, @Valid @RequestBody Criar dto) {
    return svc.criar(u, dto);
  }

  @PatchMapping("/{id}")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Cobrança atualizada")
  public Cobranca atualizar(AuthUser u, @PathVariable UUID id, @Valid @RequestBody Atualizar dto) {
    return svc.atualizar(u, id, dto);
  }

  @PostMapping("/{id}/aprovar")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Cobrança aprovada e emitida")
  public Aprovada aprovar(AuthUser u, @PathVariable UUID id) {
    return svc.aprovar(u, id);
  }

  @PostMapping("/{id}/cancelar")
  @Permissoes(Permissao.ESCRITA)
  @Mensagem("Cobrança cancelada")
  public Cobranca cancelar(AuthUser u, @PathVariable UUID id, @Valid @RequestBody Cancelar dto) {
    return svc.cancelar(u, id, dto.motivo());
  }
}
