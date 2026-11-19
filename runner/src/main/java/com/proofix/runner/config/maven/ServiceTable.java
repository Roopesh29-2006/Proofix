package com.proofix.runner.config.maven;

import java.util.Map;

/** DATA: dependency groupId:artifactId -> backing service the app needs at runtime. */
public final class ServiceTable {
    private ServiceTable() {}

    public static final Map<String, String> SERVICES = Map.ofEntries(
            Map.entry("org.postgresql:postgresql", "POSTGRESQL"),
            Map.entry("com.mysql:mysql-connector-j", "MYSQL"),
            Map.entry("mysql:mysql-connector-java", "MYSQL"),
            Map.entry("org.mariadb.jdbc:mariadb-java-client", "MARIADB"),
            Map.entry("org.springframework.boot:spring-boot-starter-data-mongodb", "MONGODB"),
            Map.entry("org.springframework.boot:spring-boot-starter-data-mongodb-reactive", "MONGODB"),
            Map.entry("org.mongodb:mongodb-driver-sync", "MONGODB"),
            Map.entry("org.springframework.boot:spring-boot-starter-data-redis", "REDIS"),
            Map.entry("org.springframework.boot:spring-boot-starter-data-redis-reactive", "REDIS"),
            Map.entry("redis.clients:jedis", "REDIS"),
            Map.entry("io.lettuce:lettuce-core", "REDIS"),
            Map.entry("org.springframework.kafka:spring-kafka", "KAFKA"),
            Map.entry("org.apache.kafka:kafka-clients", "KAFKA"),
            Map.entry("org.springframework.boot:spring-boot-starter-amqp", "RABBITMQ"),
            Map.entry("com.rabbitmq:amqp-client", "RABBITMQ"));

    public static String service(String ga) {
        return SERVICES.get(ga);
    }
}
