package de.tum.cit.aet.hephaestus.agent.handler.candidate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.StrictJson;
import de.tum.cit.aet.hephaestus.practices.spi.CitationProof;
import de.tum.cit.aet.hephaestus.practices.spi.CoverageOmission;
import de.tum.cit.aet.hephaestus.practices.spi.DutyBasis;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.IntroducedBasis;
import de.tum.cit.aet.hephaestus.practices.spi.OmissionReason;
import de.tum.cit.aet.hephaestus.practices.spi.OpenQuestion;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.WorsenedBasis;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/** Wire-only creator requirements; stored proof fields are not accepted from an agent. */
final class CandidateWireMapper {
    private CandidateWireMapper() {}

    static JsonMapper create() {
        var integers = new SimpleModule("candidate-integers");
        integers.addDeserializer(Integer.class, new ContractInteger());
        integers.addDeserializer(int.class, new ContractInteger());
        return StrictJson.configure(JsonMapper.builder())
                .addModule(integers)
                .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                .addMixIn(EvidenceCitation.class, CitationFields.class)
                .addMixIn(ReadReceipt.class, ReceiptFields.class)
                .addMixIn(OpenQuestion.class, QuestionFields.class)
                .addMixIn(IntroducedBasis.class, IntroducedFields.class)
                .addMixIn(WorsenedBasis.class, WorsenedFields.class)
                .addMixIn(DutyBasis.class, DutyFields.class)
                .addMixIn(HostCoverage.class, HostFields.class)
                .addMixIn(CoverageOmission.class, OmissionFields.class)
                .build();
    }

    // Accept JavaScript integer values such as 3.0, while rejecting fractions and numeric strings.
    private static final class ContractInteger extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.currentToken().isNumeric())
                return context.reportInputMismatch(Integer.class, "Expected an integer");
            double number = parser.getDoubleValue();
            if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
                return context.reportInputMismatch(Integer.class, "Integer is outside its contract range");
            return (int) number;
        }
    }

    private record CitationFields(
            @JsonProperty(required = true) SourceKind sourceKind,
            @JsonProperty(required = true) String sourcePath,
            @JsonProperty(required = true) String path,
            @Nullable CitationSide side,
            @Nullable String commit,
            @JsonProperty(required = true) int startLine,
            @JsonProperty(required = true) int endLine,
            @JsonProperty(required = true) String quote,
            @JsonIgnore @Nullable CitationProof verification) {}

    private record ReceiptFields(
            @JsonProperty(required = true) SourceKind sourceKind,
            @JsonProperty(required = true) String sourcePath,
            @JsonProperty(required = true) String path,
            @JsonProperty(required = true) String version,
            @JsonProperty(required = true) ReadReceiptStatus status,
            @Nullable String contentDigest,
            @Nullable Integer startLine,
            @Nullable Integer endLine) {}

    private record QuestionFields(
            @JsonProperty(required = true) String openQuestion,
            @JsonProperty(required = true) String wouldSettleIt,
            @Nullable EvidenceCitation citation) {}

    private record IntroducedFields(
            @JsonProperty(required = true) int head,
            @JsonProperty(required = true) String explanation) {}

    private record WorsenedFields(
            @JsonProperty(required = true) int base,
            @JsonProperty(required = true) int head,
            @JsonProperty(required = true) String explanation) {}

    private record DutyFields(
            @JsonProperty(required = true) String criterionQuote,
            @JsonProperty(required = true) int event,
            @JsonProperty(required = true) String explanation) {}

    private record HostFields(
            @JsonProperty(required = true) boolean complete,
            @Nullable List<CoverageOmission> omitted,
            @JsonProperty(required = true) int reads,
            @JsonProperty(required = true) int truncatedReads,
            @JsonProperty(required = true) int failedReads,
            @JsonProperty(required = true) List<ReadReceipt> receipts) {}

    private record OmissionFields(
            @JsonProperty(required = true) String what,
            @JsonProperty(required = true) int count,
            @JsonProperty(required = true) OmissionReason reason,
            @Nullable List<String> items) {}
}
