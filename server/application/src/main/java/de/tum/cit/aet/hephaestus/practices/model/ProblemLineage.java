package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.spi.ProblemBaselineState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * How one problem of an item review's result compares with the previous comparable result of the same practice, item
 * and developer. New, Unchanged and Updated name a problem of the newer result. No longer present ({@code ABSENT})
 * names a problem of the previous result that a complete review of a changed version no longer finds, while the text it
 * quoted is gone. A problem with no complete proof gets no row: a missing problem proves nothing.
 */
@Entity
@Immutable
@Table(
        name = "problem_lineage",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_problem_lineage_problem",
                    columnNames = {"result_id", "workspace_id", "problem_id"}),
            @UniqueConstraint(
                    name = "uk_problem_lineage_prior",
                    columnNames = {"result_id", "prior_problem_id"})
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemLineage {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    /** The newer result. */
    @Column(name = "result_id", nullable = false)
    private UUID resultId;

    /** The previous comparable result that the newer one is compared with. */
    @Column(name = "prior_result_id", nullable = false)
    private UUID priorResultId;

    /** The newer result's problem; none when the previous problem is no longer present. */
    @Column(name = "problem_id")
    private @Nullable UUID problemId;

    /** The previous result's problem; none when the problem is new. */
    @Column(name = "prior_problem_id")
    private @Nullable UUID priorProblemId;

    /**
     * The problem's identity in the comparison: its key, or the source and quote of its first citation. A key is not
     * unique across practices or items, so a reader matches it within one practice, item and developer.
     */
    @Column(name = "identity", nullable = false, columnDefinition = "TEXT")
    private String identity;

    /** How the problem compares: New, Unchanged, Updated, or No longer present ({@code ABSENT}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "comparison", nullable = false, length = 16)
    private ProblemBaselineState comparison;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
