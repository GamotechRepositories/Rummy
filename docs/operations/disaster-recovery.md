# Disaster Recovery & Operations Runbook

## 1. Zero-Downtime Server Draining (Phase 43)

When performing rolling updates or decommissioning a game server node:
1. Trigger Draining via API:
   ```bash
   curl -X POST "http://<game-server-ip>:8081/api/admin/drain?drain=true"
   ```
2. The node rejects new table matchmaking allocations while allowing active in-flight game rounds to conclude naturally.
3. Automated Script:
   ```bash
   ./deployment/scripts/drain-server.sh http://<game-server-ip>:8081 120
   ```

## 2. MongoDB Backup & Restore

### 2.1 Full Backup
```bash
mongodump --uri="mongodb://localhost:27017/rummy_db" --archive="/backups/rummy_db_$(date +%Y%m%d_%H%M%S).gz" --gzip
```

### 2.2 Point-in-Time Restore
```bash
mongorestore --uri="mongodb://localhost:27017/rummy_db" --drop --archive="/backups/rummy_db_backup.gz" --gzip
```

## 3. Redis Failover & Recovery
- Redis AOF (Append Only File) is set to `appendfsync everysec` for maximum data durability.
- Upon node restart, Redis automatically recovers table routing keys and presence states from `/data/appendonly.aof`.

## 4. Health Check Probes
- **Readiness Probe**: `GET http://localhost:8081/actuator/health/readiness`
- **Liveness Probe**: `GET http://localhost:8081/actuator/health/liveness`
- **Prometheus Metrics**: `GET http://localhost:8081/actuator/prometheus`
