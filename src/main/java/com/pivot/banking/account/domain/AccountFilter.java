package com.pivot.banking.account.domain;

import java.util.UUID;

/** Optional account-list filters. */
public record AccountFilter(UUID customerId, AccountType accountType) {}
