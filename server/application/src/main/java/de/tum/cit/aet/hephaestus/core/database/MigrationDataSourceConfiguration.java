package de.tum.cit.aet.hephaestus.core.database;

import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseConnectionDetails;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liquibase's own connections, in the application and in the migrate run mode ({@link DatabaseMigration}). They come
 * from outside the pool, so a migration waits for a lock as long as it needs, without the pool's lock bound. Like the
 * pool, they leave PostgreSQL's DETAIL line out of exception messages, because it quotes the rejected row values.
 *
 * <p>Not a default candidate: the application's pool stays the only {@code DataSource} that other beans get.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "spring.liquibase.enabled", matchIfMissing = true)
public class MigrationDataSourceConfiguration {

    @Bean(defaultCandidate = false)
    @LiquibaseDataSource
    DataSource liquibaseDataSource(DataSourceProperties pool, LiquibaseConnectionDetails liquibase) {
        var dataSource = new PGSimpleDataSource();
        // Boot's spring.liquibase.url, user and password still apply, each over the pool's own.
        dataSource.setUrl(either(liquibase.getJdbcUrl(), pool.determineUrl()));
        dataSource.setUser(either(liquibase.getUsername(), pool.determineUsername()));
        dataSource.setPassword(either(liquibase.getPassword(), pool.determinePassword()));
        dataSource.setLogServerErrorDetail(false);
        return dataSource;
    }

    private static @Nullable String either(@Nullable String liquibase, @Nullable String pool) {
        return liquibase != null ? liquibase : pool;
    }
}
