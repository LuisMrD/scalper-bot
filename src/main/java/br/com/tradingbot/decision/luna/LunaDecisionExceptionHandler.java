package br.com.tradingbot.decision.luna;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Nao devolve payload externo, chave, headers de autorizacao ou conteudo de prompts. */
@RestControllerAdvice(assignableTypes = LunaDecisionController.class)
public class LunaDecisionExceptionHandler {
    @ExceptionHandler(LunaUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(LunaUnavailableException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Luna desabilitado ou indisponivel; nenhuma operacao foi executada"));
    }
}
