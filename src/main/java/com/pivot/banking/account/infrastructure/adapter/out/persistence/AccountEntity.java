package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.Money;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Relational persistence representation of an account aggregate's scalar fields. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("accounts")
public class AccountEntity {

    @Id
    private UUID id;
    private String accountNumber;
    private String accountType;
    private String currency;
    private java.math.BigDecimal balance;
    private java.math.BigDecimal maintenanceFee;
    private Integer maxMonthlyMovements;
    private Integer allowedMovementDay;
    private int movementsInCurrentMonth;
    private String movementsPeriod;
    @Version
    private Long version;
    private Instant createdAt;

    /** Creates the scalar persistence record from an account aggregate. */
    public static AccountEntity fromDomain(Account account) {
        return new AccountEntity(account.id(), account.accountNumber(), account.accountType().name(),
                account.balance().currency(), account.balance().amount(),
                account.maintenanceFee() == null ? null : account.maintenanceFee().amount(),
                account.maxMonthlyMovements(), account.allowedMovementDay(), account.movementsInCurrentMonth(),
                account.movementsPeriod() == null ? null : account.movementsPeriod().toString(),
                account.version(), account.createdAt());
    }

    /** Rebuilds the account aggregate after loading its holder and signer relations. */
    public Account toDomain(List<UUID> holders, com.pivot.banking.account.domain.CustomerType holderType,
                            List<com.pivot.banking.account.domain.AuthorizedSigner> signers) {
        return new Account(id, accountNumber, AccountType.valueOf(accountType),
                new Money(balance, currency), holders, holderType, signers,
                maintenanceFee == null ? null : new Money(maintenanceFee, currency),
                maxMonthlyMovements, allowedMovementDay, movementsInCurrentMonth,
                movementsPeriod == null ? null : YearMonth.parse(movementsPeriod), version, createdAt);
    }
}
