package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Relational association between an account and one of its customer holders. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("account_holders")
public class AccountHolderEntity {

    @Id
    private UUID id;
    private UUID accountId;
    private UUID customerId;
    private String customerType;
    private String accountType;
}
