package de.tum.cit.aet.hephaestus.testconfig;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.test.util.AopTestUtils;

/**
 * Releases what keeps a closed test context reachable, so that the context cache's limit also limits the heap.
 *
 * <p>The cache evicts a context by closing it. Three things then still hold the context:
 *
 * <ul>
 *   <li>Its management child. Spring Test pauses an idle context, and Spring Boot closes the child only from a stop that
 *       the pause already spent (spring-projects/spring-boot#52544).
 *   <li>Mockito's inline mock map. It holds each spy's real instance strongly under a weak key, and that instance leads
 *       back to the context that holds the key.
 *   <li>Liquibase's history service map, a process-wide singleton keyed by a database that holds a pooled connection.
 * </ul>
 *
 * <p>A running application holds one context for its whole life and never pauses it, so this class stays in the test
 * tree.
 */
public class ClosedTestContextReleaser implements ApplicationListener<ApplicationContextEvent> {

    private final List<ConfigurableApplicationContext> children = new CopyOnWriteArrayList<>();

    @Override
    public void onApplicationEvent(ApplicationContextEvent event) {
        ApplicationContext context = event.getApplicationContext();
        // A child publishes its events to its parent too, so this listener on the parent also sees the child's refresh.
        if (event instanceof ContextRefreshedEvent
                && context.getParent() != null
                && context instanceof ConfigurableApplicationContext child) {
            children.add(child);
        } else if (event instanceof ContextClosedEvent
                && context.getParent() == null
                && context instanceof ConfigurableApplicationContext closing) {
            children.forEach(ConfigurableApplicationContext::close);
            clearInlineMocks(closing.getBeanFactory());
            // Test contexts close between tests on the one test thread, so no Liquibase run is using this map.
            Scope.getCurrentScope()
                    .getSingleton(ChangeLogHistoryServiceFactory.class)
                    .resetAll();
        }
    }

    private static void clearInlineMocks(ConfigurableListableBeanFactory beans) {
        for (String name : beans.getSingletonNames()) {
            Object singleton = beans.getSingleton(name);
            if (singleton != null) {
                Object target = AopTestUtils.getUltimateTargetObject(singleton);
                if (Mockito.mockingDetails(target).isMock()) {
                    Mockito.framework().clearInlineMock(target);
                }
            }
        }
    }
}
