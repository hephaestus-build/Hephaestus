package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.curated.PracticeIdentities;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeVersionDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class PracticeVersionService {
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final JsonMapper mapper;
    private final PracticeDefinitionValidator validator;
    private final PracticeDrafts drafts;
    private final PracticeIdentities identities;
    private final PracticeReviewDigest digests;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public PracticeDefinition definitionOf(PracticeVersion version) {
        return mapper.treeToValue(version.getDefinition(), PracticeDefinition.class);
    }

    @Transactional
    public PracticeVersion append(Practice practice, PracticeDefinition definition, @Nullable Long createdByAccountId) {
        return appendVersion(practice, definition, createdByAccountId, null);
    }

    /** As {@link #append(Practice, PracticeDefinition, Long)}, for a definition read from the practice repository. */
    @Transactional
    public PracticeVersion append(
            Practice practice,
            PracticeDefinition definition,
            @Nullable Long createdByAccountId,
            @Nullable String sourceCommit) {
        return appendVersion(practice, definition, createdByAccountId, sourceCommit);
    }

    private PracticeVersion appendVersion(
            Practice practice,
            PracticeDefinition definition,
            @Nullable Long createdByAccountId,
            @Nullable String sourceCommit) {
        Practice locked = practices
                .findByIdForUpdate(practice.getId())
                .orElseThrow(() -> new EntityNotFoundException("Practice", practice.getSlug()));
        validator.validate(locked.getKind(), definition);
        int number = versions.findFirstByPracticeIdOrderByNumberDesc(locked.getId())
                .map(version -> version.getNumber() + 1)
                .orElse(1);
        JsonNode tree = mapper.valueToTree(definition);
        String key = identityKeyOf(locked);
        PracticeVersion version = versions.saveAndFlush(new PracticeVersion(
                locked,
                number,
                tree,
                createdByAccountId,
                false,
                sourceCommit,
                key,
                identities.comparabilityClass(key, digests.of(tree))));
        locked.setCurrentVersion(version);
        practice.setCurrentVersion(version);
        practices.save(locked);
        return version;
    }

    /**
     * A version of the practice with this definition to try, beside its current one (ADR 0118 decision 7): the current
     * version or a tried one when either has the same definition, otherwise a new tried version that never becomes
     * current.
     */
    @Transactional
    public PracticeVersion tryOut(Practice practice, PracticeDefinition definition, @Nullable Long createdByAccountId) {
        Practice locked = practices
                .findByIdForUpdate(practice.getId())
                .orElseThrow(() -> new EntityNotFoundException("Practice", practice.getSlug()));
        validator.validate(locked.getKind(), definition);
        JsonNode tree = mapper.valueToTree(definition);
        if (locked.getCurrentVersion().getDefinition().equals(tree)) return locked.getCurrentVersion();
        var known = versions
                .findByWorkspaceIdAndPracticeIdAndTriedTrueOrderByNumberAsc(
                        locked.getWorkspace().getId(), locked.getId())
                .stream()
                .filter(version -> version.getDefinition().equals(tree))
                .findFirst();
        if (known.isPresent()) return known.orElseThrow();
        int number = versions.findFirstByPracticeIdOrderByNumberDesc(locked.getId())
                .map(version -> version.getNumber() + 1)
                .orElse(1);
        String key = identityKeyOf(locked);
        return versions.saveAndFlush(new PracticeVersion(
                locked,
                number,
                tree,
                createdByAccountId,
                true,
                null,
                key,
                identities.comparabilityClass(key, digests.of(tree))));
    }

    /**
     * A practice keeps the key of its current version, so a later catalog edit cannot move it to another identity. The
     * upgrade gave every stored version a key, with every role stopped, and every write path sets one.
     */
    private static String identityKeyOf(Practice practice) {
        return Objects.requireNonNull(
                practice.getCurrentVersion().getIdentityKey(),
                "A practice version has its identity from the upgrade on.");
    }

    @Transactional
    public Practice create(NewPractice request) {
        var workspace = request.workspaceId();
        var slug = request.slug();
        var kind = request.kind();
        var definition = request.definition();
        var groupId = request.groupId();
        var position = request.position();
        var createdByAccountId = request.createdByAccountId();
        validator.validate(kind, definition);
        // The deferred current-version link permits reserving both identities before inserting
        // the practice and its first version.
        Long practiceId = (Long) Objects.requireNonNull(entityManager)
                .createNativeQuery("SELECT nextval(pg_get_serial_sequence('practice', 'id'))", Long.class)
                .getSingleResult();
        Long versionId = (Long) Objects.requireNonNull(entityManager)
                .createNativeQuery("SELECT nextval(pg_get_serial_sequence('practice_version'," + " 'id'))", Long.class)
                .getSingleResult();
        String key = identities.keyOf(request.catalogSlug(), kind.value(), workspace, practiceId);
        Objects.requireNonNull(entityManager)
                .createNativeQuery("""
                    INSERT INTO practice(id, workspace_id, slug, kind, group_id, position, current_version_id, state)
                    VALUES (:id, :workspace, :slug, :kind, :group, :position, :version, :state)
                    """)
                .setParameter("id", practiceId)
                .setParameter("workspace", workspace)
                .setParameter("slug", slug)
                .setParameter("kind", kind.value())
                .setParameter("group", groupId)
                .setParameter("position", position)
                .setParameter("version", versionId)
                .setParameter("state", request.state().name())
                .executeUpdate();
        Objects.requireNonNull(entityManager)
                .createNativeQuery("""
                    INSERT INTO practice_version(
                        id, workspace_id, practice_id, number, definition, created_by_account_id, source_commit,
                        identity_key, comparability_class)
                    VALUES (:id, :workspace, :practice, 1, CAST(:definition AS jsonb), :creator, :commit,
                        :identity, :class)
                    """)
                .setParameter("id", versionId)
                .setParameter("workspace", workspace)
                .setParameter("practice", practiceId)
                .setParameter("definition", mapper.writeValueAsString(definition))
                .setParameter("creator", createdByAccountId)
                .setParameter("commit", request.sourceCommit())
                .setParameter("identity", key)
                .setParameter("class", identities.comparabilityClass(key, digests.of(definition)))
                .executeUpdate();
        // Read with drafts visible, so a new draft is found too, with its version and group loaded.
        return drafts.withDrafts(() -> practices.findByWorkspaceIdAndSlug(workspace, slug))
                .orElseThrow();
    }

    /**
     * A practice to create with its first version. {@code sourceCommit} is the commit of the practice repository that
     * the definition was read at, or null for a practice made in Hephaestus. {@code catalogSlug} names the catalog
     * practice it was adopted from, or is null for a practice of the workspace.
     */
    public record NewPractice(
            long workspaceId,
            String slug,
            ItemKind kind,
            PracticeDefinition definition,
            @Nullable Long groupId,
            int position,
            @Nullable Long createdByAccountId,
            PracticeState state,
            @Nullable String sourceCommit,
            @Nullable String catalogSlug) {
        public NewPractice(
                long workspaceId,
                String slug,
                ItemKind kind,
                PracticeDefinition definition,
                @Nullable Long groupId,
                int position,
                @Nullable Long createdByAccountId) {
            this(
                    workspaceId,
                    slug,
                    kind,
                    definition,
                    groupId,
                    position,
                    createdByAccountId,
                    PracticeState.ACTIVE,
                    null,
                    null);
        }
    }

    public PracticeVersionDTO view(
            PracticeVersion version,
            PracticeVersionRepository.@Nullable PreviousDefinition previous,
            @Nullable AccountRefDTO createdBy) {
        PracticeDefinition definition;
        try {
            definition = definitionOf(version);
        } catch (JacksonException | IllegalArgumentException exception) {
            definition = null;
        }
        List<PracticeDefinitionField> changed = previous == null
                ? List.of()
                : Arrays.stream(PracticeDefinitionField.values())
                        .filter(field -> !Objects.equals(
                                previous.definition().get(key(field)),
                                version.getDefinition().get(key(field))))
                        .toList();
        return new PracticeVersionDTO(
                Objects.requireNonNull(version.getId()).toString(),
                version.getNumber(),
                version.getName(),
                definition,
                version.getReviewDigest(),
                version.getCreatedAt(),
                createdBy,
                changed,
                previous != null && !previous.reviewDigest().equals(version.getReviewDigest()),
                version.getSourceCommit());
    }

    public static String key(PracticeDefinitionField field) {
        return switch (field) {
            case NAME -> "name";
            case REVIEW_ON -> "reviewOn";
            case APPLIES_WHEN -> "appliesWhen";
            case ABOUT -> "about";
            case READS -> "reads";
            case REVIEWED_BY -> "reviewedBy";
            case CRITERIA -> "criteria";
            case SCRIPT -> "script";
            case SCAN -> "scan";
            case LINE_NOTES -> "lineNotes";
            case GUIDANCE -> "guidance";
        };
    }
}
