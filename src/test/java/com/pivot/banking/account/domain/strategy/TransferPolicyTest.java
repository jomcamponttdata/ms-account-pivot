package com.pivot.banking.account.domain.strategy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the synchronous transfer validation strategy. */
class TransferPolicyTest {

  private final TransferPolicy policy =
      new TransferPolicy(
          new ExcessMovementFeePolicyFactory(
              List.of(
                  new SavingsExcessFeePolicy(new BigDecimal("1.50")),
                  new CheckingExcessFeePolicy(2, new BigDecimal("0.75")),
                  new FixedTermExcessFeePolicy())));
  private final LocalDate date = LocalDate.of(2026, 1, 5);

  @Test
  void acceptsTransferBetweenAccountsOfDifferentCustomers() {
    // Arrange
    Account source = account(AccountType.CHECKING, "100.00", UUID.randomUUID(), 0);
    Account destination = account(AccountType.SAVINGS, "20.00", UUID.randomUUID(), 0);

    // Act
    // Assert
    assertDoesNotThrow(
        () ->
            policy.validate(source, destination, new Money(new BigDecimal("25.00"), "PEN"), date));
  }

  @Test
  void acceptsTransferBetweenAccountsOfTheSameCustomer() {
    // Arrange
    UUID customer = UUID.randomUUID();
    Account source = account(AccountType.CHECKING, "100.00", customer, 0);
    Account destination = account(AccountType.SAVINGS, "20.00", customer, 0);

    // Act
    // Assert
    assertDoesNotThrow(
        () ->
            policy.validate(source, destination, new Money(new BigDecimal("25.00"), "PEN"), date));
  }

  @Test
  void rejectsTransferToTheSameAccount() {
    // Arrange
    Account account = account(AccountType.CHECKING, "100.00", UUID.randomUUID(), 0);

    // Act
    var failure =
        assertThrows(
            BusinessRuleViolationException.class,
            () ->
                policy.validate(account, account, new Money(new BigDecimal("1.00"), "PEN"), date));

    // Assert
    assertEquals("Source and destination accounts must be distinct", failure.getMessage());
  }

  @Test
  void rejectsNonPositiveOrMissingAmounts() {
    // Arrange
    Account source = account(AccountType.CHECKING, "100.00", UUID.randomUUID(), 0);
    Account destination = account(AccountType.SAVINGS, "20.00", UUID.randomUUID(), 0);

    // Act
    // Assert
    assertThrows(
        BusinessRuleViolationException.class,
        () -> policy.validate(source, destination, new Money(BigDecimal.ZERO, "PEN"), date));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(source, destination, new Money(new BigDecimal("-1.00"), "PEN"), date));
    assertThrows(
        BusinessRuleViolationException.class,
        () -> policy.validate(source, destination, null, date));
  }

  @Test
  void rejectsCurrencyMismatchOnEitherAccount() {
    // Arrange
    Account source = account(AccountType.CHECKING, "100.00", UUID.randomUUID(), 0);
    Account destination = account(AccountType.SAVINGS, "20.00", UUID.randomUUID(), 0);
    Account dollarSource = account(AccountType.CHECKING, "100.00", UUID.randomUUID(), 0, "USD");

    // Act
    // Assert
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(source, destination, new Money(new BigDecimal("25.00"), "USD"), date));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                dollarSource, destination, new Money(new BigDecimal("25.00"), "PEN"), date));
  }

  @Test
  void requiresSourceBalanceToCoverAmountAndItsFee() {
    // Arrange
    Account source = account(AccountType.SAVINGS, "10.00", UUID.randomUUID(), 1);
    Account destination = account(AccountType.SAVINGS, "20.00", UUID.randomUUID(), 0);
    Money amount = new Money(new BigDecimal("9.00"), "PEN");

    // Act
    // Assert
    assertThrows(
        BusinessRuleViolationException.class,
        () -> policy.validate(source, destination, amount, date));
    Account funded = account(AccountType.SAVINGS, "10.50", UUID.randomUUID(), 1);
    assertDoesNotThrow(() -> policy.validate(funded, destination, amount, date));
  }

  @Test
  void resolvesEachSideFeeFromItsOwnMonthlyCountAndPeriod() {
    // Arrange
    Account charged = account(AccountType.SAVINGS, "100.00", UUID.randomUUID(), 1);
    Account withinQuota = account(AccountType.SAVINGS, "100.00", UUID.randomUUID(), 0);
    Account stalePeriod =
        new Account(
            charged.id(),
            charged.accountNumber(),
            charged.accountType(),
            charged.balance(),
            charged.holders(),
            charged.holderType(),
            charged.authorizedSigners(),
            charged.maintenanceFee(),
            charged.maxMonthlyMovements(),
            charged.allowedMovementDay(),
            9,
            YearMonth.from(date).minusMonths(1),
            charged.version(),
            charged.createdAt());

    // Act
    // Assert
    assertEquals(new Money(new BigDecimal("1.50"), "PEN"), policy.feeFor(charged, date));
    assertEquals(new Money(BigDecimal.ZERO, "PEN"), policy.feeFor(withinQuota, date));
    assertEquals(new Money(BigDecimal.ZERO, "PEN"), policy.feeFor(stalePeriod, date));
  }

  @Test
  void rejectsInsufficientFundsOnlyAfterComparingTheFullFee() {
    // Arrange
    Account source = account(AccountType.SAVINGS, "9.99", UUID.randomUUID(), 1);
    Account destination = account(AccountType.SAVINGS, "20.00", UUID.randomUUID(), 0);

    // Act
    var failure =
        assertThrows(
            BusinessRuleViolationException.class,
            () ->
                policy.validate(
                    source, destination, new Money(new BigDecimal("9.00"), "PEN"), date));

    // Assert
    assertEquals(
        "Insufficient source balance to cover the transfer and its fee", failure.getMessage());
    Account funded = account(AccountType.SAVINGS, "11.00", UUID.randomUUID(), 1);
    assertDoesNotThrow(
        () -> policy.validate(funded, destination, new Money(new BigDecimal("9.00"), "PEN"), date));
  }

  private Account account(AccountType type, String balance, UUID holder, int movements) {
    return account(type, balance, holder, movements, "PEN");
  }

  private Account account(
      AccountType type, String balance, UUID holder, int movements, String currency) {
    return new Account(
        UUID.randomUUID(),
        "19100012345678",
        type,
        new Money(new BigDecimal(balance), currency),
        List.of(holder),
        CustomerType.PERSONAL,
        List.of(),
        null,
        type == AccountType.SAVINGS ? 1 : null,
        null,
        movements,
        YearMonth.from(date),
        0L,
        Instant.parse("2026-01-02T03:04:05Z"));
  }
}
