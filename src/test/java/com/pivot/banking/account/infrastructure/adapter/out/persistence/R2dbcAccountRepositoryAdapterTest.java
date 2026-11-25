package com.pivot.banking.account.infrastructure.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pivot.banking.account.application.port.out.AccountRepositoryPort;
import com.pivot.banking.account.domain.Account;
import com.pivot.banking.account.domain.AccountFilter;
import com.pivot.banking.account.domain.AccountType;
import com.pivot.banking.account.domain.AuthorizedSigner;
import com.pivot.banking.account.domain.CustomerType;
import com.pivot.banking.account.domain.Money;
import com.pivot.banking.account.domain.exception.BusinessRuleViolationException;
import com.pivot.banking.account.domain.exception.ConcurrentModificationException;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Query;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class R2dbcAccountRepositoryAdapterTest {

  @Mock private SpringDataAccountRepository accounts;
  @Mock private SpringDataAccountHolderRepository holders;
  @Mock private SpringDataAuthorizedSignerRepository signers;
  @Mock private R2dbcEntityTemplate template;
  @InjectMocks private R2dbcAccountRepositoryAdapter adapter;

  @Test
  void savesScalarAccountAndReplacesHolderAndSignerRelations() {
    // Arrange
    UUID firstHolder = UUID.randomUUID();
    UUID secondHolder = UUID.randomUUID();
    var signer = new AuthorizedSigner("Carlos Ruiz", "DNI", "45678912");
    Account account =
        account(
            AccountType.CHECKING,
            CustomerType.BUSINESS,
            List.of(firstHolder, secondHolder),
            List.of(signer));
    AccountEntity entity = AccountEntity.fromDomain(account);
    when(accounts.save(any(AccountEntity.class))).thenReturn(Mono.just(entity));
    when(holders.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(signers.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(template.insert(any(AccountHolderEntity.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(template.insert(any(AuthorizedSignerEntity.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(holders.findByAccountId(account.id()))
        .thenReturn(
            Flux.just(
                holderEntity(
                    account.id(), firstHolder, CustomerType.BUSINESS, AccountType.CHECKING),
                holderEntity(
                    account.id(), secondHolder, CustomerType.BUSINESS, AccountType.CHECKING)));
    when(signers.findByAccountId(account.id()))
        .thenReturn(Flux.just(signerEntity(account.id(), signer)));

    // Act
    Single<Account> result = adapter.save(account);

    // Assert
    result.test().assertResult(account);
    var scalar = ArgumentCaptor.forClass(AccountEntity.class);
    verify(accounts).save(scalar.capture());
    assertEquals(account.id(), scalar.getValue().getId());
    assertEquals("CHECKING", scalar.getValue().getAccountType());
    assertEquals(new BigDecimal("100.50"), scalar.getValue().getBalance());
    assertEquals(new BigDecimal("2.50"), scalar.getValue().getMaintenanceFee());
    verify(holders).deleteByAccountId(account.id());
    verify(signers).deleteByAccountId(account.id());

    var savedHolders = ArgumentCaptor.forClass(AccountHolderEntity.class);
    verify(template, org.mockito.Mockito.times(2)).insert(savedHolders.capture());
    assertEquals(
        List.of(firstHolder, secondHolder),
        savedHolders.getAllValues().stream().map(AccountHolderEntity::getCustomerId).toList());
    assertTrue(
        savedHolders.getAllValues().stream()
            .allMatch(
                row ->
                    account.id().equals(row.getAccountId())
                        && "BUSINESS".equals(row.getCustomerType())
                        && "CHECKING".equals(row.getAccountType())
                        && row.getId() != null));

    var savedSigners = ArgumentCaptor.forClass(AuthorizedSignerEntity.class);
    verify(template).insert(savedSigners.capture());
    assertEquals(account.id(), savedSigners.getValue().getAccountId());
    assertEquals("Carlos Ruiz", savedSigners.getValue().getFullName());
    assertEquals("DNI", savedSigners.getValue().getDocumentType());
    assertNotNull(savedSigners.getValue().getId());
  }

  @Test
  void reactiveTransactionalProxyAdaptsRxJavaSingleAndCompletableAndCommitsWithReactiveManager() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(holderId), List.of());
    when(accounts.save(any(AccountEntity.class)))
        .thenReturn(Mono.just(AccountEntity.fromDomain(account)));
    when(holders.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(signers.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(template.insert(any(AccountHolderEntity.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(holders.findByAccountId(account.id()))
        .thenReturn(
            Flux.just(
                holderEntity(account.id(), holderId, CustomerType.PERSONAL, AccountType.SAVINGS)));
    when(signers.findByAccountId(account.id())).thenReturn(Flux.empty());
    when(accounts.delete(any(AccountEntity.class))).thenReturn(Mono.empty());

    ReactiveTransactionManager transactionManager =
        org.mockito.Mockito.mock(ReactiveTransactionManager.class);
    ReactiveTransaction transaction = org.mockito.Mockito.mock(ReactiveTransaction.class);
    when(transactionManager.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
    when(transactionManager.commit(transaction)).thenReturn(Mono.empty());
    ProxyFactory proxyFactory = new ProxyFactory(adapter);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    AccountRepositoryPort transactionalAdapter = (AccountRepositoryPort) proxyFactory.getProxy();

    // Act
    Single<Account> result = transactionalAdapter.save(account);
    Completable deletion = transactionalAdapter.delete(account);

    // Assert
    result.test().assertResult(account);
    deletion.test().assertComplete();
    verify(transactionManager, org.mockito.Mockito.times(2)).getReactiveTransaction(any());
    verify(transactionManager, org.mockito.Mockito.times(2)).commit(transaction);
    verify(transactionManager, never()).rollback(any());
  }

  @Test
  void reactiveTransactionalProxyRollsBackRxJavaSingleOnFailure() {
    // Arrange
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    when(accounts.save(any(AccountEntity.class)))
        .thenReturn(Mono.error(new OptimisticLockingFailureException("stale version")));
    ReactiveTransactionManager transactionManager =
        org.mockito.Mockito.mock(ReactiveTransactionManager.class);
    ReactiveTransaction transaction = org.mockito.Mockito.mock(ReactiveTransaction.class);
    when(transactionManager.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
    when(transactionManager.rollback(transaction)).thenReturn(Mono.empty());
    ProxyFactory proxyFactory = new ProxyFactory(adapter);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    AccountRepositoryPort transactionalAdapter = (AccountRepositoryPort) proxyFactory.getProxy();

    // Act
    Single<Account> result = transactionalAdapter.save(account);

    // Assert
    result.test().assertError(ConcurrentModificationException.class);
    verify(transactionManager).getReactiveTransaction(any());
    verify(transactionManager).rollback(transaction);
    verify(transactionManager, never()).commit(any());
  }

  @Test
  void transferWritesBothAccountsInsideOneCommittedTransaction() {
    // Arrange
    UUID sourceHolder = UUID.randomUUID();
    UUID destinationHolder = UUID.randomUUID();
    Account source =
        account(AccountType.CHECKING, CustomerType.PERSONAL, List.of(sourceHolder), List.of());
    Account destination =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(destinationHolder), List.of());
    stubBulkSave(source, destination, true);
    ReactiveTransactionManager transactionManager =
        org.mockito.Mockito.mock(ReactiveTransactionManager.class);
    ReactiveTransaction transaction = org.mockito.Mockito.mock(ReactiveTransaction.class);
    when(transactionManager.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
    when(transactionManager.commit(transaction)).thenReturn(Mono.empty());
    ProxyFactory proxyFactory = new ProxyFactory(adapter);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    AccountRepositoryPort transactionalAdapter = (AccountRepositoryPort) proxyFactory.getProxy();

    // Act
    Single<List<Account>> result = transactionalAdapter.transfer(source, destination);

    // Assert
    result
        .test()
        .assertValue(
            saved ->
                saved.size() == 2
                    && source.id().equals(saved.get(0).id())
                    && destination.id().equals(saved.get(1).id())
                    && saved.get(0).balance().equals(source.balance())
                    && saved.get(1).balance().equals(destination.balance()))
        .assertComplete();
    var savedEntities = ArgumentCaptor.forClass(AccountEntity.class);
    verify(accounts, org.mockito.Mockito.times(2)).save(savedEntities.capture());
    assertEquals(
        List.of(source.id(), destination.id()),
        savedEntities.getAllValues().stream().map(AccountEntity::getId).toList());
    verify(transactionManager).getReactiveTransaction(any());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(any());
  }

  @Test
  void transferRollsBackBothWritesWhenTheSecondAccountFails() {
    // Arrange
    UUID sourceHolder = UUID.randomUUID();
    UUID destinationHolder = UUID.randomUUID();
    Account source =
        account(AccountType.CHECKING, CustomerType.PERSONAL, List.of(sourceHolder), List.of());
    Account destination =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(destinationHolder), List.of());
    stubBulkSave(source, destination, false);
    when(accounts.save(any(AccountEntity.class)))
        .thenAnswer(
            invocation -> {
              AccountEntity entity = invocation.getArgument(0);
              return destination.id().equals(entity.getId())
                  ? Mono.error(new OptimisticLockingFailureException("stale version"))
                  : Mono.just(entity);
            });
    ReactiveTransactionManager transactionManager =
        org.mockito.Mockito.mock(ReactiveTransactionManager.class);
    ReactiveTransaction transaction = org.mockito.Mockito.mock(ReactiveTransaction.class);
    when(transactionManager.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
    when(transactionManager.rollback(transaction)).thenReturn(Mono.empty());
    ProxyFactory proxyFactory = new ProxyFactory(adapter);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    AccountRepositoryPort transactionalAdapter = (AccountRepositoryPort) proxyFactory.getProxy();

    // Act
    Single<List<Account>> result = transactionalAdapter.transfer(source, destination);

    // Assert
    result.test().assertError(ConcurrentModificationException.class);
    verify(transactionManager).getReactiveTransaction(any());
    verify(transactionManager).rollback(transaction);
    verify(transactionManager, never()).commit(any());
  }

  @Test
  void restoreWritesBothAccountsInsideOneCommittedTransaction() {
    // Arrange
    Account source =
        account(AccountType.CHECKING, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    Account destination =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    stubBulkSave(source, destination, true);
    ReactiveTransactionManager transactionManager =
        org.mockito.Mockito.mock(ReactiveTransactionManager.class);
    ReactiveTransaction transaction = org.mockito.Mockito.mock(ReactiveTransaction.class);
    when(transactionManager.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
    when(transactionManager.commit(transaction)).thenReturn(Mono.empty());
    ProxyFactory proxyFactory = new ProxyFactory(adapter);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    AccountRepositoryPort transactionalAdapter = (AccountRepositoryPort) proxyFactory.getProxy();

    // Act
    Single<List<Account>> result = transactionalAdapter.restore(source, destination);

    // Assert
    result
        .test()
        .assertValue(
            restored ->
                restored.size() == 2
                    && restored.get(0).balance().equals(source.balance())
                    && restored.get(1).balance().equals(destination.balance()))
        .assertComplete();
    verify(accounts, org.mockito.Mockito.times(2)).save(any(AccountEntity.class));
    verify(transactionManager).getReactiveTransaction(any());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(any());
  }

  @Test
  void mapsOptimisticLockingFailureToConcurrentModification() {
    // Arrange
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    when(accounts.save(any(AccountEntity.class)))
        .thenReturn(Mono.error(new OptimisticLockingFailureException("stale version")));

    // Act
    Single<Account> result = adapter.save(account);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof ConcurrentModificationException
                    && error
                        .getMessage()
                        .equals("Account was modified concurrently: " + account.id()));
    verify(holders, never()).deleteByAccountId(any());
    verify(signers, never()).deleteByAccountId(any());
  }

  @Test
  void mapsNestedPersonalProductConstraintToBusinessRuleViolation() {
    // Arrange
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    var cause =
        new IllegalStateException(
            "duplicate key value violates unique constraint " + "uq_personal_account_product");
    when(accounts.save(any(AccountEntity.class)))
        .thenReturn(Mono.error(new DataIntegrityViolationException("insert failed", cause)));

    // Act
    Single<Account> result = adapter.save(account);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof BusinessRuleViolationException
                    && error
                        .getMessage()
                        .equals("A personal customer may have at most one account of this type"));
  }

  @Test
  void preservesUnrelatedDataIntegrityFailures() {
    // Arrange
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    var failure = new DataIntegrityViolationException("violates another_constraint");
    when(accounts.save(any(AccountEntity.class))).thenReturn(Mono.error(failure));

    // Act
    Single<Account> result = adapter.save(account);

    // Assert
    result.test().assertError(error -> error == failure);
  }

  @Test
  void findsAndHydratesAccountWithItsRelations() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    var signer = new AuthorizedSigner("Lucia Torres", "PASSPORT", "P123456");
    Account account =
        account(AccountType.CHECKING, CustomerType.BUSINESS, List.of(holderId), List.of(signer));
    when(accounts.findById(account.id())).thenReturn(Mono.just(AccountEntity.fromDomain(account)));
    when(holders.findByAccountId(account.id()))
        .thenReturn(
            Flux.just(
                holderEntity(account.id(), holderId, CustomerType.BUSINESS, AccountType.CHECKING)));
    when(signers.findByAccountId(account.id()))
        .thenReturn(Flux.just(signerEntity(account.id(), signer)));

    // Act
    Maybe<Account> result = adapter.findById(account.id());

    // Assert
    result.test().assertResult(account);
    verify(accounts).findById(account.id());
    verify(holders).findByAccountId(account.id());
    verify(signers).findByAccountId(account.id());
  }

  @Test
  void returnsEmptyWhenAccountScalarRowDoesNotExist() {
    // Arrange
    UUID accountId = UUID.randomUUID();
    when(accounts.findById(accountId)).thenReturn(Mono.empty());

    // Act
    Maybe<Account> result = adapter.findById(accountId);

    // Assert
    result.test().assertComplete();
    verifyNoInteractions(holders, signers, template);
  }

  @Test
  void rejectsHydratingAccountWithoutHolderRelations() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(holderId), List.of());
    when(accounts.findById(account.id())).thenReturn(Mono.just(AccountEntity.fromDomain(account)));
    when(holders.findByAccountId(account.id())).thenReturn(Flux.empty());
    when(signers.findByAccountId(account.id())).thenReturn(Flux.empty());

    // Act
    Maybe<Account> result = adapter.findById(account.id());

    // Assert
    result.test().assertError(NoSuchElementException.class);
  }

  @Test
  void findsPageByAccountTypeWithStableSortAndHydratesResults() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(holderId), List.of());
    when(template.select(any(Query.class), eq(AccountEntity.class)))
        .thenReturn(Flux.just(AccountEntity.fromDomain(account)));
    when(holders.findByAccountId(account.id()))
        .thenReturn(
            Flux.just(
                holderEntity(account.id(), holderId, CustomerType.PERSONAL, AccountType.SAVINGS)));
    when(signers.findByAccountId(account.id())).thenReturn(Flux.empty());

    // Act
    Flowable<Account> result = adapter.findAll(new AccountFilter(null, AccountType.SAVINGS), 2, 5);

    // Assert
    result.test().assertResult(account);
    var query = ArgumentCaptor.forClass(Query.class);
    verify(template).select(query.capture(), eq(AccountEntity.class));
    assertEquals(5, query.getValue().getLimit());
    assertEquals(10, query.getValue().getOffset());
    assertEquals(
        Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")), query.getValue().getSort());
    assertTrue(query.getValue().getCriteria().orElseThrow().toString().contains("accountType"));
    assertFalse(query.getValue().getCriteria().orElseThrow().toString().contains("id IN"));
    verify(holders, never()).findByCustomerId(any());
  }

  @Test
  void restrictsPageToHolderAccountIdsWhenCustomerFilterIsPresent() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(holders.findByCustomerId(customerId))
        .thenReturn(
            Flux.just(
                holderEntity(accountId, customerId, CustomerType.PERSONAL, AccountType.SAVINGS)));
    when(template.select(any(Query.class), eq(AccountEntity.class))).thenReturn(Flux.empty());

    // Act
    Flowable<Account> result = adapter.findAll(new AccountFilter(customerId, null), 1, 20);

    // Assert
    result.test().assertComplete();
    var query = ArgumentCaptor.forClass(Query.class);
    verify(template).select(query.capture(), eq(AccountEntity.class));
    assertEquals(20, query.getValue().getLimit());
    assertEquals(20, query.getValue().getOffset());
    String criteria = query.getValue().getCriteria().orElseThrow().toString();
    assertTrue(criteria.contains("id"));
    assertTrue(criteria.contains(accountId.toString()));
    assertFalse(criteria.contains("accountType"));
    verify(holders).findByCustomerId(customerId);
  }

  @Test
  void skipsAccountQueryWhenCustomerHasNoHolderRelations() {
    // Arrange
    UUID customerId = UUID.randomUUID();
    when(holders.findByCustomerId(customerId)).thenReturn(Flux.empty());

    // Act
    Flowable<Account> result =
        adapter.findAll(new AccountFilter(customerId, AccountType.CHECKING), 0, 20);

    // Assert
    result.test().assertComplete();
    verifyNoInteractions(template);
    verify(accounts, never()).findById(any(UUID.class));
  }

  @Test
  void deletesRelationsBeforeDeletingAccount() {
    // Arrange
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    when(holders.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(signers.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(accounts.delete(any(AccountEntity.class))).thenReturn(Mono.empty());

    // Act
    Completable result = adapter.delete(account);

    // Assert
    result.test().assertComplete();
    InOrder deletionOrder = inOrder(holders, signers, accounts);
    deletionOrder.verify(holders).deleteByAccountId(account.id());
    deletionOrder.verify(signers).deleteByAccountId(account.id());
    var deleted = ArgumentCaptor.forClass(AccountEntity.class);
    deletionOrder.verify(accounts).delete(deleted.capture());
    assertEquals(account.id(), deleted.getValue().getId());
    assertEquals(4L, deleted.getValue().getVersion());
  }

  @Test
  void mapsOptimisticLockingFailureDuringDeleteToConcurrentModification() {
    // Arrange
    Account account =
        account(AccountType.SAVINGS, CustomerType.PERSONAL, List.of(UUID.randomUUID()), List.of());
    when(holders.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(signers.deleteByAccountId(account.id())).thenReturn(Mono.empty());
    when(accounts.delete(any(AccountEntity.class)))
        .thenReturn(Mono.error(new OptimisticLockingFailureException("stale version")));

    // Act
    Completable result = adapter.delete(account);

    // Assert
    result
        .test()
        .assertError(
            error ->
                error instanceof ConcurrentModificationException
                    && error
                        .getMessage()
                        .equals("Account was modified concurrently: " + account.id()));
  }

  @Test
  void mapsMinimumDailyAverageInBothDirections() {
    // Arrange
    UUID holderId = UUID.randomUUID();
    Money minimum = new Money(new BigDecimal("150.00"), "PEN");
    Account account =
        new Account(
            UUID.randomUUID(),
            "19100012345678",
            AccountType.SAVINGS,
            new Money(new BigDecimal("100.50"), "PEN"),
            minimum,
            List.of(holderId),
            CustomerType.PERSONAL,
            List.of(),
            null,
            10,
            null,
            2,
            YearMonth.parse("2026-01"),
            4L,
            Instant.parse("2026-01-02T03:04:05Z"));

    // Act
    AccountEntity entity = AccountEntity.fromDomain(account);
    BigDecimal storedMinimum = entity.getMinimumDailyAverage();
    Account restored = entity.toDomain(List.of(holderId), CustomerType.PERSONAL, List.of());
    entity.setMinimumDailyAverage(null);
    Account legacyRow = entity.toDomain(List.of(holderId), CustomerType.PERSONAL, List.of());

    // Assert
    assertEquals(new BigDecimal("150.00"), storedMinimum);
    assertEquals(account, restored);
    assertEquals(new Money(BigDecimal.ZERO, "PEN"), legacyRow.minimumDailyAverage());
  }

  private void stubBulkSave(Account source, Account destination, boolean stubSave) {
    if (stubSave) {
      when(accounts.save(any(AccountEntity.class)))
          .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }
    when(holders.deleteByAccountId(any(UUID.class))).thenReturn(Mono.empty());
    when(signers.deleteByAccountId(any(UUID.class))).thenReturn(Mono.empty());
    when(template.insert(any(AccountHolderEntity.class)))
        .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    when(holders.findByAccountId(any(UUID.class)))
        .thenAnswer(
            invocation -> {
              UUID accountId = invocation.getArgument(0);
              Account matching = source.id().equals(accountId) ? source : destination;
              return Flux.just(
                  holderEntity(
                      accountId,
                      matching.holders().get(0),
                      CustomerType.PERSONAL,
                      matching.accountType()));
            });
    when(signers.findByAccountId(any(UUID.class))).thenReturn(Flux.empty());
  }

  private Account account(
      AccountType type,
      CustomerType holderType,
      List<UUID> holderIds,
      List<AuthorizedSigner> authorizedSigners) {
    return new Account(
        UUID.randomUUID(),
        "19100012345678",
        type,
        new Money(new BigDecimal("100.50"), "PEN"),
        holderIds,
        holderType,
        authorizedSigners,
        type == AccountType.CHECKING ? new Money(new BigDecimal("2.50"), "PEN") : null,
        type == AccountType.SAVINGS ? 10 : null,
        null,
        2,
        YearMonth.parse("2026-01"),
        4L,
        Instant.parse("2026-01-02T03:04:05Z"));
  }

  private AccountHolderEntity holderEntity(
      UUID accountId, UUID customerId, CustomerType customerType, AccountType accountType) {
    return new AccountHolderEntity(
        UUID.randomUUID(), accountId, customerId, customerType.name(), accountType.name());
  }

  private AuthorizedSignerEntity signerEntity(UUID accountId, AuthorizedSigner signer) {
    return new AuthorizedSignerEntity(
        UUID.randomUUID(),
        accountId,
        signer.fullName(),
        signer.documentType(),
        signer.documentNumber());
  }
}
