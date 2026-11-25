package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.AuthorizedSigner;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountOperationRuleTest {

  @Test
  void savingsAcceptsMovementAfterMonthlyLimitIsReached() {
    // Arrange
    var date = LocalDate.of(2025, 5, 10);
    var account =
        account(AccountType.SAVINGS, 3, null, 3, YearMonth.from(date), new BigDecimal("100.00"));
    var rule = new SavingsOperationRule();

    // Act
    // Assert
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () ->
            rule.validate(
                account, MovementType.DEPOSIT, new Money(new BigDecimal("1.00"), "PEN"), date));
  }

  @Test
  void checkingAllowsMovementsWithoutMonthlyCapWhenBalanceCoversWithdrawal() {
    // Arrange
    var date = LocalDate.of(2025, 5, 10);
    var account =
        account(
            AccountType.CHECKING, null, null, 500, YearMonth.from(date), new BigDecimal("100.00"));
    var rule = new CheckingOperationRule();

    // Act
    // Assert
    rule.validate(
        account, MovementType.WITHDRAWAL, new Money(new BigDecimal("25.00"), "PEN"), date);
  }

  @Test
  void fixedTermRejectsOperationOutsideConfiguredDay() {
    // Arrange
    var date = LocalDate.of(2025, 5, 10);
    var account =
        account(
            AccountType.FIXED_TERM, null, 15, 0, YearMonth.from(date), new BigDecimal("100.00"));
    var rule = new FixedTermOperationRule();

    // Act
    // Assert
    org.junit.jupiter.api.Assertions.assertThrows(
        BusinessRuleViolationException.class,
        () ->
            rule.validate(
                account, MovementType.DEPOSIT, new Money(new BigDecimal("25.00"), "PEN"), date));
  }

  @Test
  void fixedTermRejectsSecondMovementInTheMonth() {
    // Arrange
    var date = LocalDate.of(2025, 5, 15);
    var account =
        account(
            AccountType.FIXED_TERM, null, 15, 1, YearMonth.from(date), new BigDecimal("100.00"));
    var rule = new FixedTermOperationRule();

    // Act
    // Assert
    org.junit.jupiter.api.Assertions.assertThrows(
        BusinessRuleViolationException.class,
        () ->
            rule.validate(
                account, MovementType.DEPOSIT, new Money(new BigDecimal("25.00"), "PEN"), date));
  }

  @Test
  void withdrawalRejectsInsufficientBalance() {
    // Arrange
    var date = LocalDate.of(2025, 5, 15);
    var account =
        account(AccountType.CHECKING, null, null, 0, YearMonth.from(date), new BigDecimal("10.00"));
    var rule = new CheckingOperationRule();

    // Act
    // Assert
    org.junit.jupiter.api.Assertions.assertThrows(
        BusinessRuleViolationException.class,
        () ->
            rule.validate(
                account, MovementType.WITHDRAWAL, new Money(new BigDecimal("25.00"), "PEN"), date));
  }

  @Test
  void fixedTermAcceptsOperationOnConfiguredDayWithoutPriorMonthlyMovement() {
    // Arrange
    var date = LocalDate.of(2025, 5, 15);
    var account =
        account(
            AccountType.FIXED_TERM, null, 15, 0, YearMonth.from(date), new BigDecimal("100.00"));

    // Act
    // Assert
    new FixedTermOperationRule()
        .validate(account, MovementType.DEPOSIT, new Money(new BigDecimal("25.00"), "PEN"), date);
  }

  @Test
  void savingsAllowsMovementAfterMonthRollover() {
    // Arrange
    var date = LocalDate.of(2025, 6, 1);
    var account =
        account(
            AccountType.SAVINGS,
            3,
            null,
            3,
            YearMonth.from(date.minusMonths(1)),
            new BigDecimal("100.00"));

    // Act
    // Assert
    new SavingsOperationRule()
        .validate(account, MovementType.DEPOSIT, new Money(new BigDecimal("1.00"), "PEN"), date);
  }

  @Test
  void fixedTermAllowsMovementAfterMonthRollover() {
    // Arrange
    var date = LocalDate.of(2025, 6, 15);
    var account =
        account(
            AccountType.FIXED_TERM,
            null,
            15,
            1,
            YearMonth.from(date.minusMonths(1)),
            new BigDecimal("100.00"));

    // Act
    // Assert
    new FixedTermOperationRule()
        .validate(account, MovementType.DEPOSIT, new Money(new BigDecimal("1.00"), "PEN"), date);
  }

  @Test
  void rejectsOperationWhoseCurrencyDoesNotMatchAccountCurrency() {
    // Arrange
    var date = LocalDate.of(2025, 5, 15);
    var account =
        account(
            AccountType.CHECKING, null, null, 0, YearMonth.from(date), new BigDecimal("100.00"));

    // Act
    // Assert
    var exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            BusinessRuleViolationException.class,
            () ->
                new CheckingOperationRule()
                    .validate(
                        account,
                        MovementType.DEPOSIT,
                        new Money(new BigDecimal("1.00"), "USD"),
                        date));
    org.junit.jupiter.api.Assertions.assertEquals(
        "Operation currency must match account currency", exception.getMessage());
  }

  /** Builds an account fixture with product-specific operation counters. */
  private Account account(
      AccountType type,
      Integer maxMonthly,
      Integer movementDay,
      int movements,
      YearMonth period,
      BigDecimal balance) {
    return new Account(
        UUID.randomUUID(),
        "1234567890123456",
        type,
        new Money(balance, "PEN"),
        List.of(UUID.randomUUID()),
        CustomerType.PERSONAL,
        List.<AuthorizedSigner>of(),
        null,
        maxMonthly,
        movementDay,
        movements,
        period,
        0L,
        Instant.parse("2025-01-01T00:00:00Z"));
  }
}
