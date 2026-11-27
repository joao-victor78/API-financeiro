package br.com.erp.financeiro.documentos;

import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.api.Resposta;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Biblioteca de documentos (§96). O download é a resposta sem envelope, como a exportação (RN-57). */
@Tag(name = "documentos")
@RestController
@RequestMapping("/api/financeiro/documentos")
public class DocumentoController {

  private final DocumentoService documentos;

  public DocumentoController(DocumentoService documentos) {
    this.documentos = documentos;
  }

  public record DocumentoSaida(UUID id, String nomeArquivo, String tipoConteudo, long tamanhoBytes, String sha256,
      Documento.Categoria categoria, String entidade, UUID registroId, String descricao, UUID createdBy, OffsetDateTime createdAt) {
    public static DocumentoSaida de(Documento d) {
      return new DocumentoSaida(d.getId(), d.getNomeArquivo(), d.getTipoConteudo(), d.getTamanhoBytes(), d.getSha256(),
          d.getCategoria(), d.getEntidade(), d.getRegistroId(), d.getDescricao(), d.getCreatedBy(), d.getCreatedAt());
    }
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('financeiro.documento.enviar')")
  public ResponseEntity<Resposta<DocumentoSaida>> enviar(
      @RequestPart("arquivo") MultipartFile arquivo,
      @RequestParam(required = false) Documento.Categoria categoria,
      @RequestParam(required = false) String entidade,
      @RequestParam(required = false) UUID registroId,
      @RequestParam(required = false) String descricao) {
    byte[] bytes;
    try {
      bytes = arquivo.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Documento d = documentos.enviar(arquivo.getOriginalFilename(), bytes, categoria, entidade, registroId, descricao);
    return ResponseEntity.created(URI.create("/api/financeiro/documentos/" + d.getId()))
        .body(Resposta.ok(DocumentoSaida.de(d), "Documento enviado."));
  }

  @GetMapping
  @PreAuthorize("hasAuthority('financeiro.documento.ver')")
  public Resposta<Pagina<DocumentoSaida>> listar(
      @RequestParam(required = false) String entidade,
      @RequestParam(required = false) UUID registroId,
      @RequestParam(required = false) Documento.Categoria categoria,
      @RequestParam(required = false) Integer pagina,
      @RequestParam(required = false) Integer tamanho) {
    Pagina<Documento> p = documentos.listar(entidade, registroId, categoria,
        Pagina.pedido(pagina, tamanho, Sort.by("createdAt").descending().and(Sort.by("id"))));
    return Resposta.ok(new Pagina<>(p.itens().stream().map(DocumentoSaida::de).toList(), p.pagina(), p.tamanho(), p.total()));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('financeiro.documento.ver')")
  public Resposta<DocumentoSaida> ver(@PathVariable UUID id) {
    return Resposta.ok(DocumentoSaida.de(documentos.obter(id)));
  }

  @GetMapping("/{id}/arquivo")
  @PreAuthorize("hasAuthority('financeiro.documento.ver')")
  public ResponseEntity<byte[]> baixar(@PathVariable UUID id) {
    DocumentoService.Arquivo a = documentos.baixar(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(a.documento().getTipoConteudo()))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(a.documento().getNomeArquivo()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(a.conteudo());
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('financeiro.documento.enviar')")
  public Resposta<Void> excluir(@PathVariable UUID id) {
    documentos.excluir(id);
    return Resposta.ok(null, "Documento excluído.");
  }
}
