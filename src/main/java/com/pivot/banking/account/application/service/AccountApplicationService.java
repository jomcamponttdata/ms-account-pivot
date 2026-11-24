package com.pivot.banking.account.application.service;

import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccountCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.DeleteAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.GetAccounts;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperationCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccountCommand;
import com.pivot.banking.account.application.port.out.AccountRepositoryPort;
import com.pivot.banking.account.application.port.out.CustomerGateway;
import com.pivot.banking.account.application.port.out.MovementGateway;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Coordinates account use cases and delegates to outbound ports. */
@Service
public class AccountApplicationService implements CreateAccount, GetAccounts, UpdateAccount,
        DeleteAccount, OperateAccount {

    private final AccountRepositoryPort repository;
    private final CustomerGateway customers;
    private final MovementGateway movements;
    private final AccountCreationPolicyFactory creationPolicies;
    private final AccountOperationRuleFactory operationRules;
    private final int maxPageSize;

    /** Creates the service with persistence, downstream gateways, policies, and page-size configuration. */
    public AccountApplicationService(AccountRepositoryPort repository, CustomerGateway customers,
                                     MovementGateway movements,
                                     AccountCreationPolicyFactory creationPolicies,
                                     AccountOperationRuleFactory operationRules,
                                     @Value("${account.pagination.max-size:100}") int maxPageSize) {
        this.repository = repository;
        this.customers = customers;
        this.movements = movements;
        this.creationPolicies = creationPolicies;
        this.operationRules = operationRules;
        this.maxPageSize = maxPageSize;
    }

    /** Opens a zero-balance account after validating all customer holders and product rules. */
    @Override
    public Single<Account> create(CreateAccountCommand command) {
        if (command.holders().isEmpty()
                || command.holders().stream().distinct().count() != command.holders().size()) {
            return Single.error(new BusinessRuleViolationException(
                    "Account holders must be present and unique"));
        }
        try {
            validateProductFields(command.accountType(), command.maintenanceFee(),
                    command.maxMonthlyMovements(), command.allowedMovementDay());
        } catch (RuntimeException failure) {
            return Single.error(failure);
        }
        return Flowable.fromIterable(command.holders())
                .concatMapMaybe(holderId -> customers.findById(holderId, command.correlationId())).toList()
                .flatMap(holderSnapshots -> validateAndSaveNewAccount(command, holderSnapshots));
    }

    /** Returns one stable paginated account page. */
    @Override
    public Flowable<Account> findAll(AccountFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > maxPageSize) {
            return Flowable.error(new IllegalArgumentException(
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
            return Single.error(new BusinessRuleViolationException("At least one account holder is required"));
        }
        return findById(id).flatMap(existing -> {
            Money updatedMaintenanceFee = command.maintenanceFee() == null ? null
                    : new Money(command.maintenanceFee().amount(), existing.balance().currency());
            validateProductFields(existing.accountType(), updatedMaintenanceFee,
                    command.maxMonthlyMovements(), command.allowedMovementDay());
            return Flowable.fromIterable(command.holders())
                    .concatMapMaybe(holderId -> customers.findById(holderId, command.correlationId())).toList()
                    .flatMap(holderSnapshots -> validateAndUpdate(existing, command, holderSnapshots));
        });
    }

    /** Deletes an account and its relations. */
    @Override
    public Completable delete(UUID id) {
        return findById(id).flatMapCompletable(repository::delete);
    }

    /** Persists a balance change, registers its movement, and compensates only confirmed failures. */
    @Override
    public Single<MovementReceipt> operate(UUID accountId, MovementType movementType, OperationCommand command) {
        if (command.amount() == null || command.amount().amount().signum() <= 0) {
            return Single.error(new BusinessRuleViolationException("Operation amount must be greater than zero"));
        }
        LocalDate operationDate = LocalDate.now();
        return findById(accountId).flatMap(account -> {
            operationRules.get(account.accountType()).validate(account, movementType, command.amount(), operationDate);
            return repository.save(account.registerMovement(movementType, command.amount(), operationDate))
                    .flatMap(updated -> registerMovement(updated, account, movementType, command));
        });
    }

    /** Checks holder eligibility and product limits before saving a newly opened account. */
    private Single<Account> validateAndSaveNewAccount(CreateAccountCommand command,
                                                       List<CustomerSnapshot> holderSnapshots) {
        if (holderSnapshots.size() != command.holders().size()) {
            return Single.error(new BusinessRuleViolationException("All account holders must exist"));
        }
        if (holderSnapshots.stream().anyMatch(holder -> !holder.active())) {
            return Single.error(new BusinessRuleViolationException("All account holders must be active"));
        }
        if (holderSnapshots.stream().anyMatch(holder -> holder.customerType() != holderSnapshots.get(0).customerType())) {
            return Single.error(new BusinessRuleViolationException("All account holders must have the same customer type"));
        }
        CustomerType holderType = holderSnapshots.get(0).customerType();
        Account accountCandidate = candidate(command, holderType);
        Single<List<Account>> existingAccounts = holderType == CustomerType.PERSONAL
                && command.accountType() != AccountType.FIXED_TERM
                ? repository.findAll(new AccountFilter(command.holders().get(0), command.accountType()), 0, 100)
                    .toList()
                : Single.just(List.of());
        return existingAccounts.flatMap(existing -> {
            creationPolicies.get(holderType).validate(accountCandidate, holderSnapshots, existing);
            return Single.defer(() -> {
                    if (holderType == CustomerType.PERSONAL
                            && !accountCandidate.authorizedSigners().isEmpty()) {
                        return Single.error(new BusinessRuleViolationException(
                                "Authorized signers are supported only for business accounts"));
                    }
                    return repository.save(accountCandidate);
                });
        });
    }

    /** Validates replacement holders and product-specific fields before updating an account. */
    private Single<Account> validateAndUpdate(Account existing, UpdateAccountCommand command,
                                               List<CustomerSnapshot> holderSnapshots) {
        Money updatedMaintenanceFee = command.maintenanceFee() == null ? null
                : new Money(command.maintenanceFee().amount(), existing.balance().currency());
        validateProductFields(existing.accountType(), updatedMaintenanceFee,
                command.maxMonthlyMovements(), command.allowedMovementDay());
        if (holderSnapshots.stream().anyMatch(holder -> !holder.active())) {
            return Single.error(new BusinessRuleViolationException("All account holders must be active"));
        }
        if (holderSnapshots.stream().anyMatch(holder -> holder.customerType() != holderSnapshots.get(0).customerType())) {
            return Single.error(new BusinessRuleViolationException("All account holders must have the same customer type"));
        }
        if (holderSnapshots.size() != command.holders().size()
                || command.holders().stream().distinct().count() != command.holders().size()) {
            return Single.error(new BusinessRuleViolationException("Account holders must exist and be unique"));
        }
        CustomerType holderType = holderSnapshots.get(0).customerType();
        var updateCandidate = existing.update(command.holders(), holderType,
                command.authorizedSigners() == null ? existing.authorizedSigners() : command.authorizedSigners(),
                updatedMaintenanceFee, command.maxMonthlyMovements(), command.allowedMovementDay());
        Single<List<Account>> existingAccounts = holderType == CustomerType.PERSONAL
                && existing.accountType() != AccountType.FIXED_TERM
                ? repository.findAll(new AccountFilter(command.holders().get(0), existing.accountType()), 0, 100)
                    .filter(account -> !account.id().equals(existing.id())).toList()
                : Single.just(List.of());
        return existingAccounts.flatMap(accounts -> {
            creationPolicies.get(holderType).validate(updateCandidate, holderSnapshots, accounts);
            return Single.defer(() -> {
                    if (updateCandidate.holderType() == CustomerType.PERSONAL
                            && !updateCandidate.authorizedSigners().isEmpty()) {
                        return Single.error(new BusinessRuleViolationException(
                                "Authorized signers are supported only for business accounts"));
                    }
                    return repository.save(updateCandidate);
                });
        });
    }

    /** Registers the remote movement and restores the original account only after confirmed failure. */
    private Single<MovementReceipt> registerMovement(Account updated, Account original, MovementType movementType,
                                                      OperationCommand command) {
        return movements.register(updated, movementType, command.amount(), command.description(),
                        command.correlationId())
                .onErrorResumeNext(failure -> {
                    if (!(failure instanceof DownstreamServiceException downstreamFailure)) {
                        return Single.error(failure);
                    }
                    if (downstreamFailure.isAmbiguous()) {
                        return Single.error(downstreamFailure);
                    }
                    Account compensation = original.withVersion(updated.version());
                    return repository.save(compensation)
                            .ignoreElement()
                            .andThen(Single.<MovementReceipt>error(downstreamFailure))
                            .onErrorResumeNext(rollbackFailure -> Single.error(
                                    rollbackFailure == downstreamFailure
                                            ? rollbackFailure
                                            : new DownstreamServiceException(
                                                    "Movement failed and account compensation could not be confirmed",
                                                    true, rollbackFailure)));
                });
    }

    /** Creates the initial account aggregate with a zero balance and the default currency when omitted. */
    private Account candidate(CreateAccountCommand command, CustomerType holderType) {
        String currency = command.currency() == null ? "PEN" : command.currency();
        return new Account(UUID.randomUUID(), UUID.randomUUID().toString().replace("-", ""),
                command.accountType(), new Money(BigDecimal.ZERO, currency), command.holders(), holderType,
                command.authorizedSigners(), command.maintenanceFee(), command.maxMonthlyMovements(),
                command.allowedMovementDay(), 0, YearMonth.from(LocalDate.now()), null, Instant.now());
    }

    /** Ensures the required account-type-specific creation or update fields are valid. */
    private void validateProductFields(AccountType type, Money maintenanceFee, Integer maxMovements,
                                       Integer movementDay) {
        if (type == null) {
            throw new BusinessRuleViolationException("Account type is required");
        }
        boolean valid = switch (type) {
            case SAVINGS -> maxMovements != null && maxMovements > 0
                    && maintenanceFee == null && movementDay == null;
            case CHECKING -> maintenanceFee != null && maintenanceFee.amount().signum() >= 0
                    && maxMovements == null && movementDay == null;
            case FIXED_TERM -> movementDay != null && movementDay >= 1 && movementDay <= 31
                    && maintenanceFee == null && maxMovements == null;
        };
        if (!valid) {
            throw new BusinessRuleViolationException("Required account-type fields are missing or invalid");
        }
    }
}
