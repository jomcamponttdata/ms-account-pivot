package com.pivot.banking.account.infrastructure.adapter.in;

import com.pivot.banking.account.adapter.in.dto.AccountCreateRequest;
import com.pivot.banking.account.adapter.in.dto.AccountUpdateRequest;
import com.pivot.banking.account.adapter.in.dto.AuthorizedSigner;
import com.pivot.banking.account.adapter.in.dto.CheckingAccountCreateRequest;
import com.pivot.banking.account.adapter.in.dto.FixedTermAccountCreateRequest;
import com.pivot.banking.account.adapter.in.dto.OperationRequest;
import com.pivot.banking.account.adapter.in.dto.SavingsAccountCreateRequest;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for REST request and response mapping. */
class AccountRestMapperTest {

    private final AccountRestMapper mapper = new AccountRestMapper();

    @Test
    void mapsEverySupportedCreateRequestType() {
        // Arrange
        UUID holder = UUID.randomUUID();
        var savings = new SavingsAccountCreateRequest();
        savings.setHolders(List.of(holder));
        savings.setCurrency("PEN");
        savings.setMaxMonthlyMovements(10);
        var checking = new CheckingAccountCreateRequest();
        checking.setHolders(List.of(holder));
        checking.setCurrency("USD");
        checking.setMaintenanceFee(new BigDecimal("2.50"));
        checking.setAuthorizedSigners(List.of(apiSigner()));
        var fixedTerm = new FixedTermAccountCreateRequest();
        fixedTerm.setHolders(List.of(holder));
        fixedTerm.setCurrency("PEN");
        fixedTerm.setAllowedMovementDay(15);

        // Act
        var savingsCommand = mapper.toCreateCommand(savings);
        var checkingCommand = mapper.toCreateCommand(checking);
        var fixedTermCommand = mapper.toCreateCommand(fixedTerm);

        // Assert
        assertEquals(AccountType.SAVINGS, savingsCommand.accountType());
        assertEquals(10, savingsCommand.maxMonthlyMovements());
        assertEquals(AccountType.CHECKING, checkingCommand.accountType());
        assertEquals("USD", checkingCommand.maintenanceFee().currency());
        assertEquals(1, checkingCommand.authorizedSigners().size());
        assertEquals(AccountType.FIXED_TERM, fixedTermCommand.accountType());
        assertEquals(15, fixedTermCommand.allowedMovementDay());
    }

    @Test
    void rejectsUnsupportedCreateRequest() {
        // Arrange
        AccountCreateRequest unsupported = new AccountCreateRequest() {
            @Override
            public Enum getAccountType() {
                return null;
            }
        };

        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> mapper.toCreateCommand(unsupported));

        // Assert
        assertEquals("Unsupported account creation request", error.getMessage());
    }

    @Test
    void mapsUpdateRequestWithAndWithoutOptionalFields() {
        // Arrange
        UUID holder = UUID.randomUUID();
        var withoutFee = new AccountUpdateRequest();
        withoutFee.setHolders(List.of(holder));
        withoutFee.setAuthorizedSigners(null);
        var withFee = new AccountUpdateRequest();
        withFee.setHolders(List.of(holder));
        withFee.setAuthorizedSigners(List.of(apiSigner()));
        withFee.setMaintenanceFee(new BigDecimal("3.00"));

        // Act
        var first = mapper.toUpdateCommand(withoutFee);
        var second = mapper.toUpdateCommand(withFee);

        // Assert
        assertNull(first.maintenanceFee());
        assertEquals(List.of(), first.authorizedSigners());
        assertEquals(new Money(new BigDecimal("3.00"), "PEN"), second.maintenanceFee());
        assertEquals(1, second.authorizedSigners().size());
    }

    @Test
    void mapsOperationRequestWithDefaultAndExplicitCurrency() {
        // Arrange
        var withDefault = new OperationRequest();
        withDefault.setAmount(new BigDecimal("10.00"));
        var withExplicit = new OperationRequest();
        withExplicit.setAmount(new BigDecimal("20.00"));
        withExplicit.setCurrency("USD");
        withExplicit.setDescription("ATM");

        // Act
        var first = mapper.toOperationCommand(withDefault);
        var second = mapper.toOperationCommand(withExplicit);

        // Assert
        assertEquals(new Money(new BigDecimal("10.00"), "PEN"), first.amount());
        assertEquals(new Money(new BigDecimal("20.00"), "USD"), second.amount());
        assertEquals("ATM", second.description());
    }

    @Test
    void mapsAccountWithAndWithoutMaintenanceFeeToResponse() {
        // Arrange
        Account withFee = account(new BigDecimal("2.50"), new Money(new BigDecimal("1.00"), "PEN"),
                List.of(new com.pivot.banking.account.domain.AuthorizedSigner(
                        "Carlos Ruiz", "DNI", "45678912")));
        Account withoutFee = account(null, null, List.of());

        // Act
        var first = mapper.toResponse(withFee);
        var second = mapper.toResponse(withoutFee);
        var balance = mapper.toBalanceResponse(withoutFee);
        var receipt = mapper.toMovementResponse(new MovementReceipt("movement-1", MovementType.DEPOSIT,
                new BigDecimal("1.00"), "PEN", Instant.parse("2025-01-01T00:00:00Z")));

        // Assert
        assertEquals(new BigDecimal("1.00"), first.getMaintenanceFee());
        assertEquals(1, first.getAuthorizedSigners().size());
        assertNull(second.getMaintenanceFee());
        assertEquals(0, second.getAuthorizedSigners().size());
        assertEquals("BANK_ACCOUNT", balance.getProductType().getValue());
        assertEquals("DEPOSIT", receipt.getMovementType().getValue());
    }

    private AuthorizedSigner apiSigner() {
        return new AuthorizedSigner("Carlos Ruiz", AuthorizedSigner.DocumentTypeEnum.DNI, "45678912");
    }

    private Account account(BigDecimal maintenanceFee, Money fee,
                            List<com.pivot.banking.account.domain.AuthorizedSigner> signers) {
        return new Account(UUID.randomUUID(), "19100012345678", AccountType.CHECKING,
                new Money(new BigDecimal("100.00"), "PEN"), List.of(UUID.randomUUID()), CustomerType.BUSINESS,
                signers, fee, null, null, 0, YearMonth.from(LocalDate.now()), 0L,
                Instant.parse("2025-01-01T00:00:00Z"));
    }
}
