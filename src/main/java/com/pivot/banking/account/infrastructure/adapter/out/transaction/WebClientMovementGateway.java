package com.pivot.banking.account.infrastructure.adapter.out.transaction;

import com.pivot.banking.account.application.port.out.MovementGateway;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.MovementReceipt;
import com.pivot.banking.account.domain.MovementType;
import com.pivot.banking.account.domain.exception.DownstreamServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Reactive HTTP adapter for movement registration in transaction-service. */
@Component
public class WebClientMovementGateway implements MovementGateway {

    private final WebClient client;

    /** Creates the client using the externally configured transaction-service endpoint. */
    public WebClientMovementGateway(WebClient.Builder builder,
                                    @Value("${account.services.transaction-url}") String transactionServiceUrl) {
        this.client = builder.baseUrl(transactionServiceUrl).build();
    }

    /** Registers an account movement and preserves ambiguous transport outcomes. */
    @Override
    public Mono<MovementReceipt> register(Account account, MovementType movementType,
                                          Money amount, String description) {
        var request = new MovementRequest(account.id(), "BANK_ACCOUNT", movementType.name(),
                amount.amount(), amount.currency(), description);
        return client.post().uri("/api/v1/movements").bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(),
                        response -> response.bodyToMono(String.class).defaultIfEmpty("Movement service failed")
                                .map(body -> new DownstreamServiceException(
                                        "Transaction service returned an error: " + body,
                                        response.statusCode().is5xxServerError(), null)))
                .bodyToMono(MovementResponse.class)
                .map(response -> new MovementReceipt(response.id(), MovementType.valueOf(response.movementType()),
                        response.amount(), response.currency(), response.createdAt().toInstant()))
                .onErrorMap(WebClientRequestException.class, failure ->
                        new DownstreamServiceException("Movement registration outcome is ambiguous",
                                true, failure))
                .onErrorMap(failure -> !(failure instanceof DownstreamServiceException),
                        failure -> new DownstreamServiceException(
                                "Movement registration response could not be confirmed", true, failure));
    }

    private record MovementRequest(java.util.UUID productId, String productType, String movementType,
                                   BigDecimal amount, String currency, String description) {
    }

    private record MovementResponse(String id, String productId, String productType, String movementType,
                                    BigDecimal amount, String currency, OffsetDateTime createdAt) {
    }
}
