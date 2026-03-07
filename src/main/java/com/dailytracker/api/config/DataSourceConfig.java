package com.dailytracker.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${DATABASE_URL}") String url) {
        // If already JDBC format, use as-is
        if (url.startsWith("jdbc:")) {
            return DataSourceBuilder.create().url(url).build();
        }

        // Parse Prisma format: postgresql://user:pass@host:port/db?params
        URI uri = URI.create(url);

        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : 5432;
        String database = uri.getPath().substring(1); // remove leading /
        String query = uri.getQuery();

        // Build JDBC URL without credentials
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        if (query != null && !query.isEmpty()) {
            // Remove Prisma-specific params like "schema=public"
            String filteredQuery = java.util.Arrays.stream(query.split("&"))
                    .filter(p -> !p.startsWith("schema="))
                    .collect(java.util.stream.Collectors.joining("&"));
            if (!filteredQuery.isEmpty()) {
                jdbcUrl += "?" + filteredQuery;
            }
        }

        // Extract user:password from userInfo
        String userInfo = uri.getUserInfo();
        String username = null;
        String password = null;
        if (userInfo != null && userInfo.contains(":")) {
            String[] parts = userInfo.split(":", 2);
            username = parts[0];
            password = parts[1];
        }

        DataSourceBuilder<?> builder = DataSourceBuilder.create().url(jdbcUrl);
        if (username != null) {
            builder.username(username).password(password);
        }
        return builder.build();
    }
}
