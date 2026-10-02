package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Transactional
class PracticeRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeGroupRepository practiceGroupRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("practice-test"));
    }

    private Practice createPractice(String slug, String name) {
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName(name);
        practice.setCriteria("Default criteria for " + slug);
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        return practice;
    }

    private PracticeGroup persistGroup(String slug, @Nullable PracticeAutonomy autonomy, int displayOrder) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug(slug);
        group.setName("Group " + slug);
        group.setAutonomy(autonomy);
        group.setDisplayOrder(displayOrder);
        return practiceGroupRepository.save(group);
    }

    @Nested
    class CrudTests {

        @Test
        void savesAndRetrieves() {
            Practice practice = createPractice("test-slug", "Test Practice");
            practice.setCriteria("Check for quality");
            practice.setAutonomy(PracticeAutonomy.OFF);

            Practice saved = practiceRepository.save(practice);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();

            Practice found = practiceRepository.findById(saved.getId()).orElseThrow();
            assertThat(found.getSlug()).isEqualTo("test-slug");
            assertThat(found.getName()).isEqualTo("Test Practice");
            assertThat(found.getSignals()).isEqualTo(saved.getSignals());
            assertThat(found.getCriteria()).isEqualTo("Check for quality");
            assertThat(found.getAutonomy()).isEqualTo(PracticeAutonomy.OFF);
        }
    }

    @Nested
    class UniqueConstraintTests {

        @Test
        void rejectsDuplicateSlugInWorkspace() {
            practiceRepository.save(createPractice("unique-slug", "First"));

            Practice duplicate = createPractice("unique-slug", "Second");
            assertThatThrownBy(() -> practiceRepository.saveAndFlush(duplicate))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void allowsSameSlugInDifferentWorkspaces() {
            practiceRepository.save(createPractice("shared-slug", "First"));

            Workspace otherWorkspace =
                    workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("other-workspace"));
            Practice otherPractice = createPractice("shared-slug", "Second");
            otherPractice.setWorkspace(otherWorkspace);

            Practice saved = practiceRepository.saveAndFlush(otherPractice);
            assertThat(saved.getId()).isNotNull();
        }
    }

    @Nested
    class QueryTests {

        /**
         * The gate reads the whole catalogue, at every autonomy, on purpose: it has to tell "nothing is bound
         * to this signal" apart from "something is bound and the workspace turned it off", which are
         * different answers to "why did nothing happen" and get different recorded reasons.
         */
        @Test
        void findsEveryPracticeOfTheWorkspaceWhateverItsTier() {
            Practice loud = createPractice("loud", "Loud");
            Practice silent = createPractice("silent", "Silent");
            silent.setAutonomy(PracticeAutonomy.OFF);
            practiceRepository.save(loud);
            practiceRepository.save(silent);

            List<Practice> result = practiceRepository.findByWorkspaceId(workspace.getId());

            assertThat(result).extracting(Practice::getSlug).containsExactlyInAnyOrder("loud", "silent");
        }

        /**
         * The work-type finder narrows by kind of work and by nothing else — autonomy resolution happens in
         * the JVM, not SQL. A {@code autonomy <> 'OFF'} predicate here would silently drop every
         * inheriting (NULL) practice, since {@code NULL <> 'OFF'} is UNKNOWN in SQL.
         */
        @Test
        @DisplayName("findByWorkspaceIdAndArtifactKind returns every autonomy, including the inheriting NULL")
        void workTypeQueryReturnsEveryTierIncludingTheInheritingOnes() {
            Practice loud = createPractice("loud", "Loud");
            loud.setAutonomy(PracticeAutonomy.AUTOMATIC);
            Practice observed = createPractice("observed", "Observed");
            observed.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
            Practice silent = createPractice("silent", "Silent");
            silent.setAutonomy(PracticeAutonomy.OFF);
            // Holds no autonomy of its own — the inheriting case a `<> 'OFF'` predicate would silently drop.
            Practice inheriting = createPractice("inheriting", "Inheriting");
            practiceRepository.saveAll(List.of(loud, observed, silent, inheriting));

            List<Practice> result =
                    practiceRepository.findByWorkspaceIdAndArtifactKind(workspace.getId(), loud.getArtifactKind());

            assertThat(result)
                    .extracting(Practice::getSlug)
                    .containsExactlyInAnyOrder("loud", "observed", "silent", "inheriting");
        }

        @Test
        @DisplayName("findByWorkspaceIdAndArtifactKind keeps out the other kinds of work")
        void workTypeQueryNarrowsToTheRequestedKind() {
            Practice pullRequest = createPractice("on-pull-requests", "On pull requests");
            Practice issue = createPractice("on-issues", "On issues");
            PracticeTestEvidence.configure(issue, ScmSignals.ISSUE_OPENED);
            issue.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.ISSUE));
            practiceRepository.saveAll(List.of(pullRequest, issue));

            List<Practice> result =
                    practiceRepository.findByWorkspaceIdAndArtifactKind(workspace.getId(), ArtifactKinds.PULL_REQUEST);

            assertThat(result).extracting(Practice::getSlug).containsExactly("on-pull-requests");
        }

        /**
         * The autonomy rows are what the rollup and the "is anything switched on here" check read instead of
         * hydrating the catalogue. They carry both levels of the chain raw — a null means "this level
         * decided nothing", which the resolver needs in order to fall through to the next one, so a
         * projection that turned it into a value would resolve the chain wrongly and silently.
         */
        @Test
        @DisplayName("findAutonomyRows carries the practice's autonomy and its group's, nulls kept as nulls")
        void autonomyRowsCarryBothLevelsAndKeepTheirNulls() {
            PracticeGroup silencedGroup = persistGroup("silenced-group", PracticeAutonomy.OFF, 0);
            PracticeGroup undecidedGroup = persistGroup("undecided-group", null, 1);
            Practice ownTier = createPractice("own-autonomy", "Own autonomy");
            ownTier.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
            ownTier.setGroup(silencedGroup);
            Practice fromGroup = createPractice("from-group", "From group");
            fromGroup.setGroup(silencedGroup);
            Practice fromWorkspace = createPractice("from-workspace", "From workspace");
            fromWorkspace.setGroup(undecidedGroup);
            Practice unfiled = createPractice("unfiled", "Unfiled");
            practiceRepository.saveAll(List.of(ownTier, fromGroup, fromWorkspace, unfiled));

            List<PracticeRepository.PracticeAutonomyRow> rows = practiceRepository.findAutonomyRows(workspace.getId());

            assertThat(rows)
                    .extracting(
                            PracticeRepository.PracticeAutonomyRow::getPracticeAutonomy,
                            PracticeRepository.PracticeAutonomyRow::getGroupAutonomy,
                            PracticeRepository.PracticeAutonomyRow::getGroupId,
                            PracticeRepository.PracticeAutonomyRow::getArtifactKind)
                    .containsExactlyInAnyOrder(
                            tuple(
                                    PracticeAutonomy.HUMAN_APPROVAL,
                                    PracticeAutonomy.OFF,
                                    silencedGroup.getId(),
                                    ArtifactKinds.PULL_REQUEST),
                            tuple(null, PracticeAutonomy.OFF, silencedGroup.getId(), ArtifactKinds.PULL_REQUEST),
                            tuple(null, null, undecidedGroup.getId(), ArtifactKinds.PULL_REQUEST),
                            // No group at all: the chain falls straight through to the workspace.
                            tuple(null, null, null, ArtifactKinds.PULL_REQUEST));
        }

        @Test
        @DisplayName("findAllForCatalog returns the whole workspace catalogue, groups first")
        void catalogQueryReturnsEveryPracticeOrderedByGroup() {
            PracticeGroup first = persistGroup("first-group", null, 0);
            PracticeGroup second = persistGroup("second-group", null, 1);
            Practice inSecond = createPractice("in-second", "In second");
            inSecond.setGroup(second);
            Practice inFirst = createPractice("in-first", "In first");
            inFirst.setGroup(first);
            // Holds no autonomy: findByFilters' `p.autonomy = :autonomy` could never have matched it.
            Practice unfiled = createPractice("unfiled", "Unfiled");
            practiceRepository.saveAll(List.of(inSecond, inFirst, unfiled));

            List<Practice> result = practiceRepository.findAllForCatalog(workspace.getId());

            assertThat(result).extracting(Practice::getSlug).containsExactly("in-first", "in-second", "unfiled");
        }

        @Test
        @DisplayName("findByWorkspaceIdAndSlug returns matching practice")
        void findsBySlug() {
            practiceRepository.save(createPractice("target", "Target"));
            practiceRepository.save(createPractice("other", "Other"));

            var found = practiceRepository.findByWorkspaceIdAndSlug(workspace.getId(), "target");

            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("Target");
        }

        @Test
        void existsByWorkspace() {
            assertThat(practiceRepository.existsByWorkspaceId(workspace.getId()))
                    .isFalse();

            practiceRepository.save(createPractice("test", "Test"));

            assertThat(practiceRepository.existsByWorkspaceId(workspace.getId()))
                    .isTrue();
        }

        @Test
        void deletesAllByWorkspace() {
            practiceRepository.save(createPractice("one", "One"));
            practiceRepository.save(createPractice("two", "Two"));

            Workspace otherWorkspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("other-ws"));
            Practice otherPractice = createPractice("other", "Other");
            otherPractice.setWorkspace(otherWorkspace);
            practiceRepository.save(otherPractice);

            practiceRepository.deleteAllByWorkspaceId(workspace.getId());

            assertThat(practiceRepository.existsByWorkspaceId(workspace.getId()))
                    .isFalse();
            assertThat(practiceRepository.existsByWorkspaceId(otherWorkspace.getId()))
                    .isTrue();
        }
    }
}
