package com.pivot.banking.account.domain.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for product-specific opening-minimum strategies and their factory. */
class OpeningMinimumPolicyTest {

  @Test
  void everyProductPolicyDefaultsToZeroInTheRequestedCurrency() {
    // Arrange
    var savings = new SavingsOpeningMinimumPolicy(BigDecimal.ZERO);
    var checking = new CheckingOpeningMinimumPolicy(BigDecimal.ZERO);
    var fixedTerm = new FixedTermOpeningMinimumPolicy(BigDecimal.ZERO);

    // Act
    var savingsMinimum = savings.minimumFor(AccountType.SAVINGS, "USD");
    var checkingMinimum = checking.minimumFor(AccountType.CHECKING, "USD");
    var fixedTermMinimum = fixedTerm.minimumFor(AccountType.FIXED_TERM, "PEN");

    // Assert
    assertEquals(0, savingsMinimum.amount().compareTo(BigDecimal.ZERO));
    assertEquals(0, checkingMinimum.amount().compareTo(BigDecimal.ZERO));
    assertEquals(0, fixedTermMinimum.amount().compareTo(BigDecimal.ZERO));
    assertEquals("USD", savingsMinimum.currency());
    assertEquals("USD", checkingMinimum.currency());
    assertEquals("PEN", fixedTermMinimum.currency());
  }

  @Test
  void eachPolicySupportsOnlyItsOwnProduct() {
    // Arrange
    var policies =
        List.<OpeningMinimumPolicy>of(
            new SavingsOpeningMinimumPolicy(BigDecimal.ZERO),
            new CheckingOpeningMinimumPolicy(BigDecimal.ZERO),
            new FixedTermOpeningMinimumPolicy(BigDecimal.ZERO));

    // Act
    // Assert
    for (AccountType type : AccountType.values()) {
      long supporters = policies.stream().filter(policy -> policy.supports(type)).count();
      assertEquals(1, supporters);
    }
    assertTrue(policies.get(0).supports(AccountType.SAVINGS));
    assertFalse(policies.get(0).supports(AccountType.CHECKING));
    assertFalse(policies.get(2).supports(null));
  }

  @Test
  void factorySelectsThePolicyForEveryProductAndRejectsUnknownTypes() {
    // Arrange
    var factory =
        new OpeningMinimumPolicyFactory(
            List.of(
                new SavingsOpeningMinimumPolicy(BigDecimal.ZERO),
                new CheckingOpeningMinimumPolicy(BigDecimal.ZERO),
                new FixedTermOpeningMinimumPolicy(BigDecimal.ZERO)));

    // Act
    // Assert
    assertInstanceOf(SavingsOpeningMinimumPolicy.class, factory.get(AccountType.SAVINGS));
    assertInstanceOf(CheckingOpeningMinimumPolicy.class, factory.get(AccountType.CHECKING));
    assertInstanceOf(FixedTermOpeningMinimumPolicy.class, factory.get(AccountType.FIXED_TERM));
    assertThrows(BusinessRuleViolationException.class, () -> factory.get(null));
    assertThrows(
        BusinessRuleViolationException.class,
        () -> new OpeningMinimumPolicyFactory(List.of()).get(AccountType.SAVINGS));
  }
}
