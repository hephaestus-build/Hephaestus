package de.tum.cit.aet.hephaestus.workspace;

import java.security.SecureRandom;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** A personal space as the database holds it, for tests: the workspace and its owner's one membership. */
final class PersonalSpaceFixtures {

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private PersonalSpaceFixtures() {}

    /** Inserts the owner's personal space and returns its id. */
    static long insert(JdbcTemplate jdbc, long ownerId) {
        String slug = slug();
        Long id = jdbc.queryForObject(
                "INSERT INTO workspace (slug, display_name, account_login, account_type, status, kind,"
                        + " personal_account_id) VALUES (?, 'Personal space', ?, 'USER', 'ACTIVE', 'PERSONAL', ?)"
                        + " RETURNING id",
                Long.class,
                slug,
                slug,
                ownerId);
        long spaceId = Objects.requireNonNull(id);
        jdbc.update(
                "INSERT INTO membership (workspace_id, account_id, role, role_source) VALUES (?, ?, 'MEMBER', 'PERSON')",
                spaceId,
                ownerId);
        return spaceId;
    }

    /** A slug that only a personal space can have. */
    static String slug() {
        var slug = new StringBuilder("me--");
        for (int i = 0; i < 16; i++) {
            slug.append(BASE32[RANDOM.nextInt(BASE32.length)]);
        }
        return slug.toString();
    }
}
