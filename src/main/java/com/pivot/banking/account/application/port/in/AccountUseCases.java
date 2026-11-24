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

    private AccountUseCases() {
    }

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
        Single<MovementReceipt> operate(UUID accountId, MovementType movementType, OperationCommand command);
    }

    /** Data required to open one of the supported account products. */
    public record CreateAccountCommand(AccountType accountType, List<UUID> holders, String currency,
                                        Money maintenanceFee, Integer maxMonthlyMovements,
                                        Integer allowedMovementDay, List<AuthorizedSigner> authorizedSigners,
                                        String correlationId) {
        public CreateAccountCommand(AccountType accountType, List<UUID> holders, String currency,
                                    Money maintenanceFee, Integer maxMonthlyMovements,
                                    Integer allowedMovementDay, List<AuthorizedSigner> authorizedSigners) {
            this(accountType, holders, currency, maintenanceFee, maxMonthlyMovements, allowedMovementDay,
                    authorizedSigners, null);
        }

        public CreateAccountCommand {
            holders = List.copyOf(holders);
            authorizedSigners = authorizedSigners == null ? List.of() : List.copyOf(authorizedSigners);
        }

        public CreateAccountCommand withCorrelationId(String value) {
            return new CreateAccountCommand(accountType, holders, currency, maintenanceFee,
                    maxMonthlyMovements, allowedMovementDay, authorizedSigners, value);
        }
    }

    /** Editable account details, with product-specific values validated against account type. */
    public record UpdateAccountCommand(List<UUID> holders, List<AuthorizedSigner> authorizedSigners,
                                       Money maintenanceFee, Integer maxMonthlyMovements,
                                       Integer allowedMovementDay, String correlationId) {
        public UpdateAccountCommand(List<UUID> holders, List<AuthorizedSigner> authorizedSigners,
                                    Money maintenanceFee, Integer maxMonthlyMovements,
                                    Integer allowedMovementDay) {
            this(holders, authorizedSigners, maintenanceFee, maxMonthlyMovements, allowedMovementDay, null);
        }

        public UpdateAccountCommand {
            holders = holders == null ? null : List.copyOf(holders);
            authorizedSigners = authorizedSigners == null ? null : List.copyOf(authorizedSigners);
        }

        public UpdateAccountCommand withCorrelationId(String value) {
            return new UpdateAccountCommand(holders, authorizedSigners, maintenanceFee,
                    maxMonthlyMovements, allowedMovementDay, value);
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
}
