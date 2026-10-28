package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.dto.AcceptPracticeReleaseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.SetFeedbackModeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeDefinitionTest extends BaseUnitTest {
    @Test
    void shouldRoundTripExactlyTheAuthoredContractWhenSerialized() {
        var mapper = JsonMapper.builder().build();
        var definition = DefinitionTestFixtures.definition();
        var json = mapper.valueToTree(definition);
        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder(
                        "name",
                        "reviewOn",
                        "appliesWhen",
                        "about",
                        "reads",
                        "reviewedBy",
                        "criteria",
                        "script",
                        "scan",
                        "lineNotes",
                        "guidance");
        assertThat(mapper.treeToValue(json, PracticeDefinition.class)).isEqualTo(definition);
        assertThat(json.has("about")).isTrue();
        assertThat(json.path("script").isNull()).isTrue();
    }

    @Test
    void shouldAcceptEventFreeGuidanceAndExplicitNobodyWhenAuthored() {
        var definition = new PracticeDefinition(
                "Team guidance",
                new ReviewOn(List.of(), Map.of()),
                null,
                null,
                new PracticeReads("3.0.0", List.of()),
                new ReviewedBy(ReviewedByKind.NOBODY, null, List.of()),
                "Follow the team guidance.",
                null,
                null,
                false,
                new PracticeGuidance(null, null, null, null, null));
        assertThat(definition.about()).isNull();
        assertThat(definition.reviewOn().events()).isEmpty();
    }

    @Test
    void shouldRejectRemovedDefinitionFieldsWhenReadFromJson() {
        var mapper = JsonMapper.builder().build();
        var json = mapper.valueToTree(DefinitionTestFixtures.definition());
        if (!(json instanceof ObjectNode object)) throw new IllegalStateException();
        for (String field : List.of("signals", "subject", "practiceScript", "groupSlug", "automatedReviewPolicy")) {
            var invalid = object.deepCopy();
            invalid.put(field, "removed");
            assertThatThrownBy(() -> mapper.treeToValue(invalid, PracticeDefinition.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void shouldRejectDuplicateReadSourcesWhenAuthored() {
        var required = new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.REQUIRED);
        assertThatThrownBy(() -> new PracticeReads("3.0.0", List.of(required, required)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepLiteralCriteriaAndScriptBytesWhenAuthored() {
        var original = DefinitionTestFixtures.definition();
        String criteria = " # Heading\n\n- Detail\n";
        String script = "export default { run() {} };\n";
        var definition = new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                null,
                original.about(),
                original.reads(),
                original.reviewedBy(),
                criteria,
                script,
                null,
                true,
                original.guidance());
        assertThat(definition.criteria()).isEqualTo(criteria);
        assertThat(definition.script()).isEqualTo(script);
    }

    @Test
    void shouldRejectMissingRequiredNullableKeysAndDuplicatedStateValues() {
        var mapper = JsonMapper.builder().build();
        var json = (ObjectNode) mapper.valueToTree(DefinitionTestFixtures.definition());
        for (String field : List.of("about", "script", "scan", "appliesWhen")) {
            var invalid = json.deepCopy();
            invalid.remove(field);
            assertThatThrownBy(() -> mapper.treeToValue(invalid, PracticeDefinition.class))
                    .isInstanceOf(RuntimeException.class);
        }
        var invalidBoolean = json.deepCopy();
        invalidBoolean.putNull("lineNotes");
        assertThatThrownBy(() -> mapper.treeToValue(invalidBoolean, PracticeDefinition.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() ->
                        mapper.readValue("{\"events\":[],\"state\":{\"status\":[\"open\",\"open\"]}}", ReviewOn.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldRequireExplicitFeedbackModeAndKnownReleaseFields() {
        var mapper = JsonMapper.builder().build();
        assertThatThrownBy(() -> mapper.readValue("{}", SetFeedbackModeRequestDTO.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> mapper.readValue(
                        "{\"choices\":{\"UNKNOWN\":\"OFFERED\"}}", AcceptPracticeReleaseRequestDTO.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () -> mapper.readValue("{\"choices\":{\"NAME\":null}}", AcceptPracticeReleaseRequestDTO.class))
                .isInstanceOf(RuntimeException.class);
    }
}
