package com.pivot.banking.account.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccountCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperationCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccountCommand;
import com.pivot.banking.account.application.port.out.AccountRepositoryPort;
import com.pivot.banking.account.application.port.out.CreditCardGateway;
import com.pivot.banking.account.application.port.out.CustomerGateway;
import com.pivot.banking.account.application.port.out.MovementGateway;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import com.pivot.banking.account.domain.strategy.AccountCreationPolicyFactory;
import com.pivot.banking.account.domain.strategy.AccountOperationRuleFactory;
import com.pivot.banking.account.domain.strategy.AccountProfilePolicyFactory;
import com.pivot.banking.account.domain.strategy.BusinessAccountCreationPolicy;
import com.pivot.banking.account.domain.strategy.CheckingExcessFeePolicy;
import com.pivot.banking.account.domain.strategy.CheckingOpeningMinimumPolicy;
import com.pivot.banking.account.domain.strategy.CheckingOperationRule;
import com.pivot.banking.account.domain.strategy.ExcessMovementFeePolicyFactory;
import com.pivot.banking.account.domain.strategy.FixedTermExcessFeePolicy;
import com.pivot.banking.account.domain.strategy.FixedTermOpeningMinimumPolicy;
import com.pivot.banking.account.domain.strategy.FixedTermOperationRule;
import com.pivot.banking.account.domain.strategy.OpeningMinimumPolicyFactory;
import com.pivot.banking.account.domain.strategy.PersonalAccountCreationPolicy;
import com.pivot.banking.account.domain.strategy.PymeCheckingAccountPolicy;
import com.pivot.banking.account.domain.strategy.SavingsExcessFeePolicy;
import com.pivot.banking.account.domain.strategy.SavingsOpeningMinimumPolicy;
import com.pivot.banking.account.domain.strategy.SavingsOperationRule;
import com.pivot.banking.account.domain.strategy.StandardAccountProfilePolicy;
import com.pivot.banking.account.domain.strategy.TransferPolicy;
import com.pivot.banking.account.domain.strategy.VipSavingsAccountPolicy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountApplicationServiceTest {

  @Mock private AccountRepositoryPort repository;
  @Mock private CustomerGateway customers;
  @Mock private CreditCardGateway creditCards;
  @Mock private MovementGateway movements;

  private AccountApplicationService service;
  private AccountCreationPolicyFactory creationPolicies;
  private AccountProfilePolicyFactory profilePolicies;
  private AccountOperationRuleFactory operationRules;
  private OpeningMinimumPolicyFactory openingMinimums;
  private ExcessMovementFeePolicyFactory feePolicies;

  @BeforeEach
  void setUp() {
    creationPolicies =
        new AccountCreationPolicyFactory(
            List.of(new PersonalAccountCreationPolicy(), new BusinessAccountCreationPolicy()));
    profilePolicies =
        new AccountProfilePolicyFactory(
            List.of(
                new VipSavingsAccountPolicy(),
                new PymeCheckingAccountPolicy(),
                new StandardAccountProfilePolicy()));
    operationRules =
        new AccountOperationRuleFactory(
            List.of(
                new SavingsOperationRule(),
                new CheckingOperationRule(),
                new FixedTermOperationRule()));
    openingMinimums = zeroOpeningMinimums();
    feePolicies = zeroExcessFees();
    service = newService(openingMinimums, feePolicies);
  }

  private AccountApplicationService newService(OpeningMinimumPolicyFactory minimums) {
    return newService(minimums, zeroExcessFees());
  }

  private AccountApplicationService newService(
      OpeningMinimumPolicyFactory minimums, ExcessMovementFeePolicyFactory fees) {
    return new AccountApplicationService(
        repository,
        customers,
        creditCards,
        movements,
        creationPolicies,
        profilePolicies,
        operationRules,
        fees,
        minimums,
        new TransferPolicy(fees),
        100);
  }

  private OpeningMinimumPolicyFactory zeroOpeningMinimums() {
    return new OpeningMinimumPolicyFactory(
        List.of(
            new SavingsOpeningMinimumPolicy(BigDecimal.ZERO),
            new CheckingOpeningMinimumPolicy(BigDecimal.ZERO),
            new FixedTermOpeningMinimumPolicy(BigDecimal.ZERO)));
  }

  private ExcessMovementFeePolicyFactory zeroExcessFees() {
    return new ExcessMovementFeePolicyFactory(
        List.of(
            new SavingsExcessFeePolicy(BigDecimal.ZERO),
            new CheckingExcessFeePolicy(0, BigDecimal.ZERO),
            new FixedTermExcessFeePolicy()));
  }

  private ExcessMovementFeePolicyFactory chargedExcessFees() {
    return new ExcessMovementFeePolicyFactory(
        List.of(
            new SavingsExcessFeePolicy(new BigDecimal("1.50")),
            new CheckingExcessFeePolicy(2, new BigDecimal("0.75")),
            new FixedTermExcessFeePolicy()));
  }

  @Test
  void createsPersonalSavingsAccountWithZeroBalance() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
                AccountType.SAVINGS, List.of(customerId), "PEN", null, 20, null, List.of())
            .withCorrelationId("account-request-123");
    when(customers.findById(eq(customerId), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(customerId, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(customerId, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            account -> {
              assertEquals(AccountType.SAVINGS, account.accountType());
              assertEquals(BigDecimal.ZERO, account.balance().amount());
              assertEquals(List.of(customerId), account.holders());
              assertNotNull(account.createdAt());
              return true;
            })
        .assertComplete();
    verify(repository).save(any(Account.class));
    verify(customers).findById(customerId, "account-request-123");
  }

  @Test
  void rejectsBusinessSavingsAccountWithoutPersisting() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS, List.of(customerId), "PEN", null, 10, null, List.of());
    when(customers.findById(eq(customerId), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(customerId, CustomerType.BUSINESS, true)));

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void createsBusinessCheckingAccountWithMultipleBusinessHolders() {
    // Arrange
    UUID firstHolder = UUID.randomUUID();
    UUID secondHolder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.CHECKING,
            List.of(firstHolder, secondHolder),
            "PEN",
            new Money(new BigDecimal("2.50"), "PEN"),
            null,
            null,
            List.of());
    when(customers.findById(eq(firstHolder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(firstHolder, CustomerType.BUSINESS, true)));
    when(customers.findById(eq(secondHolder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(secondHolder, CustomerType.BUSINESS, true)));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            account -> {
              assertEquals(CustomerType.BUSINESS, account.holderType());
              assertEquals(List.of(firstHolder, secondHolder), account.holders());
              return true;
            })
        .assertComplete();
    verify(repository).save(any(Account.class));
  }

  @Test
  void createsVipSavingsAccountWhenHolderOwnsCreditCard() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
                AccountType.SAVINGS, List.of(holder), "PEN", null, 20, null, List.of())
            .withCorrelationId("vip-request");
    when(customers.findById(eq(holder), any()))
        .thenReturn(
            Maybe.just(
                new CustomerSnapshot(holder, CustomerType.PERSONAL, true, CustomerProfile.VIP)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(creditCards.hasCard(holder, "vip-request")).thenReturn(Single.just(true));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertValueCount(1).assertComplete();
    verify(creditCards).hasCard(holder, "vip-request");
    verify(repository).save(any(Account.class));
  }

  @Test
  void rejectsVipSavingsAccountWhenHolderHasNoCreditCard() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
                AccountType.SAVINGS, List.of(holder), "PEN", null, 20, null, List.of())
            .withCorrelationId("vip-request");
    when(customers.findById(eq(holder), any()))
        .thenReturn(
            Maybe.just(
                new CustomerSnapshot(holder, CustomerType.PERSONAL, true, CustomerProfile.VIP)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(creditCards.hasCard(holder, "vip-request")).thenReturn(Single.just(false));

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void createsPymeCheckingAccountWithAllPymeHoldersAndZeroMaintenanceFee() {
    // Arrange
    UUID firstHolder = UUID.randomUUID();
    UUID secondHolder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
                AccountType.CHECKING,
                List.of(firstHolder, secondHolder),
                "PEN",
                new Money(new BigDecimal("2.50"), "PEN"),
                null,
                null,
                List.of())
            .withCorrelationId("pyme-request");
    when(customers.findById(eq(firstHolder), any()))
        .thenReturn(
            Maybe.just(
                new CustomerSnapshot(
                    firstHolder, CustomerType.BUSINESS, true, CustomerProfile.PYME)));
    when(customers.findById(eq(secondHolder), any()))
        .thenReturn(
            Maybe.just(
                new CustomerSnapshot(
                    secondHolder, CustomerType.BUSINESS, true, CustomerProfile.PYME)));
    when(creditCards.hasCard(firstHolder, "pyme-request")).thenReturn(Single.just(true));
    when(creditCards.hasCard(secondHolder, "pyme-request")).thenReturn(Single.just(true));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    var saved = ArgumentCaptor.forClass(Account.class);
    result.test().assertValueCount(1).assertComplete();
    verify(repository).save(saved.capture());
    assertEquals(0, saved.getValue().maintenanceFee().amount().compareTo(BigDecimal.ZERO));
    verify(creditCards).hasCard(firstHolder, "pyme-request");
    verify(creditCards).hasCard(secondHolder, "pyme-request");
  }

  @Test
  void fallsBackToStandardCheckingForMixedProfilesWithoutCardCheck() {
    // Arrange
    UUID pymeHolder = UUID.randomUUID();
    UUID ordinaryHolder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.CHECKING,
            List.of(pymeHolder, ordinaryHolder),
            "PEN",
            new Money(new BigDecimal("1.00"), "PEN"),
            null,
            null,
            List.of());
    when(customers.findById(eq(pymeHolder), any()))
        .thenReturn(
            Maybe.just(
                new CustomerSnapshot(
                    pymeHolder, CustomerType.BUSINESS, true, CustomerProfile.PYME)));
    when(customers.findById(eq(ordinaryHolder), any()))
        .thenReturn(
            Maybe.just(new CustomerSnapshot(ordinaryHolder, CustomerType.BUSINESS, true, null)));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            account -> {
              assertEquals(0, new BigDecimal("1.00").compareTo(account.maintenanceFee().amount()));
              return true;
            })
        .assertComplete();
    verify(creditCards, never()).hasCard(any(), any());
  }

  @Test
  void keepsStandardCheckingBehaviorForNullProfiles() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.CHECKING,
            List.of(holder),
            "PEN",
            new Money(new BigDecimal("2.50"), "PEN"),
            null,
            null,
            List.of());
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.BUSINESS, true, null)));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            account -> {
              assertEquals(0, new BigDecimal("2.50").compareTo(account.maintenanceFee().amount()));
              return true;
            })
        .assertComplete();
    verify(creditCards, never()).hasCard(any(), any());
  }

  @Test
  void rejectsInactiveHolderWithoutPersisting() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.CHECKING,
            List.of(customerId),
            "PEN",
            new Money(new BigDecimal("2.50"), "PEN"),
            null,
            null,
            List.of());
    when(customers.findById(eq(customerId), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(customerId, CustomerType.PERSONAL, false)));

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void persistsBalanceBeforeReturningSuccessfulMovementReceipt() {
    // Arrange
    var account = account(new BigDecimal("100.00"), 0L);
    var amount = new Money(new BigDecimal("25.00"), "PEN");
    var receipt =
        new MovementReceipt(
            "movement-1", MovementType.DEPOSIT, amount.amount(), amount.currency(), Instant.now());
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              return Single.just(saved.withVersion(saved.version() + 1));
            });
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt));

    // Act
    var result =
        service.operate(
            account.id(),
            MovementType.DEPOSIT,
            new OperationCommand(amount, "Cash deposit", "account-request-456"));

    // Assert
    result.test().assertResult(receipt).assertComplete();
    var savedAccount = ArgumentCaptor.forClass(Account.class);
    verify(repository).save(savedAccount.capture());
    assertEquals(new BigDecimal("125.00"), savedAccount.getValue().balance().amount());
    verify(movements)
        .register(
            savedAccount.capture(),
            org.mockito.ArgumentMatchers.eq(MovementType.DEPOSIT),
            org.mockito.ArgumentMatchers.eq(amount),
            org.mockito.ArgumentMatchers.eq("Cash deposit"),
            org.mockito.ArgumentMatchers.eq("account-request-456"));
  }

  @Test
  void compensatesBalanceAfterConfirmedMovementFailure() {
    // Arrange
    var account = account(new BigDecimal("100.00"), 0L);
    var amount = new Money(new BigDecimal("25.00"), "PEN");
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              return Single.just(saved.withVersion(saved.version() + 1));
            });
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.error(new DownstreamServiceException("Rejected", false, null)));

    // Act
    var result =
        service.operate(account.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

    // Assert
    result.test().assertError(DownstreamServiceException.class);
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
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              return Single.just(saved.withVersion(saved.version() + 1));
            });
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.error(new DownstreamServiceException("Timeout", true, null)));

    // Act
    var result =
        service.operate(account.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

    // Assert
    result
        .test()
        .assertError(
            error -> error instanceof DownstreamServiceException failure && failure.isAmbiguous());
    verify(repository).save(any(Account.class));
  }

  @Test
  void rejectsRepeatedHoldersOnCreation() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS, List.of(holderId, holderId), "PEN", null, 10, null, List.of());

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(customers, never()).findById(any(), any());
  }

  @Test
  void rejectsProductFieldsThatBelongToAnotherAccountType() {
    // Arrange
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(UUID.randomUUID()),
            "PEN",
            new Money(new BigDecimal("1.00"), "PEN"),
            10,
            null,
            List.of());

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error
                        .getMessage()
                        .equals("Required account-type fields are missing or invalid"));
    verify(customers, never()).findById(any(), any());
  }

  @Test
  void rejectsPersonalAccountWithAuthorizedSigners() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    var signer =
        new com.pivot.banking.account.domain.AuthorizedSigner("Carlos Ruiz", "DNI", "45678912");
    var command =
        new CreateAccountCommand(
            AccountType.CHECKING,
            List.of(holderId),
            "PEN",
            new Money(new BigDecimal("1.00"), "PEN"),
            null,
            null,
            List.of(signer));
    when(customers.findById(eq(holderId), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holderId, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(holderId, AccountType.CHECKING), 0, 100))
        .thenReturn(Flowable.empty());

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void rejectsHoldersWithDifferentCustomerTypes() {
    // Arrange
    UUID personal = UUID.randomUUID();
    UUID business = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.CHECKING,
            List.of(personal, business),
            "PEN",
            new Money(new BigDecimal("1.00"), "PEN"),
            null,
            null,
            List.of());
    when(customers.findById(eq(personal), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(personal, CustomerType.PERSONAL, true)));
    when(customers.findById(eq(business), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(business, CustomerType.BUSINESS, true)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error
                        .getMessage()
                        .equals("All account holders must have the same customer type"));
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void findsAndReportsMissingAccounts() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 1L);
    UUID missingId = UUID.randomUUID();
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(repository.findById(missingId)).thenReturn(Maybe.empty());

    // Act
    var found = service.findById(existing.id());
    var missing = service.findById(missingId);

    // Assert
    found.test().assertResult(existing).assertComplete();
    missing
        .test()
        .assertError(com.pivot.banking.account.domain.exception.AccountNotFoundException.class);
  }

  @Test
  void listsAccountsAndRejectsInvalidPagination() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 1L);
    when(repository.findAll(null, 0, 20)).thenReturn(Flowable.just(existing));

    // Act
    var valid = service.findAll(null, 0, 20);
    var invalid = service.findAll(null, -1, 20);

    // Assert
    valid.test().assertResult(existing).assertComplete();
    invalid.test().assertError(IllegalArgumentException.class);
    verify(repository).findAll(null, 0, 20);
  }

  @Test
  void updatesCheckingAccountWithNewHoldersAndSigners() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    var signer =
        new com.pivot.banking.account.domain.AuthorizedSigner("Carlos Ruiz", "DNI", "45678912");
    Account existing =
        account(
            AccountType.CHECKING,
            new BigDecimal("100.00"),
            new Money(new BigDecimal("2.50"), "PEN"),
            null,
            null,
            1L);
    var command =
        new UpdateAccountCommand(
            List.of(holderId),
            List.of(signer),
            new Money(new BigDecimal("3.00"), "PEN"),
            null,
            null);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(holderId), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holderId, CustomerType.BUSINESS, true)));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.update(existing.id(), command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            updated -> {
              assertEquals(List.of(holderId), updated.holders());
              assertEquals(List.of(signer), updated.authorizedSigners());
              assertEquals(new BigDecimal("3.00"), updated.maintenanceFee().amount());
              assertEquals(existing.balance(), updated.balance());
              return true;
            })
        .assertComplete();
  }

  @Test
  void rejectsUpdateWithForbiddenProductField() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    Account existing = account(new BigDecimal("100.00"), 1L);
    var command =
        new UpdateAccountCommand(
            List.of(holderId), List.of(), new Money(new BigDecimal("1.00"), "PEN"), 10, null);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));

    // Act
    var result = service.update(existing.id(), command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(customers, never()).findById(any(), any());
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void rejectsUpdateWithoutHolders() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 1L);
    var command = new UpdateAccountCommand(List.of(), List.of(), null, 10, null);

    // Act
    var result = service.update(existing.id(), command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).findById(any());
  }

  @Test
  void deletesExistingAccountAndReportsMissingAccount() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 1L);
    UUID missingId = UUID.randomUUID();
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(repository.findById(missingId)).thenReturn(Maybe.empty());
    when(repository.delete(existing)).thenReturn(Completable.complete());

    // Act
    var deleted = service.delete(existing.id());
    var missing = service.delete(missingId);

    // Assert
    deleted.test().assertComplete();
    missing
        .test()
        .assertError(com.pivot.banking.account.domain.exception.AccountNotFoundException.class);
    verify(repository).delete(existing);
  }

  @Test
  void rejectsNonPositiveOperationAmount() {
    // Arrange
    UUID accountId = UUID.randomUUID();

    // Act
    var result =
        service.operate(
            accountId,
            MovementType.DEPOSIT,
            new OperationCommand(new Money(BigDecimal.ZERO, "PEN"), null));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).findById(any());
  }

  @Test
  void withdrawsMoneyAndAdvancesMonthlyMovementCounter() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 0L);
    Money amount = new Money(new BigDecimal("25.00"), "PEN");
    MovementReceipt receipt =
        new MovementReceipt(
            "movement-1",
            MovementType.WITHDRAWAL,
            amount.amount(),
            amount.currency(),
            Instant.now());
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(repository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              return Single.just(saved.withVersion(saved.version() + 1));
            });
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt));

    // Act
    var result =
        service.operate(
            existing.id(), MovementType.WITHDRAWAL, new OperationCommand(amount, "ATM"));

    // Assert
    result.test().assertResult(receipt).assertComplete();
    var saved = ArgumentCaptor.forClass(Account.class);
    verify(repository).save(saved.capture());
    assertEquals(new BigDecimal("75.00"), saved.getValue().balance().amount());
    assertEquals(1, saved.getValue().movementsInCurrentMonth());
  }

  @Test
  void firstSavingsMovementWithinQuotaIsFree() {
    // Arrange
    var feeService = newService(zeroOpeningMinimums(), chargedExcessFees());
    var account =
        account(AccountType.SAVINGS, new BigDecimal("100.00"), 1, null, 0, YearMonth.now());
    var amount = new Money(new BigDecimal("25.00"), "PEN");
    var receipt =
        new MovementReceipt(
            "movement-1", MovementType.DEPOSIT, amount.amount(), amount.currency(), Instant.now());
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt));

    // Act
    var result =
        feeService.operate(
            account.id(),
            MovementType.DEPOSIT,
            new OperationCommand(amount, "Cash deposit", "request-1"));

    // Assert
    result.test().assertResult(receipt).assertComplete();
    var saved = ArgumentCaptor.forClass(Account.class);
    var description = ArgumentCaptor.forClass(String.class);
    verify(repository).save(saved.capture());
    assertEquals(new BigDecimal("125.00"), saved.getValue().balance().amount());
    verify(movements)
        .register(
            any(Account.class),
            eq(MovementType.DEPOSIT),
            eq(amount),
            description.capture(),
            eq("request-1"));
    assertEquals("Cash deposit", description.getValue());
  }

  @Test
  void savingsMovementBeyondQuotaIsAcceptedAndCharged() {
    // Arrange
    var feeService = newService(zeroOpeningMinimums(), chargedExcessFees());
    var account =
        account(AccountType.SAVINGS, new BigDecimal("100.00"), 1, null, 1, YearMonth.now());
    var amount = new Money(new BigDecimal("25.00"), "PEN");
    var receipt =
        new MovementReceipt(
            "movement-2", MovementType.DEPOSIT, amount.amount(), amount.currency(), Instant.now());
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.just(receipt));

    // Act
    var result =
        feeService.operate(
            account.id(),
            MovementType.DEPOSIT,
            new OperationCommand(amount, "Cash deposit", "request-2"));

    // Assert
    result.test().assertResult(receipt).assertComplete();
    var saved = ArgumentCaptor.forClass(Account.class);
    var description = ArgumentCaptor.forClass(String.class);
    verify(repository).save(saved.capture());
    assertEquals(new BigDecimal("123.50"), saved.getValue().balance().amount());
    assertEquals(2, saved.getValue().movementsInCurrentMonth());
    verify(movements)
        .register(
            any(Account.class),
            eq(MovementType.DEPOSIT),
            eq(amount),
            description.capture(),
            eq("request-2"));
    assertEquals("Cash deposit | Excess movement fee: 1.50 PEN", description.getValue());
  }

  @Test
  void zeroConfiguredFeeKeepsBalanceAndDescriptionUnchangedBeyondQuota() {
    // Arrange
    var account =
        account(AccountType.SAVINGS, new BigDecimal("100.00"), 1, null, 5, YearMonth.now());
    var amount = new Money(new BigDecimal("25.00"), "PEN");
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(
            Single.just(
                new MovementReceipt(
                    "movement-3",
                    MovementType.DEPOSIT,
                    amount.amount(),
                    amount.currency(),
                    Instant.now())));

    // Act
    var result =
        service.operate(
            account.id(), MovementType.DEPOSIT, new OperationCommand(amount, "Cash deposit"));

    // Assert
    result.test().assertValueCount(1).assertComplete();
    var saved = ArgumentCaptor.forClass(Account.class);
    verify(repository).save(saved.capture());
    assertEquals(new BigDecimal("125.00"), saved.getValue().balance().amount());
    verify(movements)
        .register(
            any(Account.class), eq(MovementType.DEPOSIT), eq(amount), eq("Cash deposit"), any());
  }

  @Test
  void checkingMovementBeyondConfiguredQuotaIsCharged() {
    // Arrange
    var feeService = newService(zeroOpeningMinimums(), chargedExcessFees());
    var account =
        account(AccountType.CHECKING, new BigDecimal("100.00"), null, null, 2, YearMonth.now());
    var amount = new Money(new BigDecimal("25.00"), "PEN");
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(
            Single.just(
                new MovementReceipt(
                    "movement-4",
                    MovementType.DEPOSIT,
                    amount.amount(),
                    amount.currency(),
                    Instant.now())));

    // Act
    var result =
        feeService.operate(
            account.id(), MovementType.DEPOSIT, new OperationCommand(amount, "Cash deposit"));

    // Assert
    result.test().assertValueCount(1).assertComplete();
    var saved = ArgumentCaptor.forClass(Account.class);
    var description = ArgumentCaptor.forClass(String.class);
    verify(repository).save(saved.capture());
    assertEquals(new BigDecimal("124.25"), saved.getValue().balance().amount());
    verify(movements)
        .register(
            any(Account.class), eq(MovementType.DEPOSIT), eq(amount), description.capture(), any());
    assertEquals("Cash deposit | Excess movement fee: 0.75 PEN", description.getValue());
  }

  @Test
  void withdrawalIsRejectedWhenBalanceCannotCoverAmountPlusFee() {
    // Arrange
    var feeService = newService(zeroOpeningMinimums(), chargedExcessFees());
    var account =
        account(AccountType.SAVINGS, new BigDecimal("10.00"), 1, null, 1, YearMonth.now());
    var amount = new Money(new BigDecimal("10.00"), "PEN");
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));

    // Act
    var result =
        feeService.operate(
            account.id(), MovementType.WITHDRAWAL, new OperationCommand(amount, "ATM"));

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error.getMessage().contains("operation and its fee"));
    verify(repository, never()).save(any(Account.class));
    verify(movements, never())
        .register(any(Account.class), any(MovementType.class), any(Money.class), any(), any());
  }

  @Test
  void fixedTermMovementChargesNoExcessFee() {
    // Arrange
    var feeService = newService(zeroOpeningMinimums(), chargedExcessFees());
    var account =
        account(
            AccountType.FIXED_TERM,
            new BigDecimal("100.00"),
            null,
            LocalDate.now().getDayOfMonth(),
            0,
            YearMonth.now());
    var amount = new Money(new BigDecimal("25.00"), "PEN");
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(
            Single.just(
                new MovementReceipt(
                    "movement-5",
                    MovementType.DEPOSIT,
                    amount.amount(),
                    amount.currency(),
                    Instant.now())));

    // Act
    var result =
        feeService.operate(
            account.id(), MovementType.DEPOSIT, new OperationCommand(amount, "Cash deposit"));

    // Assert
    result.test().assertValueCount(1).assertComplete();
    var saved = ArgumentCaptor.forClass(Account.class);
    verify(repository).save(saved.capture());
    assertEquals(new BigDecimal("125.00"), saved.getValue().balance().amount());
    verify(movements)
        .register(
            any(Account.class), eq(MovementType.DEPOSIT), eq(amount), eq("Cash deposit"), any());
  }

  @Test
  void doesNotRegisterMovementWhenOperationRuleRejectsTheRequest() {
    // Arrange
    var account =
        account(AccountType.SAVINGS, new BigDecimal("100.00"), 10, null, 0, YearMonth.now());
    var amount = new Money(new BigDecimal("25.00"), "USD");
    when(repository.findById(account.id())).thenReturn(Maybe.just(account));

    // Act
    var result =
        service.operate(account.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
    verify(movements, never())
        .register(any(Account.class), any(MovementType.class), any(Money.class), any(), any());
  }

  @Test
  void resetsMonthlyCounterWhenMovementPeriodChanges() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 0L);
    Account previousPeriod =
        new Account(
            existing.id(),
            existing.accountNumber(),
            existing.accountType(),
            existing.balance(),
            existing.holders(),
            existing.holderType(),
            existing.authorizedSigners(),
            existing.maintenanceFee(),
            10,
            null,
            10,
            YearMonth.now().minusMonths(1),
            existing.version(),
            existing.createdAt());
    Money amount = new Money(new BigDecimal("5.00"), "PEN");
    when(repository.findById(existing.id())).thenReturn(Maybe.just(previousPeriod));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(
            Single.just(
                new MovementReceipt(
                    "movement-2",
                    MovementType.DEPOSIT,
                    amount.amount(),
                    amount.currency(),
                    Instant.now())));

    // Act
    var result =
        service.operate(existing.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

    // Assert
    result.test().assertValueCount(1).assertComplete();
    var saved = ArgumentCaptor.forClass(Account.class);
    verify(repository).save(saved.capture());
    assertEquals(1, saved.getValue().movementsInCurrentMonth());
    assertEquals(YearMonth.now(), saved.getValue().movementsPeriod());
  }

  @Test
  void marksFailedCompensationAsAmbiguous() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 0L);
    Money amount = new Money(new BigDecimal("25.00"), "PEN");
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(repository.save(any(Account.class)))
        .thenReturn(Single.just(existing.withVersion(1L)))
        .thenReturn(Single.error(new IllegalStateException("compensation failed")));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(Single.error(new DownstreamServiceException("Rejected", false, null)));

    // Act
    var result =
        service.operate(existing.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

    // Assert
    result
        .test()
        .assertError(
            error -> error instanceof DownstreamServiceException failure && failure.isAmbiguous());
    verify(repository, times(2)).save(any(Account.class));
  }

  @Test
  void reportsEmptyMovementReceiptAsAmbiguous() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 0L);
    Money amount = new Money(new BigDecimal("25.00"), "PEN");
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));
    when(movements.register(
            any(Account.class), any(MovementType.class), any(Money.class), any(), any()))
        .thenReturn(
            Single.error(
                new DownstreamServiceException(
                    "Movement registration outcome is ambiguous", true, null)));

    // Act
    var result =
        service.operate(existing.id(), MovementType.DEPOSIT, new OperationCommand(amount, null));

    // Assert
    result
        .test()
        .assertError(
            error -> error instanceof DownstreamServiceException failure && failure.isAmbiguous());
    verify(repository).save(any(Account.class));
  }

  @Test
  void rejectsCreationWithoutHolders() {
    // Arrange
    var command =
        new CreateAccountCommand(AccountType.SAVINGS, List.of(), "PEN", null, 10, null, List.of());

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(customers, never()).findById(any(), any());
  }

  @Test
  void createsPersonalAccountWithDefaultCurrencyWhenOmitted() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS, List.of(holder), null, null, 10, null, List.of());
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValue(
            account -> {
              assertEquals("PEN", account.balance().currency());
              return true;
            })
        .assertComplete();
  }

  @Test
  void createsMultiplePersonalFixedTermAccounts() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.FIXED_TERM, List.of(holder), "PEN", null, null, 15, List.of());
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValue(
            account -> {
              assertEquals(AccountType.FIXED_TERM, account.accountType());
              return true;
            })
        .assertComplete();
    verify(repository, never())
        .findAll(any(AccountFilter.class), any(Integer.class), any(Integer.class));
  }

  @Test
  void rejectsEveryInvalidProductFieldCombination() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Money fee = new Money(new BigDecimal("1.00"), "PEN");
    var invalidSavings =
        java.util.List.of(
            new CreateAccountCommand(
                AccountType.SAVINGS, List.of(holder), "PEN", null, null, null, List.of()),
            new CreateAccountCommand(
                AccountType.SAVINGS, List.of(holder), "PEN", null, 0, null, List.of()),
            new CreateAccountCommand(
                AccountType.SAVINGS, List.of(holder), "PEN", fee, 10, null, List.of()),
            new CreateAccountCommand(
                AccountType.SAVINGS, List.of(holder), "PEN", null, 10, 15, List.of()),
            new CreateAccountCommand(
                AccountType.CHECKING, List.of(holder), "PEN", null, null, null, List.of()),
            new CreateAccountCommand(
                AccountType.CHECKING,
                List.of(holder),
                "PEN",
                new Money(new BigDecimal("-1.00"), "PEN"),
                null,
                null,
                List.of()),
            new CreateAccountCommand(
                AccountType.CHECKING, List.of(holder), "PEN", fee, 10, null, List.of()),
            new CreateAccountCommand(
                AccountType.CHECKING, List.of(holder), "PEN", fee, null, 15, List.of()),
            new CreateAccountCommand(
                AccountType.FIXED_TERM, List.of(holder), "PEN", null, null, null, List.of()),
            new CreateAccountCommand(
                AccountType.FIXED_TERM, List.of(holder), "PEN", fee, null, 15, List.of()),
            new CreateAccountCommand(null, List.of(holder), "PEN", null, null, null, List.of()));

    // Act
    var results = invalidSavings.stream().map(service::create).toList();

    // Assert
    for (Single<Account> result : results) {
      result.test().assertError(BusinessRuleViolationException.class);
    }
    verify(customers, never()).findById(any(), any());
  }

  @Test
  void rejectsCreationWhenCustomerCannotBeResolved() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS, List.of(holder), "PEN", null, 10, null, List.of());
    when(customers.findById(eq(holder), any())).thenReturn(Maybe.empty());

    // Act
    var result = service.create(command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void updatesSavingsAccountWithoutOptionalProductFields() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Account existing = account(new BigDecimal("100.00"), 1L);
    var command = new UpdateAccountCommand(List.of(holder), null, null, 10, null);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.update(existing.id(), command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            updated -> {
              assertEquals(List.of(holder), updated.holders());
              assertEquals(10, updated.maxMonthlyMovements());
              return true;
            })
        .assertComplete();
  }

  @Test
  void rejectsUpdateWithNullHolders() {
    // Arrange
    Account existing = account(new BigDecimal("100.00"), 1L);
    var command = new UpdateAccountCommand(null, List.of(), null, 10, null);

    // Act
    var result = service.update(existing.id(), command);

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
  }

  @Test
  void rejectsInactiveHolderReplacementOnUpdate() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Account existing = account(new BigDecimal("100.00"), 1L);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, false)));

    // Act
    var result =
        service.update(
            existing.id(), new UpdateAccountCommand(List.of(holder), List.of(), null, 10, null));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void rejectsMixedHolderTypesOnUpdate() {
    // Arrange
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Account existing = account(new BigDecimal("100.00"), 1L);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(first), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(first, CustomerType.PERSONAL, true)));
    when(customers.findById(eq(second), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(second, CustomerType.BUSINESS, true)));

    // Act
    var result =
        service.update(
            existing.id(),
            new UpdateAccountCommand(List.of(first, second), List.of(), null, 10, null));

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error
                        .getMessage()
                        .equals("All account holders must have the same customer type"));
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void rejectsDuplicateHoldersOnUpdate() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Account existing = account(new BigDecimal("100.00"), 1L);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));

    // Act
    var result =
        service.update(
            existing.id(),
            new UpdateAccountCommand(List.of(holder, holder), List.of(), null, 10, null));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void rejectsUpdateWhenReplacementCustomerDoesNotExist() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Account existing = account(new BigDecimal("100.00"), 1L);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(holder), any())).thenReturn(Maybe.empty());

    // Act
    var result =
        service.update(
            existing.id(), new UpdateAccountCommand(List.of(holder), List.of(), null, 10, null));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void updatesFixedTermAccountWithConfiguredMovementDay() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Account existing =
        account(AccountType.FIXED_TERM, new BigDecimal("100.00"), null, null, 10, 1L);
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result =
        service.update(
            existing.id(), new UpdateAccountCommand(List.of(holder), List.of(), null, null, 15));

    // Assert
    result
        .test()
        .assertValue(
            updated -> {
              assertEquals(15, updated.allowedMovementDay());
              return true;
            })
        .assertComplete();
  }

  @Test
  void rejectsPersonalUpdateWithAuthorizedSigners() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Account existing = account(new BigDecimal("100.00"), 1L);
    var signer =
        new com.pivot.banking.account.domain.AuthorizedSigner("Carlos Ruiz", "DNI", "45678912");
    when(repository.findById(existing.id())).thenReturn(Maybe.just(existing));
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());

    // Act
    var result =
        service.update(
            existing.id(),
            new UpdateAccountCommand(List.of(holder), List.of(signer), null, 10, null));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void rejectsEveryOutOfRangePaginationValue() {
    // Arrange
    // Act
    var zeroSize = service.findAll(null, 0, 0);
    var oversized = service.findAll(null, 0, 101);

    // Assert
    zeroSize.test().assertError(IllegalArgumentException.class);
    oversized.test().assertError(IllegalArgumentException.class);
    verify(repository, never()).findAll(any(), any(Integer.class), any(Integer.class));
  }

  @Test
  void rejectsOperationWithNullAmount() {
    // Arrange
    // Act
    var result =
        service.operate(UUID.randomUUID(), MovementType.DEPOSIT, new OperationCommand(null, null));

    // Assert
    result.test().assertError(BusinessRuleViolationException.class);
    verify(repository, never()).findById(any());
  }

  @Test
  void opensAccountWithOpeningAmountAtTheConfiguredMinimum() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var strictService =
        newService(
            new OpeningMinimumPolicyFactory(
                List.of(new SavingsOpeningMinimumPolicy(new BigDecimal("10.00")))));
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(holder),
            "PEN",
            null,
            10,
            null,
            List.of(),
            new Money(new BigDecimal("10.00"), "PEN"));
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = strictService.create(command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            account -> {
              assertEquals(new BigDecimal("10.00"), account.balance().amount());
              assertEquals("PEN", account.balance().currency());
              return true;
            })
        .assertComplete();
  }

  @Test
  void rejectsOpeningAmountBelowConfiguredMinimumBeforeCallingDownstreamServices() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var strictService =
        newService(
            new OpeningMinimumPolicyFactory(
                List.of(new SavingsOpeningMinimumPolicy(new BigDecimal("10.00")))));
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(holder),
            "PEN",
            null,
            10,
            null,
            List.of(),
            new Money(new BigDecimal("9.99"), "PEN"));

    // Act
    var result = strictService.create(command);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error.getMessage().contains("below the configured minimum"));
    verify(customers, never()).findById(any(), any());
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void keepsZeroOpeningBalanceWhenAmountIsOmitted() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS, List.of(holder), "PEN", null, 10, null, List.of(), (Money) null);
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            account -> {
              assertEquals(BigDecimal.ZERO, account.balance().amount());
              assertEquals("PEN", account.balance().currency());
              return true;
            })
        .assertComplete();
  }

  @Test
  void normalizesOpeningAmountToTheEffectiveAccountCurrency() {
    // Arrange
    UUID dollarHolder = UUID.randomUUID();
    UUID defaultHolder = UUID.randomUUID();
    var withCurrency =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(dollarHolder),
            "USD",
            null,
            10,
            null,
            List.of(),
            new Money(new BigDecimal("20.00"), "PEN"));
    var withDefault =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(defaultHolder),
            null,
            null,
            10,
            null,
            List.of(),
            new Money(new BigDecimal("5.00"), "USD"));
    when(customers.findById(eq(dollarHolder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(dollarHolder, CustomerType.PERSONAL, true)));
    when(customers.findById(eq(defaultHolder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(defaultHolder, CustomerType.PERSONAL, true)));
    when(repository.findAll(any(AccountFilter.class), any(Integer.class), any(Integer.class)))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var dollarAccount = service.create(withCurrency).blockingGet();
    var defaultAccount = service.create(withDefault).blockingGet();

    // Assert
    assertEquals(new Money(new BigDecimal("20.00"), "USD"), dollarAccount.balance());
    assertEquals(new Money(new BigDecimal("5.00"), "PEN"), defaultAccount.balance());
  }

  @Test
  void storesCustomerDefinedMinimumDailyAverageForVipSavings() {
    // Arrange
    UUID holder = UUID.randomUUID();
    Money minimum = new Money(new BigDecimal("250.00"), "PEN");
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(holder),
            "PEN",
            null,
            20,
            null,
            List.of(),
            null,
            minimum,
            "vip-minimum-request");
    when(customers.findById(eq(holder), any()))
        .thenReturn(
            Maybe.just(
                new CustomerSnapshot(holder, CustomerType.PERSONAL, true, CustomerProfile.VIP)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(creditCards.hasCard(holder, "vip-minimum-request")).thenReturn(Single.just(true));
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    var saved = ArgumentCaptor.forClass(Account.class);
    result.test().assertValueCount(1).assertComplete();
    verify(repository).save(saved.capture());
    assertEquals(minimum, saved.getValue().minimumDailyAverage());
  }

  @Test
  void defaultsMinimumDailyAverageToZeroWhenOmitted() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS, List.of(holder), "PEN", null, 20, null, List.of());
    when(customers.findById(eq(holder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(holder, CustomerType.PERSONAL, true)));
    when(repository.findAll(new AccountFilter(holder, AccountType.SAVINGS), 0, 100))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertValueAt(
            0,
            account -> {
              assertEquals(new Money(BigDecimal.ZERO, "PEN"), account.minimumDailyAverage());
              return true;
            })
        .assertComplete();
  }

  @Test
  void rejectsNegativeMinimumDailyAverageBeforeCallingDownstreamServices() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var command =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(holder),
            "PEN",
            null,
            20,
            null,
            List.of(),
            null,
            new Money(new BigDecimal("-1.00"), "PEN"));

    // Act
    var result = service.create(command);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error.getMessage().contains("nonnegative"));
    verify(customers, never()).findById(any(), any());
    verify(repository, never()).save(any(Account.class));
  }

  @Test
  void normalizesMinimumDailyAverageToTheEffectiveAccountCurrency() {
    // Arrange
    UUID dollarHolder = UUID.randomUUID();
    UUID defaultHolder = UUID.randomUUID();
    var withCurrency =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(dollarHolder),
            "USD",
            null,
            20,
            null,
            List.of(),
            null,
            new Money(new BigDecimal("40.00"), "PEN"));
    var withDefault =
        new CreateAccountCommand(
            AccountType.SAVINGS,
            List.of(defaultHolder),
            null,
            null,
            20,
            null,
            List.of(),
            null,
            new Money(new BigDecimal("60.00"), "USD"));
    when(customers.findById(eq(dollarHolder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(dollarHolder, CustomerType.PERSONAL, true)));
    when(customers.findById(eq(defaultHolder), any()))
        .thenReturn(Maybe.just(new CustomerSnapshot(defaultHolder, CustomerType.PERSONAL, true)));
    when(repository.findAll(any(AccountFilter.class), any(Integer.class), any(Integer.class)))
        .thenReturn(Flowable.empty());
    when(repository.save(any(Account.class)))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(0)));

    // Act
    var dollarAccount = service.create(withCurrency).blockingGet();
    var defaultAccount = service.create(withDefault).blockingGet();

    // Assert
    assertEquals(new Money(new BigDecimal("40.00"), "USD"), dollarAccount.minimumDailyAverage());
    assertEquals(new Money(new BigDecimal("60.00"), "PEN"), defaultAccount.minimumDailyAverage());
  }

  private Account account(BigDecimal balance, Long version) {
    return account(AccountType.SAVINGS, balance, null, 10, null, version);
  }

  private Account account(
      AccountType type,
      BigDecimal balance,
      Money maintenanceFee,
      Integer maxMonthlyMovements,
      Integer allowedMovementDay,
      Long version) {
    return new Account(
        UUID.randomUUID(),
        "1234567890123456",
        type,
        new Money(balance, "PEN"),
        List.of(UUID.randomUUID()),
        CustomerType.PERSONAL,
        List.of(),
        maintenanceFee,
        maxMonthlyMovements,
        allowedMovementDay,
        0,
        YearMonth.from(LocalDate.now()),
        version,
        Instant.now());
  }

  private Account account(
      AccountType type,
      BigDecimal balance,
      Integer maxMonthlyMovements,
      Integer allowedMovementDay,
      int movements,
      YearMonth period) {
    return new Account(
        UUID.randomUUID(),
        "1234567890123456",
        type,
        new Money(balance, "PEN"),
        List.of(UUID.randomUUID()),
        CustomerType.PERSONAL,
        List.of(),
        null,
        maxMonthlyMovements,
        allowedMovementDay,
        movements,
        period,
        0L,
        Instant.now());
  }
}
