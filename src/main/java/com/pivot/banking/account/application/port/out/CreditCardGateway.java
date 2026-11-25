package com.pivot.banking.account.application.port.out;

import io.reactivex.rxjava3.core.Single;
import java.util.UUID;

/** Outbound client port for credit-service card ownership checks. */
public interface CreditCardGateway {

  /** Reports whether the given customer owns at least one credit card. */
  Single<Boolean> hasCard(UUID customerId, String correlationId);
}
