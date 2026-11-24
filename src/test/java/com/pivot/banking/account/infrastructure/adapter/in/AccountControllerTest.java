package com.pivot.banking.account.infrastructure.adapter.in;

import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccountCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.DeleteAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.GetAccounts;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperationCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccountCommand;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.AccountNotFoundException;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.ConcurrentModificationException;
import com.pivot.banking.account.domain.exception.CustomerNotFoundException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    private static final String ACCOUNTS_URI = "/api/v1/accounts";

    @Mock
    private CreateAccount createAccountUseCase;
    @Mock
    private GetAccounts getAccounts;
    @Mock
    private UpdateAccount updateAccountUseCase;
    @Mock
    private DeleteAccount deleteAccountUseCase;
    @Mock
    private OperateAccount operateAccount;
    @Spy
    private AccountRestMapper mapper = new AccountRestMapper();
    @InjectMocks
    private AccountController controller = new AccountController(
            null, null, null, null, null, new AccountRestMapper(), 0, 20);

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        controller = new AccountController(createAccountUseCase, getAccounts, updateAccountUseCase,
                deleteAccountUseCase, operateAccount, mapper, 0, 20);
        webTestClient = WebTestClient.bindToController(controller)
                .controllerAdvice(new AccountExceptionHandler())
                .build();
    }

    @Test
    void createsAccountAndReturnsItsLocation() {
        // Arrange
        UUID holderId = UUID.randomUUID();
        Account account = account(AccountType.SAVINGS, holderId);
        Account created = new Account(account.id(), account.accountNumber(), account.accountType(),
                new Money(BigDecimal.ZERO, "PEN"), account.holders(), account.holderType(),
                account.authorizedSigners(), account.maintenanceFee(), account.maxMonthlyMovements(),
                account.allowedMovementDay(), account.movementsInCurrentMonth(), account.movementsPeriod(),
                account.version(), account.createdAt());
        when(createAccountUseCase.create(any(CreateAccountCommand.class))).thenReturn(Single.just(created));
        String request = savingsRequest(holderId, 12);

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post().uri(ACCOUNTS_URI)
                .header("X-Correlation-ID", "account-request-123")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isCreated()
                .expectHeader().location(ACCOUNTS_URI + "/" + account.id());
        response.expectBody()
                .jsonPath("$.id").isEqualTo(account.id().toString())
                .jsonPath("$.accountType").isEqualTo("SAVINGS")
                .jsonPath("$.balance").isEqualTo(0.00)
                .jsonPath("$.maxMonthlyMovements").isEqualTo(12);
        var command = ArgumentCaptor.forClass(CreateAccountCommand.class);
        verify(createAccountUseCase).create(command.capture());
        assertEquals(AccountType.SAVINGS, command.getValue().accountType());
        assertEquals(List.of(holderId), command.getValue().holders());
        assertEquals(12, command.getValue().maxMonthlyMovements());
        assertEquals("account-request-123", command.getValue().correlationId());
        assertNull(command.getValue().maintenanceFee());
    }

    @Test
    void listsAccountsUsingRequestedFiltersAndPaging() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        Account account = account(AccountType.SAVINGS, customerId);
        when(getAccounts.findAll(new AccountFilter(customerId, AccountType.SAVINGS), 2, 5))
                .thenReturn(Flowable.just(account));

        // Act
        WebTestClient.ResponseSpec response = webTestClient.get().uri(uriBuilder -> uriBuilder
                        .path(ACCOUNTS_URI)
                        .queryParam("customerId", customerId)
                        .queryParam("accountType", "SAVINGS")
                        .queryParam("page", 2)
                        .queryParam("size", 5)
                        .build())
                .exchange();

        // Assert
        response.expectStatus().isOk().expectBody()
                .jsonPath("$[0].id").isEqualTo(account.id().toString())
                .jsonPath("$[0].holders[0]").isEqualTo(customerId.toString())
                .jsonPath("$[1]").doesNotExist();
        verify(getAccounts).findAll(new AccountFilter(customerId, AccountType.SAVINGS), 2, 5);
    }

    @Test
    void usesConfiguredPagingDefaultsAndAllowsEmptyFilters() {
        // Arrange
        AccountController configuredController = new AccountController(createAccountUseCase, getAccounts,
                updateAccountUseCase, deleteAccountUseCase, operateAccount, new AccountRestMapper(), 3, 7);
        when(getAccounts.findAll(new AccountFilter(null, null), 3, 7)).thenReturn(Flowable.empty());

        // Act
        Mono<org.springframework.http.ResponseEntity<Flux<com.pivot.banking.account.adapter.in.dto.Account>>> result =
                configuredController.findAllAccounts(null, null, null, null, null);

        // Assert
        StepVerifier.create(result).assertNext(response -> {
            assertEquals(HttpStatus.OK, response.getStatusCode());
            StepVerifier.create(response.getBody()).verifyComplete();
        }).verifyComplete();
        verify(getAccounts).findAll(new AccountFilter(null, null), 3, 7);
    }

    @Test
    void findsAccountById() {
        // Arrange
        Account account = account(AccountType.CHECKING, UUID.randomUUID());
        when(getAccounts.findById(account.id())).thenReturn(Single.just(account));

        // Act
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(ACCOUNTS_URI + "/{accountId}", account.id()).exchange();

        // Assert
        response.expectStatus().isOk().expectBody()
                .jsonPath("$.id").isEqualTo(account.id().toString())
                .jsonPath("$.accountType").isEqualTo("CHECKING")
                .jsonPath("$.currency").isEqualTo("PEN");
        verify(getAccounts).findById(account.id());
    }

    @Test
    void mapsMissingAccountToProblemResponse() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        when(getAccounts.findById(accountId)).thenReturn(Single.error(new AccountNotFoundException(accountId)));

        // Act
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(ACCOUNTS_URI + "/{accountId}", accountId).exchange();

        // Assert
        response.expectStatus().isNotFound()
                .expectHeader().contentType("application/problem+json");
        response.expectBody()
                .jsonPath("$.title").isEqualTo("Resource not found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Account not found: " + accountId);
    }

    @Test
    void returnsCurrentBalance() {
        // Arrange
        Account account = account(AccountType.SAVINGS, UUID.randomUUID());
        when(getAccounts.findById(account.id())).thenReturn(Single.just(account));

        // Act
        WebTestClient.ResponseSpec response = webTestClient.get()
                .uri(ACCOUNTS_URI + "/{accountId}/balance", account.id()).exchange();

        // Assert
        response.expectStatus().isOk().expectBody()
                .jsonPath("$.productId").isEqualTo(account.id().toString())
                .jsonPath("$.productType").isEqualTo("BANK_ACCOUNT")
                .jsonPath("$.balance").isEqualTo(100.50)
                .jsonPath("$.currency").isEqualTo("PEN");
    }

    @Test
    void updatesAccountUsingMappedEditableFields() {
        // Arrange
        Account account = account(AccountType.SAVINGS, UUID.randomUUID());
        UUID replacementHolder = UUID.randomUUID();
        when(updateAccountUseCase.update(eq(account.id()), any(UpdateAccountCommand.class)))
                .thenReturn(Single.just(account));
        String request = """
                {"holders":["%s"],"maxMonthlyMovements":25}
                """.formatted(replacementHolder);

        // Act
        WebTestClient.ResponseSpec response = webTestClient.put()
                .uri(ACCOUNTS_URI + "/{accountId}", account.id())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isOk().expectBody().jsonPath("$.id").isEqualTo(account.id().toString());
        var command = ArgumentCaptor.forClass(UpdateAccountCommand.class);
        verify(updateAccountUseCase).update(eq(account.id()), command.capture());
        assertEquals(List.of(replacementHolder), command.getValue().holders());
        assertEquals(25, command.getValue().maxMonthlyMovements());
        assertNull(command.getValue().maintenanceFee());
    }

    @Test
    void mapsConcurrentUpdateToConflict() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        UUID holderId = UUID.randomUUID();
        when(updateAccountUseCase.update(eq(accountId), any(UpdateAccountCommand.class)))
                .thenReturn(Single.error(new ConcurrentModificationException(accountId)));
        String request = """
                {"holders":["%s"],"maxMonthlyMovements":25}
                """.formatted(holderId);

        // Act
        WebTestClient.ResponseSpec response = webTestClient.put()
                .uri(ACCOUNTS_URI + "/{accountId}", accountId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody()
                .jsonPath("$.title").isEqualTo("Concurrent modification")
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").isEqualTo("Account was modified concurrently: " + accountId);
    }

    @Test
    void deletesAccountAndReturnsNoContent() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        when(deleteAccountUseCase.delete(accountId)).thenReturn(Completable.complete());

        // Act
        WebTestClient.ResponseSpec response = webTestClient.delete()
                .uri(ACCOUNTS_URI + "/{accountId}", accountId).exchange();

        // Assert
        response.expectStatus().isNoContent().expectBody().isEmpty();
        verify(deleteAccountUseCase).delete(accountId);
    }

    @Test
    void depositsMoneyAndReturnsCreatedReceipt() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        MovementReceipt receipt = receipt(MovementType.DEPOSIT);
        when(operateAccount.operate(eq(accountId), eq(MovementType.DEPOSIT), any(OperationCommand.class)))
                .thenReturn(Single.just(receipt));
        String request = """
                {"amount":25.75,"description":"Cash deposit"}
                """;

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri(ACCOUNTS_URI + "/{accountId}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isCreated().expectBody()
                .jsonPath("$.movementId").isEqualTo(receipt.movementId())
                .jsonPath("$.movementType").isEqualTo("DEPOSIT")
                .jsonPath("$.amount").isEqualTo(25.75)
                .jsonPath("$.currency").isEqualTo("PEN");
        var command = ArgumentCaptor.forClass(OperationCommand.class);
        verify(operateAccount).operate(eq(accountId), eq(MovementType.DEPOSIT), command.capture());
        assertEquals(new Money(new BigDecimal("25.75"), "PEN"), command.getValue().amount());
        assertEquals("Cash deposit", command.getValue().description());
    }

    @Test
    void withdrawsMoneyAndUsesRequestedCurrency() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        MovementReceipt receipt = new MovementReceipt("withdrawal-1", MovementType.WITHDRAWAL,
                new BigDecimal("10.00"), "USD", Instant.parse("2026-01-05T10:15:30Z"));
        when(operateAccount.operate(eq(accountId), eq(MovementType.WITHDRAWAL), any(OperationCommand.class)))
                .thenReturn(Single.just(receipt));
        String request = """
                {"amount":10.00,"currency":"USD","description":"ATM withdrawal"}
                """;

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri(ACCOUNTS_URI + "/{accountId}/withdrawals", accountId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isCreated().expectBody()
                .jsonPath("$.movementId").isEqualTo("withdrawal-1")
                .jsonPath("$.movementType").isEqualTo("WITHDRAWAL")
                .jsonPath("$.currency").isEqualTo("USD");
        var command = ArgumentCaptor.forClass(OperationCommand.class);
        verify(operateAccount).operate(eq(accountId), eq(MovementType.WITHDRAWAL), command.capture());
        assertEquals(new Money(new BigDecimal("10.00"), "USD"), command.getValue().amount());
    }

    @Test
    void mapsBusinessOperationRejectionToUnprocessableEntity() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        when(operateAccount.operate(eq(accountId), eq(MovementType.DEPOSIT), any(OperationCommand.class)))
                .thenReturn(Single.error(new BusinessRuleViolationException("Monthly movement limit exceeded")));
        String request = "{\"amount\":25.75}";

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri(ACCOUNTS_URI + "/{accountId}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY).expectBody()
                .jsonPath("$.title").isEqualTo("Business rule violated")
                .jsonPath("$.status").isEqualTo(422)
                .jsonPath("$.detail").isEqualTo("Monthly movement limit exceeded");
    }

    @Test
    void mapsAmbiguousMovementFailureToReconciliationProblem() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        when(operateAccount.operate(eq(accountId), eq(MovementType.WITHDRAWAL), any(OperationCommand.class)))
                .thenReturn(Single.error(new DownstreamServiceException("Movement registration timed out", true, null)));
        String request = "{\"amount\":10.00}";

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri(ACCOUNTS_URI + "/{accountId}/withdrawals", accountId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE).expectBody()
                .jsonPath("$.title").isEqualTo("Dependent service unavailable")
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo("Movement registration timed out; "
                        + "operation outcome is ambiguous and requires reconciliation");
    }

    @Test
    void mapsConfirmedDownstreamFailureWithoutAmbiguitySuffix() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        when(operateAccount.operate(eq(accountId), eq(MovementType.DEPOSIT), any(OperationCommand.class)))
                .thenReturn(Single.error(new DownstreamServiceException("Movement rejected", false, null)));
        String request = "{\"amount\":25.75}";

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri(ACCOUNTS_URI + "/{accountId}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE).expectBody()
                .jsonPath("$.title").isEqualTo("Dependent service unavailable")
                .jsonPath("$.detail").isEqualTo("Movement rejected");
    }

    @Test
    void mapsInvalidRequestValuesToBadRequest() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        String request = "{\"amount\":25.75,\"currency\":\"pen\"}";

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post()
                .uri(ACCOUNTS_URI + "/{accountId}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isBadRequest().expectBody()
                .jsonPath("$.title").isEqualTo("Invalid request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").exists();
        verify(operateAccount, never()).operate(any(), any(), any());
    }

    @Test
    void mapsMissingCustomerDuringCreationToNotFound() {
        // Arrange
        UUID holderId = UUID.randomUUID();
        when(createAccountUseCase.create(any(CreateAccountCommand.class)))
                .thenReturn(Single.error(new CustomerNotFoundException(holderId)));
        String request = savingsRequest(holderId, 12);

        // Act
        WebTestClient.ResponseSpec response = webTestClient.post().uri(ACCOUNTS_URI)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange();

        // Assert
        response.expectStatus().isNotFound().expectBody()
                .jsonPath("$.title").isEqualTo("Resource not found")
                .jsonPath("$.detail").isEqualTo("Customer not found: " + holderId);
    }

    private Account account(AccountType type, UUID holderId) {
        return new Account(UUID.randomUUID(), "19100012345678", type,
                new Money(new BigDecimal("100.50"), "PEN"), List.of(holderId), CustomerType.PERSONAL,
                List.of(), type == AccountType.CHECKING ? new Money(new BigDecimal("2.50"), "PEN") : null,
                type == AccountType.SAVINGS ? 12 : null, null, 0, YearMonth.parse("2026-01"), 1L,
                Instant.parse("2026-01-02T03:04:05Z"));
    }

    private MovementReceipt receipt(MovementType type) {
        return new MovementReceipt("movement-1", type, new BigDecimal("25.75"), "PEN",
                Instant.parse("2026-01-05T10:15:30Z"));
    }

    private String savingsRequest(UUID holderId, int maxMonthlyMovements) {
        return """
                {"accountType":"SAVINGS","holders":["%s"],"currency":"PEN","maxMonthlyMovements":%d}
                """.formatted(holderId, maxMonthlyMovements);
    }
}
