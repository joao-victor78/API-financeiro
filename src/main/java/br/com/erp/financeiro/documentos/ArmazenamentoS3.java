package br.com.erp.financeiro.documentos;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * Armazenamento compatível com S3 (§95): AWS S3, MinIO, Wasabi, Backblaze B2... Com endpoint próprio
 * (MinIO) use path-style. Credenciais por variável de ambiente; sem elas, a cadeia padrão da AWS
 * (perfil, papel da instância). Os objetos são gravados com criptografia no servidor (AES256).
 */
@Component
@ConditionalOnProperty(name = "financeiro.armazenamento.tipo", havingValue = "s3")
public class ArmazenamentoS3 implements Armazenamento {

  private final S3Client s3;
  private final String bucket;
  private final boolean cifrar;

  public ArmazenamentoS3(
      @Value("${financeiro.armazenamento.s3.bucket}") String bucket,
      @Value("${financeiro.armazenamento.s3.regiao:us-east-1}") String regiao,
      @Value("${financeiro.armazenamento.s3.endpoint:}") String endpoint,
      @Value("${financeiro.armazenamento.s3.chave-acesso:}") String chaveAcesso,
      @Value("${financeiro.armazenamento.s3.chave-secreta:}") String chaveSecreta,
      @Value("${financeiro.armazenamento.s3.path-style:false}") boolean pathStyle,
      @Value("${financeiro.armazenamento.s3.criptografia-servidor:true}") boolean cifrar) {
    this.bucket = bucket;
    this.cifrar = cifrar;
    S3ClientBuilder b = S3Client.builder().region(Region.of(regiao)).forcePathStyle(pathStyle);
    if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
    b.credentialsProvider(chaveAcesso == null || chaveAcesso.isBlank()
        ? DefaultCredentialsProvider.builder().build()
        : StaticCredentialsProvider.create(AwsBasicCredentials.create(chaveAcesso, chaveSecreta)));
    this.s3 = b.build();
  }

  @Override
  public String tipo() {
    return "s3";
  }

  @Override
  public void salvar(String chave, byte[] conteudo, String tipoConteudo) {
    PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(chave).contentType(tipoConteudo)
        .contentLength((long) conteudo.length);
    if (cifrar) req.serverSideEncryption(ServerSideEncryption.AES256);
    s3.putObject(req.build(), RequestBody.fromBytes(conteudo));
  }

  @Override
  public byte[] ler(String chave) {
    return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(chave).build()).asByteArray();
  }

  @PreDestroy
  void fechar() {
    s3.close();
  }
}
