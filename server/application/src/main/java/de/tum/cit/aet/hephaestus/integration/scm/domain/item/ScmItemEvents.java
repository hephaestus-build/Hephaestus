package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import java.util.Set;

public final class ScmItemEvents {
    public static final ItemKind PULL_REQUEST = new ItemKind("scm.pull_request");
    public static final ItemKind PULL_REQUEST_REVIEW = new ItemKind("scm.pull_request_review");
    public static final ItemKind ISSUE = new ItemKind("scm.issue");
    public static final ItemKind REPOSITORY = new ItemKind("scm.repository");
    public static final ItemKind FILE = new ItemKind("scm.file");
    public static final Set<ItemKind> ALL = Set.of(ISSUE, PULL_REQUEST, PULL_REQUEST_REVIEW, REPOSITORY, FILE);
    public static final ItemEventName PULL_REQUEST_OPENED = new ItemEventName("scm.pull_request.opened");
    public static final ItemEventName PULL_REQUEST_READY = new ItemEventName("scm.pull_request.ready");
    public static final ItemEventName PULL_REQUEST_SYNCHRONIZED = new ItemEventName("scm.pull_request.synchronized");
    public static final ItemEventName PULL_REQUEST_EDITED = new ItemEventName("scm.pull_request.edited");
    public static final ItemEventName PULL_REQUEST_CHECKS_COMPLETED =
            new ItemEventName("scm.pull_request.checks_completed");
    public static final ItemEventName PULL_REQUEST_LINKED_ISSUE_UPDATED =
            new ItemEventName("scm.pull_request.linked_issue_updated");
    public static final ItemEventName PULL_REQUEST_REVIEW_SUBMITTED =
            new ItemEventName("scm.pull_request_review.submitted");
    public static final ItemEventName PULL_REQUEST_MERGED = new ItemEventName("scm.pull_request.merged");
    public static final ItemEventName PULL_REQUEST_CLOSED = new ItemEventName("scm.pull_request.closed");
    public static final ItemEventName ISSUE_OPENED = new ItemEventName("scm.issue.opened");
    public static final ItemEventName ISSUE_UPDATED = new ItemEventName("scm.issue.updated");
    public static final ItemEventName ISSUE_CLOSED = new ItemEventName("scm.issue.closed");
    public static final ItemEventName ISSUE_DELETED = new ItemEventName("scm.issue.deleted");
    public static final String ISSUE_DISCUSSION_FIELD = "discussion";
    public static final String ISSUE_PROGRESS_FIELD = "subIssues";
    public static final String CLOSING_ISSUES_FIELD = "closingIssues";
    public static final Set<String> REVIEWABLE_ISSUE_FIELDS = Set.of(
            "title",
            "body",
            "state",
            "stateReason",
            "issueType",
            "milestone",
            "relationships",
            ISSUE_DISCUSSION_FIELD,
            ISSUE_PROGRESS_FIELD);
    public static final Set<String> LINKED_ISSUE_MATERIAL_FIELDS =
            Set.of("title", "body", "state", "createdAt", "closedAt");
    public static final Set<String> LINKED_PULL_REQUEST_MATERIAL_FIELDS =
            Set.of("headRefOid", "title", "body", CLOSING_ISSUES_FIELD);

    private ScmItemEvents() {}
}
