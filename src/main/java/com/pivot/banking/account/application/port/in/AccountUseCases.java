package com.pivot.banking.account.application.port.in;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.AuthorizedSigner;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.UUID;

/** Input ports for account CRUD, balance, and monetary operations. */
public final class AccountUseCases {

  private AccountUseCases() {}

  /** Opens an account. */
  public interface CreateAccount {
    Single<Account> create(CreateAccountCommand command);
  }

  /** Retrieves accounts and balances. */
  public interface GetAccounts {
    Flowable<Account> findAll(AccountFilter filter, int page, int size);

    Single<Account> findById(UUID id);
  }

  /** Updates editable account details. */
  public interface UpdateAccount {
    Single<Account> update(UUID id, UpdateAccountCommand command);
  }

  /** Deletes an account. */
  public interface DeleteAccount {
    Completable delete(UUID id);
  }

  /** Deposits or withdraws money and registers the corresponding movement. */
  public interface OperateAccount {
    Single<MovementReceipt> operate(
        UUID accountId, MovementType movementType, OperationCommand command);
  }

  /** Moves money between two accounts of the same bank with one shared correlation id. */
  public interface TransferBetweenAccounts {
    Single<TransferReceipt> transfer(TransferCommand command);
  }

  /**
   * Data required to open one of the supported account products. The optional {@code
   * minimumDailyAverage} is customer-defined and defaults to zero; monthly verification is not
   * implemented because daily balance history is not modeled.
   */
  public record CreateAccountCommand(
      AccountType accountType,
      List<UUID> holders,
      String currency,
      Money maintenanceFee,
      Integer maxMonthlyMovements,
      Integer allowedMovementDay,
      List<AuthorizedSigner> authorizedSigners,
      Money openingAmount,
      Money minimumDailyAverage,
      String correlationId) {
    /** Creates a command without an opening amount, daily-average minimum, or correlation id. */
    public CreateAccountCommand(
        AccountType accountType,
        List<UUID> holders,
        String currency,
        Money maintenanceFee,
        Integer maxMonthlyMovements,
        Integer allowedMovementDay,
        List<AuthorizedSigner> authorizedSigners) {
      this(
          accountType,
          holders,
          currency,
          maintenanceFee,
          maxMonthlyMovements,
          allowedMovementDay,
          authorizedSigners,
          null,
          null,
          null);
    }

    /** Creates a command with a correlation id and no monetary opening options. */
    public CreateAccountCommand(
        AccountType accountType,
        List<UUID> holders,
        String currency,
        Money maintenanceFee,
        Integer maxMonthlyMovements,
        Integer allowedMovementDay,
        List<AuthorizedSigner> authorizedSigners,
        String correlationId) {
      this(
          accountType,
          holders,
          currency,
          maintenanceFee,
          maxMonthlyMovements,
          allowedMovementDay,
          authorizedSigners,
          null,
          null,
          correlationId);
    }

    /** Creates a command with an opening amount and no correlation id. */
    public CreateAccountCommand(
        AccountType accountType,
        List<UUID> holders,
        String currency,
        Money maintenanceFee,
        Integer maxMonthlyMovements,
        Integer allowedMovementDay,
        List<AuthorizedSigner> authorizedSigners,
        Money openingAmount) {
      this(
          accountType,
          holders,
          currency,
          maintenanceFee,
          maxMonthlyMovements,
          allowedMovementDay,
          authorizedSigners,
          openingAmount,
          null,
          null);
    }

    /** Creates a command with monetary opening options and no correlation id. */
    public CreateAccountCommand(
        AccountType accountType,
        List<UUID> holders,
        String currency,
        Money maintenanceFee,
        Integer maxMonthlyMovements,
        Integer allowedMovementDay,
        List<AuthorizedSigner> authorizedSigners,
        Money openingAmount,
        Money minimumDailyAverage) {
      this(
          accountType,
          holders,
          currency,
          maintenanceFee,
          maxMonthlyMovements,
          allowedMovementDay,
          authorizedSigners,
          openingAmount,
          minimumDailyAverage,
          null);
    }

    public CreateAccountCommand {
      holders = List.copyOf(holders);
      authorizedSigners = authorizedSigners == null ? List.of() : List.copyOf(authorizedSigners);
    }

    /** Returns a copy carrying the supplied correlation id. */
    public CreateAccountCommand withCorrelationId(String value) {
      return new CreateAccountCommand(
          accountType,
          holders,
          currency,
          maintenanceFee,
          maxMonthlyMovements,
          allowedMovementDay,
          authorizedSigners,
          openingAmount,
          minimumDailyAverage,
          value);
    }
  }

  /** Editable account details, with product-specific values validated against account type. */
  public record UpdateAccountCommand(
      List<UUID> holders,
      List<AuthorizedSigner> authorizedSigners,
      Money maintenanceFee,
      Integer maxMonthlyMovements,
      Integer allowedMovementDay,
      String correlationId) {
    /** Creates an update command without a correlation id. */
    public UpdateAccountCommand(
        List<UUID> holders,
        List<AuthorizedSigner> authorizedSigners,
        Money maintenanceFee,
        Integer maxMonthlyMovements,
        Integer allowedMovementDay) {
      this(
          holders,
          authorizedSigners,
          maintenanceFee,
          maxMonthlyMovements,
          allowedMovementDay,
          null);
    }

    public UpdateAccountCommand {
      holders = holders == null ? null : List.copyOf(holders);
      authorizedSigners = authorizedSigners == null ? null : List.copyOf(authorizedSigners);
    }

    /** Returns a copy carrying the supplied correlation id. */
    public UpdateAccountCommand withCorrelationId(String value) {
      return new UpdateAccountCommand(
          holders,
          authorizedSigners,
          maintenanceFee,
          maxMonthlyMovements,
          allowedMovementDay,
          value);
    }
  }

  /** Details of a deposit or withdrawal request. */
  public record OperationCommand(Money amount, String description, String correlationId) {
    public OperationCommand(Money amount, String description) {
      this(amount, description, null);
    }

    public OperationCommand withCorrelationId(String value) {
      return new OperationCommand(amount, description, value);
    }
  }

  /** Details of a transfer between a source and a destination account. */
  public record TransferCommand(
      UUID sourceAccountId,
      UUID destinationAccountId,
      Money amount,
      String description,
      String correlationId) {
    public TransferCommand(
        UUID sourceAccountId, UUID destinationAccountId, Money amount, String description) {
      this(sourceAccountId, destinationAccountId, amount, description, null);
    }

    public TransferCommand withCorrelationId(String value) {
      return new TransferCommand(sourceAccountId, destinationAccountId, amount, description, value);
    }
  }

  /** Receipts of the two movements registered for one transfer. */
  public record TransferReceipt(
      MovementReceipt withdrawal, MovementReceipt deposit, String correlationId) {}
}
