# API Quack PDV (Spring Boot) - build multi-stage.
# Contexto de build: a pasta JAVA/api.   docker build -t quack-pdv-api-java JAVA/api
#
# Variaveis de ambiente obrigatorias em producao (ver README): SPRING_DATASOURCE_URL/USERNAME/PASSWORD,
# QUACK_AUTH_JWT_SECRET e QUACK_SECURITY_ENCRYPTION_KEY (32+ caracteres; a mesma cifra/segredo da API .NET).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencias em camada propria: so refaz quando o pom muda.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/quack-pdv-api-*.jar /build/app.jar

FROM eclipse-temurin:21-jre
ENV TZ=America/Sao_Paulo \
    JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    PORT=8080
WORKDIR /app
# Usuario sem privilegios.
RUN groupadd --system quack && useradd --system --gid quack --no-create-home quack
COPY --from=build --chown=quack:quack /build/app.jar /app/app.jar
USER quack
EXPOSE 8080

# /actuator/health nao exige token; usa o /dev/tcp do bash (a imagem nao tem curl).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
