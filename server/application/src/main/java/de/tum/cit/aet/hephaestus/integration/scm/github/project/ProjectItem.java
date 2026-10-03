package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.BaseGitServiceEntity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Represents an item in a GitHub Projects V2 project.
 * <p>
 * Items can be:
 * - ISSUE: Links to an existing Issue (or PullRequest via Issue inheritance)
 * - DRAFT_ISSUE: A draft that hasn't been converted to a real issue yet
 * - PULL_REQUEST: Links to a PullRequest (mapped through issue_id since PRs extend Issue)
 */
@Entity
@Table(
        name = "project_item",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_project_item_project_nodeid",
                    columnNames = {"project_id", "node_id"}),
            @UniqueConstraint(
                    name = "uq_project_item_provider_native_id",
                    columnNames = {"provider_id", "native_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class ProjectItem extends BaseGitServiceEntity {

    /**
     * The type of content this item represents.
     */
    public enum ContentType {
        DRAFT_ISSUE,
        ISSUE,
        PULL_REQUEST,
    }

    /**
     * GitHub GraphQL node ID for this item.
     */
    @Column(name = "node_id", length = 64)
    private String nodeId;

    /**
     * The project this item belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @ToString.Exclude
    private @Nullable Project project;

    /**
     * The type of content this item represents.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", length = 32, nullable = false)
    private ContentType contentType;

    /**
     * The linked issue/pull request, if content type is ISSUE or PULL_REQUEST.
     * Null for DRAFT_ISSUE content type or when the issue hasn't been synced locally yet.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id")
    @ToString.Exclude
    private Issue issue;

    /**
     * The GitHub database ID of the content (issue or pull request).
     * <p>
     * Stored independently of the {@link #issue} FK so that orphaned project items
     * (where the issue hasn't been synced locally yet) can be relinked after the
     * issue sync completes. This is common when project item webhooks arrive before
     * the full issue sync runs.
     * <p>
     * Null for DRAFT_ISSUE content type.
     */
    @Column(name = "content_database_id")
    private Long contentDatabaseId;

    /**
     * Title for draft issues. Only used when contentType is DRAFT_ISSUE.
     */
    @Column(name = "draft_title", length = 1024)
    private String draftTitle;

    /**
     * Body for draft issues. Only used when contentType is DRAFT_ISSUE.
     */
    @Column(name = "draft_body", columnDefinition = "TEXT")
    @ToString.Exclude
    private String draftBody;

    /**
     * Whether this item is archived in the project.
     */
    @Column(nullable = false)
    private boolean archived = false;

    /**
     * The user who added this item to the project.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id")
    @ToString.Exclude
    private User creator;

    /**
     * Field values assigned to this item.
     */
    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    private Set<ProjectFieldValue> fieldValues = new HashSet<>();

    // Bidirectional Relationship Helpers

    /**
     * Adds a field value to this item and maintains bidirectional consistency.
     *
     * @param fieldValue the field value to add
     */
    public void addFieldValue(ProjectFieldValue fieldValue) {
        if (fieldValue != null) {
            this.fieldValues.add(fieldValue);
            fieldValue.setItem(this);
        }
    }

    /**
     * Removes a field value from this item and maintains bidirectional consistency.
     *
     * @param fieldValue the field value to remove
     */
    public void removeFieldValue(ProjectFieldValue fieldValue) {
        if (fieldValue != null) {
            this.fieldValues.remove(fieldValue);
            fieldValue.setItem(null);
        }
    }
}
