package br.com.tradingbot.forex;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Localhost-only Forex research endpoints. Expose the first meaningful
 * failure reason in JSON instead of forcing the operator to scroll past a
 * hundred irrelevant Tomcat stack frames. Does not include stack traces,
 * passwords, environment properties or server filesystem paths.
 */
@RestControllerAdvice(assignableTypes=ForexResearchController.class)
public final class ForexResearchErrorHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ForexResearchErrorHandler.class);

    public record ErrorBody(String category,String detail,
            String rootCause,Instant timestamp) {}

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> forexFailure(Exception failure) {
        Throwable root=failure;
        int depth=0;
        while(root.getCause()!=null &&root.getCause()!=root &&depth++<16)
            root=root.getCause();
        LOG.error("Forex history research failed",failure);
        HttpStatus status;
        if(failure instanceof IllegalArgumentException) {
            status=HttpStatus.UNPROCESSABLE_ENTITY;
        }else if(failure instanceof IllegalStateException
                && root instanceof IOException) {
            status=HttpStatus.BAD_GATEWAY;
        }else {
            status=HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String problem=limit(failure.getMessage());
        String primary=limit(root.getMessage());
        return ResponseEntity.status(status).body(new ErrorBody(
                failure.getClass().getSimpleName(),problem,
                primary,Instant.now()));
    }
    private static String limit(String source) {
        if(source==null)return "(no error message)";
        // Error text may include URL or local absolute file locations.
        // localhost caller can inspect full stack trace in server logs.
        String safe=source.replaceAll("(?i)(password|api[_-]?key|token)\\s*[=:]\\s*\\S+",
                "$1=[REDACTED]");
        return safe.length()>450?safe.substring(0,450):safe;
    }
}
