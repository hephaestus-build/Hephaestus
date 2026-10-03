package de.tum.cit.aet.hephaestus.core.runtime;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the ShedLock {@link LockProvider} (PostgreSQL-backed via the existing app
 * {@link DataSource}) and activates {@link EnableSchedulerLock @EnableSchedulerLock}.
 *
 * <p>Gated by the same flag as {@link ServerSchedulingConfig} — schedulers only run
 * on the server-role pod, so the lock provider has nothing to provide elsewhere.
 *
 * <p>{@code defaultLockAtMostFor = "PT30M"} is the safety net for any
 * {@code @SchedulerLock} that forgets to set {@code lockAtMostFor} explicitly. It is
 * deliberately generous: a 30-minute upper bound is long enough to outlast any
 * batch-size'd cleanup pass we ship today, but short enough that a crashed pod's
 * stuck lock auto-clears within a single deploy window. Individual jobs should still
 * set their own {@code lockAtMostFor} to match their actual P99 runtime + headroom.
 *
 * <p>The ShedLock schema is created by Liquibase changeset {@code 1779700600000_shedlock.xml}.
 * {@code useDbTime()} pushes lock-expiry comparisons to the DB clock so we don't
 * depend on JVM clock skew between pods.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
