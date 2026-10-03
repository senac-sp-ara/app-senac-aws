package sp.senac.demo.aws.controller;

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
    public ResponseEntity<String> hello(@RequestParam String nome) {
        return ResponseEntity.status(HttpStatus.OK.value())
                .body("Olá " + nome + ", Bem-vindo ao mundo AWS - Versão 1.0");
    }

}