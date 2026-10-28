import type {
	ItemEventOption,
	PracticeDefinition,
	PracticeDefinitionOptions,
	PracticeItemTypeOption,
	ReadSourceOption,
	ScanDefinition,
} from "@/api/types.gen";

import type { PracticeDefinitionValue } from "./PracticeDefinitionForm";

export const MOCK_SOURCE_CONTRACT = "3.0.0";

function readSource(
	source: string,
	label: string,
	complete = true,
	citation?: ReadSourceOption["citation"],
): ReadSourceOption {
	return {
		source,
		label,
		needs: complete ? ["REQUIRED", "COMPLETE", "OPTIONAL"] : ["REQUIRED", "OPTIONAL"],
		citation: citation ?? { side: "FORBIDDEN", commit: "FORBIDDEN" },
	};
}

function event(name: string, label: string, recommended: boolean): ItemEventOption {
	return { name, label, recommended, connected: true, raisedBy: ["GITHUB", "GITLAB"] };
}

const repositoryTree = readSource("scm.repository.tree", "Repository files and history", true, {
	side: "FORBIDDEN",
	commit: "OPTIONAL",
});

const pullRequestSources = [
	readSource("scm.pull-request.core", "Pull request details"),
	readSource("scm.pull-request.checks", "CI checks and merge status"),
	readSource("scm.pull-request.diff", "Code changes", true, {
		side: "REQUIRED",
		commit: "OPTIONAL",
	}),
	readSource("scm.pull-request.comments", "Inline review comments"),
	readSource("scm.review-threads", "Review threads and decisions"),
	readSource("scm.general-review-comments", "General review comments"),
	repositoryTree,
	readSource("scm.linked-work-items", "Linked work items", false),
];

const applicabilitySources = pullRequestSources.filter(
	({ source }) => source === "scm.review-threads" || source === "scm.general-review-comments",
);

export const mockPullRequestItemType = {
	kind: "scm.pull_request",
	label: "Pull request",
	events: [
		event("scm.pull_request.opened", "Opened", true),
		event("scm.pull_request.ready", "Marked ready for review", true),
		event("scm.pull_request.synchronized", "New commits pushed", false),
		event("scm.pull_request.edited", "Description edited", false),
		event("scm.pull_request.merged", "Merged", false),
		event("scm.pull_request.closed", "Closed without merging", false),
	],
	aboutRoles: ["AUTHOR", "ASSIGNEE", "MERGER"],
	readSources: pullRequestSources,
	reviewOnStateDimensions: [
		{
			name: "draftStatus",
			label: "Draft status",
			values: [
				{ value: "DRAFT", label: "Draft" },
				{ value: "NOT_DRAFT", label: "Ready for review" },
			],
		},
	],
	applicabilityClauses: ["CHANGED_PATH", "DIFF_CONTAINS", "HAS_ITEMS"],
	applicabilitySources,
	scanSupported: true,
	scanItemKinds: ["scm.file"],
} satisfies PracticeItemTypeOption;

export const mockPullRequestReviewItemType = {
	kind: "scm.pull_request_review",
	label: "Pull request review",
	events: [event("scm.pull_request_review.submitted", "Pull request review submitted", true)],
	aboutRoles: ["REVIEWER"],
	readSources: pullRequestSources,
	reviewOnStateDimensions: [],
	applicabilityClauses: ["HAS_ITEMS"],
	applicabilitySources,
	scanSupported: false,
	scanItemKinds: [],
} satisfies PracticeItemTypeOption;

export const mockIssueItemType = {
	kind: "scm.issue",
	label: "Issue",
	events: [
		event("scm.issue.opened", "Opened", true),
		event("scm.issue.updated", "Details changed", true),
		event("scm.issue.closed", "Closed", false),
	],
	aboutRoles: ["AUTHOR", "ASSIGNEE"],
	readSources: [
		readSource("scm.issue.core", "Issue details"),
		readSource("scm.issue.comments", "Issue comments"),
	],
	reviewOnStateDimensions: [
		{
			name: "state",
			label: "State",
			values: [
				{ value: "OPEN", label: "Open" },
				{ value: "CLOSED", label: "Closed" },
			],
		},
	],
	applicabilityClauses: [],
	applicabilitySources: [],
	scanSupported: false,
	scanItemKinds: [],
} satisfies PracticeItemTypeOption;

export const mockThreadItemType = {
	kind: "chat.thread",
	label: "Conversation",
	events: [event("chat.thread.settled", "Discussion settled", true)],
	aboutRoles: ["AUTHOR"],
	readSources: [readSource("slack.conversation.thread", "Slack thread")],
	reviewOnStateDimensions: [],
	applicabilityClauses: [],
	applicabilitySources: [],
	scanSupported: false,
	scanItemKinds: [],
} satisfies PracticeItemTypeOption;

export const mockDocumentItemType = {
	kind: "docs.document",
	label: "Document",
	events: [
		event("docs.document.published", "Published", true),
		event("docs.document.updated", "Content changed", true),
		event("docs.document.archived", "Archived", false),
	],
	aboutRoles: ["AUTHOR"],
	readSources: [readSource("docs.document.core", "Document under review")],
	reviewOnStateDimensions: [],
	applicabilityClauses: [],
	applicabilitySources: [],
	scanSupported: false,
	scanItemKinds: [],
} satisfies PracticeItemTypeOption;

export const mockFileItemType = {
	kind: "scm.file",
	label: "File",
	events: [],
	aboutRoles: [],
	readSources: [repositoryTree],
	reviewOnStateDimensions: [],
	applicabilityClauses: [],
	applicabilitySources: [],
	scanSupported: false,
	scanItemKinds: [],
} satisfies PracticeItemTypeOption;

export const mockDefinitionOptions = {
	sourceContract: MOCK_SOURCE_CONTRACT,
	itemTypes: [
		mockPullRequestItemType,
		mockPullRequestReviewItemType,
		mockIssueItemType,
		mockThreadItemType,
		mockDocumentItemType,
		mockFileItemType,
	],
} satisfies PracticeDefinitionOptions;

/**
 * A complete pull request definition, reviewed by Hephaestus, with the given fields replaced. Every
 * field is spelled out, so a fixture never borrows a default the server does not have.
 */
export function definitionOf(overrides: Partial<PracticeDefinition> = {}): PracticeDefinition {
	return {
		name: "Keep the change reviewable",
		reviewOn: {
			events: ["scm.pull_request.opened", "scm.pull_request.ready"],
			state: { draftStatus: ["NOT_DRAFT"] },
		},
		appliesWhen: null,
		about: "AUTHOR",
		reads: {
			contract: MOCK_SOURCE_CONTRACT,
			sources: [
				{ source: "scm.pull-request.core", need: "REQUIRED" },
				{ source: "scm.pull-request.diff", need: "REQUIRED" },
				{ source: "scm.repository.tree", need: "OPTIONAL" },
			],
		},
		reviewedBy: { by: "HEPHAESTUS", humanReason: null, limits: [] },
		criteria: "Keep each change small enough to review in one sitting.",
		script: null,
		scan: null,
		lineNotes: true,
		guidance: {
			whyItMatters: "Small changes are easier to review.",
			whatGoodLooksLike: "One change with one clear purpose.",
			goingWell: "You keep each change small enough to review in one sitting.",
			visual: null,
			guide: null,
		},
		...overrides,
	};
}

export const mockScan = {
	itemKind: "scm.file",
	include: ["**/*.ts", "**/*.tsx"],
	exclude: ["**/*.gen.ts"],
	context: ["**/*"],
	reads: {
		contract: MOCK_SOURCE_CONTRACT,
		sources: [{ source: "scm.repository.tree", need: "COMPLETE" }],
	},
} satisfies ScanDefinition;

export function practiceValueOf(
	overrides: Partial<PracticeDefinition> = {},
): PracticeDefinitionValue {
	return {
		slug: "keep-the-change-reviewable",
		kind: "scm.pull_request",
		groupSlug: null,
		definition: definitionOf(overrides),
	};
}
