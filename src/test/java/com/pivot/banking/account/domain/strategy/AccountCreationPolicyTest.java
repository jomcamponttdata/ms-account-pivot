package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for account-creation policies and strategy factories. */
class AccountCreationPolicyTest {

    @Test
    void personalPolicyAcceptsOneActivePersonalHolder() {
        // Arrange
        UUID holder = UUID.randomUUID();
        var policy = new PersonalAccountCreationPolicy();

        // Act
        // Assert
        policy.validate(account(AccountType.SAVINGS),
                List.of(snapshot(holder, CustomerType.PERSONAL, true)), List.of());
    }

    @Test
    void personalPolicyRejectsInvalidHolderConfiguration() {
        // Arrange
        UUID holder = UUID.randomUUID();
        var policy = new PersonalAccountCreationPolicy();
        var account = account(AccountType.SAVINGS);

        // Act
        // Assert
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account, List.of(), List.of()));
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account,
                List.of(snapshot(holder, CustomerType.BUSINESS, true)), List.of()));
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account,
                List.of(snapshot(holder, CustomerType.PERSONAL, false)), List.of()));
    }

    @Test
    void personalPolicyRejectsMoreThanOneHolder() {
        // Arrange
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var policy = new PersonalAccountCreationPolicy();

        // Act
        // Assert
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account(AccountType.SAVINGS),
                List.of(snapshot(first, CustomerType.PERSONAL, true),
                        snapshot(second, CustomerType.PERSONAL, true)), List.of()));
    }

    @Test
    void personalPolicyRejectsDuplicateSavingsOrCheckingAndAllowsMultipleFixedTerm() {
        // Arrange
        UUID holder = UUID.randomUUID();
        List<CustomerSnapshot> holders = List.of(snapshot(holder, CustomerType.PERSONAL, true));
        var policy = new PersonalAccountCreationPolicy();
        List<Account> existingSavings = List.of(account(AccountType.SAVINGS));

        // Act
        // Assert
        assertThrows(BusinessRuleViolationException.class, () ->
                policy.validate(account(AccountType.SAVINGS), holders, existingSavings));
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account(AccountType.CHECKING),
                holders, List.of(account(AccountType.CHECKING))));
        policy.validate(account(AccountType.FIXED_TERM), holders, List.of(account(AccountType.FIXED_TERM)));
    }

    @Test
    void businessPolicyAllowsOnlyActiveBusinessCheckingHolders() {
        // Arrange
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var policy = new BusinessAccountCreationPolicy();

        // Act
        // Assert
        policy.validate(account(AccountType.CHECKING), List.of(
                snapshot(first, CustomerType.BUSINESS, true), snapshot(second, CustomerType.BUSINESS, true)), List.of());
        assertThrows(BusinessRuleViolationException.class,
                () -> policy.validate(account(AccountType.CHECKING), List.of(), List.of()));
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account(AccountType.CHECKING),
                List.of(snapshot(first, CustomerType.PERSONAL, true)), List.of()));
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account(AccountType.CHECKING),
                List.of(snapshot(first, CustomerType.BUSINESS, false)), List.of()));
        assertThrows(BusinessRuleViolationException.class, () -> policy.validate(account(AccountType.SAVINGS),
                List.of(snapshot(first, CustomerType.BUSINESS, true)), List.of()));
    }

    @Test
    void creationPolicyFactorySelectsEverySupportedPolicy() {
        // Arrange
        var factory = new AccountCreationPolicyFactory(List.of(
                new PersonalAccountCreationPolicy(), new BusinessAccountCreationPolicy()));

        // Act
        var personal = factory.get(CustomerType.PERSONAL);
        var business = factory.get(CustomerType.BUSINESS);

        // Assert
        assertInstanceOf(PersonalAccountCreationPolicy.class, personal);
        assertInstanceOf(BusinessAccountCreationPolicy.class, business);
    }

    @Test
    void creationPolicyFactoryReportsMissingPolicy() {
        // Arrange
        var factory = new AccountCreationPolicyFactory(List.of());

        // Act
        var personalError = assertThrows(BusinessRuleViolationException.class,
                () -> factory.get(CustomerType.PERSONAL));
        var businessError = assertThrows(BusinessRuleViolationException.class,
                () -> factory.get(CustomerType.BUSINESS));

        // Assert
        assertEquals("Personal policy unavailable", personalError.getMessage());
        assertEquals("Business policy unavailable", businessError.getMessage());
    }

    @Test
    void operationRuleFactorySelectsEverySupportedRule() {
        // Arrange
        var factory = new AccountOperationRuleFactory(List.of(
                new SavingsOperationRule(), new CheckingOperationRule(), new FixedTermOperationRule()));

        // Act
        var savings = factory.get(AccountType.SAVINGS);
        var checking = factory.get(AccountType.CHECKING);
        var fixedTerm = factory.get(AccountType.FIXED_TERM);

        // Assert
        assertInstanceOf(SavingsOperationRule.class, savings);
        assertInstanceOf(CheckingOperationRule.class, checking);
        assertInstanceOf(FixedTermOperationRule.class, fixedTerm);
    }

    @Test
    void operationRuleFactoryReportsMissingRule() {
        // Arrange
        var factory = new AccountOperationRuleFactory(List.of());

        // Act
        var savingsError = assertThrows(BusinessRuleViolationException.class,
                () -> factory.get(AccountType.SAVINGS));
        var checkingError = assertThrows(BusinessRuleViolationException.class,
                () -> factory.get(AccountType.CHECKING));
        var fixedTermError = assertThrows(BusinessRuleViolationException.class,
                () -> factory.get(AccountType.FIXED_TERM));

        // Assert
        assertEquals("Savings rule unavailable", savingsError.getMessage());
        assertEquals("Checking rule unavailable", checkingError.getMessage());
        assertEquals("Fixed-term rule unavailable", fixedTermError.getMessage());
    }

    private CustomerSnapshot snapshot(UUID id, CustomerType type, boolean active) {
        return new CustomerSnapshot(id, type, active);
    }

    private Account account(AccountType type) {
        return new Account(UUID.randomUUID(), "19100012345678", type, new Money(BigDecimal.ZERO, "PEN"),
                List.of(UUID.randomUUID()), CustomerType.PERSONAL, List.of(), null,
                type == AccountType.SAVINGS ? 10 : null,
                type == AccountType.FIXED_TERM ? 15 : null, 0, YearMonth.from(LocalDate.now()), 0L, Instant.now());
    }
}
