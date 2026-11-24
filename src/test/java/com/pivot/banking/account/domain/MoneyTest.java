package com.pivot.banking.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the monetary value object. */
class MoneyTest {

    @Test
    void acceptsAmountWithTwoFractionDigitsAndUpperCaseCurrency() {
        // Arrange
        // Act
        Money money = new Money(new BigDecimal("25.50"), "PEN");

        // Assert
        assertEquals(new BigDecimal("25.50"), money.amount());
        assertEquals("PEN", money.currency());
    }

    @Test
    void rejectsNullAmount() {
        // Arrange
        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Money(null, "PEN"));

        // Assert
        assertEquals("Invalid monetary amount or currency", error.getMessage());
    }

    @Test
    void rejectsAmountWithMoreThanTwoFractionDigits() {
        // Arrange
        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Money(new BigDecimal("1.234"), "PEN"));

        // Assert
        assertEquals("Invalid monetary amount or currency", error.getMessage());
    }

    @Test
    void rejectsNullCurrency() {
        // Arrange
        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, null));

        // Assert
        assertEquals("Invalid monetary amount or currency", error.getMessage());
    }

    @Test
    void rejectsCurrencyWithWrongFormat() {
        // Arrange
        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Money(BigDecimal.ONE, "pen"));

        // Assert
        assertEquals("Invalid monetary amount or currency", error.getMessage());
    }

    @Test
    void addsAndSubtractsAmountsInSameCurrency() {
        // Arrange
        Money left = new Money(new BigDecimal("25.50"), "PEN");
        Money right = new Money(new BigDecimal("5.25"), "PEN");

        // Act
        Money sum = left.add(right);
        Money difference = left.subtract(right);

        // Assert
        assertEquals(new BigDecimal("30.75"), sum.amount());
        assertEquals(new BigDecimal("20.25"), difference.amount());
    }

    @Test
    void comparesAmountsInSameCurrency() {
        // Arrange
        Money balance = new Money(new BigDecimal("100.00"), "PEN");

        // Act
        boolean sufficient = balance.isGreaterThanOrEqualTo(new Money(new BigDecimal("100.00"), "PEN"));
        boolean insufficient = balance.isGreaterThanOrEqualTo(new Money(new BigDecimal("100.01"), "PEN"));

        // Assert
        assertTrue(sufficient);
        assertFalse(insufficient);
    }

    @Test
    void rejectsArithmeticWithDifferentCurrencies() {
        // Arrange
        Money pen = new Money(BigDecimal.ONE, "PEN");
        Money usd = new Money(BigDecimal.ONE, "USD");

        // Act
        IllegalArgumentException addError = assertThrows(IllegalArgumentException.class, () -> pen.add(usd));
        IllegalArgumentException subtractError = assertThrows(IllegalArgumentException.class, () -> pen.subtract(usd));
        IllegalArgumentException compareError = assertThrows(IllegalArgumentException.class,
                () -> pen.isGreaterThanOrEqualTo(usd));

        // Assert
        assertEquals("Currencies must match", addError.getMessage());
        assertEquals("Currencies must match", subtractError.getMessage());
        assertEquals("Currencies must match", compareError.getMessage());
    }
}
