# Imagem da API do Financeiro. Publicada a cada merge na main (Contrato §14.3).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/financeiro.jar app.jar
# Documentos com ARMAZENAMENTO_TIPO=local (§95): pasta do usuário app; monte um volume em /app/dados
RUN mkdir -p /app/dados/documentos && chown -R app:app /app/dados
VOLUME /app/dados
USER app
EXPOSE 8085
# Actuator (health/Prometheus) numa porta separada, só para a rede interna (§98)
EXPOSE 9085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
