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
	sourceContractVersion: "1.0.0",
	policyDigest: "0".repeat(64),
	reviewRuleFingerprint: `v2:${"0".repeat(64)}`,
} satisfies PracticeAutomatedReviewValidation;

export const mockPullRequestPolicy = {
	sourceContractVersion: "1.0.0",
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
	sourceContractVersion: "1.0.0",
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
	sourceContractVersion: "1.0.0",
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
	sourceContractVersion: "1.0.0",
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

/**
 * Sources several work types share, declared once so a story cannot show a practice reading "Earlier
 * observations about this person" on a pull request and something differently worded on an issue.
 *
 * The wire ids are the server's; the operator-facing strings are `displayName`, `description` and
 * `selectionScope`, all copied verbatim from `contracts/artifact-source/1.0.0/catalog.json`.
 */
const relatedWorkSource = {
	sourceKind: "workspace.project-inventory",
	displayName: "Related workspace work",
	description:
		"Other work items in the same workspace, supplied so a change can be read against related work.",
	selectionScope:
		"Up to 200 issues and 200 pull requests across at most 25 visible repositories, excluding the work item under review. Beyond any of those limits the capture is reported as PARTIAL.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: true,
} satisfies PracticeEvidenceSourceOption;

const referencedDocumentsSource = {
	sourceKind: "outline.documents",
	displayName: "Referenced Outline documents",
	description: "Outline documents the reviewed work references.",
	selectionScope:
		"Only documents referenced by the reviewed work or matched to it, up to 15. Retrieval cannot establish that it found every relevant document, so this source is never reported as COMPLETE.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: false,
} satisfies PracticeEvidenceSourceOption;

const observationHistorySource = {
	sourceKind: "hephaestus.observation-history",
	displayName: "Earlier observations about this person",
	description:
		"Observations earlier reviews in this workspace recorded about the person whose work is under review.",
	selectionScope:
		"The most recent 50 observations about this person in this workspace within the last 90 days, after the same visibility rules that govern any other reading of them. A window over a growing record cannot establish that it holds every earlier observation, so this source is never reported as COMPLETE.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: false,
} satisfies PracticeEvidenceSourceOption;

const feedbackHistorySource = {
	sourceKind: "hephaestus.feedback-history",
	displayName: "Feedback already delivered to this person",
	description:
		"Feedback earlier reviews already delivered to the person whose work is under review, with the place it went to.",
	selectionScope:
		"The most recent 30 delivered feedback items for this person in this workspace within the last 90 days. A window over a growing record cannot establish that it holds every earlier delivery, so this source is never reported as COMPLETE.",
	privacyClass: "PERSONAL",
	requiredQuality: "ANY_CAPTURE",
	supportsExhaustiveEvidence: false,
} satisfies PracticeEvidenceSourceOption;

export const mockPracticeDefinitionOptions = {
	sourceContractVersion: "1.0.0",
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
			recommendedPolicy: mockPullRequestPolicy,
			recommendedNeeds: mockPullRequestBinding.needs,
			allowedSources: [
				{
					sourceKind: "scm.pull-request.core",
					displayName: "Pull request details",
					description:
						"The pull request record: title, description, author, branches, state, labels, and commit subjects.",
					selectionScope:
						"One pull request, selected by the job, with its own fields and the commit subjects available for it.",
					privacyClass: "PERSONAL",
					requiredQuality: "COMPLETE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.pull-request.diff",
					displayName: "Code changes",
					description:
						"The code changes the pull request introduces, as a unified diff annotated with line numbers.",
					selectionScope:
						"The merge-base-to-head diff for one pull request at the reviewed commit, up to 20 MiB. A diff that cannot be read is recorded as a collection error rather than as an empty diff.",
					privacyClass: "INTERNAL",
					requiredQuality: "COMPLETE_AND_NON_EMPTY",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.pull-request.comments",
					displayName: "Inline review comments",
					description: "Review comments left on specific lines of the pull request.",
					selectionScope:
						"Up to the 500 most recent inline comments on one pull request, most recent first. Beyond that limit the capture is reported as PARTIAL.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.review-threads",
					displayName: "Review threads and decisions",
					description:
						"Review conversations on the pull request, whether each was resolved, and each reviewer's decision.",
					selectionScope: "Every review thread and review decision for one pull request.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.general-review-comments",
					displayName: "General review comments",
					description:
						"Review comments addressing the pull request as a whole rather than a specific line.",
					selectionScope: "Every general review comment on one pull request.",
					privacyClass: "PERSONAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.pull-request.commits",
					displayName: "Commits",
					description:
						"The commits the pull request carries: each one's subject, body, timestamps, size, and how it was made.",
					selectionScope:
						"Up to the first 200 commits linked to one pull request, oldest authored first. Beyond that limit the capture is reported as PARTIAL. Per-commit diffs are not included. No pull request has zero commits, so an empty capture is the mirror not having linked them yet, and a practice that reads this source is refused rather than asked to judge commits it cannot see.",
					privacyClass: "PERSONAL",
					requiredQuality: "COMPLETE_AND_NON_EMPTY",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.repository.tree",
					displayName: "Repository files",
					description:
						"Files from elsewhere in the repository, supplied as context for reading the change. Not reviewed on their own.",
					selectionScope:
						"The repository at the reviewed commit: up to 20,000 files and 32 MiB. Files above 10 MiB, symbolic links, submodules, and paths outside the tree are excluded, and the capture is reported as PARTIAL.",
					privacyClass: "INTERNAL",
					requiredQuality: "ANY_CAPTURE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.linked-work-items",
					displayName: "Linked work items",
					description:
						"Issues the pull request states it addresses, resolved from its description, branch name, and commit subjects.",
					selectionScope:
						"Issues resolved from closing references, the branch name, and up to 500 commit subjects. Resolution cannot establish that it found every link the work actually has, so this source is never reported as COMPLETE. References to work outside this repository are reported as unresolved rather than as incomplete evidence.",
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
			recommendedPolicy: mockIssuePolicy,
			recommendedNeeds: mockIssueBinding.needs,
			allowedSources: [
				{
					sourceKind: "scm.issue.core",
					displayName: "Issue details",
					description:
						"The issue record: title, description, author, state, labels, and assignees.",
					selectionScope:
						"One issue, selected by the job, with its own fields and its rendered description.",
					privacyClass: "PERSONAL",
					requiredQuality: "COMPLETE",
					supportsExhaustiveEvidence: true,
				},
				{
					sourceKind: "scm.issue.comments",
					displayName: "Issue comments",
					description: "The discussion recorded on the issue.",
					selectionScope:
						"Up to the 200 most recent comments on one issue, most recent first. Beyond that limit the capture is reported as PARTIAL.",
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
			recommendedPolicy: mockConversationPolicy,
			recommendedNeeds: mockConversationBinding.needs,
			allowedSources: [
				{
					sourceKind: "slack.conversation.thread",
					displayName: "Slack thread",
					description:
						"One Slack thread in chronological order, read only from channels whose consent is active.",
					selectionScope:
						"One thread, selected by the job, with system and bot messages excluded. A thread whose channel consent is not active, or which no longer exists, is reported as REDACTED or UNAVAILABLE rather than as an empty conversation.",
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
			recommendedPolicy: mockDocumentPolicy,
			recommendedNeeds: mockDocumentBinding.needs,
			allowedSources: [
				{
					sourceKind: "docs.document.core",
					displayName: "Document under review",
					description:
						"The written document a review is about: its prose, title, collection, author, and upstream timestamps.",
					selectionScope:
						"One mirrored document, selected by the job, rendered whole. A document removed upstream or evicted from the local mirror is reported as UNAVAILABLE rather than as a document that said nothing.",
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
	if (!workType) throw new Error(`No work type fixture for ${artifactKind}`);
	return workType;
}

export const mockPullRequestWorkType = workTypeOf("scm.pull_request");
export const mockIssueWorkType = workTypeOf("scm.issue");
export const mockConversationWorkType = workTypeOf("chat.conversation_thread");
export const mockDocumentWorkType = workTypeOf("docs.document");
