package de.tum.cit.aet.hephaestus.integration.core.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One budget for one JVM: every path that holds a delivery in the heap shares it. */
@Configuration(proxyBeanMethods = false)
class WebhookInFlightBudgetConfiguration {

    @Bean
    WebhookInFlightBudget webhookInFlightBudget() {
        return WebhookInFlightBudget.forHeap(Runtime.getRuntime().maxMemory());
    }
}
