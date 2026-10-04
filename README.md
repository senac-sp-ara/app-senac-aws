# 🚀 Manual Prático: Deploy de Microsserviço Java no AWS EKS de Ponta a Ponta

> **Guia Didático e Passo a Passo para Aulas e Demonstrações Práticas**  
> Este manual orienta a preparação, containerização, configuração de manifestos Kubernetes e pipeline de CI/CD para o microsserviço Spring Boot **`app-senac-aws`**, integrado à infraestrutura AWS (EKS + NLB + API Gateway).

---

## 📋 Visão Geral da Arquitetura

A aplicação Java (Spring Boot) roda em um cluster **Amazon EKS** com 3 réplicas (Pods) para garantir alta disponibilidade. O tráfego segue o seguinte fluxo:

```
[ Usuário / Cliente ]
         │
         ▼
[ AWS API Gateway (HTTP API v2) ]
         │ (VPC Link)
         ▼
[ Network Load Balancer (NLB Interno Privado) ]
         │ (Porta 30080 - NodePort)
         ▼
[ Nós EC2 do EKS (Auto Scaling Group) ]
         │
         ▼
[ Pods da Aplicação Spring Boot (Porta 8080) ]
```

Para conectar a aplicação a essa infraestrutura, implementamos 4 etapas principais:
1. **Ajustes no Spring Boot:** Integração com **Spring Boot Actuator** (probes de liveness e readiness) e identificação de Pod/IP no Controller.
2. **Dockerfile Otimizado:** Build multi-stage com Java 21, alpine e usuário sem privilégios de root.
3. **Manifestos Kubernetes (`k8s/`):** `Deployment` com 3 réplicas e `Service` do tipo `NodePort: 30080`.
4. **Pipeline CI/CD (GitHub Actions):** Automação completa de testes, build Docker, push no Amazon ECR e deploy no Amazon EKS.

---

## 🛠️ Passo 1: Ajustar a Aplicação Spring Boot (Java 21)

### 1.1. Dependência do Spring Boot Actuator no `pom.xml`

O Kubernetes precisa saber quando a aplicação está pronta para receber requisições (*Readiness Probe*) e quando ela está saudável (*Liveness Probe*). Para isso, adicione o **Spring Boot Actuator** no `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

---

### 1.2. Configuração de Health Probes em `src/main/resources/application.yaml`

Habilite os endpoints de integridade do Kubernetes no arquivo de configuração da aplicação:

```yaml
server:
  port: 8080
  
spring:
  application:
    name: demo-aws

# Expõe probes de liveness e readiness para o Kubernetes
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

> [!NOTE]
> Essa configuração ativa automaticamente os seguintes endpoints que serão consumidos pelo Kubernetes:
> - `/actuator/health/liveness`
> - `/actuator/health/readiness`

---

### 1.3. Controller para Prova de Balanceamento (`ControlerAWS.java`)

Para provar aos alunos que as requisições estão sendo distribuídas entre réplicas diferentes pelo Load Balancer, o controller captura dinamicamente o **Hostname do Pod** e o **IP Interno**:

```java
package sp.senac.demo.aws.controller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ControlerAWS {

    @GetMapping("/hello")
    public ResponseEntity<String> hello(@RequestParam(required = false) String nome) {
        var pessoa = Objects.isNull(nome) ? "Visitante" : nome;
        
        String ip;
        String hostName;
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            ip = localHost.getHostAddress();
            hostName = localHost.getHostName(); // No Kubernetes, este é o nome exato do Pod
        } catch (UnknownHostException e) {
            ip = "desconhecido";
            hostName = "desconhecido";
        }
        
        String mensagem = String.format(
            "Olá %s, Bem-vindo a AWS | Pod: %s | IP: %s", pessoa, hostName, ip
        );
        return ResponseEntity.status(HttpStatus.OK.value()).body(mensagem);
    }

}
```

---

## 🐳 Passo 2: Dockerfile Multi-Stage Otimizado

Crie o arquivo `Dockerfile` na raiz do projeto. Ele utiliza **Multi-Stage Build** para reduzir o tamanho da imagem final e aumentar a segurança executando com um usuário sem permissões de root:

```dockerfile
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
```

> [!TIP]
> **Boas Práticas de Produção:**
> - `-XX:+UseContainerSupport` e `-XX:MaxRAMPercentage=75.0`: Fazem a JVM respeitar os limites de memória definidos pelo Kubernetes no Pod.
> - O usuário `appuser` impede que processos dentro do container acessem privilégios administrativos no nó hospedeiro.

---

## ☸️ Passo 3: Manifestos Kubernetes (`k8s/`)

Crie a pasta `k8s/` na raiz do repositório:
```bash
mkdir -p k8s
```

### 3.1. Manifesto de Deployment: `k8s/deployment.yaml`

Define 3 réplicas da aplicação, alocação de recursos (CPU/Memória) e as checagens de integridade (*health checks*):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hello-aws-deployment
  labels:
    app: hello-aws
spec:
  replicas: 3
  selector:
    matchLabels:
      app: hello-aws
  template:
    metadata:
      labels:
        app: hello-aws
    spec:
      containers:
      - name: hello-aws-container
        image: 581117995960.dkr.ecr.us-east-2.amazonaws.com/hello-aws-service:latest
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
```

---

### 3.2. Manifesto de Service: `k8s/service.yaml`

Expõe a aplicação através da porta estática **`30080` (NodePort)** nos nós do cluster. Essa é exatamente a porta onde o Target Group do Network Load Balancer (NLB) da AWS envia as requisições:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: hello-aws-service
  labels:
    app: hello-aws
spec:
  type: NodePort
  selector:
    app: hello-aws
  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
      nodePort: 30080
```

---

## 🔐 Passo 4: Permissões na AWS e Configuração de Secrets no GitHub

### 4.1. Configuração de Secrets no Repositório GitHub

No repositório do GitHub (`https://github.com/senac-sp-ara/app-senac-aws`):
1. Acesse: **Settings > Secrets and variables > Actions**.
2. Clique no botão **New repository secret**.
3. Cadastre os seguintes segredos:
   - `AWS_ACCESS_KEY_ID`: ID da chave de acesso do usuário IAM com permissão para ECR e EKS.
   - `AWS_SECRET_ACCESS_KEY`: Chave secreta de acesso.
   - `AWS_ROLE_ARN` *(opcional, se estiver utilizando autenticação federada via OIDC)*:
     ```text
     arn:aws:iam::<ID_DA_CONTA>:role/GitHubActions-Terraform-Role
     ```

### 4.2. (Opcional - Caso utilize OIDC) Atualizar a Trust Policy da IAM Role

Se for utilizar autenticação OIDC via GitHub Actions, garanta que a Role autorize o repositório da aplicação (`app-senac-aws`):

```bash
cat << 'EOF' > trust-policy-both.json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<ID_DA_CONTA>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": [
            "repo:senac-sp-ara/app-senac-aws-infra:*",
            "repo:senac-sp-ara/app-senac-aws:*"
          ]
        }
      }
    }
  ]
}
EOF

aws iam update-assume-role-policy \
  --role-name GitHubActions-Terraform-Role \
  --policy-document file://trust-policy-both.json \
  --profile admin-cli

rm trust-policy-both.json
```

---

## 🚀 Passo 5: Pipeline de CI/CD no GitHub Actions

Crie o arquivo `.github/workflows/deploy.yml`:

```yaml
name: "CI/CD App Spring Boot to EKS"

on:
  pull_request:
    branches: [ "main" ]
  push:
    branches: [ "main" ]

permissions:
  contents: read

env:
  AWS_REGION: us-east-2
  ECR_REPOSITORY: hello-aws-service
  EKS_CLUSTER_NAME: cluster-app-senac-aws

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout do código
      uses: actions/checkout@v4

    - name: Setup Java 21
      uses: actions/setup-java@v4
      with:
        java-version: '21'
        distribution: 'temurin'
        cache: maven

    - name: Executar Testes Unitários
      run: |
        chmod +x ./mvnw
        ./mvnw clean test

    # --- Daqui em diante executa apenas no push (após o merge na main) ---
    - name: Autenticar na AWS
      if: github.event_name == 'push'
      uses: aws-actions/configure-aws-credentials@v4
      with:
        aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
        aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
        aws-region: ${{ env.AWS_REGION }}

    - name: Login no Amazon ECR
      if: github.event_name == 'push'
      id: login-ecr
      uses: aws-actions/amazon-ecr-login@v2

    - name: Build e Push da Imagem Docker
      if: github.event_name == 'push'
      env:
        REGISTRY: ${{ steps.login-ecr.outputs.registry }}
        IMAGE_TAG: ${{ github.sha }}
      run: |
        docker build -t $REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG .
        docker tag $REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG $REGISTRY/$ECR_REPOSITORY:latest
        docker push $REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
        docker push $REGISTRY/$ECR_REPOSITORY:latest

    - name: Configurar Kubeconfig do EKS
      if: github.event_name == 'push'
      run: |
        aws eks update-kubeconfig --name ${{ env.EKS_CLUSTER_NAME }} --region ${{ env.AWS_REGION }}

    - name: Deploy no Kubernetes
      if: github.event_name == 'push'
      env:
        REGISTRY: ${{ steps.login-ecr.outputs.registry }}
        IMAGE_TAG: ${{ github.sha }}
      run: |
        # Substitui dinamicamente a tag da imagem no deployment.yaml pelo SHA do commit atual
        sed -i "s|581117995960.dkr.ecr.us-east-2.amazonaws.com/hello-aws-service:latest|$REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG|g" k8s/deployment.yaml
        
        kubectl apply -f k8s/service.yaml
        kubectl apply -f k8s/deployment.yaml
        
        # Aguarda as 3 instâncias ficarem prontas e saudáveis
        kubectl rollout status deployment/hello-aws-deployment --timeout=180s
```

### Fluxo Didático de Git para os Alunos

```bash
# 1. Trabalhar em branch de feature
git checkout -b feature/minha-feature
git add .
git commit -m "feat: implementacao com probes e manifestos k8s"
git push origin feature/minha-feature

# 2. Abrir Pull Request para 'main'
# A pipeline roda apenas a etapa de compilação e testes unitários (validação pré-merge).

# 3. Fazer o Merge do Pull Request na 'main'
# A pipeline dispara o build da imagem, push no ECR e deploy automático no EKS.
```

---

## 🛠️ Passo 6: Guia de Resolução de Problemas (Troubleshooting Real)

Durante a prática de laboratório, dois cenários reais e comuns podem ocorrer. Abaixo está a explicação e a solução detalhada para cada um.

---

### Cenário 1: Erro "Unauthorized" ao tentar ver os Pods no Console AWS EKS

**Sintoma:**  
Ao abrir a aba **Resources > Pods** do cluster EKS no console da AWS, é exibida a mensagem:
```
Error loading resources: Unauthorized
```

**Causa:**  
O cluster EKS utiliza o novo modelo de autenticação **EKS Access Entries**. O usuário IAM com o qual você fez login no Console da AWS ainda não tem permissão concedida dentro da API do Kubernetes.

**Solução Passo a Passo:**
1. No AWS Console, vá até o serviço **EKS** e clique no cluster `cluster-app-senac-aws`.
2. Acesse a aba **Access** (ao lado de *Capabilities*).
3. Na seção **Access entries**, clique no botão **Create access entry**.
4. Em **IAM principal ARN**, selecione o ARN do seu usuário IAM atual.
5. Deixe o tipo como **Standard** e clique em **Next**.
6. Na tela de permissões, adicione a política gerenciada **`AmazonEKSClusterAdminPolicy`** (ou `AmazonEKSAdminViewPolicy`).
7. Clique em **Next** e depois em **Create**.
8. Volte para a aba **Resources > Pods** e clique no botão circular de atualizar (**Refresh**). Todos os Pods aparecerão listados com status *Running*!

---

### Cenário 2: Target Group Desconectado do Auto Scaling Group (ASG)

**Sintoma:**  
A requisição ao API Gateway retorna erro **502 Bad Gateway** ou **504 Gateway Timeout**, e no Console EC2 o Target Group `senac-app-tg` não possui instâncias registradas (*Empty Targets*).

**Causa:**  
O cluster EKS cria um Auto Scaling Group (ASG) dinamicamente para gerenciar os nós EC2. No entanto, o Target Group do Network Load Balancer (NLB) escuta na porta `30080` (NodePort) e precisa ter essas instâncias EC2 associadas a ele.

**Solução Imediata via AWS CLI:**
Execute o comando abaixo para anexar o Target Group diretamente ao Auto Scaling Group do Node Group:

```bash
# 1. Localizar o nome do Auto Scaling Group do EKS
ASG_NAME=$(aws autoscaling describe-auto-scaling-groups \
  --region us-east-2 \
  --query "AutoScalingGroups[?contains(AutoScalingGroupName, 'ng-senac-app')].AutoScalingGroupName | [0]" \
  --output text)

# 2. Localizar o ARN do Target Group senac-app-tg
TG_ARN=$(aws elbv2 describe-target-groups \
  --names senac-app-tg \
  --region us-east-2 \
  --query "TargetGroups[0].TargetGroupArn" \
  --output text)

# 3. Conectar o Auto Scaling Group ao Target Group
aws autoscaling attach-load-balancer-target-groups \
  --auto-scaling-group-name "$ASG_NAME" \
  --target-group-arns "$TG_ARN" \
  --region us-east-2

# 4. Aguardar 10 segundos e verificar o status de saúde das instâncias
sleep 10
aws elbv2 describe-target-health --target-group-arn "$TG_ARN" --region us-east-2
```

> [!TIP]
> **Correção Definitiva no Terraform (`app-senac-aws-infra`):**  
> Para que essa amarração seja automática em novos provisionamentos, adicione o recurso `aws_autoscaling_attachment` no Terraform de infraestrutura:
> ```hcl
> resource "aws_autoscaling_attachment" "eks_node_group_attachment" {
>   autoscaling_group_name = module.eks.eks_managed_node_groups["app_nodes"].node_group_autoscaling_group_names[0]
>   lb_target_group_arn    = aws_lb_target_group.eks_nodes_tg.arn
> }
> ```

---

## 🧪 Passo 7: Teste Final e Demonstração do Balanceamento

Com a aplicação rodando e o Load Balancer integrado, realize as chamadas utilizando o endpoint público do **API Gateway**:

### Chamada Simples:
```bash
curl -i "https://<URL_DO_API_GATEWAY>/api/v1/hello?nome=Everton"
```

**Exemplo de Resposta:**
```http
HTTP/2 200 
content-type: text/plain;charset=UTF-8

Olá Everton, Bem-vindo a AWS | Pod: hello-aws-deployment-7b89f5bc74-4kzdp | IP: 10.0.1.182
```

---

### 🎯 Teste de Carga para Provar o Balanceamento Entre Pods:

Execute um loop no terminal para realizar 6 requisições sucessivas:

```bash
for i in {1..6}; do 
  curl -s "https://<URL_DO_API_GATEWAY>/api/v1/hello?nome=Aluno"
  echo ""
done
```

**Resultado Esperado (Demonstração Prática):**
```text
Olá Aluno, Bem-vindo a AWS | Pod: hello-aws-deployment-7b89f5bc74-4kzdp | IP: 10.0.1.182
Olá Aluno, Bem-vindo a AWS | Pod: hello-aws-deployment-7b89f5bc74-8xqw2 | IP: 10.0.2.145
Olá Aluno, Bem-vindo a AWS | Pod: hello-aws-deployment-7b89f5bc74-m3v9a | IP: 10.0.1.94
Olá Aluno, Bem-vindo a AWS | Pod: hello-aws-deployment-7b89f5bc74-4kzdp | IP: 10.0.1.182
Olá Aluno, Bem-vindo a AWS | Pod: hello-aws-deployment-7b89f5bc74-8xqw2 | IP: 10.0.2.145
Olá Aluno, Bem-vindo a AWS | Pod: hello-aws-deployment-7b89f5bc74-m3v9a | IP: 10.0.1.94
```

> [!NOTE]
> Observe a alternância dos nomes dos Pods e de seus respectivos IPs a cada requisição: **isso comprova que o Network Load Balancer e o Kubernetes estão distribuindo a carga com sucesso entre as réplicas ativas!**

---

## 🧠 Entendendo a Arquitetura: Lógica do API Gateway, Load Balancer e Pods

Esta arquitetura reflete o **padrão recomendado pela AWS** para publicação segura e resiliente de microsserviços em containers. O fluxo de dados de ponta a ponta acontece nesta ordem exata:

```text
[ 1. Usuário / Postman / Frontend ]
               │
               ▼  (Chamada HTTPS pública: https://<api-id>.execute-api.../api/v1/hello)
       [ 2. AWS API Gateway ]
               │  • Valida a rota (GET /api/v1/hello)
               │  • Aplica segurança, CORS, autenticação e rate limiting
               │
               ▼  (Túnel seguro via VPC Link)
 [ 3. AWS Network Load Balancer (NLB Privado) ]
               │  • Balanceia a carga de rede em nível L4 (TCP)
               │  • Encaminha para os nós do Kubernetes na porta do Target Group (30080)
               │
               ▼
   [ 4. Kubernetes Service (NodePort: 30080) ]
               │  • Roteamento interno do cluster EKS
               │
               ▼
      [ 5. Pods Spring Boot (Porta 8080) ]
                  └── Executa o método @GetMapping e devolve a resposta!
```

### 🏆 Por que esse desenho é o padrão ouro na indústria?

1. **Segurança Máxima (*Zero Exposure*):**
   - Nenhum Pod, nó EC2 ou Load Balancer possui IP público exposto diretamente na internet.
   - O cluster EKS e o NLB residem em **subnets privadas**.
   - O único ponto de contato público com a internet é a URL segura do **API Gateway**.
   - A comunicação entre o API Gateway e a VPC privada é feita através de um túnel fechado via **VPC Link**.

2. **Alta Disponibilidade e Resiliência em Múltiplas Camadas:**
   - **Camada 1 (API Gateway):** Gerenciado pela AWS, com redundância geográfica e tolerância a falhas nativa.
   - **Camada 2 (NLB Privado):** Distribui o tráfego de rede entre as diferentes zonas de disponibilidade (Availability Zones, ex: `us-east-2a` e `us-east-2b`).
   - **Camada 3 (Kubernetes / EKS):** Distribui o tráfego entre as **3 réplicas** dos Pods Spring Boot através do `kube-proxy` e do `Service`.

3. **Facilidade e Simplicidade de Consumo:**
   - Para quem consome o microsserviço, basta chamar a URL pública padrão fornecida no output da infraestrutura:
     ```bash
     curl "https://<SEU_API_GATEWAY_ID>.execute-api.us-east-2.amazonaws.com/api/v1/hello?nome=Everton"
     ```
   - Toda a complexidade de rede, roteamento interno, balanceamento entre nós e containers acontece nos bastidores de forma 100% transparente.

---

## 🌐 Prática de Mercado: Como Conectar um Domínio Personalizado (`api.suaempresa.com`)

Em ambientes corporativos reais, os clientes e frontends **não** acessam a URL crua da AWS (`https://d-xxxx.execute-api...`). Em vez disso, eles acessam um domínio profissional, como **`https://api.suaempresa.com/api/v1/hello`**.

> [!IMPORTANT]
> **Regra de Ouro da Arquitetura:**  
> - **Nunca** aponte um domínio público diretamente para Pods (eles são efêmeros e seus IPs mudam a cada reinicialização).  
> - **Nunca** aponte o domínio público para o Load Balancer neste modelo (o NLB é estritamente **privado** dentro da VPC).  
> - **O domínio público aponta SEMPRE para o AWS API Gateway!**

---

### 🗺️ Fluxo Completo de Ponta a Ponta com Domínio Próprio

```text
[ Usuário / Cliente digita: https://api.suaempresa.com ]
                │
                ▼
      [ Amazon Route 53 / Servidor DNS ]
                │  • Resolve o nome via registro Alias / CNAME
                ▼
       [ AWS API Gateway ]
                │  • Custom Domain Name (api.suaempresa.com)
                │  • Terminação SSL/TLS com Certificado gratuito do AWS ACM
                │
                ▼  (Túnel seguro via VPC Link)
   [ AWS NLB Interno Privado ]
                │  (Porta 30080)
                ▼
         [ Cluster EKS ]
                └── [ Pods Spring Boot (8080) ]
```

---

### 📚 Passo a Passo Didático para Configuração (Os 3 Pilares)

#### 1. Criar Certificado SSL/TLS Gratuito no AWS Certificate Manager (ACM)
Para que a API responda sob HTTPS seguro sem alertas no navegador:
1. Abra o serviço **AWS Certificate Manager (ACM)** na mesma região da API (`us-east-2`).
2. Solicite um certificado público (*Request public certificate*) para seu domínio (ex: `api.suaempresa.com` ou `*.suaempresa.com`).
3. Valide a titularidade do domínio inserindo o registro **CNAME** gerado pelo ACM no seu provedor de DNS (Route 53, Registro.br, GoDaddy, Cloudflare, etc.). Em poucos minutos o status mudará para **Issued** (Emitido).

#### 2. Configurar o "Custom Domain Name" no API Gateway
1. No menu do **API Gateway**, acesse **Custom domain names** e clique em **Create**.
2. Preencha:
   - **Domain name:** `api.suaempresa.com`
   - **ACM Certificate:** Selecione o certificado validado no passo anterior.
3. Na aba **API Mappings**, mapeie:
   - **API:** `senac-api-gateway`
   - **Stage:** `$default`
4. A AWS criará um endpoint gerenciado do CloudFront no formato:
   ```text
   d-xxxxxxxxxx.execute-api.us-east-2.amazonaws.com
   ```

#### 3. Fazer o Apontamento no Servidor de DNS
Agora basta apontar o seu domínio próprio para o endereço gerado pela AWS:

- **Se o DNS estiver no Amazon Route 53:**  
  Crie um registro do tipo **A (Alias)** apontando `api.suaempresa.com` diretamente para o Custom Domain Name do API Gateway.
- **Se o DNS estiver externo (Registro.br, Cloudflare, GoDaddy, Hostinger):**  
  Crie uma entrada do tipo **CNAME**:
  - **Nome/Host:** `api`
  - **Tipo:** `CNAME`
  - **Destino/Valor:** `d-xxxxxxxxxx.execute-api.us-east-2.amazonaws.com`

---

### 💻 Como Automatizar via Terraform (IaC no `app-senac-aws-infra`)

Se desejar versionar essa infraestrutura de domínio como código, adicione os seguintes blocos no repositório de infraestrutura:

```hcl
# 1. Custom Domain Name no API Gateway com certificado ACM
resource "aws_apigatewayv2_domain_name" "api_custom_domain" {
  domain_name = "api.suaempresa.com"

  domain_name_configuration {
    certificate_arn = "arn:aws:acm:us-east-2:<ID_DA_CONTA>:certificate/xxxx-xxxx"
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }
}

# 2. Mapeamento da rota do domínio personalizado para o API Gateway
resource "aws_apigatewayv2_api_mapping" "api_mapping" {
  api_id      = aws_apigatewayv2_api.http_api.id
  domain_name = aws_apigatewayv2_domain_name.api_custom_domain.id
  stage       = aws_apigatewayv2_stage.default.id
}

# 3. Apontamento no Amazon Route 53 (se a zona de DNS estiver na AWS)
resource "aws_route53_record" "api_dns" {
  zone_id = "ID_DA_SUA_HOSTED_ZONE"
  name    = aws_apigatewayv2_domain_name.api_custom_domain.domain_name
  type    = "A"

  alias {
    name                   = aws_apigatewayv2_domain_name.api_custom_domain.domain_name_configuration[0].target_domain_name
    zone_id                = aws_apigatewayv2_domain_name.api_custom_domain.domain_name_configuration[0].hosted_zone_id
    evaluate_target_health = false
  }
}
```

---

### 🎯 Resumo para Ensinar em Aula
> Quando o usuário acessa `https://api.suaempresa.com/api/v1/hello`, o DNS entrega a requisição ao **API Gateway**, que valida o certificado HTTPS e a rota, encaminha a chamada através do **VPC Link** para o **Network Load Balancer Privado**, que por sua vez entrega na porta `30080` do **NodePort do Kubernetes**, caindo em uma das 3 réplicas do **Pod Spring Boot**. Tudo isso com segurança *zero-trust*, alta performance e isolamento de rede!