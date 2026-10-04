# Estágio 1: Build da aplicação
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copia arquivos de dependência e wrapper do Maven
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B

# Copia o código fonte e compila o JAR
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Estágio 2: Imagem final enxuta de execução
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Cria usuário não-root por segurança
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]