package com.pivot.banking.account.infrastructure.adapter.in;

import com.pivot.banking.account.adapter.in.api.AccountsApi;
import com.pivot.banking.account.adapter.in.dto.Account;
import com.pivot.banking.account.adapter.in.dto.AccountCreateRequest;
import com.pivot.banking.account.adapter.in.dto.AccountType;
import com.pivot.banking.account.adapter.in.dto.AccountUpdateRequest;
import com.pivot.banking.account.adapter.in.dto.Balance;
import com.pivot.banking.account.adapter.in.dto.MovementReceipt;
import com.pivot.banking.account.adapter.in.dto.OperationRequest;
import com.pivot.banking.account.application.port.in.AccountUseCases.CreateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.DeleteAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.GetAccounts;
import com.pivot.banking.account.application.port.in.AccountUseCases.OperateAccount;
import com.pivot.banking.account.application.port.in.AccountUseCases.UpdateAccount;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.MovementType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ServerWebExchange;
import reactor.adapter.rxjava.RxJava3Adapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/** Implements the generated account REST API using application use cases. */
@Controller
public class AccountController implements AccountsApi {

    private final CreateAccount createAccount;
    private final GetAccounts getAccounts;
    private final UpdateAccount updateAccount;
    private final DeleteAccount deleteAccount;
    private final OperateAccount operateAccount;
    private final AccountRestMapper mapper;
    private final int defaultPage;
    private final int defaultPageSize;

    /** Creates the REST adapter with its use cases, mapper, and configured paging defaults. */
    public AccountController(CreateAccount createAccount, GetAccounts getAccounts, UpdateAccount updateAccount,
                             DeleteAccount deleteAccount, OperateAccount operateAccount, AccountRestMapper mapper,
                             @Value("${account.pagination.default-page:0}") int defaultPage,
                             @Value("${account.pagination.default-size:20}") int defaultPageSize) {
        this.createAccount = createAccount;
        this.getAccounts = getAccounts;
        this.updateAccount = updateAccount;
        this.deleteAccount = deleteAccount;
        this.operateAccount = operateAccount;
        this.mapper = mapper;
        this.defaultPage = defaultPage;
        this.defaultPageSize = defaultPageSize;
    }

    /** Opens an account and returns its resource URI. */
    @Override
    public Mono<ResponseEntity<Account>> createAccount(Mono<AccountCreateRequest> request,
                                                        ServerWebExchange exchange) {
        return request.flatMap(body -> RxJava3Adapter.singleToMono(
                        createAccount.create(mapper.toCreateCommand(body)
                                .withCorrelationId(correlationId(exchange)))))
                .map(mapper::toResponse)
                .map(account -> ResponseEntity.created(URI.create("/api/v1/accounts/" + account.getId()))
                        .body(account));
    }

    /** Deletes an account and returns no content. */
    @Override
    public Mono<ResponseEntity<Void>> deleteAccount(UUID accountId, ServerWebExchange exchange) {
        return RxJava3Adapter.completableToMono(deleteAccount.delete(accountId))
                .thenReturn(ResponseEntity.noContent().build());
    }

    /** Deposits money and returns the registered movement receipt. */
    @Override
    public Mono<ResponseEntity<MovementReceipt>> depositToAccount(UUID accountId, Mono<OperationRequest> request,
                                                                   ServerWebExchange exchange) {
        return request.flatMap(body -> RxJava3Adapter.singleToMono(operateAccount.operate(accountId,
                        MovementType.DEPOSIT, mapper.toOperationCommand(body)
                                .withCorrelationId(correlationId(exchange)))))
                .map(mapper::toMovementResponse).map(receipt -> ResponseEntity.status(201).body(receipt));
    }

    /** Retrieves an account by identifier. */
    @Override
    public Mono<ResponseEntity<Account>> findAccountById(UUID accountId, ServerWebExchange exchange) {
        return RxJava3Adapter.singleToMono(getAccounts.findById(accountId))
                .map(mapper::toResponse).map(ResponseEntity::ok);
    }

    /** Returns the requested page with optional customer and type filters. */
    @Override
    public Mono<ResponseEntity<Flux<Account>>> findAllAccounts(@Nullable UUID customerId,
            @Nullable AccountType accountType, Integer page, Integer size, ServerWebExchange exchange) {
        var type = accountType == null ? null
                : com.pivot.banking.account.domain.AccountType.valueOf(accountType.name());
        Flux<Account> accounts = RxJava3Adapter.flowableToFlux(getAccounts.findAll(
                        new AccountFilter(customerId, type), page == null ? defaultPage : page,
                        size == null ? defaultPageSize : size))
                .map(mapper::toResponse);
        return Mono.just(ResponseEntity.ok(accounts));
    }

    /** Retrieves the current available account balance. */
    @Override
    public Mono<ResponseEntity<Balance>> getAccountBalance(UUID accountId, ServerWebExchange exchange) {
        return RxJava3Adapter.singleToMono(getAccounts.findById(accountId))
                .map(mapper::toBalanceResponse).map(ResponseEntity::ok);
    }

    /** Updates editable account properties. */
    @Override
    public Mono<ResponseEntity<Account>> updateAccount(UUID accountId, Mono<AccountUpdateRequest> request,
                                                        ServerWebExchange exchange) {
        return request.flatMap(body -> RxJava3Adapter.singleToMono(
                        updateAccount.update(accountId, mapper.toUpdateCommand(body)
                                .withCorrelationId(correlationId(exchange)))))
                .map(mapper::toResponse).map(ResponseEntity::ok);
    }

    /** Withdraws money and returns the registered movement receipt. */
    @Override
    public Mono<ResponseEntity<MovementReceipt>> withdrawFromAccount(UUID accountId,
            Mono<OperationRequest> request, ServerWebExchange exchange) {
        return request.flatMap(body -> RxJava3Adapter.singleToMono(operateAccount.operate(accountId,
                        MovementType.WITHDRAWAL, mapper.toOperationCommand(body)
                                .withCorrelationId(correlationId(exchange)))))
                .map(mapper::toMovementResponse).map(receipt -> ResponseEntity.status(201).body(receipt));
    }

    private String correlationId(ServerWebExchange exchange) {
        String correlationId = exchange.getAttribute("correlationId");
        return correlationId == null
                ? exchange.getRequest().getHeaders().getFirst("X-Correlation-ID")
                : correlationId;
    }
}
