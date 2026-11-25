package com.pivot.banking.account.domain.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
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

/** Unit tests for profile-specific account policies and their factory. */
class AccountProfilePolicyTest {

  @Test
  void vipPolicyAcceptsOnlyVipSavingsAndActiveVipPersonalHolders() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var policy = new VipSavingsAccountPolicy();

    // Act
    // Assert
    assertTrue(policy.supports(CustomerProfile.VIP, AccountType.SAVINGS));
    assertFalse(policy.supports(CustomerProfile.VIP, AccountType.CHECKING));
    assertFalse(policy.supports(CustomerProfile.PYME, AccountType.SAVINGS));
    assertFalse(policy.supports(null, AccountType.SAVINGS));
    assertTrue(policy.requiresCreditCard());
    policy.validate(
        account(AccountType.SAVINGS),
        List.of(snapshot(holder, CustomerType.PERSONAL, true, CustomerProfile.VIP)));
  }

  @Test
  void vipPolicyRejectsMixedOrInactiveHolders() {
    // Arrange
    UUID holder = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    var policy = new VipSavingsAccountPolicy();
    var account = account(AccountType.SAVINGS);

    // Act
    // Assert
    assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account, List.of()));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                account,
                List.of(snapshot(holder, CustomerType.BUSINESS, true, CustomerProfile.VIP))));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                account,
                List.of(snapshot(holder, CustomerType.PERSONAL, true, CustomerProfile.PYME))));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                account,
                List.of(snapshot(holder, CustomerType.PERSONAL, false, CustomerProfile.VIP))));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                account,
                List.of(
                    snapshot(holder, CustomerType.PERSONAL, true, CustomerProfile.VIP),
                    snapshot(other, CustomerType.PERSONAL, true, null))));
  }

  @Test
  void pymePolicyAcceptsOnlyPymeCheckingAndActivePymeBusinessHolders() {
    // Arrange
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var policy = new PymeCheckingAccountPolicy();

    // Act
    // Assert
    assertTrue(policy.supports(CustomerProfile.PYME, AccountType.CHECKING));
    assertFalse(policy.supports(CustomerProfile.PYME, AccountType.SAVINGS));
    assertFalse(policy.supports(CustomerProfile.VIP, AccountType.CHECKING));
    assertFalse(policy.supports(null, AccountType.CHECKING));
    assertTrue(policy.requiresCreditCard());
    policy.validate(
        account(AccountType.CHECKING),
        List.of(
            snapshot(first, CustomerType.BUSINESS, true, CustomerProfile.PYME),
            snapshot(second, CustomerType.BUSINESS, true, CustomerProfile.PYME)));
  }

  @Test
  void pymePolicyRejectsMixedOrInactiveHolders() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var policy = new PymeCheckingAccountPolicy();
    var account = account(AccountType.CHECKING);

    // Act
    // Assert
    assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account, List.of()));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                account,
                List.of(snapshot(holder, CustomerType.PERSONAL, true, CustomerProfile.PYME))));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                account,
                List.of(snapshot(holder, CustomerType.BUSINESS, true, CustomerProfile.VIP))));
    assertThrows(
        BusinessRuleViolationException.class,
        () ->
            policy.validate(
                account,
                List.of(snapshot(holder, CustomerType.BUSINESS, false, CustomerProfile.PYME))));
  }

  @Test
  void pymePolicyForcesZeroMaintenanceFeeWithoutRejectingIt() {
    // Arrange
    var policy = new PymeCheckingAccountPolicy();
    Money requested = new Money(new BigDecimal("2.50"), "PEN");

    // Act
    Money effective = policy.effectiveMaintenanceFee(requested);

    // Assert
    assertEquals(0, effective.amount().compareTo(BigDecimal.ZERO));
    assertEquals("PEN", effective.currency());
    assertNull(policy.effectiveMaintenanceFee(null));
  }

  @Test
  void standardPolicyIsAnInertFallback() {
    // Arrange
    UUID holder = UUID.randomUUID();
    var policy = new StandardAccountProfilePolicy();
    Money requested = new Money(new BigDecimal("2.50"), "PEN");

    // Act
    // Assert
    assertFalse(policy.supports(CustomerProfile.VIP, AccountType.SAVINGS));
    assertFalse(policy.supports(null, AccountType.CHECKING));
    assertFalse(policy.requiresCreditCard());
    policy.validate(
        account(AccountType.CHECKING),
        List.of(snapshot(holder, CustomerType.BUSINESS, true, null)));
    assertEquals(requested, policy.effectiveMaintenanceFee(requested));
  }

  @Test
  void factorySelectsSpecialPoliciesAndFallsBackForNullOrMismatchedCombinations() {
    // Arrange
    var factory =
        new AccountProfilePolicyFactory(
            List.of(
                new VipSavingsAccountPolicy(),
                new PymeCheckingAccountPolicy(),
                new StandardAccountProfilePolicy()));

    // Act
    var vip = factory.get(CustomerProfile.VIP, AccountType.SAVINGS);
    var pyme = factory.get(CustomerProfile.PYME, AccountType.CHECKING);
    var nullProfile = factory.get(null, AccountType.CHECKING);
    var wrongProduct = factory.get(CustomerProfile.VIP, AccountType.CHECKING);
    var wrongSegment = factory.get(CustomerProfile.PYME, AccountType.SAVINGS);

    // Assert
    assertInstanceOf(VipSavingsAccountPolicy.class, vip);
    assertInstanceOf(PymeCheckingAccountPolicy.class, pyme);
    assertInstanceOf(StandardAccountProfilePolicy.class, nullProfile);
    assertInstanceOf(StandardAccountProfilePolicy.class, wrongProduct);
    assertInstanceOf(StandardAccountProfilePolicy.class, wrongSegment);
  }

  private CustomerSnapshot snapshot(
      UUID id, CustomerType type, boolean active, CustomerProfile profile) {
    return new CustomerSnapshot(id, type, active, profile);
  }

  private Account account(AccountType type) {
    return new Account(
        UUID.randomUUID(),
        "19100012345678",
        type,
        new Money(BigDecimal.ZERO, "PEN"),
        List.of(UUID.randomUUID()),
        CustomerType.PERSONAL,
        List.of(),
        null,
        type == AccountType.SAVINGS ? 10 : null,
        type == AccountType.FIXED_TERM ? 15 : null,
        0,
        YearMonth.from(LocalDate.now()),
        0L,
        Instant.now());
  }
}
