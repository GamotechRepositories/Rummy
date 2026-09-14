// ==============================================================================
// MongoDB Production Index Initialization Script
// High Concurrency Tuning for 300k+ CCU Real-Time Rummy Platform
// ==============================================================================

db = db.getSiblingDB('rummy_db');

print('[INIT] Creating production indexes for rummy_db collections...');

// 1. Wallet Accounts Collection
db.createCollection('wallet_accounts');
db.wallet_accounts.createIndex({ playerId: 1 }, { unique: true, name: 'idx_wallet_player_unique' });
db.wallet_accounts.createIndex({ updatedAt: -1 }, { name: 'idx_wallet_updated_at' });

// 2. Immutable Wallet Transactions (Ledger) Collection
db.createCollection('wallet_transactions');
db.wallet_transactions.createIndex({ idempotencyKey: 1 }, { unique: true, name: 'idx_tx_idempotency_unique' });
db.wallet_transactions.createIndex({ playerId: 1, createdAt: -1 }, { name: 'idx_tx_player_history' });
db.wallet_transactions.createIndex({ gameId: 1 }, { sparse: true, name: 'idx_tx_game_id' });
db.wallet_transactions.createIndex({ transactionType: 1, createdAt: -1 }, { name: 'idx_tx_type_created' });

// 3. Persistent Game Records & History Collection
db.createCollection('game_records');
db.game_records.createIndex({ gameId: 1 }, { unique: true, name: 'idx_game_id_unique' });
db.game_records.createIndex({ tableId: 1, createdAt: -1 }, { name: 'idx_game_table_created' });
db.game_records.createIndex({ 'players.playerId': 1, createdAt: -1 }, { name: 'idx_game_player_history' });

// 4. Responsible Gaming Profiles Collection
db.createCollection('responsible_gaming_profiles');
db.responsible_gaming_profiles.createIndex({ playerId: 1 }, { unique: true, name: 'idx_rg_player_unique' });
db.responsible_gaming_profiles.createIndex({ isSelfExcluded: 1 }, { name: 'idx_rg_self_excluded' });

// 5. Anti-Fraud & Collusion Alert Logs
db.createCollection('fraud_alerts');
db.fraud_alerts.createIndex({ alertId: 1 }, { unique: true, name: 'idx_fraud_alert_id' });
db.fraud_alerts.createIndex({ detectedAt: -1 }, { name: 'idx_fraud_detected_at' });
db.fraud_alerts.createIndex({ severity: 1 }, { name: 'idx_fraud_severity' });
db.fraud_alerts.createIndex({ tableId: 1 }, { name: 'idx_fraud_table' });

print('[INIT SUCCESS] All database indexes created successfully.');
