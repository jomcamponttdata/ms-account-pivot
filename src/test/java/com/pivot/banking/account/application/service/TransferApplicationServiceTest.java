package com.pivot.banking.account.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pivot.banking.account.application.port.in.AccountUseCases.TransferCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.TransferReceipt;
import com.pivot.banking.account.application.port.out.AccountRepositoryPort;
import com.pivot.banking.account.application.port.out.CreditCardGateway;
import com.pivot.banking.account.application.port.out.CustomerGateway;
import com.pivot.banking.account.application.port.out.MovementGateway;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.AccountNotFoundException;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import com.pivot.banking.account.domain.strategy.AccountCreationPolicyFactory;
import com.pivot.banking.account.domain.strategy.AccountOperationRuleFactory;
import com.pivot.banking.account.domain.strategy.AccountProfilePolicyFactory;
import com.pivot.banking.account.domain.strategy.CheckingExcessFeePolicy;
import com.pivot.banking.account.domain.strategy.CheckingOpeningMinimumPolicy;
import com.pivot.banking.account.domain.strategy.ExcessMovementFeePolicyFactory;
import com.pivot.banking.account.domain.strategy.FixedTermExcessFeePolicy;
import com.pivot.banking.account.domain.strategy.FixedTermOpeningMinimumPolicy;
import com.pivot.banking.account.domain.strategy.OpeningMinimumPolicyFactory;
import com.pivot.banking.account.domain.strategy.SavingsExcessFeePolicy;
import com.pivot.banking.account.domain.strategy.SavingsOpeningMinimumPolicy;
import com.pivot.banking.account.domain.strategy.TransferPolicy;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Application-service tests for the fully reactive transfer work unit. */
@ExtendWith(MockitoExtension.class)
class TransferApplicationServiceTest {

  @Mock private AccountRepositoryPort repository;
  @Mock private CustomerGateway customers;
  @Mock private CreditCardGateway creditCards;
  @Mock private MovementGateway movements;

  private AccountApplicationService service;
  private Account source;
  private Account destination;
  private UUID sourceHolder;
  private UUID destinationHolder;

  @BeforeEach
  void setUp() {
    sourceHolder = UUID.randomUUID();
    destinationHolder = UUID.randomUUID();
    source = account(AccountType.CHECKING, "100.00", sourceHolder, 0, 0L);
    destination = account(AccountType.SAVINGS, "20.00", destinationHolder, 0, 0L);
    service = newService(zeroFees());
  }

  private AccountApplicationService newService(ExcessMovementFeePolicyFactory fees) {
    return new AccountApplicationService(
        repository,
        customers,
        creditCards,
        movements,
        new AccountCreationPolicyFactory(List.of()),
        new AccountProfilePolicyFactory(List.of()),
        new AccountOperationRuleFactory(List.of()),
        fees,
        new OpeningMinimumPolicyFactory(
            List.of(
                new SavingsOpeningMinimumPolicy(BigDecimal.ZERO),
                new CheckingOpeningMinimumPolicy(BigDecimal.ZERO),
                new FixedTermOpeningMinimumPolicy(BigDecimal.ZERO))),
        new TransferPolicy(fees),
        100);
  }

  private ExcessMovementFeePolicyFactory zeroFees() {
    return new ExcessMovementFeePolicyFactory(
        List.of(
            new SavingsExcessFeePolicy(BigDecimal.ZERO),
            new CheckingExcessFeePolicy(0, BigDecimal.ZERO),
            new FixedTermExcessFeePolicy()));
  }

  private ExcessMovementFeePolicyFactory chargedFees() {
    return new ExcessMovementFeePolicyFactory(
        List.of(
            new SavingsExcessFeePolicy(new BigDecimal("1.50")),
            new CheckingExcessFeePolicy(0, new BigDecimal("0.75")),
            new FixedTermExcessFeePolicy()));
  }

  private Money amount() {
    return new Money(new BigDecimal("25.00"), "PEN");
  }

  private TransferCommand command() {
    return new TransferCommand(
        source.id(), destination.id(), amount(), "Rent payment", "transfer-123");
  }

  private void stubLookupAndPersistence() {
    when(repository.findById(source.id())).thenReturn(Maybe.just(source));
    when(repository.findById(destination.id())).thenReturn(Maybe.just(destination));
    when(repository.transfer(any(Account.class), any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account savedSource = invocation.getArgument(0);
              Account savedDestination = invocation.getArgument(1);
              return Single.just(
                  List.of(
                      savedSource.withVersion(savedSource.version() + 1),
                      savedDestination.withVersion(savedDestination.version() + 1)));
            });
  }

  private MovementReceipt receipt(String id, MovementType type) {
    return new MovementReceipt(
        id, type, amount().amount(), "PEN", Instant.parse("2026-01-05T10:15:30Z"));
  }

  private TransferReceipt valueOf(Single<TransferReceipt> result) {
    return result.test().assertValueCount(1).assertComplete().values().get(0);
  }

  @Test
  void transfersBetweenAccountsOfTheSameCustomerWithOneAtomicWrite() {
    // Arrange
    Account sharedHolderSource = account(AccountType.CHECKING, "100.00", sourceHolder, 0, 0L);
    Account sharedHolderDestination = account(AccountType.SAVINGS, "20.00", sourceHolder, 0, 0L);
    when(repository.findById(sharedHolderSource.id())).thenReturn(Maybe.just(sharedHolderSource));
    when(repository.findById(sharedHolderDestination.id()))
        .thenReturn(Maybe.just(sharedHolderDestination));
    when(repository.transfer(any(Account.class), any(Account.class)))
        .thenAnswer(
            invocation ->
                Single.just(List.of(invocation.getArgument(0), invocation.getArgument(1))));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt("withdrawal-1", MovementType.WITHDRAWAL)))
        .thenReturn(Single.just(receipt("deposit-1", MovementType.DEPOSIT)));
    var command =
        new TransferCommand(
            sharedHolderSource.id(),
            sharedHolderDestination.id(),
            amount(),
            "Top-up",
            "corr-same-customer");

    // Act
    TransferReceipt result = valueOf(service.transfer(command));

    // Assert
    assertEquals("withdrawal-1", result.withdrawal().movementId());
    assertEquals(MovementType.WITHDRAWAL, result.withdrawal().movementType());
    assertEquals("deposit-1", result.deposit().movementId());
    assertEquals(MovementType.DEPOSIT, result.deposit().movementType());
    assertEquals("corr-same-customer", result.correlationId());
    verify(repository).transfer(any(Account.class), any(Account.class));
    verify(repository, never()).save(any(Account.class));
    var savedSource = ArgumentCaptor.forClass(Account.class);
    var savedDestination = ArgumentCaptor.forClass(Account.class);
    verify(movements)
        .register(
            savedSource.capture(),
            eq(MovementType.WITHDRAWAL),
            eq(amount()),
            eq("Top-up"),
            eq("corr-same-customer"));
    verify(movements)
        .register(
            savedDestination.capture(),
            eq(MovementType.DEPOSIT),
            eq(amount()),
            eq("Top-up"),
            eq("corr-same-customer"));
    assertEquals(new BigDecimal("75.00"), savedSource.getValue().balance().amount());
    assertEquals(new BigDecimal("45.00"), savedDestination.getValue().balance().amount());
  }

  @Test
  void transfersToThirdPartyAccountWithoutSharedHolders() {
    // Arrange
    stubLookupAndPersistence();
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt("withdrawal-2", MovementType.WITHDRAWAL)))
        .thenReturn(Single.just(receipt("deposit-2", MovementType.DEPOSIT)));

    // Act
    TransferReceipt result = valueOf(service.transfer(command()));

    // Assert
    assertFalse(source.holders().equals(destination.holders()));
    assertEquals("deposit-2", result.deposit().movementId());
    verify(repository).transfer(any(Account.class), any(Account.class));
    verify(repository, never()).restore(any(Account.class), any(Account.class));
  }

  @Test
  void registersWithdrawalBeforeDepositUsingTheSameCorrelationId() {
    // Arrange
    stubLookupAndPersistence();
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt("withdrawal-3", MovementType.WITHDRAWAL)))
        .thenReturn(Single.just(receipt("deposit-3", MovementType.DEPOSIT)));

    // Act
    valueOf(service.transfer(command()));

    // Assert
    InOrder order = inOrder(movements);
    order
        .verify(movements)
        .register(
            argThat(account -> source.id().equals(account.id())),
            eq(MovementType.WITHDRAWAL),
            eq(amount()),
            eq("Rent payment"),
            eq("transfer-123"));
    order
        .verify(movements)
        .register(
            argThat(account -> destination.id().equals(account.id())),
            eq(MovementType.DEPOSIT),
            eq(amount()),
            eq("Rent payment"),
            eq("transfer-123"));
  }

  @Test
  void reportsMissingSourceOrDestinationWithoutPersisting() {
    // Arrange
    UUID missing = UUID.randomUUID();
    when(repository.findById(source.id())).thenReturn(Maybe.just(source));
    when(repository.findById(missing)).thenReturn(Maybe.empty());
    var missingDestination =
        new TransferCommand(source.id(), missing, amount(), null, "corr-missing");

    // Act
    var found =
        service.transfer(
            new TransferCommand(missing, destination.id(), amount(), null, "corr-missing"));
    var missingTarget = service.transfer(missingDestination);

    // Assert
    found.test().assertError(AccountNotFoundException.class);
    missingTarget.test().assertError(AccountNotFoundException.class);
    verify(repository, never()).transfer(any(Account.class), any(Account.class));
    verify(movements, never())
        .register(any(Account.class), any(MovementType.class), any(Money.class), any(), any());
  }

  @Test
  void rejectsTransferToTheSameAccount() {
    // Arrange
    when(repository.findById(source.id())).thenReturn(Maybe.just(source));

    // Act
    var result =
        service.transfer(
            new TransferCommand(source.id(), source.id(), amount(), null, "corr-same"));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).transfer(any(Account.class), any(Account.class));
    verify(movements, never())
        .register(any(Account.class), any(MovementType.class), any(Money.class), any(), any());
  }

  @Test
  void rejectsCurrencyMismatchBeforePersisting() {
    // Arrange
    when(repository.findById(source.id())).thenReturn(Maybe.just(source));
    when(repository.findById(destination.id())).thenReturn(Maybe.just(destination));
    var dollarAmount = new Money(new BigDecimal("25.00"), "USD");
    var command =
        new TransferCommand(source.id(), destination.id(), dollarAmount, null, "corr-currency");

    // Act
    var result = service.transfer(command);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error.getMessage().contains("currencies"));
    verify(repository, never()).transfer(any(Account.class), any(Account.class));
  }

  @Test
  void rejectsInsufficientFundsBeforePersisting() {
    // Arrange
    Account poorSource = account(AccountType.CHECKING, "10.00", sourceHolder, 0, 0L);
    when(repository.findById(poorSource.id())).thenReturn(Maybe.just(poorSource));
    when(repository.findById(destination.id())).thenReturn(Maybe.just(destination));
    var command =
        new TransferCommand(
            poorSource.id(),
            destination.id(),
            new Money(new BigDecimal("25.00"), "PEN"),
            null,
            "corr-funds");

    // Act
    var result = service.transfer(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).transfer(any(Account.class), any(Account.class));
  }

  @Test
  void chargesEachSideItsOwnFeeAndNotesItInTheMatchingDescription() {
    // Arrange
    var feeService = newService(chargedFees());
    Account chargedSource = account(AccountType.CHECKING, "100.00", sourceHolder, 2, 0L);
    Account chargedDestination = account(AccountType.SAVINGS, "20.00", destinationHolder, 1, 0L);
    when(repository.findById(chargedSource.id())).thenReturn(Maybe.just(chargedSource));
    when(repository.findById(chargedDestination.id())).thenReturn(Maybe.just(chargedDestination));
    when(repository.transfer(any(Account.class), any(Account.class)))
        .thenAnswer(
            invocation ->
                Single.just(List.of(invocation.getArgument(0), invocation.getArgument(1))));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt("withdrawal-fee", MovementType.WITHDRAWAL)))
        .thenReturn(Single.just(receipt("deposit-fee", MovementType.DEPOSIT)));
    var command =
        new TransferCommand(
            chargedSource.id(), chargedDestination.id(), amount(), "Rent payment", "corr-fees");

    // Act
    valueOf(feeService.transfer(command));

    // Assert
    var savedSource = ArgumentCaptor.forClass(Account.class);
    var savedDestination = ArgumentCaptor.forClass(Account.class);
    verify(repository).transfer(savedSource.capture(), savedDestination.capture());
    assertEquals(new BigDecimal("74.25"), savedSource.getValue().balance().amount());
    assertEquals(new BigDecimal("43.50"), savedDestination.getValue().balance().amount());
    verify(movements)
        .register(
            any(Account.class),
            eq(MovementType.WITHDRAWAL),
            eq(amount()),
            eq("Rent payment | Excess movement fee: 0.75 PEN"),
            eq("corr-fees"));
    verify(movements)
        .register(
            any(Account.class),
            eq(MovementType.DEPOSIT),
            eq(amount()),
            eq("Rent payment | Excess movement fee: 1.50 PEN"),
            eq("corr-fees"));
  }

  @Test
  void compensatesBothBalancesAfterConfirmedDownstreamFailure() {
    // Arrange
    stubLookupAndPersistence();
    when(repository.restore(any(Account.class), any(Account.class)))
        .thenAnswer(
            invocation ->
                Single.just(List.of(invocation.getArgument(0), invocation.getArgument(1))));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.error(new DownstreamServiceException("Rejected", false, null)));

    // Act
    var result = service.transfer(command());

    // Assert
    result.test().assertError(DownstreamServiceException.class);
    verify(repository).transfer(any(Account.class), any(Account.class));
    var restoredSource = ArgumentCaptor.forClass(Account.class);
    var restoredDestination = ArgumentCaptor.forClass(Account.class);
    verify(repository).restore(restoredSource.capture(), restoredDestination.capture());
    assertEquals(new BigDecimal("100.00"), restoredSource.getValue().balance().amount());
    assertEquals(new BigDecimal("20.00"), restoredDestination.getValue().balance().amount());
    assertEquals(1L, restoredSource.getValue().version());
    assertEquals(1L, restoredDestination.getValue().version());
    assertEquals(0, restoredSource.getValue().movementsInCurrentMonth());
    assertEquals(0, restoredDestination.getValue().movementsInCurrentMonth());
  }

  @Test
  void doesNotCompensateOnAmbiguousFailureAndKeepsTheCorrelationId() {
    // Arrange
    stubLookupAndPersistence();
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.error(new DownstreamServiceException("Timeout", true, null)));

    // Act
    var result = service.transfer(command());

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && failure.isAmbiguous()
                    && failure.getMessage().contains("transfer-123"));
    verify(repository, never()).restore(any(Account.class), any(Account.class));
  }

  @Test
  void reportsFailedCompensationAsAmbiguousWithTheCorrelationId() {
    // Arrange
    stubLookupAndPersistence();
    when(repository.restore(any(Account.class), any(Account.class)))
        .thenReturn(Single.error(new IllegalStateException("restore failed")));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.error(new DownstreamServiceException("Rejected", false, null)));

    // Act
    var result = service.transfer(command());

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof DownstreamServiceException failure
                    && failure.isAmbiguous()
                    && failure.getMessage().contains("transfer-123"));
    verify(repository).restore(any(Account.class), any(Account.class));
  }

  @Test
  void transferDoesNotBlockOrSubscribeEagerly() {
    // Arrange
    stubLookupAndPersistence();
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.never());

    // Act
    var result = service.transfer(command());
    var observer = result.test();

    // Assert
    observer.assertNoValues().assertNotComplete();
    verify(repository).transfer(any(Account.class), any(Account.class));
  }

  @Test
  void applicationSourcesContainNoBlockingCallsOrFieldInjection() throws IOException {
    // Arrange
    Path mainSources = Path.of("src", "main", "java");
    List<String> forbidden =
        List.of(
            "blockingGet(",
            "blockingFirst(",
            "blockingLast(",
            "blockingAwait(",
            "blockingSubscribe(",
            "blockingForEach(",
            "blockingIterable(",
            ".block()",
            "@Autowired");

    // Act
    StringBuilder violations = new StringBuilder();
    try (Stream<Path> files = Files.walk(mainSources)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        forbidden.stream()
            .filter(content::contains)
            .forEach(
                token -> violations.append(file).append(" contains ").append(token).append('\n'));
      }
    }

    // Assert
    assertTrue(violations.isEmpty(), violations.toString());
  }

  private Account account(
      AccountType type, String balance, UUID holder, int movements, Long version) {
    return new Account(
        UUID.randomUUID(),
        "19100012345678",
        type,
        new Money(new BigDecimal(balance), "PEN"),
        List.of(holder),
        CustomerType.PERSONAL,
        List.of(),
        null,
        type == AccountType.SAVINGS ? 1 : null,
        null,
        movements,
        YearMonth.now(),
        version,
        Instant.parse("2026-01-02T03:04:05Z"));
  }
}
