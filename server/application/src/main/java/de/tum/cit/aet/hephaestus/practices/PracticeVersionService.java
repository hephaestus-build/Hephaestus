package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeVersionDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
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
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class PracticeVersionService {
    private final PracticeRepository practices;
    private final PracticeVersionRepository versions;
    private final JsonMapper mapper;
    private final PracticeDefinitionValidator validator;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public PracticeDefinition definitionOf(PracticeVersion version) {
        return mapper.treeToValue(version.getDefinition(), PracticeDefinition.class);
    }

    @Transactional
    public PracticeVersion append(Practice practice, PracticeDefinition definition, @Nullable Long createdByAccountId) {
        Practice locked = practices
                .findByIdForUpdate(practice.getId())
                .orElseThrow(() -> new EntityNotFoundException("Practice", practice.getSlug()));
        validator.validate(locked.getKind(), definition);
        int number = versions.findFirstByPracticeIdOrderByNumberDesc(locked.getId())
                .map(version -> version.getNumber() + 1)
                .orElse(1);
        PracticeVersion version = versions.saveAndFlush(
                new PracticeVersion(locked, number, mapper.valueToTree(definition), createdByAccountId));
        locked.setCurrentVersion(version);
        practice.setCurrentVersion(version);
        practices.save(locked);
        return version;
    }

    @Transactional
    public Practice create(NewPractice request) {
        var workspace = request.workspace();
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
        Objects.requireNonNull(entityManager)
                .createNativeQuery("""
                    INSERT INTO practice(id, workspace_id, slug, kind, group_id, position, current_version_id)
                    VALUES (:id, :workspace, :slug, :kind, :group, :position, :version)
                    """)
                .setParameter("id", practiceId)
                .setParameter("workspace", workspace.getId())
                .setParameter("slug", slug)
                .setParameter("kind", kind.value())
                .setParameter("group", groupId)
                .setParameter("position", position)
                .setParameter("version", versionId)
                .executeUpdate();
        Objects.requireNonNull(entityManager)
                .createNativeQuery("""
                    INSERT INTO practice_version(id, workspace_id, practice_id, number, definition, created_by_account_id)
                    VALUES (:id, :workspace, :practice, 1, CAST(:definition AS jsonb), :creator)
                    """)
                .setParameter("id", versionId)
                .setParameter("workspace", workspace.getId())
                .setParameter("practice", practiceId)
                .setParameter("definition", mapper.writeValueAsString(definition))
                .setParameter("creator", createdByAccountId)
                .executeUpdate();
        return practices.findByWorkspaceIdAndSlug(workspace.getId(), slug).orElseThrow();
    }

    public record NewPractice(
            Workspace workspace,
            String slug,
            ItemKind kind,
            PracticeDefinition definition,
            @Nullable Long groupId,
            int position,
            @Nullable Long createdByAccountId) {}

    public PracticeVersionDTO view(
            PracticeVersion version, @Nullable PracticeVersion previous, @Nullable AccountRefDTO createdBy) {
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
                                previous.getDefinition().get(key(field)),
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
                previous != null && !previous.getReviewDigest().equals(version.getReviewDigest()));
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
