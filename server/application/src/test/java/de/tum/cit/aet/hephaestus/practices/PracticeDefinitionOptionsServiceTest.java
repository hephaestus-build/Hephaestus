package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeEvidenceSourceOptionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeWorkTypeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class PracticeDefinitionOptionsServiceTest {

    @Test
    void exposesTheCurrentBaselineAndOnlyProfileCompatibleSources() {
        var catalogs =
                new ClasspathArtifactSourceCatalogRegistry(JsonMapper.builder().build(), Clock.systemUTC());
        var service = new PracticeDefinitionOptionsService(
                catalogs,
                new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()),
                PracticeSignalOptionsFixture.real());

        PracticeDefinitionOptionsDTO result = service.options();

        // Ordered by kind rather than by whichever descriptor bean happened to register first: bean
        // order is not stable across builds, and an authoring picker that reshuffles between deploys is
        // worse than one that is merely alphabetical.
        assertThat(result.workTypes())
                .extracting(PracticeWorkTypeDefinitionOptionsDTO::artifactKind)
                .containsExactly(
                        ArtifactKinds.CONVERSATION_THREAD,
                        ArtifactKind.of("docs.document"),
                        ArtifactKinds.ISSUE,
                        ArtifactKinds.PULL_REQUEST);
        PracticeWorkTypeDefinitionOptionsDTO pullRequests = workType(result, ArtifactKinds.PULL_REQUEST);
        assertThat(pullRequests.signals())
                .filteredOn(option -> option.recommended())
                .extracting(option -> option.signal().value())
                .containsExactly("scm.pull_request.opened", "scm.pull_request.ready", "scm.pull_request.synchronized");
        assertThat(pullRequests.signals())
                .filteredOn(option -> option.signal().equals(ScmSignals.PULL_REQUEST_CLOSED))
                .singleElement()
                .satisfies(option -> {
                    // The label is the domain's own, shown under a "Run mentoring when" legend that already
                    // names the work type — so it says what happened, not what it happened to.
                    assertThat(option.displayName()).isEqualTo("Closed without merging");
                    assertThat(option.recommended()).isFalse();
                });
        assertThat(pullRequests.subjectRoles())
                .containsExactly(ActorRole.AUTHOR, ActorRole.ASSIGNEE, ActorRole.REVIEWER, ActorRole.MERGER);
        assertThat(workType(result, ArtifactKinds.ISSUE).subjectRoles())
                .containsExactly(ActorRole.AUTHOR, ActorRole.ASSIGNEE);
        assertThat(workType(result, ArtifactKinds.CONVERSATION_THREAD).subjectRoles())
                .containsExactly(ActorRole.AUTHOR);
        assertThat(pullRequests.supportedAutomatedReviewModes())
                .containsExactly(PracticeAutomatedReviewMode.LANGUAGE_MODEL);
        assertThat(pullRequests.recommendedNeeds())
                .extracting(need -> need.sourceKind().value())
                .containsExactly("scm.pull-request.core", "scm.pull-request.diff", "scm.pull-request.comments");
        assertThat(pullRequests.allowedSources())
                .extracting(PracticeEvidenceSourceOptionDTO::sourceKind)
                .contains("scm.pull-request.core", "scm.repository.tree")
                .doesNotContain("scm.issue.core", "slack.conversation.thread");
        assertThat(pullRequests.allowedSources())
                .filteredOn(option -> option.sourceKind().equals("scm.pull-request.core"))
                .singleElement()
                .satisfies(option -> assertThat(option.displayName()).isEqualTo("Pull request details"));
    }

    /**
     * The occasion list is the list of things an author may pick. A review somebody asks for by hand is
     * carried beside it instead, so no reader has to re-derive the exception from a signal's spelling.
     */
    @Test
    void offersOnlyChoosableOccasionsAndNamesTheHandRequestSeparately() {
        var catalogs =
                new ClasspathArtifactSourceCatalogRegistry(JsonMapper.builder().build(), Clock.systemUTC());
        var service = new PracticeDefinitionOptionsService(
                catalogs,
                new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()),
                PracticeSignalOptionsFixture.real());

        PracticeDefinitionOptionsDTO result = service.options();

        assertThat(workType(result, ArtifactKinds.PULL_REQUEST)).satisfies(pullRequests -> {
            assertThat(pullRequests.signals())
                    .extracting(option -> option.signal().value())
                    .doesNotContain("scm.pull_request.manual_review");
            assertThat(pullRequests.manualReviewSignal()).isNotNull().satisfies(manual -> {
                assertThat(manual.signal()).isEqualTo(ScmSignals.PULL_REQUEST_MANUAL_REVIEW);
                assertThat(manual.displayName()).isNotBlank();
            });
        });
        // Absent rather than invented: nothing lets a person ask for a review of a conversation thread.
        assertThat(workType(result, ArtifactKinds.CONVERSATION_THREAD).manualReviewSignal())
                .isNull();
    }

    /**
     * The evidence facts below belong to one contract version, and a practice records the version it was
     * written against — without this a reader cannot tell "these options describe this practice" from
     * "these options describe a newer contract".
     */
    @Test
    void saysWhichSourceContractTheOptionsDescribe() {
        var catalogs =
                new ClasspathArtifactSourceCatalogRegistry(JsonMapper.builder().build(), Clock.systemUTC());
        var service = new PracticeDefinitionOptionsService(
                catalogs,
                new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()),
                PracticeSignalOptionsFixture.real());

        assertThat(service.options().sourceContractVersion())
                .isEqualTo(catalogs.current().version());
    }

    @Test
    void saysWhichSourcesCanCarryAClaimAboutWhatIsAbsent() {
        var catalogs =
                new ClasspathArtifactSourceCatalogRegistry(JsonMapper.builder().build(), Clock.systemUTC());
        var service = new PracticeDefinitionOptionsService(
                catalogs,
                new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()),
                PracticeSignalOptionsFixture.real());

        PracticeDefinitionOptionsDTO result = service.options();

        PracticeWorkTypeDefinitionOptionsDTO pullRequests = workType(result, ArtifactKinds.PULL_REQUEST);
        // The two sit at the same required-capture floor, so the flag is the only thing that separates
        // them — an author offered EXHAUSTIVE over linked work items would be sending a request
        // PracticeDefinitionValidator refuses.
        assertThat(pullRequests.allowedSources())
                .filteredOn(option -> option.sourceKind().equals("scm.pull-request.comments"))
                .singleElement()
                .satisfies(option -> {
                    assertThat(option.supportsExhaustiveEvidence()).isTrue();
                    // The bound the claim is made against travels with the flag. Offering EXHAUSTIVE without
                    // saying where the capture stops asks an author to promise something they cannot check.
                    assertThat(option.selectionScope()).contains("500", "PARTIAL");
                });
        assertThat(pullRequests.allowedSources())
                .filteredOn(option -> option.sourceKind().equals("scm.linked-work-items"))
                .singleElement()
                .satisfies(option ->
                        assertThat(option.supportsExhaustiveEvidence()).isFalse());
    }

    private static PracticeWorkTypeDefinitionOptionsDTO workType(
            PracticeDefinitionOptionsDTO options, ArtifactKind kind) {
        return options.workTypes().stream()
                .filter(candidate -> candidate.artifactKind().equals(kind))
                .findFirst()
                .orElseThrow();
    }
}
