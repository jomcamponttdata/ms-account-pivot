package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import com.pivot.banking.account.domain.AuthorizedSigner;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** Relational persistence representation of an account's authorized signer. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("account_authorized_signers")
public class AuthorizedSignerEntity {

    @Id
    private UUID id;
    private UUID accountId;
    private String fullName;
    private String documentType;
    private String documentNumber;

    /** Creates the persistence record from a signer. */
    public static AuthorizedSignerEntity fromDomain(UUID accountId, AuthorizedSigner signer) {
        return new AuthorizedSignerEntity(UUID.randomUUID(), accountId, signer.fullName(),
                signer.documentType(), signer.documentNumber());
    }

    /** Reconstructs an authorized signer from its persistence record. */
    public AuthorizedSigner toDomain() {
        return new AuthorizedSigner(fullName, documentType, documentNumber);
    }
}
