package tech.jojidev.postbox.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationExceptionHandler {

    public record ErrorResponse(String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(MethodArgumentNotValidException exception) {
        var error = exception.getBindingResult().getFieldErrors().getFirst();
        return ResponseEntity.badRequest().body(new ErrorResponse(error.getField() + ": " + error.getDefaultMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> malformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("Malformed JSON, unsupported channel, or invalid notification ID"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(tech.jojidev.postbox.push.PushService.Conflict.class)
    public ResponseEntity<ErrorResponse> conflict(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("Idempotency key already used for different content"));
    }

    @ExceptionHandler(tech.jojidev.postbox.push.TokenCipher.KeyUnavailable.class)
    public ResponseEntity<ErrorResponse> missingKey(Exception exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Push encryption key unavailable"));
    }
}
