package com.genvn.api;

import com.genvn.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A model that returns nonsense must produce a clean 502, never a stack trace and never a
 * corrupted session. Canonical state is only ever written after a successful generation, so
 * anything that lands here has left the save untouched.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CreationQueueFullException.class)
    public ResponseEntity<Dtos.ErrorView> creationQueueFull(CreationQueueFullException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5")
                .body(new Dtos.ErrorView("creation_queue_full", e.getMessage()));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Dtos.ErrorView> sessionNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Dtos.ErrorView("session_not_found", e.getMessage()));
    }

    @ExceptionHandler(PendingRollException.class)
    public ResponseEntity<Dtos.ErrorView> rollPending(PendingRollException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Dtos.ErrorView("roll_pending", e.getMessage()));
    }

    @ExceptionHandler(SceneConflictException.class)
    public ResponseEntity<Dtos.ErrorView> conflict(SceneConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Dtos.ErrorView("scene_conflict", e.getMessage()));
    }

    @ExceptionHandler(ChoiceResolvingException.class)
    public ResponseEntity<Dtos.ErrorView> choiceResolving(ChoiceResolvingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "2")
                .body(new Dtos.ErrorView("choice_resolving", e.getMessage()));
    }

    @ExceptionHandler(RestructureInProgressException.class)
    public ResponseEntity<Dtos.ErrorView> restructureInProgress(RestructureInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "3")
                .body(new Dtos.ErrorView("restructure_in_progress", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Dtos.ErrorView> unreadableRequest(Exception e) {
        return ResponseEntity.badRequest().body(new Dtos.ErrorView("invalid_request", "A valid JSON request body is required."));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Dtos.ErrorView> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Dtos.ErrorView("not_found", e.getMessage()));
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Dtos.ErrorView> llm(LlmException e) {
        log.warn("LLM failure surfaced to the client: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new Dtos.ErrorView("llm_failed",
                        "The story generator could not produce a usable scene. Your game is unchanged -- try again. ("
                                + e.getMessage() + ")"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dtos.ErrorView> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("request was not valid");
        return ResponseEntity.badRequest().body(new Dtos.ErrorView("invalid_request", detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorView> illegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new Dtos.ErrorView("invalid_request", e.getMessage()));
    }

    /** A query parameter of the wrong type or missing is the client's mistake, not a server failure. */
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<Dtos.ErrorView> badParameter(Exception e) {
        return ResponseEntity.badRequest().body(new Dtos.ErrorView("invalid_request",
                "A request parameter was missing or had the wrong type."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorView> unexpected(Exception e) {
        log.error("Unexpected failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorView("internal_error",
                        "Unexpected " + e.getClass().getSimpleName() + "; details are in the backend log."));
    }
}
