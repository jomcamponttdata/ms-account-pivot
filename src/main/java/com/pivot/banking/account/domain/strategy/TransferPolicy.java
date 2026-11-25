package com.pivot.banking.account.domain.strategy;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Component;

/** Synchronous validation and per-side fee resolution for a transfer between two accounts. */
@Component
public class TransferPolicy {

  private final ExcessMovementFeePolicyFactory fees;

  /** Creates the policy with the configured excess-movement fee strategies. */
  public TransferPolicy(ExcessMovementFeePolicyFactory fees) {
    this.fees = fees;
  }

  /**
   * Validates that the accounts are distinct, the amount is positive, both accounts and the amount
   * share one currency, and the source balance covers the amount plus its excess fee.
   */
  public void validate(Account source, Account destination, Money amount, LocalDate date) {
    if (source.id().equals(destination.id())) {
      throw new BusinessRuleViolationException("Source and destination accounts must be distinct");
    }
    if (amount == null || amount.amount().signum() <= 0) {
      throw new BusinessRuleViolationException("Transfer amount must be greater than zero");
    }
    boolean currenciesMatch =
        List.of(source, destination).stream()
            .map(account -> account.balance().currency())
            .allMatch(currency -> currency.equals(amount.currency()));
    if (!currenciesMatch) {
      throw new BusinessRuleViolationException(
          "Transfer currency must match both account currencies");
    }
    Money sourceFee = feeFor(source, date);
    if (!source.balance().isGreaterThanOrEqualTo(amount.add(sourceFee))) {
      throw new BusinessRuleViolationException(
          "Insufficient source balance to cover the transfer and its fee");
    }
  }

  /** Returns the excess-movement fee charged to one side, using that account's monthly count. */
  public Money feeFor(Account account, LocalDate date) {
    int currentCount =
        YearMonth.from(date).equals(account.movementsPeriod())
            ? account.movementsInCurrentMonth()
            : 0;
    ExcessMovementFeePolicy policy = fees.get(account.accountType());
    return currentCount >= policy.freeMovements(account)
        ? policy.fee(account)
        : new Money(BigDecimal.ZERO, account.balance().currency());
  }
}
