package com.pivot.banking.account.application.port.in;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.AuthorizedSigner;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Input ports for account CRUD, balance, and monetary operations. */
public final class AccountUseCases {

    private AccountUseCases() {
    }

    /** Opens an account. */
    public interface CreateAccount {
        Mono<Account> create(CreateAccountCommand command);
    }

    /** Retrieves accounts and balances. */
    public interface GetAccounts {
        Flux<Account> findAll(AccountFilter filter, int page, int size);
        Mono<Account> findById(UUID id);
    }

    /** Updates editable account details. */
    public interface UpdateAccount {
        Mono<Account> update(UUID id, UpdateAccountCommand command);
    }

    /** Deletes an account. */
    public interface DeleteAccount {
        Mono<Void> delete(UUID id);
    }

    /** Deposits or withdraws money and registers the corresponding movement. */
    public interface OperateAccount {
        Mono<MovementReceipt> operate(UUID accountId, MovementType movementType, OperationCommand command);
    }

    /** Data required to open one of the supported account products. */
    public record CreateAccountCommand(AccountType accountType, List<UUID> holders, String currency,
                                       Money maintenanceFee, Integer maxMonthlyMovements,
                                       Integer allowedMovementDay, List<AuthorizedSigner> authorizedSigners) {
        public CreateAccountCommand {
            holders = List.copyOf(holders);
            authorizedSigners = authorizedSigners == null ? List.of() : List.copyOf(authorizedSigners);
        }
    }

    /** Editable account details, with product-specific values validated against account type. */
    public record UpdateAccountCommand(List<UUID> holders, List<AuthorizedSigner> authorizedSigners,
                                       Money maintenanceFee, Integer maxMonthlyMovements,
                                       Integer allowedMovementDay) {
        public UpdateAccountCommand {
            holders = holders == null ? null : List.copyOf(holders);
            authorizedSigners = authorizedSigners == null ? null : List.copyOf(authorizedSigners);
        }
    }

    /** Details of a deposit or withdrawal request. */
    public record OperationCommand(Money amount, String description) {
    }
}
