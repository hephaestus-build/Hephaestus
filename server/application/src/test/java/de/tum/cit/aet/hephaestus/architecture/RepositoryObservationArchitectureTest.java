package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.framework.ResourceReaders;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RepositoryObservationArchitectureTest extends HephaestusArchitectureTest {
    private static final String QUERIES =
            "de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationQueries";
    private static final Set<String> OLD_WRITES = Set.of(
            "recordRepositoryPrivatized",
            "recordRepositoryMetadata",
            "recordRepositoryUnavailable",
            "confirmAvailability",
            "recordUnavailable",
            "narrowRepositoryMonitors",
            "upsertFromSnapshot",
            "insertFromSnapshot",
            "updateFromSnapshot",
            "setRepositoryVisibilityConfirmedAt",
            "setPublicObservationAt",
            "setProofGenerationAt",
            "setUnavailableSince",
            "setReaders");
    private static final Set<String> MIRROR_MUTATIONS = Set.of("save", "saveAll", "saveAndFlush", "saveAllAndFlush");
    private static final Set<String> PROOF_MUTATIONS =
            Set.of("confirmedPublic", "confirmedNotPublic", "unavailable", "identityChanged");
    private static final String MONITOR_FIELDS =
            "repository_?visibility_?confirmed_?at|public_?observation_?at|unavailable_?since"
                    + "|proof_?generation(?:_?at)?|proof_?state|proof_?reason|visibility_?class|readers_?synced_?at";
    private static final Pattern SQL_WRITE =
            Pattern.compile("(?is)\\b(?:store_repository_observation|narrow_repository_monitors"
                    + "|record_repository_proof_generation_time)\\s*\\("
                    + "|\\binsert\\s+into\\s+(?:public\\.)?repository\\b"
                    + "|\\bupdate\\s+(?:public\\.)?repository\\b[^;]*?\\bset\\b"
                    + "|\\binsert\\s+into\\s+(?:public\\.)?repository_to_monitor\\s*\\([^)]*\\b(?:"
                    + MONITOR_FIELDS + ")\\b"
                    + "|\\bupdate\\s+(?:public\\.)?repository_?to_?monitor\\b[^;]*?\\bset\\b"
                    + "(?:(?!\\bwhere\\b).)*?\\b(?:" + MONITOR_FIELDS + ")\\s*=");

    @Test
    void repositoryObservationsHaveOneWriter() throws IOException {
        var violations = new ArrayList<String>();
        for (var type : classes) {
            if (type.isEquivalentTo(RepositoryObservationStore.class)) continue;
            for (var call : type.getMethodCallsFromSelf()) {
                var target = call.getTargetOwner();
                String method = call.getName();
                if ((target.getName().equals(QUERIES)
                                && (method.equals("applyObservation") || method.equals("restrictPublicAccess")))
                        || OLD_WRITES.contains(method)
                        || (target.isEquivalentTo(ResourceReaders.class) && PROOF_MUTATIONS.contains(method))
                        || (target.isEquivalentTo(Repository.class)
                                && (method.startsWith("set") || method.equals("applyMetadataVisibility")))
                        || (target.isEquivalentTo(RepositoryRepository.class) && MIRROR_MUTATIONS.contains(method))) {
                    violations.add(call.getDescription());
                }
            }
        }
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (var file :
                    paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("RepositoryObservationQueries.java")) continue;
                String source = Files.readString(file)
                        .replace("UPDATE Repository r SET r.lastSyncAt = :lastSyncAt WHERE r.id = :id", "");
                var writer = SQL_WRITE.matcher(source);
                if (writer.find()) violations.add(file + " uses " + writer.group());
            }
        }
        assertThat(violations)
                .as("Route repository observations through RepositoryObservationStore")
                .isEmpty();
    }
}
