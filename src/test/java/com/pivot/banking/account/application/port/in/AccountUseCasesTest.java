package com.pivot.banking.account.application.port.in;

import com.pivot.banking.account.domain.AccountType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for inbound command defensive copies. */
class AccountUseCasesTest {

    @Test
    void createCommandNormalizesNullAuthorizedSignersAndCopiesHolders() {
        // Arrange
        UUID holder = UUID.randomUUID();

        // Act
        var command = new AccountUseCases.CreateAccountCommand(
                AccountType.SAVINGS, List.of(holder), "PEN", null, 10, null, null);

        // Assert
        assertEquals(List.of(holder), command.holders());
        assertEquals(List.of(), command.authorizedSigners());
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
