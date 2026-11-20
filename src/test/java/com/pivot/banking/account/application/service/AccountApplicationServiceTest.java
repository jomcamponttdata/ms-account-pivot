package com.pivot.banking.account.application.service;

import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccountCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperationCommand;
import com.pivot.banking.account.application.port.out.AccountRepositoryPort;
import com.pivot.banking.account.application.port.out.CustomerGateway;
import com.pivot.banking.account.application.port.out.MovementGateway;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import com.pivot.banking.account.domain.strategy.AccountCreationPolicyFactory;
import com.pivot.banking.account.domain.strategy.AccountOperationRuleFactory;
import com.pivot.banking.account.domain.strategy.BusinessAccountCreationPolicy;
import com.pivot.banking.account.domain.strategy.CheckingOperationRule;
import com.pivot.banking.account.domain.strategy.FixedTermOperationRule;
import com.pivot.banking.account.domain.strategy.PersonalAccountCreationPolicy;
import com.pivot.banking.account.domain.strategy.SavingsOperationRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountApplicationServiceTest {

    @Mock
    private AccountRepositoryPort repository;
    @Mock
    private CustomerGateway customers;
    @Mock
    private MovementGateway movements;

    private AccountApplicationService service;

    @BeforeEach
    void setUp() {
        var creationPolicies = new AccountCreationPolicyFactory(List.of(
                new PersonalAccountCreationPolicy(), new BusinessAccountCreationPolicy()));
        var operationRules = new AccountOperationRuleFactory(List.of(
                new SavingsOperationRule(), new CheckingOperationRule(), new FixedTermOperationRule()));
        service = new AccountApplicationService(repository, customers, movements,
                creationPolicies, operationRules, 100);
    }

    @Test
    void createsPersonalSavingsAccountWithZeroBalance() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        var command = new CreateAccountCommand(AccountType.SAVINGS, List.of(customerId),
                "PEN", null, 20, null, List.of());
        when(customers.findById(customerId))
                .thenReturn(Mono.just(new CustomerSnapshot(customerId, CustomerType.PERSONAL, true)));
        when(repository.findAll(new AccountFilter(customerId, AccountType.SAVINGS), 0, 100))
                .thenReturn(Flux.empty());
        when(repository.save(any(Account.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act
        var result = service.create(command);

        // Assert
        StepVerifier.create(result)
                .assertNext(account -> {
                    assertEquals(AccountType.SAVINGS, account.accountType());
                    assertEquals(BigDecimal.ZERO, account.balance().amount());
                    assertEquals(List.of(customerId), account.holders());
                    assertNotNull(account.createdAt());
                })
                .verifyComplete();
        verify(repository).save(any(Account.class));
    }

    @Test
    void rejectsBusinessSavingsAccountWithoutPersisting() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        var command = new CreateAccountCommand(AccountType.SAVINGS, List.of(customerId),
                "PEN", null, 10, null, List.of());
        when(customers.findById(customerId))
                .thenReturn(Mono.just(new CustomerSnapshot(customerId, CustomerType.BUSINESS, true)));

        // Act
        var result = service.create(command);

        // Assert
        StepVerifier.create(result).expectError(BusinessRuleViolationException.class).verify();
        verify(repository, never()).save(any(Account.class));
    }

    @Test
    void createsBusinessCheckingAccountWithMultipleBusinessHolders() {
        // Arrange
        UUID firstHolder = UUID.randomUUID();
        UUID secondHolder = UUID.randomUUID();
        var command = new CreateAccountCommand(AccountType.CHECKING, List.of(firstHolder, secondHolder),
                "PEN", new Money(new BigDecimal("2.50"), "PEN"), null, null, List.of());
        when(customers.findById(firstHolder))
                .thenReturn(Mono.just(new CustomerSnapshot(firstHolder, CustomerType.BUSINESS, true)));
        when(customers.findById(secondHolder))
                .thenReturn(Mono.just(new CustomerSnapshot(secondHolder, CustomerType.BUSINESS, true)));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act
        var result = service.create(command);

        // Assert
        StepVerifier.create(result)
                .assertNext(account -> {
                    assertEquals(CustomerType.BUSINESS, account.holderType());
                    assertEquals(List.of(firstHolder, secondHolder), account.holders());
                })
                .verifyComplete();
        verify(repository).save(any(Account.class));
    }

    @Test
    void rejectsInactiveHolderWithoutPersisting() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        var command = new CreateAccountCommand(AccountType.CHECKING, List.of(customerId),
                "PEN", new Money(new BigDecimal("2.50"), "PEN"), null, null, List.of());
        when(customers.findById(customerId))
                .thenReturn(Mono.just(new CustomerSnapshot(customerId, CustomerType.PERSONAL, false)));

        // Act
        var result = service.create(command);

        // Assert
        StepVerifier.create(result).expectError(BusinessRuleViolationException.class).verify();
        verify(repository, never()).save(any(Account.class));
    }

    @Test
    void persistsBalanceBeforeReturningSuccessfulMovementReceipt() {
        // Arrange
        var account = account(new BigDecimal("100.00"), 0L);
        var amount = new Money(new BigDecimal("25.00"), "PEN");
        var receipt = new MovementReceipt("movement-1", MovementType.DEPOSIT,
                amount.amount(), amount.currency(), Instant.now());
        when(repository.findById(account.id())).thenReturn(Mono.just(account));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            return Mono.just(saved.withVersion(saved.version() + 1));
        });
        when(movements.register(any(Account.class), any(MovementType.class), any(Money.class), any()))
                .thenReturn(Mono.just(receipt));

        // Act
        var result = service.operate(account.id(), MovementType.DEPOSIT,
                new OperationCommand(amount, "Cash deposit"));

        // Assert
        StepVerifier.create(result).expectNext(receipt).verifyComplete();
        var savedAccount = ArgumentCaptor.forClass(Account.class);
        verify(repository).save(savedAccount.capture());
        assertEquals(new BigDecimal("125.00"), savedAccount.getValue().balance().amount());
        verify(movements).register(savedAccount.capture(), org.mockito.ArgumentMatchers.eq(MovementType.DEPOSIT),
                org.mockito.ArgumentMatchers.eq(amount), org.mockito.ArgumentMatchers.eq("Cash deposit"));
    }

    @Test
    void compensatesBalanceAfterConfirmedMovementFailure() {
        // Arrange
        var account = account(new BigDecimal("100.00"), 0L);
        var amount = new Money(new BigDecimal("25.00"), "PEN");
        when(repository.findById(account.id())).thenReturn(Mono.just(account));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            return Mono.just(saved.withVersion(saved.version() + 1));
        });
        when(movements.register(any(Account.class), any(MovementType.class), any(Money.class), any()))
                .thenReturn(Mono.error(new DownstreamServiceException("Rejected", false, null)));

        // Act
        var result = service.operate(account.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

        // Assert
        StepVerifier.create(result).expectError(DownstreamServiceException.class).verify();
        var savedAccounts = ArgumentCaptor.forClass(Account.class);
        verify(repository, times(2)).save(savedAccounts.capture());
        assertEquals(new BigDecimal("125.00"), savedAccounts.getAllValues().get(0).balance().amount());
        assertEquals(new BigDecimal("100.00"), savedAccounts.getAllValues().get(1).balance().amount());
        assertEquals(0, savedAccounts.getAllValues().get(1).movementsInCurrentMonth());
    }

    @Test
    void doesNotCompensateWhenMovementOutcomeIsAmbiguous() {
        // Arrange
        var account = account(new BigDecimal("100.00"), 0L);
        var amount = new Money(new BigDecimal("25.00"), "PEN");
        when(repository.findById(account.id())).thenReturn(Mono.just(account));
        when(repository.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            return Mono.just(saved.withVersion(saved.version() + 1));
        });
        when(movements.register(any(Account.class), any(MovementType.class), any(Money.class), any()))
                .thenReturn(Mono.error(new DownstreamServiceException("Timeout", true, null)));

        // Act
        var result = service.operate(account.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

        // Assert
        StepVerifier.create(result).expectErrorMatches(error ->
                error instanceof DownstreamServiceException failure && failure.isAmbiguous()).verify();
        verify(repository).save(any(Account.class));
    }

    private Account account(BigDecimal balance, Long version) {
        return new Account(UUID.randomUUID(), "1234567890123456", AccountType.SAVINGS,
                new Money(balance, "PEN"), List.of(UUID.randomUUID()), CustomerType.PERSONAL, List.of(),
                null, 10, null, 0, YearMonth.from(LocalDate.now()), version, Instant.now());
    }
}
