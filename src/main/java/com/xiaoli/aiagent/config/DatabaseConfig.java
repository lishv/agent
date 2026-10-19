package com.xiaoli.aiagent.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** 业务库是 MySQL 主数据源；向量库使用独立的 PostgreSQL 数据源。 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.xiaoli.aiagent.mapper")
public class DatabaseConfig {
    @Bean @Primary @ConfigurationProperties("spring.datasource")
    public DataSourceProperties businessDataSourceProperties() { return new DataSourceProperties(); }

    @Bean @Primary @ConfigurationProperties("spring.datasource.hikari")
    public DataSource businessDataSource(@Qualifier("businessDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean @Primary
    public JdbcTemplate businessJdbcTemplate(@Qualifier("businessDataSource") DataSource source) { return new JdbcTemplate(source); }

    @Bean @ConfigurationProperties("app.vector-datasource")
    @ConditionalOnProperty(name = "app.vector-store.enabled", havingValue = "true")
    public DataSourceProperties vectorDataSourceProperties() { return new DataSourceProperties(); }

    @Bean
    @ConditionalOnProperty(name = "app.vector-store.enabled", havingValue = "true")
    public DataSource vectorDataSource(@Qualifier("vectorDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.vector-store.enabled", havingValue = "true")
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource source) { return new JdbcTemplate(source); }
}
