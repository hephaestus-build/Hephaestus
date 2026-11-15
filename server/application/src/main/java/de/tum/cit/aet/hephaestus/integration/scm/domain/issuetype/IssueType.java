package de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype;

import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An organization's issue type (GitHub) or work item type (GitLab), such as Bug or Task.
 * <p>
 * The provider's id is unique only within one organization: GitLab's system-defined types have the same global id on
 * every instance. So the row has its own id, and {@code (organization_id, native_id)} identifies the provider's type.
 */
@Entity
@Table(
        name = "issue_type",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_issue_type_organization_native_id",
                        columnNames = {"organization_id", "native_id"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
public class IssueType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The provider's id: GitHub's GraphQL node id, or GitLab's work item type global id. */
    @Column(name = "native_id", nullable = false, length = 128)
    private String nativeId;

    @NonNull
    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private @Nullable String description;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Color color;

    @Column(nullable = false)
    private boolean isEnabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @ToString.Exclude
    private Organization organization;

    /**
     * Timestamp of the last successful sync for this issue type from the Git provider.
     * <p>
     * This is ETL infrastructure used by the sync engine to track when this issue type
     * was last synchronized via GraphQL. Used to implement sync cooldown logic
     * and detect stale data.
     */
    private Instant lastSyncAt;

    /**
     * Issue type colors as defined in GitHub's IssueTypeColor enum.
     */
    public enum Color {
        BLUE,
        GRAY,
        GREEN,
        ORANGE,
        PINK,
        PURPLE,
        RED,
        YELLOW,
    }
}
