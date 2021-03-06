package me.ele.jarch.athena.netty;

import com.github.mpjct.jmpjct.mysql.proto.ERR;
import com.github.mpjct.jmpjct.util.ErrorCode;
import me.ele.jarch.athena.exception.QuitException;
import me.ele.jarch.athena.netty.state.*;
import me.ele.jarch.athena.server.async.AsyncLocalClient;
import me.ele.jarch.athena.sharding.ShardingRouter;
import me.ele.jarch.athena.sql.BatchQuery;
import me.ele.jarch.athena.sql.ComposableCommand;
import me.ele.jarch.athena.worker.SchedulerWorker;
import me.ele.jarch.athena.worker.manager.BatchContextManager;
import me.ele.jarch.athena.worker.manager.BatchTargetStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by zhengchao on 16/8/29.
 */
public class BatchSessionContext extends ComposableCommand {
    private static final Logger logger = LoggerFactory.getLogger(BatchSessionContext.class);
    private static ErrorCode[] abortUserConnectionErrorCodes = {ErrorCode.ER_ABORTING_CONNECTION,};

    private final BatchAnalyzeState analyzeState = new BatchAnalyzeState(this);
    private final BatchHandleState handleState = new BatchHandleState(this);
    private final BatchResultState resultState = new BatchResultState(this);
    private final BatchQuitState quitState = new BatchQuitState(this);
    private State currentState;

    private final BatchContextManager batchContextManager;
    /**
     * every batchCtx will be bind to only one transId cause that sqlCtx will abandon the batchCtx after one transaction
     */
    public final String transId;
    /**
     * use client's connection id and trans id from main sqlCtx to identify every batch operation
     */
    public final String batchTransLogIdStr;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean closedUser = new AtomicBoolean(false);
    private final AtomicLong localClientIdGenerator = new AtomicLong(0);

    private byte[] packet;
    //<shardTable,batchQuery>
    private final Map<String, BatchQuery> querySqls;
    /**
     * all clients created by the batchCtx,<shardTable,AsyncLocalClient>
     */
    public final Map<String, AsyncLocalClient> clients;
    /**
     * all clients used for current query,<batchClientId,AsyncLocalClient>
     */
    public final Map<String, AsyncLocalClient> queryUsedClients;
    /**
     * The map is used for tracking whether we have received the client's query result from DB.
     * The key is the local clients' id generated by `localClientIdGenerator`.
     * The value is Boolean, `true` for that we have received its result; `false` for not.
     * <batchClientId,boolean>
     */
    private final Map<String, Boolean> usedClientsReturnedMap;
    private final AtomicInteger finishedBatchQueryCount = new AtomicInteger(0);
    private final AtomicBoolean abortErrorDetected = new AtomicBoolean(false);
    // store the error packets for the current sql
    private final List<ERR> errorPackets = new ArrayList<>();

    public BatchSessionContext(BatchContextManager batchContextManager) {
        this.batchContextManager = Objects.requireNonNull(batchContextManager,
            () -> "BatchContextManager must be not null, msg: " + toString());
        this.transId = batchContextManager.getTransactionId();
        this.batchTransLogIdStr = String.format("batch transId=%s,clientConnId=%d", transId,
            batchContextManager.getConnectionId());
        this.packet = new byte[0];
        this.querySqls = new HashMap<>();
        this.clients = new HashMap<>();
        this.queryUsedClients = new HashMap<>();
        this.usedClientsReturnedMap = new HashMap<>();
        this.currentState = analyzeState;
    }

    public SqlSessionContext getSqlSessionContext() {
        return batchContextManager.getSqlSessionContext();
    }

    public void markAsDead() {
        if (this.currentState != quitState) {
            return;
        }
        this.alive.set(false);
    }

    public boolean isAlive() {
        return this.alive.get();
    }

    public void clientHasReturned(String localClientId) {
        if (!isAlive()) {
            return;
        }
        if (!usedClientsReturnedMap.containsKey(localClientId)) {
            logger.error("{},batchClientId={},strange,illegal localClientId to mark return",
                batchTransLogIdStr, localClientId);
            return;
        }

        Boolean previousStatus = usedClientsReturnedMap.put(localClientId, true);
        if (Objects.isNull(previousStatus) || !previousStatus) {
            finishedBatchQueryCount.incrementAndGet();
        }
    }

    public BatchSessionContext setPacket(byte[] packet) {
        if (packet != null) {
            this.packet = packet;
        }
        return this;
    }

    public byte[] getPacket() {
        return this.packet;
    }

    public String getGroupName() {
        return this.batchContextManager.getGroupName();
    }

    public boolean isAutoCommit4Client() {
        return this.batchContextManager.isAutoCommit4Client();
    }

    public boolean isBindMaster() {
        return this.batchContextManager.isBindMaster();
    }

    public Map<String, BatchQuery> getQuerySqls() {
        return querySqls;
    }

    public void setQuerySqls(Map<String, BatchQuery> querySqls) {
        this.querySqls.clear();
        if (Objects.nonNull(querySqls)) {
            this.querySqls.putAll(querySqls);
        }
    }

    public State getCurrentState() {
        return currentState;
    }

    public ShardingRouter getShardingRouter() {
        return this.batchContextManager.getShardingRouter();
    }

    @Override protected boolean doInnerExecute() throws QuitException {
        return this.currentState.handle();
    }

    public void setState(SESSION_STATUS status) {
        switch (status) {
            case BATCH_ANALYZE:
                this.currentState = analyzeState;
                break;
            case BATCH_HANDLE:
                this.currentState = handleState;
                break;
            case BATCH_RESULT:
                this.currentState = resultState;
                break;
            case BATCH_QUIT:
                this.currentState = quitState;
                break;
            default:
                logger.error("{},try to set invalid state type:{}", batchTransLogIdStr, status);
        }
    }

    public void closeUserConn() {
        if (!closedUser.getAndSet(true)) {
            this.batchContextManager.closeUserConn();
        }
    }

    /*
     * BatchSessionContext use AsyncLocalClient to connect to its local Athena.
     * When this connection is aborted, athena will send an error packet of ER_ABORTING_CONNECTION
     * to the client. We give priority to this error packet when handling errors,
     * so we have to clear the error packets that we received so far.
     * */
    public void asyncClientAborted(ERR err) {
        if (this.abortErrorDetected.getAndSet(true)) {
            return;
        }
        this.errorPackets.clear();
        this.errorPackets.add(err);
    }

    public void addErrorPacketIfNonAbortingDetected(ERR errPacket) {
        if (errPacket == null || this.abortErrorDetected.get()) {
            return;
        }
        this.errorPackets.add(errPacket);
    }

    public void trySendBack2UserOrCloseUser() {
        if (allResultsReceived()) {
            /*
             * This is the implementation tradeoff. We choose to fail after collecting all the clients' result,
             * including good and error.
             * Fast-fail is avoided as there is more concurrent problem. For example, if fast fail is chosen,
             * we have to return when we detect there is an error happened. But at this time, some other AsyncLocalClients
             * are still running. After user's receiving our reported error, it will trigger another query before
             * all our AsyncLocalClients finishing, which results to more errors.
             * As such, we choose not to do fast fail, but to wait for all AsyncLocalClients come back.
             * */
            if (abortErrorDetected.getAndSet(false)) {
                closeUserConn();
            } else {
                send2UserAndWait4NextSql();
            }
        }
    }

    public boolean trySendBack2UserOrCloseUser(Queue<byte[]> packets) {
        boolean status = false;
        if (allResultsReceived()) {
            if (abortErrorDetected.getAndSet(false)) {
                closeUserConn();
                status = true;
            } else {
                status = send2UserAndWait4NextSql(packets);
            }
        }

        return status;
    }

    private boolean allResultsReceived() {
        return this.finishedBatchQueryCount.get() >= this.queryUsedClients.size();
    }

    public static boolean isAbortingErrorCode(long errorCode) {
        for (ErrorCode ec : abortUserConnectionErrorCodes) {
            if (ec.getErrorNo() == errorCode) {
                return true;
            }
        }

        return false;
    }

    private boolean send2UserAndWait4NextSql() {
        if (this.errorPackets.isEmpty()) {
            return false;
        }
        setState(SESSION_STATUS.BATCH_ANALYZE);
        ERR first = this.errorPackets.get(0);
        if (!isAbortingErrorCode(first.errorCode)) {
            Queue<byte[]> packets = new ArrayDeque<>();
            for (ERR err : this.errorPackets) {
                packets.add(err.toPacket());
            }
            this.batchContextManager.sendResult2User(packets);
        }
        return true;
    }

    private boolean send2UserAndWait4NextSql(Queue<byte[]> packets) {
        if (send2UserAndWait4NextSql()) {
            return true;
        }
        setState(SESSION_STATUS.BATCH_ANALYZE);
        boolean status = false;
        if (packets != null && !packets.isEmpty()) {
            this.batchContextManager.sendResult2User(packets);
            status = true;
        }
        return status;
    }

    public void enqueue(SESSION_STATUS status) {
        SchedulerWorker.getInstance().enqueue(new BatchTargetStatusManager(this, status));
    }

    /**
     * reset the state to the default without the influence from the prev sql
     */
    public void reset() {
        this.errorPackets.clear();
        this.queryUsedClients.clear();
        this.usedClientsReturnedMap.clear();
        this.finishedBatchQueryCount.set(0);
    }

    public String etraceCurrentRequestId() {
        return batchContextManager.etraceCurrentRequestId();
    }

    public String etraceNextLocalRpcId() {
        return batchContextManager.etraceNextLocalRpcId();
    }

    /*
     * We try to generate unique id for each local client we use, for identifying whether we have received
     * its result from DB.
     * This is used for the key of `clientsResultReturnedMap`.
     * */
    public String generateLocalClientId() {
        long id = localClientIdGenerator.incrementAndGet();
        return String.valueOf(id);
    }

    public boolean sendBatchQuery(AsyncLocalClient client, BatchQuery batchQuery) {
        client.setBatchQuery(batchQuery);
        client.setBatchTransLogIdStr(batchTransLogIdStr);
        client.setHandler(this.resultState, this.quitState);
        if (!client.doAsyncExecute()) {
            logger.error("{},failed to execute async local client, target query is [{}]",
                client.getAsyncClientTransLogIdStr(), batchQuery.getQuery());
            client.doQuit("failed to execute", false);
            closeUserConn();
            return false;
        }
        queryUsedClients.put(client.getId(), client);
        usedClientsReturnedMap.put(client.getId(), false);
        return true;
    }

    @Override public String toString() {
        return String.format("BatchSessionContext[hashCode:%s, currentState:%s]", hashCode(),
            currentState == null ? "unknown" : currentState.toString());
    }
}
