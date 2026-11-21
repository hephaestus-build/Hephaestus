package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeVersionServiceTest extends BaseUnitTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final PracticeVersionService service = new PracticeVersionService(
            mock(PracticeRepository.class),
            mock(PracticeVersionRepository.class),
            MAPPER,
            mock(PracticeDefinitionValidator.class),
            mock(PracticeDrafts.class));

    @ParameterizedTest
    @MethodSource("incompleteDefinitions")
    void shouldOmitAnIncompleteHistoricalDefinitionWithoutChangingRecordedFacts(ObjectNode recorded) {
        String original = recorded.toString();
        var version = TestEntities.practiceVersion(9_007_199_254_740_993L, 3, recorded, "v6:" + "a".repeat(64));
        var view = service.view(version, null, null);
        assertThat(view.id()).isEqualTo("9007199254740993");
        assertThat(view.definition()).isNull();
        assertThat(version.getDefinition().toString()).isEqualTo(original);
        assertThat(recorded.toString()).isEqualTo(original);
    }

    @Test
    void shouldExposeACompleteDefinitionWithAnExplicitNullAbout() {
        var recorded = definition();
        recorded.putNull("about");
        var version = TestEntities.practiceVersion(1L, 1, recorded, "v6:" + "b".repeat(64));
        assertThat(service.view(version, null, null).definition()).isEqualTo(service.definitionOf(version));
        assertThat(service.definitionOf(version).about()).isNull();
    }

    private static Stream<ObjectNode> incompleteDefinitions() {
        var missingAbout = definition();
        missingAbout.remove("about");
        var missingSourceContract = definition();
        ((ObjectNode) missingSourceContract.path("reads")).putNull("contract");
        var missingScanReads = definition();
        var scan = missingScanReads.putObject("scan");
        scan.put("itemKind", "scm.file");
        scan.putArray("include").add("**/*.ts");
        scan.putArray("exclude");
        scan.putArray("context").add("**/*");
        var missingLimitCode = definition();
        ((ObjectNode) missingLimitCode.path("reviewedBy"))
                .putArray("limits")
                .addObject()
                .put("description", "A previously recorded limitation.");
        return Stream.of(missingAbout, missingSourceContract, missingScanReads, missingLimitCode);
    }

    private static ObjectNode definition() {
        return (ObjectNode) MAPPER.valueToTree(DefinitionTestFixtures.definition());
    }
}
