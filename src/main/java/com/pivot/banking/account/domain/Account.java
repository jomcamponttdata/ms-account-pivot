package com.pivot.banking.account.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Account aggregate holding its balance and its own monthly movement counter. */
public record Account(
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

    /** Defensively copies holder and signer collections. */
    public Account {
        holders = List.copyOf(holders);
        authorizedSigners = List.copyOf(authorizedSigners);
    }

    /** Applies a deposit or withdrawal and advances this account's monthly movement counter. */
    public Account registerMovement(MovementType movementType, Money amount, LocalDate date) {
        Money updatedBalance = movementType == MovementType.DEPOSIT
                ? balance.add(amount)
                : balance.subtract(amount);
        YearMonth currentPeriod = YearMonth.from(date);
        int currentCount = currentPeriod.equals(movementsPeriod) ? movementsInCurrentMonth : 0;
        return new Account(id, accountNumber, accountType, updatedBalance, holders, holderType, authorizedSigners,
                maintenanceFee, maxMonthlyMovements, allowedMovementDay, currentCount + 1,
                currentPeriod, version, createdAt);
    }

    /** Replaces editable account details without changing type, balance, or movement history. */
    public Account update(List<UUID> updatedHolders, CustomerType updatedHolderType,
                         List<AuthorizedSigner> updatedSigners,
                         Money updatedMaintenanceFee, Integer updatedMaxMonthlyMovements,
                         Integer updatedAllowedMovementDay) {
        return new Account(id, accountNumber, accountType, balance, updatedHolders, updatedHolderType, updatedSigners,
                updatedMaintenanceFee, updatedMaxMonthlyMovements, updatedAllowedMovementDay,
                movementsInCurrentMonth, movementsPeriod, version, createdAt);
    }

    /** Returns a copy carrying the version assigned by the persistence layer. */
    public Account withVersion(Long updatedVersion) {
        return new Account(id, accountNumber, accountType, balance, holders, holderType, authorizedSigners,
                maintenanceFee, maxMonthlyMovements, allowedMovementDay, movementsInCurrentMonth,
                movementsPeriod, updatedVersion, createdAt);
    }
}
