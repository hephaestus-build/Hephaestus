package de.tum.cit.aet.hephaestus.testconfig;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Supplies the non-JPA lock table without hiding missing tables in migrated-schema tests. */
@Configuration(proxyBeanMethods = false)
@Profile("test")
@ConditionalOnProperty(name = "spring.liquibase.enabled", havingValue = "false")
public class TestSchedulerSchemaConfiguration {

    @Bean
    @DependsOn("entityManagerFactory")
    DataSourceInitializer schedulerLockSchema(DataSource dataSource) {
        var initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(
                new ResourceDatabasePopulator(new ClassPathResource("db/test-scheduler-schema.sql")));
        return initializer;
    }
}
