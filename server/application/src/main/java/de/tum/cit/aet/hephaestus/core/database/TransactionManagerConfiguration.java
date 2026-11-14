package de.tum.cit.aet.hephaestus.core.database;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Replaces Boot's JPA transaction manager with {@link EndedSessionTransactionManager}, customized the same way. */
@Configuration(proxyBeanMethods = false)
class TransactionManagerConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(ObjectProvider<TransactionManagerCustomizers> customizers) {
        var transactionManager = new EndedSessionTransactionManager();
        customizers.ifAvailable(each -> each.customize(transactionManager));
        return transactionManager;
    }
}
