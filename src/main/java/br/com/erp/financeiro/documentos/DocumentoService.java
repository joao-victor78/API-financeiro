package br.com.erp.financeiro.documentos;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Pagina;
import br.com.erp.financeiro.auditoria.AuditoriaService;
import br.com.erp.financeiro.comum.Relogio;
import br.com.erp.financeiro.comum.Tenant;
import br.com.erp.financeiro.tenant.TenantContexto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Biblioteca de documentos financeiros (§96). Valida tipo e tamanho na entrada (§84), calcula o SHA-256
 * e grava o arquivo no armazenamento (§95) antes do registro; o vínculo é sempre com um registro do
 * próprio tenant.
 */
@Service
public class DocumentoService {

  /** Entidades que aceitam documento e a tabela onde o registro precisa existir. */
  static final Map<String, String> ENTIDADES = Map.of(
      "cobranca", "cobrancas", "conta_pagar", "contas_pagar", "pagamento", "pagamentos",
      "nota_fiscal", "notas_fiscais", "extrato", "extratos_importados", "fechamento", "fechamentos_contabeis",
      "recorrencia", "recorrencias", "comissao", "comissoes");

  /** Extensão → tipo de conteúdo aceito. Executáveis, HTML e scripts ficam de fora (XSS, §84). */
  static final Map<String, String> TIPOS = Map.ofEntries(
      Map.entry("pdf", "application/pdf"), Map.entry("xml", "application/xml"), Map.entry("png", "image/png"),
      Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("ofx", "application/x-ofx"),
      Map.entry("csv", "text/csv"), Map.entry("txt", "text/plain"),
      Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      Map.entry("zip", "application/zip"));

  private static final Set<String> TEXTO = Set.of("xml", "ofx", "csv", "txt");

  private final DocumentoRepository documentos;
  private final Armazenamento armazenamento;
  private final AuditoriaService auditoria;
  private final JdbcTemplate jdbc;
  private final Relogio relogio;
  private final long tamanhoMaximo;

  public DocumentoService(DocumentoRepository documentos, Armazenamento armazenamento, AuditoriaService auditoria,
      JdbcTemplate jdbc, Relogio relogio, @Value("${financeiro.armazenamento.tamanho-maximo-mb:15}") long tamanhoMaximoMb) {
    this.documentos = documentos;
    this.armazenamento = armazenamento;
    this.auditoria = auditoria;
    this.jdbc = jdbc;
    this.relogio = relogio;
    this.tamanhoMaximo = tamanhoMaximoMb * 1024 * 1024;
  }

  public record Arquivo(Documento documento, byte[] conteudo) {}

  @Transactional
  public Documento enviar(String nomeOriginal, byte[] conteudo, Documento.Categoria categoria, String entidade,
      UUID registroId, String descricao) {
    if (conteudo == null || conteudo.length == 0) throw ApiException.campo("arquivo", "ARQUIVO_VAZIO", "Envie um arquivo.");
    if (conteudo.length > tamanhoMaximo) {
      throw ApiException.campo("arquivo", "ARQUIVO_GRANDE", "O arquivo passa de " + (tamanhoMaximo / 1024 / 1024) + " MB.");
    }
    String nome = nomeSeguro(nomeOriginal);
    String extensao = extensao(nome);
    String tipo = TIPOS.get(extensao);
    if (tipo == null) {
      throw ApiException.campo("arquivo", "TIPO_NAO_ACEITO", "Tipos aceitos: " + String.join(", ", TIPOS.keySet().stream().sorted().toList()) + ".");
    }
    if (!conteudoConfere(extensao, conteudo)) {
      throw ApiException.campo("arquivo", "CONTEUDO_INVALIDO", "O conteúdo do arquivo não corresponde à extensão ." + extensao + ".");
    }
    validarVinculo(entidade, registroId);
    String sha = sha256(conteudo);
    LocalDate hoje = relogio.hoje();
    String chave = TenantContexto.exigir() + "/" + hoje.getYear() + "/" + String.format("%02d", hoje.getMonthValue()) + "/"
        + UUID.randomUUID() + "-" + nome;
    armazenamento.salvar(chave, conteudo, tipo);
    Documento d = documentos.save(new Documento(nome, tipo, conteudo.length, sha, chave,
        categoria == null ? Documento.Categoria.OUTRO : categoria, entidade, registroId,
        descricao == null || descricao.isBlank() ? null : descricao.trim()));
    auditoria.registrar("ENVIAR_DOCUMENTO", "documento", d.getId(), null, d.instantaneo());
    return d;
  }

  @Transactional(readOnly = true)
  public Pagina<Documento> listar(String entidade, UUID registroId, Documento.Categoria categoria, Pageable pagina) {
    Specification<Documento> s = (r, q, cb) -> cb.isNull(r.get("deletedAt"));
    if (entidade != null && !entidade.isBlank()) s = s.and((r, q, cb) -> cb.equal(r.get("entidade"), entidade));
    if (registroId != null) s = s.and((r, q, cb) -> cb.equal(r.get("registroId"), registroId));
    if (categoria != null) s = s.and((r, q, cb) -> cb.equal(r.get("categoria"), categoria));
    return Pagina.de(documentos.findAll(s, pagina), d -> d);
  }

  @Transactional(readOnly = true)
  public Documento obter(UUID id) {
    return Tenant.ativoDoTenant(documentos.findById(id), "Documento");
  }

  /** Para quem vincula um documento já enviado (conta a pagar, nota fiscal): tem de ser do tenant. */
  @Transactional(readOnly = true)
  public Documento paraVincular(UUID id, String campo) {
    if (id == null) return null;
    return documentos.findById(id).filter(d -> d.getDeletedAt() == null)
        .filter(d -> d.getTenantId().equals(TenantContexto.exigir()))
        .orElseThrow(() -> ApiException.campo(campo, "DOCUMENTO_INEXISTENTE", "Documento não encontrado."));
  }

  @Transactional
  public Arquivo baixar(UUID id) {
    Documento d = obter(id);
    byte[] conteudo = armazenamento.ler(d.getChaveArmazenamento());
    auditoria.registrar("BAIXAR_DOCUMENTO", "documento", d.getId(), null, Map.of("nomeArquivo", d.getNomeArquivo()));
    return new Arquivo(d, conteudo);
  }

  /** Exclusão lógica: o arquivo permanece no armazenamento para a retenção (§83, §99). */
  @Transactional
  public void excluir(UUID id) {
    Documento d = obter(id);
    Map<String, Object> antes = d.instantaneo();
    d.excluir();
    auditoria.registrar("EXCLUIR_DOCUMENTO", "documento", d.getId(), antes, null);
  }

  @Transactional
  public void vincular(Documento d, String entidade, UUID registroId) {
    d.vincular(entidade, registroId);
  }

  private void validarVinculo(String entidade, UUID registroId) {
    if (entidade == null || entidade.isBlank()) {
      if (registroId != null) throw ApiException.campo("entidade", "OBRIGATORIA", "Informe a entidade do registro.");
      return;
    }
    String tabela = ENTIDADES.get(entidade);
    if (tabela == null) {
      throw ApiException.campo("entidade", "ENTIDADE_INVALIDA", "Entidades aceitas: " + String.join(", ", ENTIDADES.keySet().stream().sorted().toList()) + ".");
    }
    if (registroId == null) throw ApiException.campo("registroId", "OBRIGATORIO", "Informe o registro.");
    Integer n = jdbc.queryForObject("SELECT count(*) FROM financeiro." + tabela + " WHERE id = ? AND tenant_id = ?",
        Integer.class, registroId, TenantContexto.exigir());
    if (n == null || n == 0) throw ApiException.naoEncontrado("Registro");
  }

  /** Assinatura dos formatos binários e texto sem bytes de controle nos formatos de texto. */
  static boolean conteudoConfere(String extensao, byte[] c) {
    return switch (extensao) {
      case "pdf" -> comeca(c, "%PDF");
      case "png" -> c.length > 8 && (c[0] & 0xFF) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G';
      case "jpg", "jpeg" -> c.length > 3 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xD8;
      case "xlsx", "zip" -> c.length > 4 && c[0] == 'P' && c[1] == 'K';
      default -> TEXTO.contains(extensao) && semControle(c);
    };
  }

  private static boolean comeca(byte[] c, String prefixo) {
    byte[] p = prefixo.getBytes(StandardCharsets.US_ASCII);
    if (c.length < p.length) return false;
    for (int i = 0; i < p.length; i++) if (c[i] != p[i]) return false;
    return true;
  }

  private static boolean semControle(byte[] c) {
    int limite = Math.min(c.length, 8192);
    for (int i = 0; i < limite; i++) {
      int b = c[i] & 0xFF;
      if (b == 0) return false;
    }
    return true;
  }

  static String nomeSeguro(String nome) {
    String base = nome == null ? "arquivo" : nome.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1);
    base = java.text.Normalizer.normalize(base, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    base = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
    if (base.isBlank() || base.startsWith(".")) base = "arquivo" + base;
    return base.length() > 150 ? base.substring(base.length() - 150) : base;
  }

  static String extensao(String nome) {
    int i = nome.lastIndexOf('.');
    return i < 0 ? "" : nome.substring(i + 1).toLowerCase(Locale.ROOT);
  }

  public static String sha256(byte[] conteudo) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
