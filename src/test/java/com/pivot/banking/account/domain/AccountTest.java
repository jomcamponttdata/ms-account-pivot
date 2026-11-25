package com.pivot.banking.account.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the account aggregate's optional minimum daily-average balance. */
class AccountTest {

  @Test
  void legacyConstructorDefaultsMinimumDailyAverageToZeroInBalanceCurrency() {
    // Arrange
    Account account = account();

    // Act / Assert
    assertEquals(new Money(BigDecimal.ZERO, "USD"), account.minimumDailyAverage());
  }

  @Test
  void registerMovementWithFeeDeductsTheFeeFromTheUpdatedBalance() {
    // Arrange
    Account account = account();
    Money fee = new Money(new BigDecimal("2.50"), "USD");
    LocalDate date = LocalDate.of(2025, 5, 10);
    Money amount = new Money(new BigDecimal("5.00"), "USD");

    // Act
    Account afterDeposit = account.registerMovement(MovementType.DEPOSIT, amount, date, fee);
    Account afterWithdrawal = account.registerMovement(MovementType.WITHDRAWAL, amount, date, fee);
    Account withoutFee = account.registerMovement(MovementType.DEPOSIT, amount, date);

    // Assert
    assertEquals(new BigDecimal("102.50"), afterDeposit.balance().amount());
    assertEquals(new BigDecimal("92.50"), afterWithdrawal.balance().amount());
    assertEquals(new BigDecimal("105.00"), withoutFee.balance().amount());
    assertEquals(1, afterDeposit.movementsInCurrentMonth());
    assertEquals(YearMonth.from(date), afterDeposit.movementsPeriod());
  }

  @Test
  void preservesMinimumDailyAverageAcrossDerivedAccounts() {
    // Arrange
    Account original = account();
    Money minimum = new Money(new BigDecimal("75.00"), "USD");
    Account account =
        new Account(
            original.id(),
            original.accountNumber(),
            original.accountType(),
            original.balance(),
            minimum,
            original.holders(),
            original.holderType(),
            original.authorizedSigners(),
            original.maintenanceFee(),
            original.maxMonthlyMovements(),
            original.allowedMovementDay(),
            original.movementsInCurrentMonth(),
            original.movementsPeriod(),
            original.version(),
            original.createdAt());

    // Act
    Account afterMovement =
        account.registerMovement(
            MovementType.DEPOSIT, new Money(new BigDecimal("5.00"), "USD"), LocalDate.now());
    Account afterUpdate =
        account.update(account.holders(), CustomerType.PERSONAL, List.of(), null, 10, null);
    Account afterVersion = account.withVersion(1L);

    // Assert
    assertEquals(minimum, afterMovement.minimumDailyAverage());
    assertEquals(minimum, afterUpdate.minimumDailyAverage());
    assertEquals(minimum, afterVersion.minimumDailyAverage());
  }

  private Account account() {
    return new Account(
        UUID.randomUUID(),
        "19100012345678",
        AccountType.SAVINGS,
        new Money(new BigDecimal("100.00"), "USD"),
        List.of(UUID.randomUUID()),
        CustomerType.PERSONAL,
        List.of(),
        null,
        10,
        null,
        0,
        YearMonth.now(),
        0L,
        Instant.now());
  }
}
