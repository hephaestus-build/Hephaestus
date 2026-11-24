package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.curated.PracticeIdentities;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An edit that changes the judgement gives the new version a new comparability class under the practice's key. */
class PracticeVersionIdentityTest extends BaseUnitTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String KEY = "software-engineering/scope-one-reviewable-change";
    private static final String OLD = "v6:" + "a".repeat(64);
    private static final String EDITED = "v6:" + "b".repeat(64);

    private final PracticeRepository practices = mock(PracticeRepository.class);
    private final PracticeVersionRepository versions = mock(PracticeVersionRepository.class);
    private final PracticeReviewDigest digests = mock(PracticeReviewDigest.class);
    private final PracticeVersionService service = new PracticeVersionService(
            practices,
            versions,
            MAPPER,
            mock(PracticeDefinitionValidator.class),
            mock(PracticeDrafts.class),
            new PracticeIdentities(),
            digests);

    @Test
    void shouldGiveAnEditThatChangesTheJudgementANewClassUnderTheSameKey() {
        var original = DefinitionTestFixtures.definition();
        var edited = DefinitionTestFixtures.withCriteria(original, "Each change names the problem it solves.");
        JsonNode originalTree = MAPPER.valueToTree(original);
        JsonNode editedTree = MAPPER.valueToTree(edited);
        when(digests.of(originalTree)).thenReturn(OLD);
        when(digests.of(editedTree)).thenReturn(EDITED);
        var practice = practice();
        var current = new PracticeVersion(practice, 1, originalTree, null, false, null, KEY, OLD);
        practice.setCurrentVersion(current);
        when(practices.findByIdForUpdate(practice.getId())).thenReturn(Optional.of(practice));
        when(versions.findFirstByPracticeIdOrderByNumberDesc(practice.getId())).thenReturn(Optional.of(current));
        when(versions.saveAndFlush(any(PracticeVersion.class))).thenAnswer(call -> call.getArgument(0));

        var appended = service.append(practice, edited, null);
        var tried = service.tryOut(practice, original, null);

        assertThat(appended.getIdentityKey()).isEqualTo(KEY);
        assertThat(appended.getComparabilityClass()).isEqualTo(EDITED).isNotEqualTo(OLD);
        // A trial of the earlier judgement beside the edited version has the earlier class.
        assertThat(tried.getIdentityKey()).isEqualTo(KEY);
        assertThat(tried.getComparabilityClass()).isEqualTo(OLD);
    }

    private static Practice practice() {
        var workspace = new Workspace();
        workspace.setId(41L);
        var practice = new Practice();
        practice.setId(7L);
        practice.setWorkspace(workspace);
        practice.setKind(new ItemKind("scm.pull_request"));
        practice.setSlug("scope-one-reviewable-change");
        return practice;
    }
}
