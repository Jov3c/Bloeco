package com.blocke.centraleconomy.storage.mysql;

import com.blocke.bloeco.api.SettlementKind;
import com.blocke.centraleconomy.application.integration.EcosystemStore;
import com.blocke.centraleconomy.application.integration.InstitutionRecord;
import com.blocke.centraleconomy.application.integration.ReservationRecord;
import com.blocke.centraleconomy.application.integration.SettlementRecord;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.ledger.Posting;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.storage.mysql.repository.BalanceRepository;
import com.blocke.centraleconomy.storage.mysql.repository.InstitutionRepository;
import com.blocke.centraleconomy.storage.mysql.repository.LedgerRepository;
import com.blocke.centraleconomy.storage.mysql.repository.OutboxRepository;
import com.blocke.centraleconomy.storage.mysql.repository.ReservationRepository;
import com.blocke.centraleconomy.storage.mysql.repository.SettlementRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Concurrent InnoDB implementation of the embedded ecosystem API store. */
public final class MySqlEcosystemStore implements EcosystemStore {
    private final DataSource dataSource;
    private final MySqlTransactionManager transactions;
    private final AutoCloseable ownedDataSource;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final InstitutionRepository institutions = new InstitutionRepository();
    private final SettlementRepository settlements = new SettlementRepository();
    private final ReservationRepository reservations = new ReservationRepository();
    private final BalanceRepository balances = new BalanceRepository();
    private final LedgerRepository journals = new LedgerRepository();
    private final OutboxRepository outbox = new OutboxRepository();

    public MySqlEcosystemStore(String url, String user, String password, int poolSize) {
        this(new BloecoDataSource(url, user, password, poolSize), true);
    }
    public MySqlEcosystemStore(BloecoDataSource source, MySqlTransactionManager transactions) {
        this.dataSource=source.dataSource(); this.transactions=transactions; this.ownedDataSource=null;
        MySqlMigrations.migrate(dataSource, transactions);
    }
    private MySqlEcosystemStore(BloecoDataSource source, boolean owned) {
        this.dataSource=source.dataSource(); this.transactions=new MySqlTransactionManager(dataSource);
        this.ownedDataSource=owned?source:null; MySqlMigrations.migrate(dataSource,transactions);
    }

    @Override public InstitutionRecord registerOrResume(InstitutionRegistration value) {
        open(); return transactions.execute(c->institutions.registerOrResume(c,value));
    }
    @Override public Optional<InstitutionRecord> institution(String clientId) {
        open(); try(Connection c=dataSource.getConnection()){return institutions.find(c,clientId,false);}
        catch(SQLException e){throw storage("无法读取机构",e);}
    }

    @Override public SettlementRecord commitSettlement(SettlementCommand command) {
        open(); return transactions.execute(c->{
            Optional<SettlementRecord> replay=settlements.findByKey(c,command.clientId(),command.idempotencyKey());
            if(replay.isPresent()){matching(replay.get(),command.fingerprint());return replay.get();}
            institutions.requireOwnedAccount(c,command.clientId(),command.institutionAccountId());
            List<Posting> lines=new ArrayList<>();
            if(command.type()==SettlementRecord.Type.CHARGE){
                long debit=Math.addExact(command.principal().minor(),Math.addExact(command.tax().minor(),command.fee().minor()));
                lines.add(new Posting(AccountId.player(command.playerId()),-debit));
                lines.add(new Posting(command.institutionAccountId(),command.institutionNet().minor()));
                add(lines,AccountId.taxRevenue(),command.tax().minor());add(lines,AccountId.feeRevenue(),command.fee().minor());
            }else if(command.type()==SettlementRecord.Type.PAYOUT){
                lines.add(new Posting(command.institutionAccountId(),-command.principal().minor()));
                lines.add(new Posting(AccountId.player(command.playerId()),command.principal().minor()));
            }else throw new LedgerException(LedgerException.Code.INVALID_JOURNAL,"invalid settlement type");
            commitJournal(c,command.journalId(),command.clientId(),command.idempotencyKey(),command.displayMemo(),
                    command.committedAt(),lines);
            SettlementRecord result=new SettlementRecord(command.operationId(),command.journalId(),command.clientId(),
                    command.type(),command.kind(),command.playerId(),command.institutionAccountId(),command.principal(),
                    command.tax(),command.fee(),command.institutionNet(),command.businessType(),command.businessReference(),
                    command.displayMemo(),command.idempotencyKey(),command.fingerprint(),command.committedAt(),null,
                    command.taxRuleVersion());
            settlements.insert(c,result);return result;
        });
    }

    @Override public SettlementRecord commitRefund(RefundCommand command) {
        open(); return transactions.execute(c->{
            Optional<SettlementRecord> replay=settlements.findByKey(c,command.clientId(),command.idempotencyKey());
            if(replay.isPresent()){matching(replay.get(),command.fingerprint());return replay.get();}
            SettlementRecord original=settlements.findById(c,command.originalOperationId(),true).orElseThrow(()->
                    new LedgerException(LedgerException.Code.INVALID_JOURNAL,"original settlement does not exist"));
            if(!original.clientId().equals(command.clientId())||original.type()!=SettlementRecord.Type.CHARGE)
                throw new LedgerException(LedgerException.Code.UNAUTHORIZED,"original charge is not owned by caller");
            long refundedPrincipal=settlements.refunded(c,original.operationId(),"principal_minor");
            long refundedTax=settlements.refunded(c,original.operationId(),"tax_minor");
            long principalLeft=Math.subtractExact(original.principal().minor(),refundedPrincipal);
            long taxLeft=Math.subtractExact(original.tax().minor(),refundedTax);
            if(command.principal().minor()>principalLeft)throw new LedgerException(
                    LedgerException.Code.INVALID_AMOUNT,"refund exceeds remaining principal");
            long tax=command.principal().minor()==principalLeft?taxLeft:Math.min(taxLeft,proportional(
                    original.tax().minor(),command.principal().minor(),original.principal().minor()));
            List<Posting> lines=new ArrayList<>();
            lines.add(new Posting(original.institutionAccountId(),-command.principal().minor()));
            add(lines,AccountId.taxRevenue(),-tax);
            lines.add(new Posting(AccountId.player(original.playerId()),Math.addExact(command.principal().minor(),tax)));
            commitJournal(c,command.journalId(),command.clientId(),command.idempotencyKey(),command.displayMemo(),
                    command.committedAt(),lines);
            SettlementRecord result=new SettlementRecord(command.operationId(),command.journalId(),command.clientId(),
                    SettlementRecord.Type.REFUND,original.kind(),original.playerId(),original.institutionAccountId(),
                    command.principal(),Money.ofMinor(tax),Money.ofMinor(0),command.principal(),command.businessType(),
                    command.businessReference(),command.displayMemo(),command.idempotencyKey(),command.fingerprint(),
                    command.committedAt(),original.operationId(),original.taxRuleVersion());
            settlements.insert(c,result);return result;
        });
    }

    @Override public Optional<SettlementRecord> findOperation(String clientId,String key){
        open();try(Connection c=dataSource.getConnection()){return settlements.findByKey(c,clientId,key);}
        catch(SQLException e){throw storage("无法查询结算",e);}
    }
    @Override public List<SettlementRecord> recentTransactions(String clientId,String purpose,int limit){
        open();if(limit<1||limit>100)throw new IllegalArgumentException("limit must be between 1 and 100");
        try(Connection c=dataSource.getConnection()){
            AccountId account=AccountId.plugin(clientId,purpose);institutions.requireOwnedAccount(c,clientId,account);
            return settlements.recent(c,clientId,account,limit);
        }catch(SQLException e){throw storage("无法查询结算记录",e);}
    }
    @Override public long balance(AccountId id){return balanceValue(id,"balance_minor");}
    @Override public long reservedBalance(UUID player){return balanceValue(AccountId.player(player),"reserved_minor");}
    @Override public long availableBalance(UUID player){AccountId id=AccountId.player(player);
        return Math.subtractExact(balanceValue(id,"balance_minor"),balanceValue(id,"reserved_minor"));}

    @Override public ReservationRecord reserve(ReservationCommand command){
        open();return transactions.execute(c->{
            Optional<ReservationRecord> replay=reservations.findByKey(c,command.clientId(),command.idempotencyKey());
            if(replay.isPresent()){if(!replay.get().fingerprint().equals(command.fingerprint()))conflict();return replay.get();}
            AccountId player=AccountId.player(command.playerId());
            BalanceRepository.StoredAccount account=lockOne(c,player);long total=Math.addExact(
                    command.principal().minor(),command.tax().minor());
            if(account.available()<total)throw new LedgerException(LedgerException.Code.INSUFFICIENT_FUNDS,
                    "player has insufficient available funds");
            balances.updateReserved(c,player,Math.addExact(account.reserved(),total));
            ReservationRecord result=new ReservationRecord(command.reservationId(),command.clientId(),command.playerId(),
                    command.principal(),command.tax(),command.taxRuleVersion(),ReservationRecord.Status.ACTIVE,
                    command.expiresAt(),command.createdAt(),command.createdAt(),command.idempotencyKey(),command.fingerprint());
            reservations.insert(c,result);return result;
        });
    }

    @Override public SettlementRecord capture(CaptureCommand command){
        open();return transactions.execute(c->{
            Optional<SettlementRecord> replay=settlements.findByKey(c,command.clientId(),command.idempotencyKey());
            if(replay.isPresent()){matching(replay.get(),command.fingerprint());return replay.get();}
            ReservationRecord reservation=reservations.find(c,command.reservationId(),true).orElseThrow(()->
                    new LedgerException(LedgerException.Code.INVALID_JOURNAL,"reservation does not exist"));
            own(command.clientId(),reservation);institutions.requireOwnedAccount(c,command.clientId(),command.institutionAccountId());
            if(reservation.status()!=ReservationRecord.Status.ACTIVE||!reservation.expiresAt().isAfter(command.committedAt()))
                throw new LedgerException(LedgerException.Code.POLICY_REJECTED,"reservation is no longer active");
            AccountId player=AccountId.player(reservation.playerId());
            List<Posting> lines=new ArrayList<>();lines.add(new Posting(player,-reservation.reservedTotal().minor()));
            lines.add(new Posting(command.institutionAccountId(),reservation.principal().minor()));
            add(lines,AccountId.taxRevenue(),reservation.tax().minor());
            commitJournal(c,command.journalId(),command.clientId(),command.idempotencyKey(),command.displayMemo(),
                    command.committedAt(),lines,Map.of(player,-reservation.reservedTotal().minor()));
            SettlementRecord result=new SettlementRecord(command.operationId(),command.journalId(),command.clientId(),
                    SettlementRecord.Type.CHARGE,SettlementKind.PURCHASE,reservation.playerId(),command.institutionAccountId(),
                    reservation.principal(),reservation.tax(),Money.ofMinor(0),reservation.principal(),command.businessType(),
                    command.businessReference(),command.displayMemo(),command.idempotencyKey(),command.fingerprint(),
                    command.committedAt(),null,reservation.taxRuleVersion());settlements.insert(c,result);
            updateReservation(c,reservation.reservationId(),"CAPTURED",command.committedAt(),command.operationId(),null,null);
            return result;
        });
    }

    @Override public ReservationRecord release(ReservationTransition command){
        open();return transactions.execute(c->{
            ReservationRecord r=reservations.find(c,command.reservationId(),true).orElseThrow(()->
                    new LedgerException(LedgerException.Code.INVALID_JOURNAL,"reservation does not exist"));own(command.clientId(),r);
            String[] prior=releaseIdentity(c,r.reservationId());
            if(prior[0]!=null){if(!prior[0].equals(command.idempotencyKey())||!prior[1].equals(command.fingerprint()))conflict();return r;}
            if(r.status()!=ReservationRecord.Status.ACTIVE)throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                    "reservation is no longer active");
            AccountId player=AccountId.player(r.playerId());BalanceRepository.StoredAccount locked=lockOne(c,player);
            balances.updateReserved(c,player,Math.subtractExact(locked.reserved(),r.reservedTotal().minor()));
            updateReservation(c,r.reservationId(),"RELEASED",command.transitionedAt(),null,command.idempotencyKey(),command.fingerprint());
            return reservations.find(c,r.reservationId(),false).orElseThrow();
        });
    }

    @Override public Optional<ReservationRecord> reservation(String clientId,UUID id,Instant now){
        open();return transactions.execute(c->{Optional<ReservationRecord> found=reservations.find(c,id,true);
            if(found.isEmpty()||!found.get().clientId().equals(clientId))return Optional.empty();
            ReservationRecord r=found.get();if(r.status()==ReservationRecord.Status.ACTIVE&&!r.expiresAt().isAfter(now)){
                AccountId player=AccountId.player(r.playerId());BalanceRepository.StoredAccount locked=lockOne(c,player);
                balances.updateReserved(c,player,Math.subtractExact(locked.reserved(),r.reservedTotal().minor()));
                updateReservation(c,id,"EXPIRED",now,null,null,null);r=reservations.find(c,id,false).orElseThrow();}
            return Optional.of(r);});
    }

    private void commitJournal(Connection c,UUID id,String client,String key,String memo,Instant at,List<Posting> raw)
            throws Exception{
        commitJournal(c,id,client,key,memo,at,raw,Map.of());
    }
    private void commitJournal(Connection c,UUID id,String client,String key,String memo,Instant at,List<Posting> raw,
                               Map<AccountId,Long> reservedDeltas) throws Exception{
        Map<AccountId,Long>deltas=new LinkedHashMap<>();for(Posting p:raw)deltas.merge(p.accountId(),p.amountMinor(),Math::addExact);
        deltas.entrySet().removeIf(e->e.getValue()==0);List<AccountId>ids=java.util.stream.Stream.concat(
                deltas.keySet().stream(),reservedDeltas.keySet().stream()).distinct().sorted().toList();
        Map<AccountId,BalanceRepository.StoredAccount>locked=balances.lockInOrder(c,ids);Map<AccountId,Long>updated=new LinkedHashMap<>();
        Map<AccountId,Long>updatedReserved=new LinkedHashMap<>();
        for(AccountId accountId:ids){var a=locked.get(accountId);if(a==null)throw new LedgerException(
                LedgerException.Code.ACCOUNT_NOT_FOUND,"account does not exist: "+accountId.value());
            long value=Math.addExact(a.balance(),deltas.getOrDefault(accountId,0L));
            long reserved=Math.addExact(a.reserved(),reservedDeltas.getOrDefault(accountId,0L));
            if(reserved<0)throw new LedgerException(LedgerException.Code.INTEGRITY_FAILURE,"reserved balance would be negative");
            if(!a.permitsNegative()&&value<reserved)throw new LedgerException(LedgerException.Code.INSUFFICIENT_FUNDS,
                    "account has insufficient available funds");if(deltas.containsKey(accountId))updated.put(accountId,value);
            if(reservedDeltas.containsKey(accountId))updatedReserved.put(accountId,reserved);}
        JournalEntry journal=JournalEntry.create(id, JournalType.PLUGIN_TRANSFER,memo,client,key,at,
                deltas.entrySet().stream().map(e->new Posting(e.getKey(),e.getValue())).toList());
        journals.insertJournal(c,journal);journals.insertIdempotency(c,journal);balances.update(c,updated);
        for(var entry:updatedReserved.entrySet())balances.updateReserved(c,entry.getKey(),entry.getValue());
        outbox.insertJournalCommitted(c,journal);
    }
    private BalanceRepository.StoredAccount lockOne(Connection c,AccountId id)throws SQLException{
        var result=balances.lockInOrder(c,List.of(id));var account=result.get(id);if(account==null)throw new LedgerException(
                LedgerException.Code.ACCOUNT_NOT_FOUND,"account does not exist: "+id.value());return account;}
    private long balanceValue(AccountId id,String column){if(!column.equals("balance_minor")&&!column.equals("reserved_minor"))throw new IllegalArgumentException();
        open();try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(
                "SELECT "+column+" FROM account_balances WHERE account_id=?")){s.setString(1,id.value());try(ResultSet r=s.executeQuery()){
            if(!r.next())throw new LedgerException(LedgerException.Code.ACCOUNT_NOT_FOUND,"account does not exist");return r.getLong(1);}}
        catch(SQLException e){throw storage("无法读取余额",e);}}
    private static void updateReservation(Connection c,UUID id,String status,Instant at,UUID capture,String key,String fp)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("""
                UPDATE ecosystem_reservations SET status=?,updated_at_epoch_ms=?,capture_operation_id=COALESCE(?,capture_operation_id),
                release_idempotency_key=COALESCE(?,release_idempotency_key),release_fingerprint=COALESCE(?,release_fingerprint)
                WHERE reservation_id=?
                """)){s.setString(1,status);s.setLong(2,at.toEpochMilli());s.setString(3,capture==null?null:capture.toString());
            s.setString(4,key);s.setString(5,fp);s.setString(6,id.toString());s.executeUpdate();}}
    private static String[] releaseIdentity(Connection c,UUID id)throws SQLException{try(PreparedStatement s=c.prepareStatement(
            "SELECT release_idempotency_key,release_fingerprint FROM ecosystem_reservations WHERE reservation_id=?")){
        s.setString(1,id.toString());try(ResultSet r=s.executeQuery()){r.next();return new String[]{r.getString(1),r.getString(2)};}}}
    private static void own(String client,ReservationRecord r){if(!r.clientId().equals(client))throw new LedgerException(
            LedgerException.Code.UNAUTHORIZED,"reservation is not owned by caller");}
    private static void add(List<Posting> lines,AccountId id,long amount){if(amount!=0)lines.add(new Posting(id,amount));}
    private static long proportional(long total,long part,long whole){return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
            .divide(BigDecimal.valueOf(whole),0, RoundingMode.HALF_UP).longValueExact();}
    private static void matching(SettlementRecord r,String fp){if(!r.fingerprint().equals(fp))conflict();}
    private static void conflict(){throw new LedgerException(LedgerException.Code.IDEMPOTENCY_CONFLICT,
            "idempotency key conflicts with another request");}
    private void open(){if(closed.get())throw new LedgerException(LedgerException.Code.STORAGE_UNAVAILABLE,"ecosystem store is closed");}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;if(ownedDataSource!=null)try{ownedDataSource.close();}
        catch(Exception e){throw storage("无法关闭 MySQL",e);}}
    private static LedgerException storage(String message,Throwable cause){return new LedgerException(
            LedgerException.Code.STORAGE_UNAVAILABLE,message,cause);}
}
