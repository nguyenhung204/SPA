package iuh.fit.spa.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class MongoConfig {

    private static final String URI =
            "mongodb+srv://nguyenhung2004200_db_user:W87UoUWquGGfbjXv@cluster1.gypblkl.mongodb.net/?appName=Cluster1";
    private static final String DB_NAME = "SPA";

    @Bean
    public MongoClient mongoClient() {
        return MongoClients.create(URI);
    }

    @Bean
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, DB_NAME);
    }

    @Bean
    public MongoTemplate mongoTemplate(MongoDatabaseFactory dbFactory) {
        return new MongoTemplate(dbFactory);
    }

    /**
     * Enables multi-document ACID transactions on MongoDB Atlas (replica set required).
     */
    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }

    @Bean
    public TransactionTemplate transactionTemplate(MongoTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
