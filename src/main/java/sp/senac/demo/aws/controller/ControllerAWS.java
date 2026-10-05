package sp.senac.demo.aws.controller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ControllerAWS {

    @GetMapping(value = "/hello", produces = MediaType.TEXT_HTML_VALUE)
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
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_HTML)
                .body(this.buildHtml(pessoa, hostName, ip));
    }

    private String buildHtml(String pessoa, String hostName, String ip) {
        String html = """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Microsserviço Spring Boot no AWS EKS</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap" rel="stylesheet">
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: 'Inter', sans-serif;
                        background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
                        color: #f8fafc;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    .card {
                        background: rgba(30, 41, 59, 0.7);
                        backdrop-filter: blur(12px);
                        border: 1px solid rgba(255, 255, 255, 0.1);
                        border-radius: 16px;
                        max-width: 540px;
                        width: 100%;
                        padding: 32px;
                        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5);
                    }
                    .badge {
                        display: inline-flex;
                        align-items: center;
                        gap: 6px;
                        background: rgba(245, 158, 11, 0.15);
                        color: #fbbf24;
                        font-weight: 600;
                        font-size: 0.85rem;
                        padding: 6px 14px;
                        border-radius: 9999px;
                        margin-bottom: 20px;
                        border: 1px solid rgba(245, 158, 11, 0.3);
                    }
                    .badge-dot {
                        width: 8px;
                        height: 8px;
                        background-color: #10b981;
                        border-radius: 50%;
                        box-shadow: 0 0 8px #10b981;
                    }
                    h1 {
                        font-size: 1.8rem;
                        font-weight: 700;
                        margin-bottom: 8px;
                        background: linear-gradient(to right, #38bdf8, #818cf8);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                    }
                    p.subtitle {
                        color: #94a3b8;
                        font-size: 1rem;
                        margin-bottom: 24px;
                    }
                    .info-box {
                        background: #0f172a;
                        border: 1px solid #334155;
                        border-radius: 10px;
                        padding: 16px;
                        margin-bottom: 12px;
                        display: flex;
                        flex-direction: column;
                        gap: 4px;
                    }
                    .info-label {
                        font-size: 0.75rem;
                        text-transform: uppercase;
                        letter-spacing: 0.05em;
                        color: #64748b;
                        font-weight: 600;
                    }
                    .info-value {
                        font-family: monospace;
                        font-size: 1rem;
                        color: #38bdf8;
                        word-break: break-all;
                    }
                    .footer {
                        text-align: center;
                        margin-top: 24px;
                        font-size: 0.8rem;
                        color: #64748b;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="badge">
                        <span class="badge-dot"></span>
                        AWS EKS &bull; Online
                    </div>
                    <h1>Olá, {{PESSOA}}! 👋</h1>
                    <p class="subtitle">Bem-vindo ao mundo AWS Cloud & Kubernetes.</p>
                    
                    <div class="info-box">
                        <span class="info-label">Instância / Pod Ativo</span>
                        <span class="info-value">{{HOSTNAME}}</span>
                    </div>
                    <div class="info-box">
                        <span class="info-label">Endereço IP Interno</span>
                        <span class="info-value">{{IP}}</span>
                    </div>
                    <div class="footer">
                        Deploy automatizado via GitHub Actions &bull; Versão 1.0
                    </div>
                </div>
            </body>
            </html>
            """;

        return html
                .replace("{{PESSOA}}", pessoa)
                .replace("{{HOSTNAME}}", hostName)
                .replace("{{IP}}", ip);
    }
}