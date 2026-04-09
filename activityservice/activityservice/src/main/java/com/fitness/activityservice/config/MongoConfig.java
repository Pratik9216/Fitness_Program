package com.fitness.activityservice.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Bean
    public MongoClientSettings mongoClientSettings() {
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://localhost:27017/aiactivityfitness"))
                .applyToSocketSettings(builder ->
                        builder.readTimeout(0, TimeUnit.MILLISECONDS)
                                .connectTimeout(10000, TimeUnit.MILLISECONDS)
                )
                .applyToServerSettings(builder ->
                        builder.heartbeatFrequency(60000, TimeUnit.MILLISECONDS)
                                .minHeartbeatFrequency(500, TimeUnit.MILLISECONDS)
                )
                .build();
    }
}
