import type {
	PracticeAutomatedReviewPolicy,
	PracticeAutomatedReviewValidation,
	PracticeBinding,
	PracticeDefinitionOptions,
	PracticeEvidenceSourceOption,
	PracticeWorkTypeDefinitionOptions,
} from "@/api/types.gen";

export const mockAuthorDeclaredEvidenceValidation = {
	status: "AUTHOR_DECLARED",
	sourceContractVersion: "1.3.0",
	policyDigest: "0".repeat(64),
	reviewRuleFingerprint: `v4:${"0".repeat(64)}`,
} satisfies PracticeAutomatedReviewValidation;

export const mockPullRequestPolicy = {
	sourceContractVersion: "1.3.0",
	automatedReview: {
		mode: "LANGUAGE_MODEL",
		evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
	},
	whenEvidenceIsInsufficient: "SKIP_AUTOMATED_REVIEW",
	knownLimitations: [
		{
			code: "RUNTIME_BEHAVIOR_NOT_OBSERVED",
			description: "Repository evidence does not establish behavior in a deployed runtime.",
		},
	],
} satisfies PracticeAutomatedReviewPolicy;

const mockIssuePolicy = {
	sourceContractVersion: "1.3.0",
	automatedReview: {
		mode: "LANGUAGE_MODEL",
		evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
	},
	whenEvidenceIsInsufficient: "SKIP_AUTOMATED_REVIEW",
	knownLimitations: [
		{
			code: "IMPLEMENTATION_NOT_OBSERVED",
			description:
				"Issue evidence does not establish whether the described work was implemented correctly.",
		},
	],
} satisfies PracticeAutomatedReviewPolicy;

const mockConversationPolicy = {
	sourceContractVersion: "1.3.0",
	automatedReview: {
		mode: "LANGUAGE_MODEL",
		evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
	},
	whenEvidenceIsInsufficient: "SKIP_AUTOMATED_REVIEW",
	knownLimitations: [
		{
			code: "PRIVATE_CONTEXT_NOT_OBSERVED",
			description:
				"The captured thread does not include decisions or context shared outside the conversation.",
		},
	],
} satisfies PracticeAutomatedReviewPolicy;

const mockDocumentPolicy = {
	sourceContractVersion: "1.3.0",
	automatedReview: {
		mode: "LANGUAGE_MODEL",
		evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
	},
	whenEvidenceIsInsufficient: "SKIP_AUTOMATED_REVIEW",
	knownLimitations: [
		{
			code: "READERSHIP_NOT_OBSERVED",
			description: "A published document does not establish whether anyone acted on it.",
		},
	],
} satisfies PracticeAutomatedReviewPolicy;

export const mockPullRequestBinding = {
	signals: ["scm.pull_request.opened", "scm.pull_request.ready", "scm.pull_request.synchronized"],
	needs: [
		{ sourceKind: "scm.pull-request.comments", stance: "REQUIRED" },
		{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" },
		{ sourceKind: "scm.pull-request.diff", stance: "REQUIRED" },
	],
} satisfies PracticeBinding;

export const mockMergeBinding = {
	signals: ["scm.pull_request.merged"],
	needs: [
		{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" },
		{ sourceKind: "scm.repository.tree", stance: "CONTEXTUAL" },
		{ sourceKind: "scm.review-threads", stance: "EXHAUSTIVE" },
	],
} satisfies PracticeBinding;

export const mockIssueBinding = {
	signals: ["scm.issue.opened", "scm.issue.updated"],
	needs: [
		{ sourceKind: "scm.issue.comments", stance: "REQUIRED" },
		{ sourceKind: "scm.issue.core", stance: "REQUIRED" },
	],
} satisfies PracticeBinding;

export const mockConversationBinding = {
	signals: ["chat.conversation_thread.settled"],
	needs: [{ sourceKind: "slack.conversation.thread", stance: "REQUIRED" }],
} satisfies PracticeBinding;

export const mockDocumentBinding = {
	signals: ["docs.document.published", "docs.document.updated"],
	needs: [{ sourceKind: "docs.document.core", stance: "REQUIRED" }],
} satisfies PracticeBinding;

/** Shared source options must match contracts/source-use/1.3.0/catalog.json; practice.test.ts checks them. */
const relatedWorkSource = {
	sourceKind: "workspace.project-inventory",
	displayName: "Related workspace work",
	description:
		"Other work items in the same workspace, supplied so a change can be read against related work.",
	selectionScope:
		"All permitted issues, pull requests, workspace member profiles and practice definitions. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: true,
} satisfies PracticeEvidenceSourceOption;

const referencedDocumentsSource = {
	sourceKind: "outline.documents",
	displayName: "Outline documents",
	description: "Mirrored documents in permitted workspace Outline collections.",
	selectionScope:
		"Every permitted mirrored Outline document in the workspace. Deleted documents are excluded; an evicted body is an explicit refusal, not an empty document. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: true,
} satisfies PracticeEvidenceSourceOption;

const observationHistorySource = {
	sourceKind: "hephaestus.observation-history",
	displayName: "Earlier observations about this person",
	description:
		"Observations earlier reviews in this workspace recorded about the person whose work is under review, with the practice, presence, assessment and recurrence key each was filed under.",
	selectionScope:
		"All currently visible prior observations about permitted workspace members, after currentness, withdrawal and consent checks. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: true,
} satisfies PracticeEvidenceSourceOption;

const feedbackHistorySource = {
	sourceKind: "hephaestus.feedback-history",
	displayName: "Feedback already delivered to this person",
	description:
		"Feedback earlier reviews already delivered to the person whose work is under review, with the channel it went to and the recurrence keys it spoke to.",
	selectionScope:
		"All currently visible delivered feedback about permitted workspace members, plus prepared feedback for the reviewed developer, after currentness, withdrawal and consent checks. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: true,
} satisfies PracticeEvidenceSourceOption;

export const mockPracticeDefinitionOptions = {
	sourceContractVersion: "1.3.0",
	workTypes: [
		{
			artifactKind: "scm.pull_request",
			signals: [
				{ signal: "scm.pull_request.opened", displayName: "Opened", recommended: true },
				{
					signal: "scm.pull_request.ready",
					displayName: "Marked ready for review",
					recommended: true,
				},
				{
					signal: "scm.pull_request.synchronized",
					displayName: "New commits pushed",
					recommended: true,
				},
				{
					signal: "scm.pull_request.reviewed",
					displayName: "Review submitted",
					recommended: false,
				},
				{ signal: "scm.pull_request.merged", displayName: "Merged", recommended: false },
				{
					signal: "scm.pull_request.closed",
					displayName: "Closed without merging",
					recommended: false,
				},
			],
			manualReviewSignal: {
				signal: "scm.pull_request.manual_review",
				displayName: "Review requested by hand",
			},
			supportedAutomatedReviewModes: ["LANGUAGE_MODEL"],
			subjectRoles: ["AUTHOR", "ASSIGNEE", "REVIEWER", "MERGER"],
			recommendedPolicy: mockPullRequestPolicy,
			recommendedNeeds: mockPullRequestBinding.needs,
			allowedSources: [
				{
					sourceKind: "scm.pull-request.core",
					displayName: "Pull request details",
					description: "The pull request record: its fields as the provider holds them.",
					selectionScope:
						"Every permitted pull request in monitored workspace repositories, with its mirrored fields. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "PERSONAL",
					requiredQuality: "COMPLETE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.pull-request.diff",
					displayName: "Code changes",
					description:
						"The change the pull request introduces: the pinned base and head commits, from which the review derives the diff.",
					selectionScope:
						"The base and head commit of one pull request, both pinned, both present in the captured repository. GitLab records the review diff base; providers that record the target tip require its merge base with the reviewed head. The diff itself, its statistics, the changed paths and the commits are derived inside the review from the captured repository with git, so nothing about the change is truncated or rendered before the review reads it. A quote of the change names a side of that range and a repository path; it is verified against the blob at that side's commit and refused when the path is not one the change touches. A pinned range with no changes is complete and empty. A range that cannot be pinned is a collection error.",
					privacyClass: "INTERNAL",
					requiredQuality: "COMPLETE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.pull-request.comments",
					displayName: "Inline review comments",
					description: "Review comments left on specific lines of the pull request.",
					selectionScope:
						"All permitted inline review comments on workspace pull requests. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.review-threads",
					displayName: "Review threads and decisions",
					description:
						"Review conversations on the pull request, whether each was resolved, and each reviewer's decision.",
					selectionScope:
						"All permitted review threads in workspace pull requests, including resolution state. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.general-review-comments",
					displayName: "General review comments",
					description:
						"Review comments addressing the pull request as a whole rather than a specific line.",
					selectionScope:
						"All permitted pull-request discussion comments and submitted reviews in the workspace. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.repository.tree",
					displayName: "Repository files and history",
					description:
						"Repository files and reachable Git history, supplied as context for reading the change. Not reviewed on their own.",
					selectionScope:
						"Full reachable Git history and a working copy of each permitted repository, copied from the worker's bare mirrors. The reviewed repository is pinned to the job's commit. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "INTERNAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.linked-work-items",
					displayName: "Linked work items",
					description:
						"Issues the pull request refers to by number in its description, branch name or commit subjects, as this repository stores them.",
					selectionScope:
						"Every permitted linked work items record in the job workspace, without record-count or history-window caps. Existing visibility, consent, withdrawal, tenancy, processor, retention and erasure checks apply before rendering.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: false,
				},
				relatedWorkSource,
				referencedDocumentsSource,
				observationHistorySource,
				feedbackHistorySource,
			],
		},
		{
			artifactKind: "scm.issue",
			signals: [
				{ signal: "scm.issue.opened", displayName: "Opened", recommended: true },
				{ signal: "scm.issue.updated", displayName: "Details changed", recommended: true },
				{ signal: "scm.issue.closed", displayName: "Closed", recommended: false },
			],
			manualReviewSignal: {
				signal: "scm.issue.manual_review",
				displayName: "Review requested by hand",
			},
			supportedAutomatedReviewModes: ["LANGUAGE_MODEL"],
			subjectRoles: ["AUTHOR", "ASSIGNEE"],
			recommendedPolicy: mockIssuePolicy,
			recommendedNeeds: mockIssueBinding.needs,
			allowedSources: [
				{
					sourceKind: "scm.issue.core",
					displayName: "Issue details",
					description:
						"The issue record: title, description, author, state, labels, and assignees.",
					selectionScope:
						"Every permitted issue in monitored workspace repositories, with its mirrored fields. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "PERSONAL",
					requiredQuality: "COMPLETE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.issue.comments",
					displayName: "Issue comments",
					description: "The discussion recorded on the issue.",
					selectionScope:
						"All permitted comments on workspace issues. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				relatedWorkSource,
				referencedDocumentsSource,
				observationHistorySource,
				feedbackHistorySource,
			],
		},
		{
			artifactKind: "chat.conversation_thread",
			signals: [
				{
					signal: "chat.conversation_thread.settled",
					displayName: "Discussion settled",
					recommended: true,
				},
			],
			supportedAutomatedReviewModes: ["LANGUAGE_MODEL"],
			subjectRoles: ["AUTHOR"],
			recommendedPolicy: mockConversationPolicy,
			recommendedNeeds: mockConversationBinding.needs,
			allowedSources: [
				{
					sourceKind: "slack.conversation.thread",
					displayName: "Slack thread",
					description:
						"One Slack thread in chronological order, read only from channels whose consent is active.",
					selectionScope:
						"All retained, non-deleted messages in workspace Slack channels with active consent, excluding opted-out authors. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "SENSITIVE_PERSONAL",
					requiredQuality: "COMPLETE",
					supportsExhaustiveEvidence: true,
				},
				relatedWorkSource,
				observationHistorySource,
				feedbackHistorySource,
			],
		},
		{
			artifactKind: "docs.document",
			signals: [
				{ signal: "docs.document.published", displayName: "Published", recommended: true },
				{ signal: "docs.document.updated", displayName: "Content changed", recommended: true },
				{ signal: "docs.document.archived", displayName: "Archived", recommended: false },
			],
			supportedAutomatedReviewModes: ["LANGUAGE_MODEL"],
			subjectRoles: ["AUTHOR"],
			recommendedPolicy: mockDocumentPolicy,
			recommendedNeeds: mockDocumentBinding.needs,
			allowedSources: [
				{
					sourceKind: "docs.document.core",
					displayName: "Document under review",
					description:
						"The written document a review is about: its prose, title, collection, author, and upstream timestamps.",
					selectionScope:
						"The permitted mirrored document selected as the reviewed work, with its current body and metadata. No review record-count cap or history window applies. Existing visibility, tenancy, processor, retention and erasure checks remain in force.",
					privacyClass: "PERSONAL",
					requiredQuality: "COMPLETE",
					supportsExhaustiveEvidence: true,
				},
				observationHistorySource,
				feedbackHistorySource,
			],
		},
	],
} satisfies PracticeDefinitionOptions;

/** Looked up by kind rather than indexed, so a work type added above cannot repoint a story. */
function workTypeOf(artifactKind: string): PracticeWorkTypeDefinitionOptions {
	const workType = mockPracticeDefinitionOptions.workTypes.find(
		(candidate) => candidate.artifactKind === artifactKind,
	);
	if (!workType) {
		throw new Error(`No work type fixture for ${artifactKind}`);
	}
	return workType;
}

export const mockPullRequestWorkType = workTypeOf("scm.pull_request");
export const mockIssueWorkType = workTypeOf("scm.issue");
export const mockConversationWorkType = workTypeOf("chat.conversation_thread");
export const mockDocumentWorkType = workTypeOf("docs.document");
