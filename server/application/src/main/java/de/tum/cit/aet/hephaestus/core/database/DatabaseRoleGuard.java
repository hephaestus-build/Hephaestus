package de.tum.cit.aet.hephaestus.core.database;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
@Profile("!specs & !cds-training")
@RequiredArgsConstructor
class DatabaseRoleGuard {
    private final JdbcTemplate jdbc;

    @PostConstruct
    void requireApplicationRole() {
        Boolean restricted = jdbc.queryForObject("""
            SELECT current_user = 'hephaestus_app'
                AND session_user = current_user
                AND NOT EXISTS (
                    SELECT FROM pg_roles elevated
                    WHERE (elevated.rolsuper OR elevated.rolbypassrls OR elevated.rolcreaterole
                        OR elevated.rolreplication OR elevated.rolname = 'hephaestus_owner')
                        AND pg_has_role(elevated.oid, 'MEMBER')
                )
                AND NOT pg_has_role('pg_database_owner', 'USAGE')
                AND NOT EXISTS (
                    SELECT FROM pg_class
                    WHERE relrowsecurity AND pg_has_role(relowner, 'MEMBER')
                )
            FROM pg_roles WHERE rolname = current_user
            """, Boolean.class);
        if (!Boolean.TRUE.equals(restricted)) {
            throw new IllegalStateException(
                    "The server database connection must use the restricted hephaestus_app role. "
                            + "Check the database login and grants, then apply the database role setup.");
        }
    }
}
