package com.pivot.banking.account.application.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for inbound command defensive copies. */
class AccountUseCasesTest {

  @Test
  void createCommandNormalizesNullAuthorizedSignersAndCopiesHolders() {
    // Arrange
    UUID holder = UUID.randomUUID();

    // Act
    var command =
        new AccountUseCases.CreateAccountCommand(
            AccountType.SAVINGS, List.of(holder), "PEN", null, 10, null, null);

    // Assert
    assertEquals(List.of(holder), command.holders());
    assertEquals(List.of(), command.authorizedSigners());
  }

  @Test
  void createCommandKeepsOpeningAmountWhenCorrelationIdIsAttached() {
    // Arrange
    Money openingAmount = new Money(new BigDecimal("25.00"), "USD");

    // Act
    var command =
        new AccountUseCases.CreateAccountCommand(
                AccountType.SAVINGS,
                List.of(UUID.randomUUID()),
                "USD",
                null,
                10,
                null,
                List.of(),
                openingAmount)
            .withCorrelationId("account-request-789");

    // Assert
    assertEquals(openingAmount, command.openingAmount());
    assertEquals("account-request-789", command.correlationId());
  }

  @Test
  void createCommandKeepsMinimumDailyAverageWhenCorrelationIdIsAttached() {
    // Arrange
    Money minimum = new Money(new BigDecimal("100.00"), "PEN");

    // Act
    var command =
        new AccountUseCases.CreateAccountCommand(
                AccountType.SAVINGS,
                List.of(UUID.randomUUID()),
                "PEN",
                null,
                10,
                null,
                List.of(),
                null,
                minimum)
            .withCorrelationId("minimum-request");

    // Assert
    assertEquals(minimum, command.minimumDailyAverage());
    assertEquals("minimum-request", command.correlationId());
  }

  @Test
  void updateCommandPreservesNullOptionalCollections() {
    // Arrange
    // Act
    var command = new AccountUseCases.UpdateAccountCommand(null, null, null, null, null);

    // Assert
    assertNull(command.holders());
    assertNull(command.authorizedSigners());
  }
}
