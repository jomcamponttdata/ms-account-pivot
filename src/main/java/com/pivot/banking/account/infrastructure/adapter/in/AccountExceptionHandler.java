package com.pivot.banking.account.infrastructure.adapter.in;

import com.pivot.banking.account.adapter.in.dto.Problem;
import com.pivot.banking.account.domain.exception.AccountNotFoundException;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.ConcurrentModificationException;
import com.pivot.banking.account.domain.exception.CustomerNotFoundException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts application and downstream errors to documented problem responses. */
@RestControllerAdvice
public class AccountExceptionHandler {

    /** Returns 404 when an account or referenced customer cannot be found. */
    @ExceptionHandler({AccountNotFoundException.class, CustomerNotFoundException.class})
    public ResponseEntity<Problem> notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    /** Returns 409 when an account was changed concurrently. */
    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<Problem> conflict(ConcurrentModificationException exception) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification", exception.getMessage());
    }

    /** Returns 422 when an account operation violates a business rule. */
    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<Problem> businessRule(BusinessRuleViolationException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violated", exception.getMessage());
    }

    /** Returns 503 when a dependent service cannot complete the operation. */
    @ExceptionHandler(DownstreamServiceException.class)
    public ResponseEntity<Problem> downstream(DownstreamServiceException exception) {
        String detail = exception.isAmbiguous()
                ? exception.getMessage() + "; operation outcome is ambiguous and requires reconciliation"
                : exception.getMessage();
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Dependent service unavailable", detail);
    }

    /** Returns 400 for invalid request values. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Problem> badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
    }

    /** Builds a response body and status for a problem detail. */
    private ResponseEntity<Problem> problem(HttpStatus status, String title, String detail) {
        Problem body = new Problem(title, status.value()).type("about:blank").detail(detail);
        return ResponseEntity.status(status).contentType(MediaType.valueOf("application/problem+json")).body(body);
    }
}
