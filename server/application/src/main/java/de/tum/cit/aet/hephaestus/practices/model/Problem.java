package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Entity
@Immutable
@Table(
        name = "problem",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_problem_workspace",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_problem_parent",
                    columnNames = {"id", "result_id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_problem_position",
                    columnNames = {"result_id", "position"}),
            @UniqueConstraint(
                    name = "uk_problem_key",
                    columnNames = {"result_id", "key"})
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Problem {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "result_id", nullable = false)
    private UUID resultId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "key", length = 64)
    private @Nullable String key;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity;

    @Column(name = "summary", nullable = false, length = 255)
    private String summary;

    @Column(name = "explanation", nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", nullable = false, columnDefinition = "jsonb")
    private JsonNode citations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "basis", columnDefinition = "jsonb")
    private @Nullable JsonNode basis;

    @Column(name = "next_step", columnDefinition = "TEXT")
    private @Nullable String nextStep;

    /**
     * The problem's identity in a comparison: {@code key:} and its key, or a hash of the source and quote of its
     * witness citation ({@code ProblemComparison}). A key is not unique across practices or items, so a reader matches
     * it within one practice and item. The seal writes it. A problem sealed before the column has none until a lineage
     * comparison names it; a problem without a key and without a witness has none.
     */
    @Column(name = "identity", columnDefinition = "TEXT")
    private @Nullable String identity;
}
