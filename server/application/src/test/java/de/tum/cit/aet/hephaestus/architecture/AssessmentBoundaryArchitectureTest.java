package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static de.tum.cit.aet.hephaestus.architecture.AssessmentQueryArchitectureTest.CONSUMER;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaFieldAccess.AccessType;
import com.tngtech.archunit.core.domain.JavaModifier;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.mentoring.AssessmentReadFixture;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAssignmentQueryService;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import jakarta.persistence.Entity;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.RepositoryDefinition;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import tools.jackson.databind.JsonNode;

class AssessmentBoundaryArchitectureTest extends HephaestusArchitectureTest {
    private static final DescribedPredicate<JavaClass> STORAGE =
            resideInAnyPackage("..practices.result..", "..practices.reviewoutput..");
    private static final DescribedPredicate<JavaClass> MARKED_RAW = DescribedPredicate.describe(
            "marked as raw observation access", type -> type.isAnnotatedWith(RawObservationAccess.class));
    static final DescribedPredicate<JavaClass> DIRECT_RAW_READER = DescribedPredicate.describe(
            "a direct reader of raw observations", AssessmentBoundaryArchitectureTest::readsDirectly);
    private static final DescribedPredicate<JavaClass> RAW_ACCESS = DIRECT_RAW_READER.or(DescribedPredicate.describe(
            "raw observation data or an indirect reader of it",
            type -> carriesRawData(type, new HashSet<>())
                    || (type.getPackageName().startsWith(BASE_PACKAGE + ".")
                            && (returnsRawData(type)
                                    || type.getAllMethods().stream().anyMatch(method -> readsRawQuery(method, type))
                                    || callsRawQuery(type)))));
    private static final ArchRule ASSESSMENT_BOUNDARY = noClasses()
            .that(CONSUMER)
            .should()
            .dependOnClassesThat(RAW_ACCESS)
            .because("the assessment port checks the readers of each observation");
    private static final ArchRule DIRECT_BOUNDARY = noClasses()
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
        ASSESSMENT_BOUNDARY.check(classes);
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
                        DirectJobRepository.class,
                        ResultHistoryQuery.class,
                        AdmittedResultQuery.class,
                        ResultEvidenceQuery.class,
                        ReviewJobRepository.class,
                        ReviewJob.class);
        var failures = DIRECT_BOUNDARY.evaluate(fixture).getFailureReport().getDetails();
        for (var rawType : rawTypes) {
            assertReader(failures, AssessmentReadFixture.class, rawType);
        }
        assertReader(failures, DirectContentSource.class, JdbcReader.class);
        assertReader(failures, DirectContentSource.class, DeclaredReader.class);
        assertReader(failures, DirectContentSource.class, DirectJobRepository.class);
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

    @Test
    void shouldRejectRawPublicHistoryInAConversationReader() {
        var fixture = new ClassFileImporter()
                .importClasses(AssessmentSqlFixture.PublicHistoryReader.class, FeedbackHistoryEntry.class);
        assertThat(RAW_METHODS.evaluate(fixture).getFailureReport().getDetails())
                .singleElement()
                .satisfies(detail -> assertThat(detail).contains("FeedbackHistoryEntry.publicHistory("));
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
                        && (usesRawDomain(type)
                                || AssessmentQueryArchitectureTest.containsObservationSql(type)
                                || type.getAllMethods().stream()
                                        .anyMatch(AssessmentQueryArchitectureTest::readsObservationQuery)));
    }

    @Test
    void shouldRejectRawObservationReadsFromAContentSource() {
        var fixture = new ClassFileImporter()
                .importClasses(
                        LeakySource.class,
                        History.class,
                        EntryText.class,
                        EntryCount.class,
                        ConcreteHistory.class,
                        MetadataReader.class,
                        NativeHistory.class,
                        JdbcHistory.class,
                        JdbcFacade.class,
                        DerivedHistory.class,
                        DerivedOutput.class,
                        JobRows.class,
                        JobCount.class,
                        RawStatus.class,
                        ResultText.class,
                        DigestText.class,
                        Result.class,
                        WrappedHistory.class,
                        HistoryBundle.class,
                        ResultHistoryEntry.class,
                        ReviewJobRepository.class,
                        ReviewAssignmentQuery.class,
                        ReviewJob.class);
        var failures = ASSESSMENT_BOUNDARY.evaluate(fixture).getFailureReport().getDetails();
        for (var reader : List.of(
                History.class,
                EntryText.class,
                EntryCount.class,
                WrappedHistory.class,
                MetadataReader.class,
                JdbcHistory.class,
                JdbcFacade.class,
                DerivedOutput.class,
                DerivedHistory.class,
                JobRows.class,
                JobCount.class,
                RawStatus.class,
                DigestText.class,
                ReviewJob.class)) {
            String target = "<" + reader.getName();
            assertThat(failures)
                    .anySatisfy(failure -> assertThat(failure)
                            .contains(LeakySource.class.getName())
                            .containsAnyOf(target + ">", target + "."));
        }
    }

    @Test
    void shouldKeepIntegrationTypesOutsideThePort() {
        domainNeutral(AssessmentPort.class).check(classes);
    }

    @Test
    void shouldRejectAnIntegrationTypeInAPortRecord() {
        var fixture =
                new ClassFileImporter().importClasses(PortWithProviderType.class, PortWithProviderType.Work.class);
        assertThat(domainNeutral(PortWithProviderType.class)
                        .evaluate(fixture)
                        .getFailureReport()
                        .getDetails())
                .anySatisfy(failure -> assertThat(failure)
                        .contains(PortWithProviderType.Work.class.getName(), ItemRef.class.getName()));
    }

    private static boolean isRaw(JavaClass type) {
        return STORAGE.test(type)
                || type.isAssignableTo(MARKED_RAW)
                || type.getEnclosingClass()
                        .map(AssessmentBoundaryArchitectureTest::isRaw)
                        .orElse(false);
    }

    private static boolean carriesRawData(JavaClass type, Set<JavaClass> visited) {
        if (!visited.add(type)) return false;
        return isRaw(type)
                || (type.isRecord()
                        && type.getFields().stream()
                                .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                                .flatMap(field -> field.getType().getAllInvolvedRawTypes().stream())
                                .anyMatch(component -> carriesRawData(component, visited)));
    }

    private static boolean returnsRawData(JavaClass type) {
        return type.getAllMethods().stream()
                .flatMap(method -> method.getReturnType().getAllInvolvedRawTypes().stream())
                .anyMatch(returnType -> carriesRawData(returnType, new HashSet<>()));
    }

    private static boolean callsRawQuery(JavaClass type) {
        return Stream.concat(type.getMethodCallsFromSelf().stream(), type.getMethodReferencesFromSelf().stream())
                .anyMatch(AssessmentBoundaryArchitectureTest::readsRawCall);
    }

    private static boolean readsRawCall(JavaCodeUnitAccess<?> access) {
        var receiver = access.getTargetOwner();
        var method = access.getTarget().resolveMember();
        // An abstract repository method runs its own query. Other methods' constants do not widen that projection.
        boolean declaredQuery =
                (receiver.isAssignableTo(Repository.class) || receiver.isAnnotatedWith(RepositoryDefinition.class))
                        && method.filter(target -> target.getModifiers().contains(JavaModifier.ABSTRACT)
                                        && hasDeclaredQuery(target))
                                .isPresent();
        return isRaw(receiver)
                || callsRawMethod(access)
                || (!declaredQuery && AssessmentQueryArchitectureTest.containsObservationSql(receiver))
                || method.stream()
                        .anyMatch(target -> readsRawQuery(target, receiver)
                                || copiesObservationFields(target)
                                || target.getReturnType().getAllInvolvedRawTypes().stream()
                                        .anyMatch(returnType -> carriesRawData(returnType, new HashSet<>())));
    }

    private static boolean readsRawQuery(JavaCodeUnit method, JavaClass receiver) {
        if (AssessmentQueryArchitectureTest.readsObservationQuery(method)) return true;
        if (hasDeclaredQuery(method)) return false;
        return receiver.getPackageName().startsWith(BASE_PACKAGE + ".")
                && (usesRawDomain(receiver)
                        || (receiver.isInterface()
                                && !receiver.isAssignableTo(Repository.class)
                                && receiver.getAllSubclasses().stream()
                                        .filter(repository -> inProduction(repository) == inProduction(receiver))
                                        .anyMatch(AssessmentBoundaryArchitectureTest::usesRawDomain)));
    }

    private static boolean hasDeclaredQuery(JavaCodeUnit method) {
        return method.isAnnotatedWith(Query.class) || method.isAnnotatedWith(NativeQuery.class);
    }

    private static boolean copiesObservationFields(JavaCodeUnit method) {
        return Stream.concat(method.getMethodCallsFromSelf().stream(), method.getMethodReferencesFromSelf().stream())
                .flatMap(call -> call.getTarget().resolveMember().stream())
                .flatMap(target -> target.getFieldAccesses().stream())
                .filter(access -> access.getAccessType() == AccessType.GET)
                .flatMap(access -> access.getTarget().resolveMember().stream())
                .anyMatch(field -> field.isAnnotatedWith(RawObservationAccess.class));
    }

    private static boolean usesRawDomain(JavaClass repository) {
        if (repository.isAnnotatedWith(RepositoryDefinition.class)) {
            return repository
                    .getAnnotationOfType(RepositoryDefinition.class)
                    .domainClass()
                    .isAnnotationPresent(RawObservationAccess.class);
        }
        return repository.isAssignableTo(Repository.class)
                && repository.getInterfaces().stream()
                        .flatMap(parent -> parent.getAllInvolvedRawTypes().stream())
                        .anyMatch(type -> type.isAnnotatedWith(Entity.class) && isRaw(type));
    }

    private static ArchRule domainNeutral(Class<?> port) {
        var contract = DescribedPredicate.<JavaClass>describe(
                "part of " + port.getSimpleName(), type -> belongsTo(type, port));
        return noClasses().that(contract).should().dependOnClassesThat().resideInAPackage("..integration..");
    }

    private static boolean belongsTo(JavaClass type, Class<?> contract) {
        return type.isEquivalentTo(contract)
                || type.getEnclosingClass()
                        .map(enclosing -> belongsTo(enclosing, contract))
                        .orElse(false);
    }

    private interface History {
        List<ResultHistoryEntry> latest(long workspaceId, long accountId);
    }

    private record ConcreteHistory(ResultHistoryQuery query) {
        List<ResultHistoryEntry> latest(long workspaceId, long accountId) {
            return query.forDeveloper(workspaceId, accountId, Instant.EPOCH, 1);
        }
    }

    @RequiredArgsConstructor
    private static final class EntryText {
        private final ResultHistoryEntry entry;

        @Nullable
        String summary() {
            return entry.summary();
        }
    }

    @RequiredArgsConstructor
    private static final class EntryCount {
        private final ConcreteHistory history;

        int count(long workspaceId, long accountId) {
            return history.latest(workspaceId, accountId).size();
        }
    }

    private record HistoryBundle(List<ResultHistoryEntry> entries) {}

    private interface WrappedHistory {
        HistoryBundle latest(long workspaceId, long accountId);
    }

    private interface NativeHistory {
        @Query(value = "SELECT j.output FROM review_job j WHERE j.workspace_id = :workspaceId", nativeQuery = true)
        List<String> text(long workspaceId);
    }

    private interface DerivedHistory extends Repository<ReviewJob, UUID>, DerivedOutput {}

    private interface JobRows extends CrudRepository<ReviewJob, UUID> {}

    @RequiredArgsConstructor
    private static final class JobCount {
        private final JobRows rows;

        long count() {
            return rows.count();
        }
    }

    private interface DerivedOutput {
        List<Output> findByWorkspaceId(long workspaceId);

        interface Output {
            JsonNode getOutput();
        }
    }

    private record MetadataReader(NativeHistory history) {
        List<String> text(long workspaceId) {
            return history.text(workspaceId);
        }
    }

    private record JdbcHistory(NamedParameterJdbcOperations jdbc) {
        void text(long workspaceId) {
            jdbc.queryForList(
                    "SELECT output::text FROM review_job WHERE workspace_id = :workspaceId",
                    Map.of("workspaceId", workspaceId),
                    String.class);
        }
    }

    private record JdbcFacade(JdbcHistory history) {
        void text(long workspaceId) {
            history.text(workspaceId);
        }
    }

    @RequiredArgsConstructor
    private static final class ResultText {
        private final List<Result> results;

        List<String> summaries() {
            return results.stream()
                    .map(Result::getSummary)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    @RequiredArgsConstructor
    private static final class DigestText {
        private final ResultText reader;

        List<String> render() {
            return reader.summaries();
        }
    }

    @RequiredArgsConstructor
    private static final class RawStatus {
        private final ReviewAssignmentQuery assignments;

        int count(long workspaceId, long accountId, UUID reviewId) {
            return assignments
                    .recordedForDeveloper(
                            workspaceId, accountId, List.of(new ReviewAssignmentQuery.ReviewedItem(reviewId, 1)))
                    .size();
        }
    }

    private interface PortWithProviderType {
        record Work(ItemRef item) {}
    }

    private record LeakySource(
            History typed,
            EntryText text,
            EntryCount count,
            WrappedHistory wrapped,
            MetadataReader metadata,
            JdbcHistory jdbc,
            JdbcFacade jdbcFacade,
            DerivedOutput derived,
            DerivedHistory repository,
            JobRows jobRows,
            JobCount jobCount,
            DigestText digest,
            RawStatus rawStatus,
            ReviewJobRepository jobs)
            implements ContentSource {
        @Override
        public boolean supports(ConversationContextRequest request) {
            return true;
        }

        @Override
        public void contribute(ConversationContextRequest request, Map<String, byte[]> files) {
            typed.latest(request.workspaceId(), request.developerId());
            text.summary();
            count.count(request.workspaceId(), request.developerId());
            wrapped.latest(request.workspaceId(), request.developerId());
            metadata.text(request.workspaceId());
            jdbc.text(request.workspaceId());
            jdbcFacade.text(request.workspaceId());
            derived.findByWorkspaceId(request.workspaceId())
                    .forEach(attempt -> files.put(
                            "derived.json", attempt.getOutput().toString().getBytes(StandardCharsets.UTF_8)));
            repository.findByWorkspaceId(request.workspaceId());
            jobRows.count();
            jobCount.count();
            digest.render();
            rawStatus.count(request.workspaceId(), request.developerId(), request.threadId());
            jobs.findByIdAndWorkspaceId(request.threadId(), request.workspaceId())
                    .map(ReviewJob::getOutput)
                    .ifPresent(output -> files.put("job.json", output.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private record JdbcReader(JdbcOperations jdbc) {
        void read() {
            jdbc.queryForList("SELECT j.output FROM review_job j", String.class);
        }
    }

    private interface DirectJobRepository extends Repository<ReviewJob, UUID> {}

    private interface DeclaredReader {
        @NativeQuery("SELECT j.output FROM review_job j")
        List<String> read();
    }

    private record DirectContentSource(JdbcReader reader, DeclaredReader declared, DirectJobRepository jobs)
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
