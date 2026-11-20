package com.pivot.banking.account.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Receipt returned by transaction-service after movement registration. */
public record MovementReceipt(String movementId, MovementType movementType, BigDecimal amount,
                              String currency, Instant createdAt) {
}
