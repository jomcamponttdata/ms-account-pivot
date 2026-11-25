package com.pivot.banking.account.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Account aggregate holding its balance and its own monthly movement counter. The optional
 * customer-defined {@code minimumDailyAverage} is meaningful for VIP personal savings only. Monthly
 * verification is not implemented: it requires a daily balance history that this model does not
 * keep, so the value is stored but no rule is enforced against it.
 */
public record Account(
    UUID id,
    String accountNumber,
    AccountType accountType,
    Money balance,
    Money minimumDailyAverage,
    List<UUID> holders,
    CustomerType holderType,
    List<AuthorizedSigner> authorizedSigners,
    Money maintenanceFee,
    Integer maxMonthlyMovements,
    Integer allowedMovementDay,
    int movementsInCurrentMonth,
    YearMonth movementsPeriod,
    Long version,
    Instant createdAt) {

  /** Defensively copies holder and signer collections. */
  public Account {
    holders = List.copyOf(holders);
    authorizedSigners = List.copyOf(authorizedSigners);
  }

  /** Creates an account without a minimum daily-average balance, defaulting it to zero. */
  public Account(
      UUID id,
      String accountNumber,
      AccountType accountType,
      Money balance,
      List<UUID> holders,
      CustomerType holderType,
      List<AuthorizedSigner> authorizedSigners,
      Money maintenanceFee,
      Integer maxMonthlyMovements,
      Integer allowedMovementDay,
      int movementsInCurrentMonth,
      YearMonth movementsPeriod,
      Long version,
      Instant createdAt) {
    this(
        id,
        accountNumber,
        accountType,
        balance,
        new Money(BigDecimal.ZERO, balance.currency()),
        holders,
        holderType,
        authorizedSigners,
        maintenanceFee,
        maxMonthlyMovements,
        allowedMovementDay,
        movementsInCurrentMonth,
        movementsPeriod,
        version,
        createdAt);
  }

  /**
   * Applies a deposit or withdrawal with no excess fee and advances the monthly movement counter.
   */
  public Account registerMovement(MovementType movementType, Money amount, LocalDate date) {
    return registerMovement(
        movementType, amount, date, new Money(BigDecimal.ZERO, balance.currency()));
  }

  /** Applies a deposit or withdrawal, deducts its excess fee, and advances the monthly counter. */
  public Account registerMovement(
      MovementType movementType, Money amount, LocalDate date, Money fee) {
    Money movedBalance =
        movementType == MovementType.DEPOSIT ? balance.add(amount) : balance.subtract(amount);
    Money updatedBalance = movedBalance.subtract(fee);
    YearMonth currentPeriod = YearMonth.from(date);
    int currentCount = currentPeriod.equals(movementsPeriod) ? movementsInCurrentMonth : 0;
    return new Account(
        id,
        accountNumber,
        accountType,
        updatedBalance,
        minimumDailyAverage,
        holders,
        holderType,
        authorizedSigners,
        maintenanceFee,
        maxMonthlyMovements,
        allowedMovementDay,
        currentCount + 1,
        currentPeriod,
        version,
        createdAt);
  }

  /** Replaces editable account details without changing type, balance, or movement history. */
  public Account update(
      List<UUID> updatedHolders,
      CustomerType updatedHolderType,
      List<AuthorizedSigner> updatedSigners,
      Money updatedMaintenanceFee,
      Integer updatedMaxMonthlyMovements,
      Integer updatedAllowedMovementDay) {
    return new Account(
        id,
        accountNumber,
        accountType,
        balance,
        minimumDailyAverage,
        updatedHolders,
        updatedHolderType,
        updatedSigners,
        updatedMaintenanceFee,
        updatedMaxMonthlyMovements,
        updatedAllowedMovementDay,
        movementsInCurrentMonth,
        movementsPeriod,
        version,
        createdAt);
  }

  /** Returns a copy carrying the version assigned by the persistence layer. */
  public Account withVersion(Long updatedVersion) {
    return new Account(
        id,
        accountNumber,
        accountType,
        balance,
        minimumDailyAverage,
        holders,
        holderType,
        authorizedSigners,
        maintenanceFee,
        maxMonthlyMovements,
        allowedMovementDay,
        movementsInCurrentMonth,
        movementsPeriod,
        updatedVersion,
        createdAt);
  }
}
