package com.pivot.banking.account.domain.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.AuthorizedSigner;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for product-specific excess-movement fee strategies and their factory. */
class ExcessMovementFeePolicyTest {

  @Test
  void everyProductPolicyDefaultsToZeroFeeInTheAccountCurrency() {
    // Arrange
    var savings = new SavingsExcessFeePolicy(BigDecimal.ZERO);
    var checking = new CheckingExcessFeePolicy(0, BigDecimal.ZERO);
    var fixedTerm = new FixedTermExcessFeePolicy();
    var account = account(AccountType.SAVINGS, "USD", 3);

    // Act
    var savingsFee = savings.fee(account);
    var checkingFee = checking.fee(account);
    var fixedTermFee = fixedTerm.fee(account);

    // Assert
    assertEquals(0, savingsFee.amount().compareTo(BigDecimal.ZERO));
    assertEquals(0, checkingFee.amount().compareTo(BigDecimal.ZERO));
    assertEquals(0, fixedTermFee.amount().compareTo(BigDecimal.ZERO));
    assertEquals("USD", savingsFee.currency());
    assertEquals("USD", checkingFee.currency());
    assertEquals("USD", fixedTermFee.currency());
  }

  @Test
  void savingsQuotaIsTheAccountMonthlyLimitAndTheFeeIsConfigured() {
    // Arrange
    var policy = new SavingsExcessFeePolicy(new BigDecimal("1.50"));
    var account = account(AccountType.SAVINGS, "PEN", 3);

    // Act
    // Assert
    assertEquals(3, policy.freeMovements(account));
    assertEquals(new Money(new BigDecimal("1.50"), "PEN"), policy.fee(account));
    assertEquals(0, policy.freeMovements(account(AccountType.SAVINGS, "PEN", null)));
  }

  @Test
  void checkingQuotaAndFeeAreConfigured() {
    // Arrange
    var policy = new CheckingExcessFeePolicy(2, new BigDecimal("0.75"));
    var account = account(AccountType.CHECKING, "PEN", null);

    // Act
    // Assert
    assertEquals(2, policy.freeMovements(account));
    assertEquals(new Money(new BigDecimal("0.75"), "PEN"), policy.fee(account));
    assertEquals(0, new CheckingExcessFeePolicy(0, BigDecimal.ZERO).freeMovements(account));
  }

  @Test
  void fixedTermHasOneFreeMovementAndChargesNoExcessFee() {
    // Arrange
    var policy = new FixedTermExcessFeePolicy();
    var account = account(AccountType.FIXED_TERM, "PEN", null);

    // Act
    // Assert
    assertEquals(1, policy.freeMovements(account));
    assertEquals(new Money(BigDecimal.ZERO, "PEN"), policy.fee(account));
  }

  @Test
  void eachPolicySupportsOnlyItsOwnProduct() {
    // Arrange
    var policies =
        List.<ExcessMovementFeePolicy>of(
            new SavingsExcessFeePolicy(BigDecimal.ZERO),
            new CheckingExcessFeePolicy(0, BigDecimal.ZERO),
            new FixedTermExcessFeePolicy());

    // Act
    // Assert
    for (AccountType type : AccountType.values()) {
      assertEquals(1, policies.stream().filter(policy -> policy.supports(type)).count());
    }
    assertTrue(policies.get(0).supports(AccountType.SAVINGS));
    assertFalse(policies.get(0).supports(AccountType.CHECKING));
    assertFalse(policies.get(2).supports(null));
  }

  @Test
  void factorySelectsThePolicyForEveryProductAndRejectsUnknownTypes() {
    // Arrange
    var factory =
        new ExcessMovementFeePolicyFactory(
            List.of(
                new SavingsExcessFeePolicy(BigDecimal.ZERO),
                new CheckingExcessFeePolicy(0, BigDecimal.ZERO),
                new FixedTermExcessFeePolicy()));

    // Act
    // Assert
    assertInstanceOf(SavingsExcessFeePolicy.class, factory.get(AccountType.SAVINGS));
    assertInstanceOf(CheckingExcessFeePolicy.class, factory.get(AccountType.CHECKING));
    assertInstanceOf(FixedTermExcessFeePolicy.class, factory.get(AccountType.FIXED_TERM));
    assertThrows(BusinessRuleViolationException.class, () -> factory.get(null));
    assertThrows(
        BusinessRuleViolationException.class,
        () -> new ExcessMovementFeePolicyFactory(List.of()).get(AccountType.SAVINGS));
  }

  /** Builds an account fixture with the product-specific monthly movement quota. */
  private Account account(AccountType type, String currency, Integer maxMonthlyMovements) {
    return new Account(
        UUID.randomUUID(),
        "1234567890123456",
        type,
        new Money(new BigDecimal("100.00"), currency),
        List.of(UUID.randomUUID()),
        CustomerType.PERSONAL,
        List.<AuthorizedSigner>of(),
        null,
        maxMonthlyMovements,
        type == AccountType.FIXED_TERM ? 15 : null,
        0,
        YearMonth.now(),
        0L,
        Instant.now());
  }
}
