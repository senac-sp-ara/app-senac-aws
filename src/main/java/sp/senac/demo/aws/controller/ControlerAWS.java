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