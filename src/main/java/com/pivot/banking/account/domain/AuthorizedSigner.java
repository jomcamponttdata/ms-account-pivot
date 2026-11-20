package com.pivot.banking.account.domain;

/** Authorized representative attached to a business checking account. */
public record AuthorizedSigner(String fullName, String documentType, String documentNumber) {
}
