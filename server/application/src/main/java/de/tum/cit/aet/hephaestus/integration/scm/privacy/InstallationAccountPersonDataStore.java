package de.tum.cit.aet.hephaestus.integration.scm.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceNamespaceReservation;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The connections of workspaces installed on the person's own provider account. Their login is the namespace of the
 * workspace's repositories, like a repository's {@code owner/name}, so it stays while the workspace exists. Erasure
 * removes it from a purged workspace: its login, slug, display name and connection names give way to
 * {@code erased-<id>}, and {@link WorkspaceNamespaceReservation} tokens keep its slugs reserved.
 */
final class InstallationAccountPersonDataStore extends JdbcPersonDataStore {
    static final String ERASED_DISPLAY_NAME = "Erased workspace";

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkspaceNamespaceReservation reservations;
    private final WorkspaceSlugService slugs;

    InstallationAccountPersonDataStore(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            WorkspaceNamespaceReservation reservations,
            WorkspaceSlugService slugs) {
        super(
                jdbc,
                mapper,
                "installation_account",
                "connection",
                """
                t.kind IN ('GITHUB','GITLAB') AND EXISTS (SELECT 1 FROM workspace w
                    JOIN identity_provider p ON p.type=t.kind AND p.server_url=t.config->>'serverUrl'
                    JOIN "user" u ON u.provider_id=p.id
                    WHERE w.id=t.workspace_id AND w.account_type='USER' AND u.id=ANY(:users)
                      AND lower(u.login)=lower(w.account_login))
                """,
                "id,workspace_id,kind,display_name",
                "id",
                "",
                195);
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.slugs = slugs;
    }

    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        Map<Long, Namespace> purged = new LinkedHashMap<>();
        for (JsonNode row : querySelected(
                "json_build_object('connection',t.id,'workspace',w.id,'login',w.account_login,'slug',w.slug)::text",
                "connection t JOIN workspace w ON w.id=t.workspace_id AND w.status='PURGED'",
                selection)) {
            purged.computeIfAbsent(
                            row.path("workspace").asLong(),
                            id -> new Namespace(
                                    id,
                                    row.path("login").asString(),
                                    row.path("slug").asString()))
                    .connections()
                    .add(row.path("connection").asLong());
        }
        long erased = 0;
        for (Namespace namespace : purged.values()) {
            erased += erase(namespace);
        }
        return erased;
    }

    private long erase(Namespace namespace) {
        String erasedSlug = slugs.allocate("erased-" + namespace.workspace(), String.valueOf(namespace.workspace()));
        var parameters = Map.<String, Object>of(
                "workspace",
                namespace.workspace(),
                "connections",
                new SqlArrayValue("bigint", namespace.connections().toArray()),
                "erasedSlug",
                erasedSlug,
                "displayName",
                ERASED_DISPLAY_NAME);
        // The slug trigger reserves the erased slug. The current slug and the slug that the login names, which
        // an earlier slug may have been, keep their reservations as tokens.
        jdbc.update(
                "UPDATE workspace SET account_login=:erasedSlug, slug=:erasedSlug, display_name=:displayName"
                        + " WHERE id=:workspace AND status='PURGED'",
                parameters);
        for (String reserved : new LinkedHashSet<>(List.of(namespace.slug(), slugs.label(namespace.login())))) {
            var reservation = Map.of("reserved", reserved, "token", required(reservations.slugToken(reserved)));
            if (jdbc.update("DELETE FROM workspace_slug_reservation WHERE slug=:reserved", reservation) > 0) {
                jdbc.update(
                        "INSERT INTO workspace_slug_reservation(slug) VALUES (:token) ON CONFLICT DO NOTHING",
                        reservation);
            }
        }
        return jdbc.update(
                "UPDATE connection SET display_name=:erasedSlug, config=config - 'orgLogin' WHERE id = ANY(:connections)",
                parameters);
    }

    private static String required(Optional<String> token) {
        return token.orElseThrow(() ->
                new IllegalStateException("Erasing a personal workspace namespace needs the system encryption key"));
    }

    private record Namespace(long workspace, String login, String slug, List<Long> connections) {
        Namespace(long workspace, String login, String slug) {
            this(workspace, login, slug, new ArrayList<>());
        }
    }
}
