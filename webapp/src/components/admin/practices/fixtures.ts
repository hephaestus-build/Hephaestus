import type {
	AccountRef,
	CatalogOrigin,
	FeedbackModeAssignment,
	Practice,
	PracticeDefinition,
	PracticeGroup,
	PracticeReleaseProposal,
	PracticeVersion,
} from "@/api/types.gen";
import { definitionOf, mockScan } from "@/components/admin/practice-editor/fixtures";
import type { FeedbackMode } from "@/lib/practice-feedback-mode";

// Prop fixtures use Dates because they bypass the generated response transformer.

export const mockAdmin: AccountRef = {
	id: "7",
	displayName: "Ada Lovelace",
	status: "ACTIVE",
	identities: [{ provider: "GITHUB", login: "ada", displayName: "Ada Lovelace" }],
};

export function inheritedFeedbackMode(
	effective: FeedbackMode = "AUTOMATIC",
	source: "GROUP" | "WORKSPACE" = "WORKSPACE",
): FeedbackModeAssignment {
	return { effective, override: null, source };
}

export function chosenFeedbackMode(
	effective: FeedbackMode,
	source: "PRACTICE" | "GROUP" = "PRACTICE",
): FeedbackModeAssignment {
	return { effective, override: effective, source };
}

export const OWN_ORIGIN: CatalogOrigin = {
	catalogSlug: null,
	state: "YOURS",
	localEdits: [],
	offeredDigest: null,
};

export function catalogOrigin(
	catalogSlug: string,
	state: CatalogOrigin["state"] = "FROM_HEPHAESTUS",
	localEdits: CatalogOrigin["localEdits"] = [],
): CatalogOrigin {
	return {
		catalogSlug,
		state,
		localEdits,
		offeredDigest: state === "NO_LONGER_SHIPPED" ? null : `sha256:${catalogSlug}`,
	};
}

export function practiceVersion(
	definition: PracticeDefinition,
	overrides: Partial<PracticeVersion> = {},
): PracticeVersion {
	return {
		id: "101",
		number: 1,
		name: definition.name,
		definition,
		reviewDigest: "sha256:review",
		createdAt: new Date("2025-06-15"),
		createdBy: mockAdmin,
		changed: [],
		reviewChanged: false,
		...overrides,
	};
}

interface MockPracticeInput {
	id: string;
	slug: string;
	kind?: string;
	groupSlug?: string | null;
	position?: number;
	definition: PracticeDefinition;
	feedbackMode?: FeedbackModeAssignment;
	catalogOrigin?: CatalogOrigin;
	version?: Partial<PracticeVersion>;
}

export function mockPracticeOf({
	id,
	slug,
	kind = "scm.pull_request",
	groupSlug = "review-ready-work",
	position = 0,
	definition,
	feedbackMode = inheritedFeedbackMode(),
	catalogOrigin: origin = OWN_ORIGIN,
	version,
}: MockPracticeInput): Practice {
	return {
		id,
		slug,
		kind,
		groupSlug,
		position,
		currentVersion: practiceVersion(definition, { id: `${id}01`, ...version }),
		feedbackMode,
		catalogOrigin: origin,
		createdAt: new Date("2025-06-01"),
		updatedAt: new Date("2025-06-15"),
	};
}

export function practiceScript(): string {
	return [
		'import { definePracticeScript } from "@hephaestus/practice-scripts";',
		'import { scm } from "@hephaestus/practice-scripts/scm";',
		"",
		"const OPEN_WORK = {",
		'  typescript: [{ kind: "open-work", label: "A TODO or FIXME that the change adds.", test: /TODO|FIXME/u }],',
		"};",
		"",
		"export default definePracticeScript({",
		"  meta: {},",
		"  async run(ctx) {",
		'    const work = scm(ctx, "scm.pull_request");',
		"    const { leads } = await work.sites(OPEN_WORK, { comments: true });",
		"    return ctx.review({ leads });",
		"  },",
		"});",
	].join("\n");
}

export const mockPractice = mockPracticeOf({
	id: "1",
	slug: "pr-description-quality",
	catalogOrigin: catalogOrigin("pr-description-quality"),
	definition: definitionOf({
		name: "PR Description Quality",
		criteria:
			"## The standard\n\nEvaluate whether the pull request description provides sufficient context, motivation, and testing steps.\n\n### Required elements\n- Summary of changes\n- Motivation / why\n- Testing steps\n- Link to issue",
		guidance: {
			whyItMatters:
				"A clear description lets reviewers understand intent without reverse-engineering the diff, speeding up review and reducing back-and-forth.",
			whatGoodLooksLike:
				"A PR opens with a one-paragraph summary, links the issue, and lists the exact steps a reviewer ran to verify it.",
			goingWell: "Your pull requests say what changed and why.",
			visual: null,
			guide: null,
		},
	}),
});

export const mockPractices: Practice[] = [
	mockPractice,
	mockPracticeOf({
		id: "2",
		slug: "code-review-thoroughness",
		kind: "scm.pull_request_review",
		position: 1,
		definition: definitionOf({
			name: "Code Review Thoroughness",
			reviewOn: { events: ["scm.pull_request_review.submitted"], state: {} },
			about: "REVIEWER",
			criteria:
				"## Code Review Thoroughness\n\nEvaluate depth and quality of code reviews. Reviewers should engage with logic and design, not just style.",
		}),
	}),
	mockPracticeOf({
		id: "3",
		slug: "test-coverage",
		position: 2,
		feedbackMode: chosenFeedbackMode("OFF"),
		definition: definitionOf({
			name: "Test Coverage",
			reviewOn: { events: ["scm.pull_request.opened", "scm.pull_request.synchronized"], state: {} },
			criteria:
				"## Test Coverage\n\nChecks that new code includes appropriate test coverage. Critical paths and edge cases should be tested.",
			scan: mockScan,
		}),
	}),
];

export const mockUnassignedPractice = mockPracticeOf({
	id: "5",
	slug: "error-state-handling",
	groupSlug: null,
	definition: definitionOf({
		name: "Error State Handling",
		reviewOn: { events: ["scm.pull_request.opened"], state: {} },
		criteria:
			"## Error State Handling\n\nEvaluates whether the code properly handles and surfaces errors to the user instead of silently swallowing them.",
	}),
});

export const mockPracticeLongText = mockPracticeOf({
	id: "6",
	slug: "very-long-practice-name-to-test-overflow-in-card-layouts",
	definition: definitionOf({
		name: "Extremely Verbose Practice Name That Tests Text Wrapping and Overflow Behavior in Card Layouts",
		criteria:
			"## Very Long Criteria\n\nThis is a multi-paragraph criteria block designed to test the line-clamp behavior on the card preview.\n\n### Section 1\nLorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n\n### Section 2\nUt enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n\n### Section 3\nDuis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.",
	}),
});

export const mockScriptedPractice = mockPracticeOf({
	id: "4",
	slug: "commit-discipline",
	position: 3,
	definition: definitionOf({
		name: "Commit Discipline",
		criteria:
			"## Commit Discipline\n\nEach commit message must:\n- Start with a type prefix (feat, fix, refactor, etc.)\n- Have a descriptive subject (not just issue numbers)\n- Reference the related issue",
		script: practiceScript(),
	}),
});

export function practiceGroup(overrides: Partial<PracticeGroup> = {}): PracticeGroup {
	return {
		id: "1",
		slug: "review-ready-work",
		name: "Submitting review-ready work",
		description: null,
		icon: null,
		color: null,
		position: 0,
		shownToDevelopers: true,
		feedbackMode: inheritedFeedbackMode(),
		catalogOrigin: OWN_ORIGIN,
		createdAt: new Date("2025-06-01"),
		updatedAt: null,
		...overrides,
	};
}

export const mockGroups: PracticeGroup[] = [
	{
		id: "1",
		slug: "review-ready-work",
		name: "Submitting review-ready work",
		description: "Make each change easy and fast to review.",
		icon: null,
		color: null,
		shownToDevelopers: true,
		position: 1,
		feedbackMode: inheritedFeedbackMode(),
		catalogOrigin: catalogOrigin("review-ready-work"),
		createdAt: new Date("2025-06-01"),
		updatedAt: new Date("2025-06-01"),
	},
	{
		id: "2",
		slug: "actionable-issue-authoring",
		name: "Writing issues a maintainer can act on",
		description: "Give a maintainer enough to start work.",
		icon: null,
		color: null,
		shownToDevelopers: true,
		position: 3,
		feedbackMode: inheritedFeedbackMode(),
		catalogOrigin: OWN_ORIGIN,
		createdAt: new Date("2025-06-01"),
		updatedAt: new Date("2025-06-01"),
	},
];

export const releaseBase = definitionOf({
	name: "Explain the change",
	criteria: "Earlier criteria",
});

export function releaseProposal(
	overrides: Partial<PracticeReleaseProposal> = {},
): PracticeReleaseProposal {
	const base = releaseBase;
	return {
		practiceSlug: "explain-the-change",
		currentVersionId: "801",
		base,
		current: { ...base, criteria: "Our criteria" },
		offered: { ...base, criteria: "New catalog criteria", lineNotes: false },
		offeredDigest: "sha256:offered",
		etag: "reviewed-offer",
		fields: [
			{ field: "CRITERIA", changedLocally: true, offeredChanged: true, conflict: true },
			{ field: "LINE_NOTES", changedLocally: false, offeredChanged: true, conflict: false },
		],
		...overrides,
	};
}
