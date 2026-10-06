package com.rummy.gameservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Multi-document transactions for the wallet ledger. Requires a replica set (MongoDB Atlas,
 * or a local {@code mongod --replSet}); a standalone mongod rejects transactions.
 */
@Configuration
@ConditionalOnProperty(name = "rummy.mongo.transactions.enabled", havingValue = "true")
public class MongoTransactionConfig {

    @Bean
    public MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }
}
