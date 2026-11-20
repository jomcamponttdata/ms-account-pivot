package com.pivot.banking.account.infrastructure.adapter.in;

import com.pivot.banking.account.adapter.in.dto.AccountCreateRequest;
import com.pivot.banking.account.adapter.in.dto.AccountUpdateRequest;
import com.pivot.banking.account.adapter.in.dto.AuthorizedSigner;
import com.pivot.banking.account.adapter.in.dto.Balance;
import com.pivot.banking.account.adapter.in.dto.CheckingAccountCreateRequest;
import com.pivot.banking.account.adapter.in.dto.FixedTermAccountCreateRequest;
import com.pivot.banking.account.adapter.in.dto.MovementReceipt;
import com.pivot.banking.account.adapter.in.dto.OperationRequest;
import com.pivot.banking.account.adapter.in.dto.SavingsAccountCreateRequest;
import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccountCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperationCommand;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccountCommand;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Converts generated REST DTOs to account commands and domain objects back to REST DTOs. */
@Component
public class AccountRestMapper {

    /** Converts a polymorphic account create request to its application command. */
    public CreateAccountCommand toCreateCommand(AccountCreateRequest request) {
        if (request instanceof SavingsAccountCreateRequest savings) {
            return new CreateAccountCommand(com.pivot.banking.account.domain.AccountType.SAVINGS,
                    savings.getHolders(), savings.getCurrency(), null, savings.getMaxMonthlyMovements(),
                    null, List.of());
        }
        if (request instanceof CheckingAccountCreateRequest checking) {
            return new CreateAccountCommand(com.pivot.banking.account.domain.AccountType.CHECKING,
                    checking.getHolders(), checking.getCurrency(),
                    new Money(checking.getMaintenanceFee(), checking.getCurrency()), null, null,
                    toDomainSigners(checking.getAuthorizedSigners()));
        }
        if (request instanceof FixedTermAccountCreateRequest fixedTerm) {
            return new CreateAccountCommand(com.pivot.banking.account.domain.AccountType.FIXED_TERM,
                    fixedTerm.getHolders(), fixedTerm.getCurrency(), null, null,
                    fixedTerm.getAllowedMovementDay(), List.of());
        }
        throw new IllegalArgumentException("Unsupported account creation request");
    }

    /** Converts editable fields from a REST request to an update command. */
    public UpdateAccountCommand toUpdateCommand(AccountUpdateRequest request) {
        return new UpdateAccountCommand(request.getHolders(), toDomainSigners(request.getAuthorizedSigners()),
                request.getMaintenanceFee() == null ? null : new Money(request.getMaintenanceFee(), "PEN"),
                request.getMaxMonthlyMovements(), request.getAllowedMovementDay());
    }

    /** Converts a deposit or withdrawal request to an operation command. */
    public OperationCommand toOperationCommand(OperationRequest request) {
        String currency = request.getCurrency() == null ? "PEN" : request.getCurrency();
        return new OperationCommand(new Money(request.getAmount(), currency), request.getDescription());
    }

    /** Converts an account aggregate to its generated response DTO. */
    public com.pivot.banking.account.adapter.in.dto.Account toResponse(Account account) {
        var response = new com.pivot.banking.account.adapter.in.dto.Account(
                account.id(), account.accountNumber(),
                com.pivot.banking.account.adapter.in.dto.AccountType.valueOf(account.accountType().name()),
                account.balance().amount(), account.balance().currency(), account.holders(),
                OffsetDateTime.ofInstant(account.createdAt(), ZoneOffset.UTC));
        response.setAuthorizedSigners(account.authorizedSigners().stream()
                .map(this::toApiSigner).toList());
        response.setMaintenanceFee(account.maintenanceFee() == null ? null : account.maintenanceFee().amount());
        response.setMaxMonthlyMovements(account.maxMonthlyMovements());
        response.setAllowedMovementDay(account.allowedMovementDay());
        response.setMovementsInCurrentMonth(account.movementsInCurrentMonth());
        return response;
    }

    /** Converts the account balance to the balance endpoint response. */
    public Balance toBalanceResponse(Account account) {
        return new Balance(account.id(), Balance.ProductTypeEnum.BANK_ACCOUNT,
                account.balance().amount(), account.balance().currency());
    }

    /** Converts a movement receipt to the generated REST response. */
    public MovementReceipt toMovementResponse(com.pivot.banking.account.domain.MovementReceipt receipt) {
        return new MovementReceipt(receipt.movementId(),
                MovementReceipt.MovementTypeEnum.valueOf(receipt.movementType().name()),
                receipt.amount(), receipt.currency(),
                OffsetDateTime.ofInstant(receipt.createdAt(), ZoneOffset.UTC));
    }

    /** Converts API signer records to the domain representation. */
    private List<com.pivot.banking.account.domain.AuthorizedSigner> toDomainSigners(
            List<AuthorizedSigner> signers) {
        return signers == null ? List.of() : signers.stream()
                .map(signer -> new com.pivot.banking.account.domain.AuthorizedSigner(signer.getFullName(),
                        signer.getDocumentType().name(), signer.getDocumentNumber()))
                .toList();
    }

    /** Converts a domain signer to the generated API representation. */
    private AuthorizedSigner toApiSigner(com.pivot.banking.account.domain.AuthorizedSigner signer) {
        return new AuthorizedSigner(signer.fullName(),
                AuthorizedSigner.DocumentTypeEnum.valueOf(signer.documentType()), signer.documentNumber());
    }
}
