package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static de.tum.cit.aet.hephaestus.architecture.AssessmentQueryArchitectureTest.CONSUMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.conversation.AssessmentSqlFixture;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAbsorptionContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.mentoring.AssessmentReadFixture;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAssignmentQueryService;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

class AssessmentBoundaryArchitectureTest extends HephaestusArchitectureTest {
    private static final DescribedPredicate<JavaClass> MARKED_RAW = DescribedPredicate.describe(
            "marked as raw observation access", type -> type.isAnnotatedWith(RawObservationAccess.class));
    static final DescribedPredicate<JavaClass> DIRECT_RAW_READER = DescribedPredicate.describe(
            "a direct reader of raw observations", AssessmentBoundaryArchitectureTest::readsDirectly);
    private static final ArchRule BOUNDARY = noClasses()
            .that(CONSUMER)
            .should()
            .dependOnClassesThat(DIRECT_RAW_READER)
            .because("the assessment port checks the readers of each observation");
    private static final DescribedPredicate<JavaClass> PRIVACY_ADAPTER = assignableTo(PersonDataCatalog.class)
            .or(assignableTo(PersonDataContributor.class))
            .or(assignableTo(AccountAbsorptionContributor.class));
    // The ledger subscriber uses a JDBC statement for LISTEN and a ping. It does not fetch rows.
    private static final ArchRule DECLARED_SQL = TableOwnershipArchTest.checkedSqlOnly(
            CONSUMER.and(not(PRIVACY_ADAPTER).or(assignableTo(ContentSource.class))),
            assignableTo(PreparedStatement.class).or(assignableTo(ResultSet.class)));
    private static final ArchRule RAW_METHODS = noClasses()
            .that(CONSUMER)
            .should(new ArchCondition<>("call a raw review read") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (var access : type.getCodeUnitAccessesFromSelf()) {
                        if (callsRawMethod(access)) {
                            events.add(SimpleConditionEvent.satisfied(type, access.getDescription()));
                        }
                    }
                }
            });

    @Test
    void shouldReadObservationsThroughTheAssessmentPort() {
        BOUNDARY.check(classes);
        DECLARED_SQL.check(classes);
        RAW_METHODS.check(classes);
    }

    @Test
    void shouldRejectDirectJavaReadersAndAnExternalSqlReader() {
        var rawTypes = List.of(
                ResultHistoryQuery.class,
                AdmittedResultQuery.class,
                ResultEvidenceQuery.class,
                ReviewJobRepository.class,
                ReviewJob.class);
        var fixture = new ClassFileImporter()
                .importClasses(
                        AssessmentReadFixture.class,
                        DirectContentSource.class,
                        JdbcReader.class,
                        DeclaredReader.class,
                        JobRows.class,
                        ResultHistoryQuery.class,
                        AdmittedResultQuery.class,
                        ResultEvidenceQuery.class,
                        ReviewJobRepository.class,
                        ReviewJob.class);
        var failures = BOUNDARY.evaluate(fixture).getFailureReport().getDetails();
        for (var rawType : rawTypes) {
            assertReader(failures, AssessmentReadFixture.class, rawType);
        }
        assertReader(failures, DirectContentSource.class, JdbcReader.class);
        assertReader(failures, DirectContentSource.class, DeclaredReader.class);
        assertReader(failures, DirectContentSource.class, JobRows.class);
    }

    @Test
    void shouldRejectRuntimeSqlAssemblyInAContentSource() {
        var fixture = new ClassFileImporter().importClasses(FormattedSqlSource.class);
        assertReader(
                DECLARED_SQL.evaluate(fixture).getFailureReport().getDetails(),
                FormattedSqlSource.class,
                JdbcOperations.class);
    }

    @Test
    void shouldRejectRuntimeSqlExecutionInConversation() {
        var fixture = new ClassFileImporter().importClasses(AssessmentSqlFixture.RuntimeQuery.class);
        assertReader(
                DECLARED_SQL.evaluate(fixture).getFailureReport().getDetails(),
                AssessmentSqlFixture.RuntimeQuery.class,
                NamedParameterJdbcOperations.class);
    }

    @Test
    void shouldRejectReadingRawReviewStatusThroughAnAttributionReader() {
        var fixture = new ClassFileImporter()
                .importClasses(
                        AssessmentReadFixture.class, ReviewAssignmentQuery.class, ReviewAssignmentQueryService.class);
        var failures = RAW_METHODS.evaluate(fixture).getFailureReport().getDetails();
        assertThat(failures)
                .hasSize(2)
                .anySatisfy(detail -> assertThat(detail).contains("ReviewAssignmentQuery.recordedForDeveloper("))
                .anySatisfy(
                        detail -> assertThat(detail).contains("ReviewAssignmentQueryService.recordedForDeveloper("));
    }

    static boolean callsRawMethod(JavaCodeUnitAccess<?> access) {
        return access.getTargetOwner().getAllMethods().stream()
                .filter(method -> method.getName().equals(access.getName()))
                .filter(method ->
                        method.getRawParameterTypes().equals(access.getTarget().getRawParameterTypes()))
                .anyMatch(method -> method.isAnnotatedWith(RawObservationAccess.class));
    }

    private static void assertReader(List<String> failures, Class<?> consumer, Class<?> reader) {
        String target = "<" + reader.getName();
        assertThat(failures)
                .anySatisfy(failure ->
                        assertThat(failure).contains(consumer.getName()).containsAnyOf(target + ">", target + "."));
    }

    private static boolean readsDirectly(JavaClass type) {
        return type.isAssignableTo(MARKED_RAW)
                || (type.getPackageName().startsWith(BASE_PACKAGE + ".")
                        && ((type.isAssignableTo(Repository.class)
                                        && type.getInterfaces().stream()
                                                .flatMap(parent -> parent.getAllInvolvedRawTypes().stream())
                                                .anyMatch(MARKED_RAW))
                                || AssessmentQueryArchitectureTest.containsObservationSql(type)
                                || type.getAllMethods().stream()
                                        .anyMatch(AssessmentQueryArchitectureTest::readsObservationQuery)));
    }

    private record JdbcReader(JdbcOperations jdbc) {
        void read() {
            jdbc.queryForList("SELECT j.output FROM review_job j", String.class);
        }
    }

    private interface JobRows extends Repository<ReviewJob, UUID> {}

    private interface DeclaredReader {
        @NativeQuery("SELECT j.output FROM review_job j")
        List<String> read();
    }

    private record DirectContentSource(JdbcReader reader, DeclaredReader declared, JobRows jobs)
            implements ContentSource {
        @Override
        public boolean supports(ConversationContextRequest request) {
            return true;
        }

        @Override
        public void contribute(ConversationContextRequest request, Map<String, byte[]> files) {
            reader.read();
            declared.read();
        }
    }

    private record FormattedSqlSource(JdbcOperations jdbc) implements ContentSource {
        @Override
        public boolean supports(ConversationContextRequest request) {
            return true;
        }

        @Override
        public void contribute(ConversationContextRequest request, Map<String, byte[]> files) {
            jdbc.queryForList("SELECT j.%s FROM %s j".formatted("output", "review_job"), String.class);
        }
    }
}
