package com.pivot.banking.account.application.service;

import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccountCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.DeleteAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.GetAccounts;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperationCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.TransferBetweenAccounts;
import com.pivot.banking.account.application.port.in.AccountUseCases.TransferCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.TransferReceipt;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccountCommand;
import com.pivot.banking.account.application.port.out.AccountRepositoryPort;
import com.pivot.banking.account.application.port.out.CreditCardGateway;
import com.pivot.banking.account.application.port.out.CustomerGateway;
import com.pivot.banking.account.application.port.out.MovementGateway;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerProfile;
import com.pivot.banking.account.domain.CustomerSnapshot;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.AccountNotFoundException;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import com.pivot.banking.account.domain.strategy.AccountCreationPolicyFactory;
import com.pivot.banking.account.domain.strategy.AccountOperationRuleFactory;
import com.pivot.banking.account.domain.strategy.AccountProfilePolicy;
import com.pivot.banking.account.domain.strategy.AccountProfilePolicyFactory;
import com.pivot.banking.account.domain.strategy.ExcessMovementFeePolicy;
import com.pivot.banking.account.domain.strategy.ExcessMovementFeePolicyFactory;
import com.pivot.banking.account.domain.strategy.OpeningMinimumPolicyFactory;
import com.pivot.banking.account.domain.strategy.TransferPolicy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Coordinates account use cases and delegates to outbound ports. */
@Service
public class AccountApplicationService
    implements CreateAccount,
        GetAccounts,
        UpdateAccount,
        DeleteAccount,
        OperateAccount,
        TransferBetweenAccounts {

  private final AccountRepositoryPort repository;
  private final CustomerGateway customers;
  private final CreditCardGateway creditCards;
  private final MovementGateway movements;
  private final AccountCreationPolicyFactory creationPolicies;
  private final AccountProfilePolicyFactory profilePolicies;
  private final AccountOperationRuleFactory operationRules;
  private final ExcessMovementFeePolicyFactory excessFees;
  private final OpeningMinimumPolicyFactory openingMinimums;
  private final TransferPolicy transferPolicy;
  private final int maxPageSize;

  /**
   * Creates the service with persistence, downstream gateways, policies, and page-size
   * configuration.
   */
  public AccountApplicationService(
      AccountRepositoryPort repository,
      CustomerGateway customers,
      CreditCardGateway creditCards,
      MovementGateway movements,
      AccountCreationPolicyFactory creationPolicies,
      AccountProfilePolicyFactory profilePolicies,
      AccountOperationRuleFactory operationRules,
      ExcessMovementFeePolicyFactory excessFees,
      OpeningMinimumPolicyFactory openingMinimums,
      TransferPolicy transferPolicy,
      @Value("${account.pagination.max-size:100}") int maxPageSize) {
    this.repository = repository;
    this.customers = customers;
    this.creditCards = creditCards;
    this.movements = movements;
    this.creationPolicies = creationPolicies;
    this.profilePolicies = profilePolicies;
    this.operationRules = operationRules;
    this.excessFees = excessFees;
    this.openingMinimums = openingMinimums;
    this.transferPolicy = transferPolicy;
    this.maxPageSize = maxPageSize;
  }

  /**
   * Opens an account after validating product rules and the configured opening minimum. The
   * optional customer-defined minimum daily-average balance defaults to zero and must be
   * nonnegative. It may be stored for any account but is meaningful only for VIP personal savings.
   * Monthly verification is not implemented because daily balance history is not modeled, so the
   * value is stored but no rule is enforced against it.
   */
  @Override
  public Single<Account> create(CreateAccountCommand command) {
    if (command.holders().isEmpty()
        || command.holders().stream().distinct().count() != command.holders().size()) {
      return Single.error(
          new BusinessRuleViolationException("Account holders must be present and unique"));
    }
    Money openingAmount;
    Money minimumDailyAverage;
    try {
      validateProductFields(
          command.accountType(),
          command.maintenanceFee(),
          command.maxMonthlyMovements(),
          command.allowedMovementDay());
      String currency = effectiveCurrency(command.currency());
      openingAmount =
          command.openingAmount() == null
              ? new Money(BigDecimal.ZERO, currency)
              : new Money(command.openingAmount().amount(), currency);
      minimumDailyAverage =
          command.minimumDailyAverage() == null
              ? new Money(BigDecimal.ZERO, currency)
              : new Money(command.minimumDailyAverage().amount(), currency);
      if (minimumDailyAverage.amount().signum() < 0) {
        throw new BusinessRuleViolationException(
            "Minimum daily-average balance must be nonnegative");
      }
      Money minimum =
          openingMinimums.get(command.accountType()).minimumFor(command.accountType(), currency);
      if (!openingAmount.isGreaterThanOrEqualTo(minimum)) {
        throw new BusinessRuleViolationException(
            "Opening amount is below the configured minimum for the account type");
      }
    } catch (RuntimeException failure) {
      return Single.error(failure);
    }
    return Flowable.fromIterable(command.holders())
        .concatMapMaybe(holderId -> customers.findById(holderId, command.correlationId()))
        .toList()
        .flatMap(
            holderSnapshots ->
                validateAndSaveNewAccount(
                    command, openingAmount, minimumDailyAverage, holderSnapshots));
  }

  /** Returns one stable paginated account page. */
  @Override
  public Flowable<Account> findAll(AccountFilter filter, int page, int size) {
    if (page < 0 || size < 1 || size > maxPageSize) {
      return Flowable.error(
          new IllegalArgumentException(
              "page must be non-negative and size must be between 1 and " + maxPageSize));
    }
    return repository.findAll(filter, page, size);
  }

  /** Retrieves an account or signals a not-found error. */
  @Override
  public Single<Account> findById(UUID id) {
    return repository.findById(id).switchIfEmpty(Single.error(new AccountNotFoundException(id)));
  }

  /** Updates editable account details without changing its type or balance. */
  @Override
  public Single<Account> update(UUID id, UpdateAccountCommand command) {
    if (command.holders() == null || command.holders().isEmpty()) {
      return Single.error(
          new BusinessRuleViolationException("At least one account holder is required"));
    }
    return findById(id)
        .flatMap(
            existing -> {
              Money updatedMaintenanceFee =
                  command.maintenanceFee() == null
                      ? null
                      : new Money(command.maintenanceFee().amount(), existing.balance().currency());
              validateProductFields(
                  existing.accountType(),
                  updatedMaintenanceFee,
                  command.maxMonthlyMovements(),
                  command.allowedMovementDay());
              return Flowable.fromIterable(command.holders())
                  .concatMapMaybe(holderId -> customers.findById(holderId, command.correlationId()))
                  .toList()
                  .flatMap(
                      holderSnapshots -> validateAndUpdate(existing, command, holderSnapshots));
            });
  }

  /** Deletes an account and its relations. */
  @Override
  public Completable delete(UUID id) {
    return findById(id).flatMapCompletable(repository::delete);
  }

  /**
   * Persists a balance change, registers its movement, and compensates only confirmed failures. An
   * excess fee is deducted in the same balance update and noted in the downstream description; it
   * is not modeled as a second movement or a second downstream call, which would introduce
   * partial-failure ambiguity between the operation and its fee.
   */
  @Override
  public Single<MovementReceipt> operate(
      UUID accountId, MovementType movementType, OperationCommand command) {
    if (command.amount() == null || command.amount().amount().signum() <= 0) {
      return Single.error(
          new BusinessRuleViolationException("Operation amount must be greater than zero"));
    }
    LocalDate operationDate = LocalDate.now();
    return findById(accountId)
        .flatMap(
            account -> {
              operationRules
                  .get(account.accountType())
                  .validate(account, movementType, command.amount(), operationDate);
              int currentCount =
                  YearMonth.from(operationDate).equals(account.movementsPeriod())
                      ? account.movementsInCurrentMonth()
                      : 0;
              ExcessMovementFeePolicy feePolicy = excessFees.get(account.accountType());
              Money fee =
                  currentCount >= feePolicy.freeMovements(account)
                      ? feePolicy.fee(account)
                      : new Money(BigDecimal.ZERO, account.balance().currency());
              if (movementType == MovementType.WITHDRAWAL
                  && !account.balance().isGreaterThanOrEqualTo(command.amount().add(fee))) {
                return Single.error(
                    new BusinessRuleViolationException(
                        "Insufficient account balance to cover the operation and its fee"));
              }
              String description = descriptionWithFee(command.description(), fee);
              return repository
                  .save(
                      account.registerMovement(movementType, command.amount(), operationDate, fee))
                  .flatMap(
                      updated ->
                          registerMovement(updated, account, movementType, command, description));
            });
  }

  /**
   * Appends the charged fee to the movement description, leaving it unchanged when the fee is zero.
   */
  private String descriptionWithFee(String description, Money fee) {
    if (fee.amount().signum() == 0) {
      return description;
    }
    String note = "Excess movement fee: " + fee.amount().toPlainString() + " " + fee.currency();
    return description == null || description.isBlank() ? note : description + " | " + note;
  }

  /**
   * Persists both balance changes in one transaction before registering the withdrawal and the
   * deposit with the same correlation id. Confirmed downstream failures restore both balances;
   * ambiguous failures propagate the correlation id for reconciliation without compensating.
   */
  @Override
  public Single<TransferReceipt> transfer(TransferCommand command) {
    LocalDate operationDate = LocalDate.now();
    return findById(command.sourceAccountId())
        .flatMap(
            source ->
                findById(command.destinationAccountId())
                    .flatMap(
                        destination -> {
                          transferPolicy.validate(
                              source, destination, command.amount(), operationDate);
                          Money sourceFee = transferPolicy.feeFor(source, operationDate);
                          Money destinationFee = transferPolicy.feeFor(destination, operationDate);
                          Account updatedSource =
                              source.registerMovement(
                                  MovementType.WITHDRAWAL,
                                  command.amount(),
                                  operationDate,
                                  sourceFee);
                          Account updatedDestination =
                              destination.registerMovement(
                                  MovementType.DEPOSIT,
                                  command.amount(),
                                  operationDate,
                                  destinationFee);
                          return repository
                              .transfer(updatedSource, updatedDestination)
                              .flatMap(
                                  persisted ->
                                      registerTransferMovements(
                                          source,
                                          destination,
                                          persisted.get(0),
                                          persisted.get(1),
                                          command,
                                          descriptionWithFee(command.description(), sourceFee),
                                          descriptionWithFee(
                                              command.description(), destinationFee)));
                        }));
  }

  /** Registers both movements in order and combines their receipts into one transfer receipt. */
  private Single<TransferReceipt> registerTransferMovements(
      Account originalSource,
      Account originalDestination,
      Account savedSource,
      Account savedDestination,
      TransferCommand command,
      String sourceDescription,
      String destinationDescription) {
    return Flowable.just(
            new DownstreamMovement(savedSource, MovementType.WITHDRAWAL, sourceDescription),
            new DownstreamMovement(savedDestination, MovementType.DEPOSIT, destinationDescription))
        .concatMapSingle(
            movement ->
                movements.register(
                    movement.account(),
                    movement.movementType(),
                    command.amount(),
                    movement.description(),
                    command.correlationId()))
        .toList()
        .flatMap(
            receipts ->
                Single.just(
                    new TransferReceipt(receipts.get(0), receipts.get(1), command.correlationId())))
        .onErrorResumeNext(
            failure ->
                compensateTransfer(
                    failure,
                    originalSource,
                    originalDestination,
                    savedSource,
                    savedDestination,
                    command.correlationId()));
  }

  /** Restores both balances only after a confirmed downstream failure. */
  private Single<TransferReceipt> compensateTransfer(
      Throwable failure,
      Account originalSource,
      Account originalDestination,
      Account savedSource,
      Account savedDestination,
      String correlationId) {
    if (!(failure instanceof DownstreamServiceException downstreamFailure)) {
      return Single.error(failure);
    }
    if (downstreamFailure.isAmbiguous()) {
      return Single.error(
          ambiguousFailure(downstreamFailure.getMessage(), downstreamFailure, correlationId));
    }
    Account restoredSource = originalSource.withVersion(savedSource.version());
    Account restoredDestination = originalDestination.withVersion(savedDestination.version());
    return repository
        .restore(restoredSource, restoredDestination)
        .ignoreElement()
        .andThen(Single.<TransferReceipt>error(downstreamFailure))
        .onErrorResumeNext(
            rollbackFailure ->
                Single.error(
                    rollbackFailure == downstreamFailure
                        ? rollbackFailure
                        : ambiguousFailure(
                            "Transfer failed and account restoration could not be confirmed",
                            rollbackFailure,
                            correlationId)));
  }

  /** Keeps the correlation id with an ambiguous failure so the transfer can be reconciled. */
  private DownstreamServiceException ambiguousFailure(
      String message, Throwable cause, String correlationId) {
    return new DownstreamServiceException(
        message + "; correlationId=" + correlationId, true, cause);
  }

  /** Checks holder eligibility and product limits before saving a newly opened account. */
  private Single<Account> validateAndSaveNewAccount(
      CreateAccountCommand command,
      Money openingAmount,
      Money minimumDailyAverage,
      List<CustomerSnapshot> holderSnapshots) {
    if (holderSnapshots.size() != command.holders().size()) {
      return Single.error(new BusinessRuleViolationException("All account holders must exist"));
    }
    if (holderSnapshots.stream().anyMatch(holder -> !holder.active())) {
      return Single.error(new BusinessRuleViolationException("All account holders must be active"));
    }
    if (holderSnapshots.stream()
        .anyMatch(holder -> holder.customerType() != holderSnapshots.get(0).customerType())) {
      return Single.error(
          new BusinessRuleViolationException(
              "All account holders must have the same customer type"));
    }
    CustomerType holderType = holderSnapshots.get(0).customerType();
    CustomerProfile profile = commonProfile(holderSnapshots);
    AccountProfilePolicy profilePolicy = profilePolicies.get(profile, command.accountType());
    Account accountCandidate =
        candidate(
            command,
            holderType,
            openingAmount,
            minimumDailyAverage,
            profilePolicy.effectiveMaintenanceFee(command.maintenanceFee()));
    Single<List<Account>> existingAccounts =
        holderType == CustomerType.PERSONAL && command.accountType() != AccountType.FIXED_TERM
            ? repository
                .findAll(new AccountFilter(command.holders().get(0), command.accountType()), 0, 100)
                .toList()
            : Single.just(List.of());
    return existingAccounts.flatMap(
        existing -> {
          creationPolicies.get(holderType).validate(accountCandidate, holderSnapshots, existing);
          profilePolicy.validate(accountCandidate, holderSnapshots);
          Single<Boolean> cardsAvailable =
              profilePolicy.requiresCreditCard()
                  ? Flowable.fromIterable(command.holders())
                      .concatMapSingle(
                          holderId -> creditCards.hasCard(holderId, command.correlationId()))
                      .all(hasCard -> hasCard)
                  : Single.just(true);
          return cardsAvailable.flatMap(
              hasCards -> {
                if (!hasCards) {
                  return Single.error(
                      new BusinessRuleViolationException(
                          "All account holders must have a credit card"));
                }
                return Single.defer(
                    () -> {
                      if (holderType == CustomerType.PERSONAL
                          && !accountCandidate.authorizedSigners().isEmpty()) {
                        return Single.error(
                            new BusinessRuleViolationException(
                                "Authorized signers are supported only for business accounts"));
                      }
                      return repository.save(accountCandidate);
                    });
              });
        });
  }

  /** Returns the shared profile only when every holder has that same profile. */
  private CustomerProfile commonProfile(List<CustomerSnapshot> holders) {
    CustomerProfile profile = holders.get(0).customerProfile();
    if (profile == null) {
      return null;
    }
    return holders.stream().allMatch(holder -> holder.customerProfile() == profile)
        ? profile
        : null;
  }

  /** Validates replacement holders and product-specific fields before updating an account. */
  private Single<Account> validateAndUpdate(
      Account existing, UpdateAccountCommand command, List<CustomerSnapshot> holderSnapshots) {
    Money updatedMaintenanceFee =
        command.maintenanceFee() == null
            ? null
            : new Money(command.maintenanceFee().amount(), existing.balance().currency());
    validateProductFields(
        existing.accountType(),
        updatedMaintenanceFee,
        command.maxMonthlyMovements(),
        command.allowedMovementDay());
    if (holderSnapshots.stream().anyMatch(holder -> !holder.active())) {
      return Single.error(new BusinessRuleViolationException("All account holders must be active"));
    }
    if (holderSnapshots.stream()
        .anyMatch(holder -> holder.customerType() != holderSnapshots.get(0).customerType())) {
      return Single.error(
          new BusinessRuleViolationException(
              "All account holders must have the same customer type"));
    }
    if (holderSnapshots.size() != command.holders().size()
        || command.holders().stream().distinct().count() != command.holders().size()) {
      return Single.error(
          new BusinessRuleViolationException("Account holders must exist and be unique"));
    }
    CustomerType holderType = holderSnapshots.get(0).customerType();
    var updateCandidate =
        existing.update(
            command.holders(),
            holderType,
            command.authorizedSigners() == null
                ? existing.authorizedSigners()
                : command.authorizedSigners(),
            updatedMaintenanceFee,
            command.maxMonthlyMovements(),
            command.allowedMovementDay());
    Single<List<Account>> existingAccounts =
        holderType == CustomerType.PERSONAL && existing.accountType() != AccountType.FIXED_TERM
            ? repository
                .findAll(
                    new AccountFilter(command.holders().get(0), existing.accountType()), 0, 100)
                .filter(account -> !account.id().equals(existing.id()))
                .toList()
            : Single.just(List.of());
    return existingAccounts.flatMap(
        accounts -> {
          creationPolicies.get(holderType).validate(updateCandidate, holderSnapshots, accounts);
          return Single.defer(
              () -> {
                if (updateCandidate.holderType() == CustomerType.PERSONAL
                    && !updateCandidate.authorizedSigners().isEmpty()) {
                  return Single.error(
                      new BusinessRuleViolationException(
                          "Authorized signers are supported only for business accounts"));
                }
                return repository.save(updateCandidate);
              });
        });
  }

  /**
   * Registers the remote movement and restores the original account only after confirmed failure.
   */
  private Single<MovementReceipt> registerMovement(
      Account updated,
      Account original,
      MovementType movementType,
      OperationCommand command,
      String description) {
    return movements
        .register(updated, movementType, command.amount(), description, command.correlationId())
        .onErrorResumeNext(
            failure -> {
              if (!(failure instanceof DownstreamServiceException downstreamFailure)) {
                return Single.error(failure);
              }
              if (downstreamFailure.isAmbiguous()) {
                return Single.error(downstreamFailure);
              }
              Account compensation = original.withVersion(updated.version());
              return repository
                  .save(compensation)
                  .ignoreElement()
                  .andThen(Single.<MovementReceipt>error(downstreamFailure))
                  .onErrorResumeNext(
                      rollbackFailure ->
                          Single.error(
                              rollbackFailure == downstreamFailure
                                  ? rollbackFailure
                                  : new DownstreamServiceException(
                                      "Movement failed and account compensation could not be"
                                          + " confirmed",
                                      true,
                                      rollbackFailure)));
            });
  }

  /**
   * Creates the initial account aggregate with the validated opening balance and customer-defined
   * minimum.
   */
  private Account candidate(
      CreateAccountCommand command,
      CustomerType holderType,
      Money openingAmount,
      Money minimumDailyAverage,
      Money maintenanceFee) {
    return new Account(
        UUID.randomUUID(),
        UUID.randomUUID().toString().replace("-", ""),
        command.accountType(),
        openingAmount,
        minimumDailyAverage,
        command.holders(),
        holderType,
        command.authorizedSigners(),
        maintenanceFee,
        command.maxMonthlyMovements(),
        command.allowedMovementDay(),
        0,
        YearMonth.from(LocalDate.now()),
        null,
        Instant.now());
  }

  /** Resolves the account currency, defaulting to PEN when omitted. */
  private String effectiveCurrency(String currency) {
    return currency == null ? "PEN" : currency;
  }

  /** Ensures the required account-type-specific creation or update fields are valid. */
  private void validateProductFields(
      AccountType type, Money maintenanceFee, Integer maxMovements, Integer movementDay) {
    if (type == null) {
      throw new BusinessRuleViolationException("Account type is required");
    }
    boolean valid = validateProductFieldsForType(type, maintenanceFee, maxMovements, movementDay);
    if (!valid) {
      throw new BusinessRuleViolationException(
          "Required account-type fields are missing or invalid");
    }
  }

  /** Evaluates the product-specific field requirements for one account type. */
  private boolean validateProductFieldsForType(
      AccountType type, Money maintenanceFee, Integer maxMovements, Integer movementDay) {
    return switch (type) {
      case SAVINGS ->
          maxMovements != null
              && maxMovements > 0
              && maintenanceFee == null
              && movementDay == null;
      case CHECKING ->
          maintenanceFee != null
              && maintenanceFee.amount().signum() >= 0
              && maxMovements == null
              && movementDay == null;
      case FIXED_TERM ->
          movementDay != null
              && movementDay >= 1
              && movementDay <= 31
              && maintenanceFee == null
              && maxMovements == null;
    };
  }

  /** One downstream registration work item, preserving per-side account and description. */
  private record DownstreamMovement(
      Account account, MovementType movementType, String description) {}
}
