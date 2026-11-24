package com.pivot.banking.account.infrastructure.adapter.in;

import com.pivot.banking.account.adapter.in.dto.Problem;
import com.pivot.banking.account.domain.exception.AccountNotFoundException;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.ConcurrentModificationException;
import com.pivot.banking.account.domain.exception.CustomerNotFoundException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebInputException;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for HTTP problem mapping. */
class AccountExceptionHandlerTest {

    private final AccountExceptionHandler handler = new AccountExceptionHandler();

    @Test
    void mapsAccountAndCustomerNotFoundTo404() {
        // Arrange
        UUID id = UUID.randomUUID();

        // Act
        ResponseEntity<Problem> accountProblem = handler.notFound(new AccountNotFoundException(id));
        ResponseEntity<Problem> customerProblem = handler.notFound(new CustomerNotFoundException(id));

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, accountProblem.getStatusCode());
        assertEquals(MediaType.valueOf("application/problem+json"), accountProblem.getHeaders().getContentType());
        assertEquals("Resource not found", accountProblem.getBody().getTitle());
        assertEquals(HttpStatus.NOT_FOUND, customerProblem.getStatusCode());
    }

    @Test
    void mapsConcurrentModificationTo409() {
        // Arrange
        UUID id = UUID.randomUUID();

        // Act
        ResponseEntity<Problem> problem = handler.conflict(new ConcurrentModificationException(id));

        // Assert
        assertEquals(HttpStatus.CONFLICT, problem.getStatusCode());
        assertEquals("Concurrent modification", problem.getBody().getTitle());
    }

    @Test
    void mapsBusinessRuleViolationTo422() {
        // Arrange
        // Act
        ResponseEntity<Problem> problem = handler.businessRule(new BusinessRuleViolationException("Not allowed"));

        // Assert
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, problem.getStatusCode());
        assertEquals("Business rule violated", problem.getBody().getTitle());
        assertEquals("Not allowed", problem.getBody().getDetail());
    }

    @Test
    void appendsReconciliationNoticeOnlyForAmbiguousDownstreamFailure() {
        // Arrange
        var ambiguous = new DownstreamServiceException("Timeout", true, null);
        var confirmed = new DownstreamServiceException("Rejected", false, null);

        // Act
        ResponseEntity<Problem> ambiguousProblem = handler.downstream(ambiguous);
        ResponseEntity<Problem> confirmedProblem = handler.downstream(confirmed);

        // Assert
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ambiguousProblem.getStatusCode());
        assertEquals("Timeout; operation outcome is ambiguous and requires reconciliation",
                ambiguousProblem.getBody().getDetail());
        assertEquals("Rejected", confirmedProblem.getBody().getDetail());
    }

    @Test
    void mapsIllegalArgumentTo400() {
        // Arrange
        // Act
        ResponseEntity<Problem> problem = handler.badRequest(new IllegalArgumentException("invalid page"));

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatusCode());
        assertEquals("Invalid request", problem.getBody().getTitle());
    }

    @Test
    void mapsConstraintViolationTo400() {
        // Arrange
        // Act
        ResponseEntity<Problem> problem = handler.requestParameterValidation(
                new ConstraintViolationException("invalid parameter", Set.of()));

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatusCode());
        assertEquals("Request parameters are invalid", problem.getBody().getDetail());
    }

    @Test
    void mapsUnreadableInputTo400() {
        // Arrange
        // Act
        ResponseEntity<Problem> problem = handler.unreadableInput(new ServerWebInputException("Malformed JSON"));

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, problem.getStatusCode());
        assertEquals("Malformed JSON", problem.getBody().getDetail());
    }

    @Test
    void mapsDatabaseFailureTo503() {
        // Arrange
        DataAccessException failure = new DataAccessException("database unavailable") {
        };

        // Act
        ResponseEntity<Problem> problem = handler.persistenceUnavailable(failure);

        // Assert
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, problem.getStatusCode());
        assertEquals("Account storage unavailable", problem.getBody().getTitle());
        assertEquals("The account could not be persisted at this time", problem.getBody().getDetail());
    }
}
