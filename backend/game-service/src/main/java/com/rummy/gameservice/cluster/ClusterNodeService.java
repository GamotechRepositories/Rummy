package com.rummy.gameservice.cluster;

import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tracks which game-service processes are alive, so work owned by a crashed node (tables, escrowed
 * stakes) can be detected and cleaned up by the survivors.
 *
 * <p>Each process upserts a heartbeat into MongoDB; a node is dead once its heartbeat is older than
 * {@code rummy.cluster.dead-after-seconds}. Timestamps use the database clock ({@code $currentDate},
 * {@code $$NOW}), so clock skew between pods cannot make a healthy node look dead.
 *
 * <p>Without MongoDB (unit tests) every node is reported alive and nothing is ever reclaimed.
 */
@Service
public class ClusterNodeService {

    private static final Logger log = LoggerFactory.getLogger(ClusterNodeService.class);
    static final String COLLECTION = "cluster_nodes";

    private final String nodeId;
    private final TableRoutingRegistry routingRegistry;
    private final MongoTemplate mongo;
    private final long deadAfterMs;
    /** host:port other nodes use to reach this one (table relay); null when unknown. */
    private final String address;
    private volatile Set<String> aliveCache = Set.of();
    private volatile Map<String, String> addressCache = Map.of();
    private volatile boolean left;

    @Autowired
    public ClusterNodeService(TableRoutingRegistry routingRegistry,
                              @Autowired(required = false) MongoTemplate mongo,
                              @Value("${rummy.cluster.dead-after-seconds:45}") long deadAfterSeconds,
                              @Value("${rummy.cluster.advertise-address:}") String advertiseAddress,
                              @Value("${server.port:8080}") int serverPort) {
        this.routingRegistry = routingRegistry;
        this.nodeId = routingRegistry.getServerInstanceId();
        this.mongo = mongo;
        this.deadAfterMs = deadAfterSeconds * 1000;
        this.address = resolveAddress(advertiseAddress, serverPort);
    }

    public ClusterNodeService(TableRoutingRegistry routingRegistry, MongoTemplate mongo, long deadAfterSeconds) {
        this(routingRegistry, mongo, deadAfterSeconds, "", 0);
    }

    public String nodeId() {
        return nodeId;
    }

    public String address() {
        return address;
    }

    /** host:port at which a live node accepts relayed player connections, if it published one. */
    public Optional<String> addressOf(String otherNodeId) {
        String cached = addressCache.get(otherNodeId);
        if (cached != null || mongo == null) {
            return Optional.ofNullable(cached);
        }
        try {
            Document d = mongo.findOne(new BasicQuery(aliveFilter().append("_id", otherNodeId)), Document.class, COLLECTION);
            return Optional.ofNullable(d != null ? d.getString("address") : null);
        } catch (Exception e) {
            log.warn("[Cluster] Address lookup failed for {}: {}", otherNodeId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String resolveAddress(String advertiseAddress, int serverPort) {
        if (advertiseAddress != null && !advertiseAddress.isBlank()) {
            String a = advertiseAddress.trim();
            return a.contains(":") || serverPort <= 0 ? a : a + ":" + serverPort;
        }
        if (serverPort <= 0) {
            return null;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress() + ":" + serverPort;
        } catch (Exception e) {
            log.warn("[Cluster] Could not determine this node's address; set RUMMY_ADVERTISE_ADDRESS: {}", e.getMessage());
            return null;
        }
    }

    @Scheduled(fixedDelayString = "${rummy.cluster.heartbeat-interval-ms:10000}")
    public synchronized void heartbeat() {
        if (mongo == null || left) {
            return;
        }
        try {
            Update update = new Update().currentDate("heartbeatAt").setOnInsert("startedAt", Instant.now());
            if (address != null) {
                update.set("address", address);
            }
            mongo.upsert(Query.query(Criteria.where("_id").is(nodeId)), update, COLLECTION);
            Map<String, String> addresses = new HashMap<>();
            Set<String> alive = new HashSet<>();
            for (Document d : mongo.find(new BasicQuery(aliveFilter()), Document.class, COLLECTION)) {
                alive.add(d.getString("_id"));
                if (d.getString("address") != null) {
                    addresses.put(d.getString("_id"), d.getString("address"));
                }
            }
            aliveCache = Set.copyOf(alive);
            addressCache = Map.copyOf(addresses);
        } catch (Exception e) {
            log.warn("[Cluster] Heartbeat failed for {}: {}", nodeId, e.getMessage());
        }
    }

    /** True unless the node's heartbeat has expired. Unknown ids are re-checked against the database. */
    public boolean isAlive(String otherNodeId) {
        if (mongo == null || nodeId.equals(otherNodeId) || aliveCache.contains(otherNodeId)) {
            return true;
        }
        try {
            return mongo.exists(new BasicQuery(aliveFilter().append("_id", otherNodeId)), COLLECTION);
        } catch (Exception e) {
            // Never declare a node dead because the database is unreachable.
            log.warn("[Cluster] Liveness check failed for {}: {}", otherNodeId, e.getMessage());
            return true;
        }
    }

    /** Fresh read of all live node ids (used by janitors before reclaiming anything). */
    public Set<String> aliveNodes() {
        if (mongo == null) {
            return Set.of(nodeId);
        }
        Set<String> alive = new HashSet<>(queryAliveNodes());
        alive.add(nodeId);
        return alive;
    }

    public boolean isPersistent() {
        return mongo != null;
    }

    /**
     * The node that hosts {@code tableId}, if it is another live node. A routing entry left behind by
     * a dead node is removed, so callers fall through to the "table no longer exists" path.
     */
    public Optional<String> liveRemoteOwner(String tableId) {
        Optional<String> owner = routingRegistry.getServerForTable(tableId);
        if (owner.isEmpty() || owner.get().equals(nodeId)) {
            return Optional.empty();
        }
        if (isAlive(owner.get())) {
            return owner;
        }
        log.warn("[Cluster] Table {} belonged to dead node {}; dropping stale routing", tableId, owner.get());
        routingRegistry.unregisterTableIfOwnedBy(tableId, owner.get());
        return Optional.empty();
    }

    /** Stop heartbeating and deregister, so peers reclaim anything left behind right away. */
    public synchronized void leave() {
        left = true;
        if (mongo == null) {
            return;
        }
        try {
            mongo.remove(Query.query(Criteria.where("_id").is(nodeId)), COLLECTION);
            log.info("[Cluster] Node {} left the cluster", nodeId);
        } catch (Exception e) {
            log.warn("[Cluster] Failed to deregister {}: {}", nodeId, e.getMessage());
        }
    }

    private Set<String> queryAliveNodes() {
        Set<String> ids = new HashSet<>();
        for (Document d : mongo.find(new BasicQuery(aliveFilter()), Document.class, COLLECTION)) {
            ids.add(d.getString("_id"));
        }
        return ids;
    }

    private Document aliveFilter() {
        return new Document("$expr", new Document("$gt", List.of(
                "$heartbeatAt", new Document("$subtract", List.of("$$NOW", deadAfterMs)))));
    }
}
