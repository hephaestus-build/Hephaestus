package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Entity
@Table(
        name = "result",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_result_workspace",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_result_assignment",
                    columnNames = {"id", "practice_id", "item_id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_result_developer",
                    columnNames = {"id", "person_account_id", "workspace_id"})
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "practice_id", nullable = false)
    private long practiceId;

    @Column(name = "practice_version_id")
    private @Nullable Long practiceVersionId;

    @Column(name = "item_id", nullable = false)
    private long itemId;

    @Column(name = "item_version", columnDefinition = "TEXT")
    private @Nullable String itemVersion;

    @Column(name = "person_account_id")
    private @Nullable Long developerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24)
    private ResultOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private @Nullable Severity severity;

    @Column(name = "summary", length = 255)
    private @Nullable String summary;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private @Nullable String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private @Nullable JsonNode evidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "example", columnDefinition = "jsonb")
    private @Nullable JsonNode example;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coverage", columnDefinition = "jsonb")
    private @Nullable JsonNode coverage;

    /**
     * The {@linkplain #sourceKinds(JsonNode, JsonNode) source kinds that the result rests on}, recorded when the result
     * is written, so a read that authorizes many results does not load their evidence. Null when one of them is not
     * named, and on a row that an earlier release wrote: such a result is authorized from its full record.
     */
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_kinds", columnDefinition = "text[]")
    private String @Nullable [] recordedSourceKinds;

    /** The citations of this result's evidence, its open questions and its example. Authorization reads their sources. */
    public List<JsonNode> citations() {
        return citations(evidence, example);
    }

    public static List<JsonNode> citations(@Nullable JsonNode evidence, @Nullable JsonNode example) {
        List<JsonNode> all = new ArrayList<>();
        if (evidence != null) {
            evidence.path("citations").forEach(all::add);
            for (JsonNode question : evidence.path("openQuestions")) {
                if (question.path("citation").isObject()) all.add(question.path("citation"));
            }
        }
        if (example != null && example.path("citation").isObject()) all.add(example.path("citation"));
        return List.copyOf(all);
    }

    /**
     * The distinct source kinds that a result rests on: those its citations name, and those its search or
     * inapplicability warrant consulted. Admission holds a result to the same sources. A Does not apply that a
     * practice's path check proved cites nothing but rests on the source that the check read. Null when a citation or
     * a warrant names a source that is not a string, which withholds the result.
     */
    public static @Nullable List<String> sourceKinds(@Nullable JsonNode evidence, @Nullable JsonNode example) {
        Set<String> kinds = new TreeSet<>();
        for (JsonNode citation : citations(evidence, example)) {
            if (!citation.path("sourceKind").isString()) return null;
            kinds.add(citation.path("sourceKind").asString());
        }
        if (evidence != null) {
            for (String warrant : List.of("search", "inapplicability")) {
                for (JsonNode consulted : evidence.path(warrant).path("consulted")) {
                    if (!consulted.isString()) return null;
                    kinds.add(consulted.asString());
                }
            }
        }
        return List.copyOf(kinds);
    }

    /** The recorded kinds, or the kinds of this full record when an earlier release wrote the row without them. */
    public @Nullable List<String> sourceKinds() {
        return recordedSourceKinds != null ? List.of(recordedSourceKinds) : sourceKinds(evidence, example);
    }

    /** The facts that a read of results needs, from this full record. */
    public ResultFacts facts() {
        return new ResultFacts(
                id,
                workspaceId,
                reviewId,
                practiceId,
                practiceVersionId,
                itemId,
                itemVersion,
                developerId,
                outcome,
                severity,
                summary,
                isMigratedHistory(),
                recordedAt,
                endedAt,
                endReason,
                explanation != null,
                evidence != null,
                coverage != null,
                sourceKinds());
    }

    @PrePersist
    void recordSourceKinds() {
        var kinds = sourceKinds(evidence, example);
        recordedSourceKinds = kinds == null ? null : kinds.toArray(String[]::new);
    }

    /**
     * Migrated history (ADR 0070 F-1): a read-only earlier review. It is never current and never newly delivered. No current
     * version describes these results, so they keep this one: raising it would claim a capture contract that they never
     * had. The {@code ck_result_evidence_version} constraint allows only this value and
     * {@link #CAPTURED_EVIDENCE_VERSION}, and a trigger refuses it on a new result. SQL that filters
     * {@code evidence_version} names these constants.
     */
    public static final short MIGRATED_EVIDENCE_VERSION = 0;

    /** Evidence captured under the established capture contract. */
    public static final short CAPTURED_EVIDENCE_VERSION = 1;

    @Default
    @ColumnDefault("1")
    @Column(name = "evidence_version", nullable = false)
    private short evidenceVersion = CAPTURED_EVIDENCE_VERSION;

    /** {@link ResultFacts#carriesCapturedProof()} in SQL, for a query that names the result table {@code r}. */
    public static final String CAPTURED_PROOF_SQL = "(r.evidence_version=" + CAPTURED_EVIDENCE_VERSION
            + " AND r.summary IS NOT NULL AND r.explanation IS NOT NULL AND r.evidence IS NOT NULL"
            + " AND r.coverage IS NOT NULL)";

    /** True for migrated history, which stays read-only. */
    public boolean isMigratedHistory() {
        return evidenceVersion == MIGRATED_EVIDENCE_VERSION;
    }

    /** {@link ResultFacts#isAdmissibleProof()} of this record, without reading its source kinds. */
    public boolean isAdmissibleProof() {
        boolean captured = ResultFacts.capturedProof(
                isMigratedHistory(), summary, explanation != null, evidence != null, coverage != null);
        return ResultFacts.admissibleProof(captured, practiceVersionId, itemVersion);
    }

    /** Whether feedback that rests on {@code basis} carries the earlier-reviews label: some of it is migrated history. */
    public static boolean includesEarlierReviews(Collection<Result> basis) {
        return basis.stream().anyMatch(Result::isMigratedHistory);
    }

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 24)
    private @Nullable ResultEndReason endReason;

    public void end(ResultEndReason reason, Instant at) {
        if (endedAt != null) {
            throw new IllegalStateException("Result already ended");
        }
        if (at.isBefore(recordedAt)) {
            throw new IllegalArgumentException("End time precedes result");
        }
        endedAt = at;
        endReason = reason;
    }
}
