import type {
	PracticeDefinition,
	PracticeDefinitionOptions,
	PracticeItemTypeOption,
	ReadSourceOption,
	ReviewedBy,
} from "@/api/types.gen";

type ReviewFields = Pick<PracticeDefinition, "reviewOn" | "about" | "reads" | "appliesWhen">;

const SOURCE_CONTRACT = "3.0.0";

export const mockPullRequestReviewedBy = {
	by: "HEPHAESTUS",
	humanReason: null,
	limits: [
		{
			code: "RUNTIME_BEHAVIOR_NOT_OBSERVED",
			description: "Repository evidence does not establish behavior in a deployed runtime.",
		},
	],
} satisfies ReviewedBy;

export const mockPullRequestReviewFields = {
	reviewOn: {
		events: ["scm.pull_request.opened", "scm.pull_request.ready", "scm.pull_request.synchronized"],
		state: { draftStatus: ["NOT_DRAFT"] },
	},
	about: "AUTHOR",
	appliesWhen: null,
	reads: {
		contract: SOURCE_CONTRACT,
		sources: [
			{ source: "scm.pull-request.comments", need: "REQUIRED" },
			{ source: "scm.pull-request.core", need: "REQUIRED" },
			{ source: "scm.pull-request.diff", need: "REQUIRED" },
		],
	},
} satisfies ReviewFields;

export const mockMergeReviewFields = {
	reviewOn: { events: ["scm.pull_request.merged"], state: {} },
	about: "AUTHOR",
	appliesWhen: null,
	reads: {
		contract: SOURCE_CONTRACT,
		sources: [
			{ source: "scm.pull-request.core", need: "REQUIRED" },
			{ source: "scm.repository.tree", need: "OPTIONAL" },
			{ source: "scm.review-threads", need: "COMPLETE" },
		],
	},
} satisfies ReviewFields;

export const mockIssueReviewFields = {
	reviewOn: { events: ["scm.issue.opened", "scm.issue.updated"], state: {} },
	about: "AUTHOR",
	appliesWhen: null,
	reads: {
		contract: SOURCE_CONTRACT,
		sources: [
			{ source: "scm.issue.comments", need: "REQUIRED" },
			{ source: "scm.issue.core", need: "REQUIRED" },
		],
	},
} satisfies ReviewFields;

function readSource(source: string, label: string): ReadSourceOption {
	return {
		source,
		label,
		needs: ["REQUIRED", "COMPLETE", "OPTIONAL"],
		citation: { side: "FORBIDDEN", commit: "FORBIDDEN" },
	};
}

const SCM_PROVIDERS = [
	"GITHUB",
	"GITLAB",
] satisfies PracticeItemTypeOption["events"][number]["raisedBy"];

export const mockPracticeDefinitionOptions = {
	sourceContract: SOURCE_CONTRACT,
	itemTypes: [
		{
			kind: "scm.pull_request",
			label: "Pull or merge request",
			events: [
				{
					name: "scm.pull_request.opened",
					label: "Opened",
					recommended: true,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
				{
					name: "scm.pull_request.ready",
					label: "Marked ready for review",
					recommended: true,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
				{
					name: "scm.pull_request.synchronized",
					label: "New commits pushed",
					recommended: true,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
				{
					name: "scm.pull_request.edited",
					label: "Title or description edited",
					recommended: false,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
				{
					name: "scm.pull_request.merged",
					label: "Merged",
					recommended: false,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
				{
					name: "scm.pull_request.closed",
					label: "Closed without merging",
					recommended: false,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
			],
			aboutRoles: ["AUTHOR", "ASSIGNEE", "MERGER"],
			readSources: [
				readSource("scm.pull-request.core", "Pull request details"),
				readSource("scm.pull-request.checks", "CI checks and merge status"),
				{
					source: "scm.pull-request.diff",
					label: "Code changes",
					needs: ["REQUIRED", "COMPLETE", "OPTIONAL"],
					citation: { side: "REQUIRED", commit: "OPTIONAL" },
				},
				readSource("scm.pull-request.comments", "Inline review comments"),
				{
					source: "scm.repository.tree",
					label: "Repository files and history",
					needs: ["REQUIRED", "COMPLETE", "OPTIONAL"],
					citation: { side: "FORBIDDEN", commit: "OPTIONAL" },
				},
				{
					source: "scm.linked-work-items",
					label: "Linked work items",
					needs: ["REQUIRED", "OPTIONAL"],
					citation: { side: "FORBIDDEN", commit: "FORBIDDEN" },
				},
				readSource("scm.review-threads", "Review threads and decisions"),
				readSource("scm.general-review-comments", "General review comments"),
			],
			reviewOnStateDimensions: [
				{
					name: "draftStatus",
					label: "Draft status",
					values: [
						{ value: "DRAFT", label: "Draft" },
						{ value: "NOT_DRAFT", label: "Not draft" },
					],
				},
				{
					name: "state",
					label: "State",
					values: [
						{ value: "OPEN", label: "Open" },
						{ value: "CLOSED", label: "Closed" },
						{ value: "MERGED", label: "Merged" },
					],
				},
			],
			scanSupported: true,
			scanItemKinds: ["scm.file"],
			applicabilityClauses: ["CHANGED_PATH", "DIFF_CONTAINS", "HAS_ITEMS"],
			applicabilitySources: [
				readSource("scm.review-threads", "Review threads and decisions"),
				readSource("scm.general-review-comments", "General review comments"),
			],
		},
		{
			kind: "scm.issue",
			label: "Issue",
			events: [
				{
					name: "scm.issue.opened",
					label: "Opened",
					recommended: true,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
				{
					name: "scm.issue.updated",
					label: "Details changed",
					recommended: true,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
				{
					name: "scm.issue.closed",
					label: "Closed",
					recommended: false,
					connected: true,
					raisedBy: SCM_PROVIDERS,
				},
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
			scanSupported: false,
			scanItemKinds: [],
			applicabilityClauses: [],
			applicabilitySources: [],
		},
		{
			kind: "chat.thread",
			label: "Conversation thread",
			events: [
				{
					name: "chat.thread.settled",
					label: "Discussion settled",
					recommended: true,
					connected: true,
					raisedBy: [],
				},
			],
			aboutRoles: ["AUTHOR"],
			readSources: [readSource("slack.conversation.thread", "Slack thread")],
			reviewOnStateDimensions: [],
			scanSupported: false,
			scanItemKinds: [],
			applicabilityClauses: [],
			applicabilitySources: [],
		},
		{
			kind: "docs.document",
			label: "Document",
			events: [
				{
					name: "docs.document.published",
					label: "Published",
					recommended: true,
					connected: true,
					raisedBy: ["OUTLINE"],
				},
				{
					name: "docs.document.updated",
					label: "Content changed",
					recommended: true,
					connected: true,
					raisedBy: ["OUTLINE"],
				},
				{
					name: "docs.document.archived",
					label: "Archived",
					recommended: false,
					connected: true,
					raisedBy: ["OUTLINE"],
				},
			],
			aboutRoles: ["AUTHOR"],
			readSources: [readSource("docs.document.core", "Document under review")],
			reviewOnStateDimensions: [
				{
					name: "state",
					label: "State",
					values: [
						{ value: "ACTIVE", label: "Active" },
						{ value: "ARCHIVED", label: "Archived" },
					],
				},
			],
			scanSupported: false,
			scanItemKinds: [],
			applicabilityClauses: [],
			applicabilitySources: [],
		},
	],
} satisfies PracticeDefinitionOptions;

// Look up kinds by name so adding one cannot repoint a fixture by array position.
function itemTypeOf(kind: string): PracticeItemTypeOption {
	const itemType = mockPracticeDefinitionOptions.itemTypes.find(
		(candidate) => candidate.kind === kind,
	);
	if (!itemType) {
		throw new Error(`No item type fixture for ${kind}`);
	}
	return itemType;
}

export const mockPullRequestItemType = itemTypeOf("scm.pull_request");
export const mockIssueItemType = itemTypeOf("scm.issue");
export const mockDocumentItemType = itemTypeOf("docs.document");
