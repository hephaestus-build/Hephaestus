package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/** An observation behind an advice revision: one that the model saw, and whether the text cites it. */
@Entity
@Immutable
@RawObservationAccess
@Table(
        name = "practice_advice_source",
        // The database declares it NULLS NOT DISTINCT, so a source without a problem is unique too.
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_practice_advice_source_ref",
                        columnNames = {"revision_id", "result_id", "problem_id"}),
        indexes = {
            @Index(
                    name = "idx_practice_advice_source_result",
                    columnList = "result_id, person_account_id, workspace_id"),
            @Index(
                    name = "idx_practice_advice_source_revision",
                    columnList = "revision_id, person_account_id, workspace_id"),
            @Index(name = "idx_practice_advice_source_problem", columnList = "problem_id, result_id, workspace_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeAdviceSource {

    @Id
    @RawObservationAccess.Metadata
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @RawObservationAccess.Metadata
    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @RawObservationAccess.Metadata
    @Column(name = "person_account_id", nullable = false)
    private long personAccountId;

    @RawObservationAccess.Metadata
    @Column(name = "revision_id", nullable = false, columnDefinition = "UUID")
    private UUID revisionId;

    @RawObservationAccess.Metadata
    @Column(name = "result_id", nullable = false, columnDefinition = "UUID")
    private UUID resultId;

    @RawObservationAccess.Metadata
    @Column(name = "problem_id", columnDefinition = "UUID")
    private @Nullable UUID problemId;

    @RawObservationAccess.Metadata
    @Column(name = "cited", nullable = false)
    private boolean cited;

    @RawObservationAccess.Metadata
    @Column(name = "ordinal", nullable = false)
    private int ordinal;
}
