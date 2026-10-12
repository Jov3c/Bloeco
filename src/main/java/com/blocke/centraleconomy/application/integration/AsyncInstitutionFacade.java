package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.EconomyError;
import com.blocke.bloeco.api.EconomyResult;
import com.blocke.bloeco.api.InstitutionAccount;
import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionClient;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.bloeco.api.MoneyAmount;
import com.blocke.bloeco.api.OperationContext;
import com.blocke.bloeco.api.ReservationReceipt;
import com.blocke.bloeco.api.SettlementKind;
import com.blocke.bloeco.api.SettlementReceipt;
import com.blocke.centraleconomy.application.LedgerStore;
import com.blocke.centraleconomy.application.TaxRuleService;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.mysql.BloecoDataSource;
import com.blocke.centraleconomy.storage.mysql.MySqlEcosystemStore;
import com.blocke.centraleconomy.storage.mysql.MySqlLedgerStore;
import com.blocke.centraleconomy.storage.mysql.MySqlTransactionManager;
import com.blocke.centraleconomy.storage.sqlite.SqliteEcosystemStore;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Bounded asynchronous boundary for all consumer-plugin database access. */
public final class AsyncInstitutionFacade implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean accepting=new AtomicBoolean(true);
    private final BooleanSupplier readOnly;
    private final CompletableFuture<Boolean> ready=new CompletableFuture<>();
    private volatile Services services;

    public AsyncInstitutionFacade(Supplier<Services> factory,CompletionStage<Result<Void>> dependency,
                                  BooleanSupplier readOnly,int workers,int queueCapacity){
        this.readOnly=readOnly;
        executor=new ThreadPoolExecutor(workers,workers,0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),r->{Thread t=new Thread(r,"Bloeco-Ecosystem");t.setDaemon(true);return t;},
                new ThreadPoolExecutor.AbortPolicy());
        executor.execute(()->{try{Result<Void> state=dependency.toCompletableFuture().join();
            if(!state.isSuccess()){ready.complete(false);return;}services=factory.get();ready.complete(true);}
            catch(Throwable failure){ready.complete(false);}});
    }

    public static AsyncInstitutionFacade sqlite(Path path,CompletionStage<Result<Void>> dependency,
            BooleanSupplier readOnly,int queueCapacity){Clock clock=Clock.systemUTC();return new AsyncInstitutionFacade(()->{
        SqliteLedgerStore ledger=new SqliteLedgerStore(path);return new Services(new SqliteEcosystemStore(path),ledger,
                new TaxRuleService(ledger,clock),clock);},dependency,readOnly,1,queueCapacity);}

    public static AsyncInstitutionFacade mysql(BloecoDataSource source,MySqlTransactionManager transactions,
            CompletionStage<Result<Void>> dependency,BooleanSupplier readOnly,int workers,int queueCapacity){
        Clock clock=Clock.systemUTC();return new AsyncInstitutionFacade(()->{MySqlLedgerStore ledger=new MySqlLedgerStore(source,transactions);
            return new Services(new MySqlEcosystemStore(source,transactions),ledger,new TaxRuleService(ledger,clock),clock);},
                dependency,readOnly,Math.max(2,workers),queueCapacity);}

    public CompletionStage<EconomyResult<InstitutionClient>> connect(String pluginName,String mainClass,String version,
                                                                     InstitutionDescriptor descriptor){
        return submit(true,s->{InstitutionRecord record=new InstitutionService(s.store,s.clock).registerOrResume(
                pluginName,mainClass,version,descriptor);return new Client(record);});
    }

    private <T> CompletionStage<EconomyResult<T>> submit(boolean write,Function<Services,T> work){
        if(!accepting.get())return CompletableFuture.completedFuture(unavailable());
        CompletableFuture<EconomyResult<T>> future=new CompletableFuture<>();
        try{executor.execute(()->{try{if(!ready.join()||services==null){future.complete(unavailable());return;}
            if(write&&readOnly.getAsBoolean()){future.complete(EconomyResult.failure(EconomyError.INTEGRITY_READ_ONLY,
                    "Bloeco 当前处于只读保护状态。"));return;}future.complete(EconomyResult.success(work.apply(services)));}
            catch(Throwable failure){future.complete(failure(failure));}});}catch(RejectedExecutionException e){future.complete(unavailable());}
        return future;
    }

    private final class Client implements InstitutionClient{
        private final InstitutionRecord institution;private final Map<String,InstitutionAccount> publicAccounts;
        private Client(InstitutionRecord institution){this.institution=institution;Map<String,InstitutionAccount> result=new LinkedHashMap<>();
            institution.accounts().forEach((purpose,id)->result.put(purpose,new Handle(institution.clientId(),purpose,id)));
            publicAccounts=Map.copyOf(result);}
        @Override public String clientId(){return institution.clientId();}
        @Override public Set<InstitutionCapability> capabilities(){return institution.capabilities();}
        @Override public Map<String,InstitutionAccount> accounts(){return publicAccounts;}
        @Override public CompletionStage<EconomyResult<MoneyAmount>> walletBalance(UUID player){return submit(false,s->
                new MoneyAmount(s.store.balance(AccountId.player(player))));}
        @Override public CompletionStage<EconomyResult<MoneyAmount>> balance(InstitutionAccount account){return submit(false,s->{
            capability(InstitutionCapability.READ_OWN_BALANCE);return new MoneyAmount(s.store.balance(handle(account).accountId));});}
        @Override public CompletionStage<EconomyResult<SettlementReceipt>> chargePlayer(UUID player,InstitutionAccount account,
                MoneyAmount amount,SettlementKind kind,OperationContext context){return submit(true,s->receipt(
            new InstitutionSettlementService(s.store,s.taxes,s.clock).chargePlayer(clientId(),player,handle(account).purpose,
                    positive(amount),kind,context)));}
        @Override public CompletionStage<EconomyResult<SettlementReceipt>> payPlayer(InstitutionAccount account,UUID player,
                MoneyAmount amount,SettlementKind kind,OperationContext context){return submit(true,s->receipt(
            new InstitutionSettlementService(s.store,s.taxes,s.clock).payPlayer(clientId(),handle(account).purpose,player,
                    positive(amount),kind,context)));}
        @Override public CompletionStage<EconomyResult<SettlementReceipt>> refund(UUID original,MoneyAmount amount,
                OperationContext context){return submit(true,s->receipt(new InstitutionSettlementService(s.store,s.taxes,s.clock)
                .refund(clientId(),original,positive(amount),context)));}
        @Override public CompletionStage<EconomyResult<ReservationReceipt>> reservePlayerFunds(UUID player,MoneyAmount amount,
                java.time.Instant expires,OperationContext context){return submit(true,s->reservation(
            new FundReservationService(s.store,s.taxes,s.clock).reserve(clientId(),player,positive(amount),expires,context)));}
        @Override public CompletionStage<EconomyResult<SettlementReceipt>> captureReservation(UUID id,InstitutionAccount account,
                OperationContext context){return submit(true,s->receipt(new FundReservationService(s.store,s.taxes,s.clock)
                .capture(clientId(),id,handle(account).purpose,context)));}
        @Override public CompletionStage<EconomyResult<ReservationReceipt>> releaseReservation(UUID id,OperationContext context){
            return submit(true,s->reservation(new FundReservationService(s.store,s.taxes,s.clock).release(clientId(),id,context)));}
        @Override public CompletionStage<EconomyResult<Optional<SettlementReceipt>>> findOperation(String key){return submit(false,s->{
            capability(InstitutionCapability.READ_OWN_JOURNAL);return s.store.findOperation(clientId(),key).map(this::receipt);});}
        @Override public CompletionStage<EconomyResult<List<SettlementReceipt>>> recentTransactions(InstitutionAccount account,int limit){
            return submit(false,s->{capability(InstitutionCapability.READ_OWN_JOURNAL);Handle h=handle(account);
                return s.store.recentTransactions(clientId(),h.purpose,limit).stream().map(this::receipt).toList();});}
        private Handle handle(InstitutionAccount account){if(!(account instanceof Handle h)||!h.clientId.equals(clientId())
                ||publicAccounts.get(h.purpose)!=account)throw new LedgerException(LedgerException.Code.UNAUTHORIZED,
                "institution account handle is not owned by caller");return h;}
        private void capability(InstitutionCapability value){if(!capabilities().contains(value))throw new LedgerException(
                LedgerException.Code.CAPABILITY_DENIED,"institution capability is not granted: "+value);}
        private SettlementReceipt receipt(SettlementRecord r){return new SettlementReceipt(r.operationId(),r.journalId(),
                SettlementReceipt.Status.COMMITTED,r.kind(),r.playerId(),publicAccounts.values().stream().map(a->(Handle)a)
                .filter(a->a.accountId.equals(r.institutionAccountId())).findFirst().orElseThrow(),
                new MoneyAmount(r.principal().minor()),new MoneyAmount(r.tax().minor()),new MoneyAmount(r.fee().minor()),
                new MoneyAmount(r.institutionNet().minor()),r.displayMemo(),r.committedAt(),Optional.ofNullable(r.originalOperationId()));}
    }

    private static Money positive(MoneyAmount amount){return Money.ofMinor(MoneyAmount.positive(amount.minorUnits()).minorUnits());}
    private static ReservationReceipt reservation(ReservationRecord r){return new ReservationReceipt(r.reservationId(),
            ReservationReceipt.Status.valueOf(r.status().name()),r.playerId(),new MoneyAmount(r.principal().minor()),r.expiresAt(),r.createdAt());}
    private static <T> EconomyResult<T> unavailable(){return EconomyResult.failure(EconomyError.STORAGE_UNAVAILABLE,
            "Bloeco 经济服务暂时不可用。");}
    private static <T> EconomyResult<T> failure(Throwable cause){Throwable value=cause;
        while(value.getCause()!=null&&(value instanceof java.util.concurrent.CompletionException))value=value.getCause();
        if(value instanceof LedgerException e)return EconomyResult.failure(switch(e.code()){
            case INVALID_AMOUNT->EconomyError.INVALID_AMOUNT;case ACCOUNT_NOT_FOUND->EconomyError.ACCOUNT_NOT_FOUND;
            case ACCOUNT_FROZEN->EconomyError.ACCOUNT_FROZEN;case INSUFFICIENT_FUNDS->EconomyError.INSUFFICIENT_FUNDS;
            case UNAUTHORIZED->EconomyError.UNAUTHORIZED;case CAPABILITY_DENIED->EconomyError.CAPABILITY_DENIED;
            case POLICY_REJECTED->EconomyError.POLICY_REJECTED;case IDEMPOTENCY_CONFLICT->EconomyError.IDEMPOTENCY_CONFLICT;
            case INTEGRITY_FAILURE->EconomyError.INTEGRITY_READ_ONLY;case STORAGE_UNAVAILABLE->EconomyError.STORAGE_UNAVAILABLE;
            default->EconomyError.INVALID_REQUEST;},e.getMessage());
        if(value instanceof IllegalArgumentException)return EconomyResult.failure(EconomyError.INVALID_REQUEST,value.getMessage());
        return EconomyResult.failure(EconomyError.INTERNAL_ERROR,"Bloeco 未能完成该请求。");}
    @Override public void close(){if(!accepting.compareAndSet(true,false))return;executor.shutdown();
        try{if(!executor.awaitTermination(30,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException e){
            Thread.currentThread().interrupt();executor.shutdownNow();}Services current=services;if(current!=null)current.close();}

    public record Services(EcosystemStore store,LedgerStore ledger,TaxRuleService taxes,Clock clock) implements AutoCloseable{
        @Override public void close(){store.close();ledger.close();}}
    private record Handle(String clientId,String purpose,AccountId accountId) implements InstitutionAccount{}
}
