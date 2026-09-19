import type {
	AutonomyAssignment,
	Practice,
	PracticeGroup,
	PracticeReviewSettings,
	ReviewBackfillRun,
	ReviewSweepSchedule,
} from "@/api/types.gen";
import type { PracticeAutonomy } from "@/lib/practice-autonomy";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockMergeBinding,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";

export function mockReviewSettings(
	overrides: Partial<PracticeReviewSettings> = {},
): PracticeReviewSettings {
	return {
		etag: '"0"',
		revision: 0,
		cooldownMinutes: 30,
		defaultAutonomy: "HUMAN_APPROVAL",
		deliverToMerged: true,
		deliveryStatus: "ACTIVE",
		reviewScope: {
			repositoryMode: "ALL_MONITORED",
			personMode: "ALL_ELIGIBLE",
			repositories: [],
			personUserIds: [],
		},
		coverageSummary: {
			monitoredRepositories: 3,
			coveredRepositories: 3,
			eligiblePeople: 8,
			coveredPeople: 8,
			recentReviewVolume: 42,
			estimateWindowDays: 30,
		},
		...overrides,
	};
}

/**
 * Real `Date`s, not ISO strings: these go straight to a prop rather than through MSW, and a `Date`
 * is what the generated client's response transformer hands a screen at runtime.
 */
export function sweepSchedule(overrides: Partial<ReviewSweepSchedule> = {}): ReviewSweepSchedule {
	return {
		id: "22222222-2222-2222-2222-222222222222",
		artifactKind: "scm.pull_request",
		cadence: "DAILY",
		lookbackDays: 2,
		enabled: true,
		nextRunAt: new Date("2026-08-10T02:17:00Z"),
		lastRunAt: new Date("2026-08-09T02:17:00Z"),
		createdByAccountId: 7,
		createdAt: new Date("2026-08-01T09:00:00Z"),
		...overrides,
	};
}

export function backfillRun(overrides: Partial<ReviewBackfillRun> = {}): ReviewBackfillRun {
	return {
		id: "11111111-1111-1111-1111-111111111111",
		artifactKind: "scm.pull_request",
		fromAt: new Date("2026-07-08T00:00:00Z"),
		toAt: new Date("2026-08-07T00:00:00Z"),
		status: "AWAITING_CONFIRMATION",
		discoveredVia: "BACKFILL",
		estimatedArtifacts: 128,
		estimatedCostUsd: 15.36,
		submittedCount: 0,
		passedCount: 0,
		failedCount: 0,
		requestedByAccountId: 7,
		createdAt: new Date("2026-08-07T09:00:00Z"),
		...overrides,
	};
}

export function inheritedAutonomy(effective: PracticeAutonomy = "AUTOMATIC"): AutonomyAssignment {
	return { effective, source: "WORKSPACE", inherited: true };
}

export function groupAutonomy(effective: PracticeAutonomy): AutonomyAssignment {
	return { effective, source: "GROUP", inherited: true };
}

export function chosenAutonomy(effective: PracticeAutonomy): AutonomyAssignment {
	return { effective, override: effective, source: "PRACTICE", inherited: false };
}

export const mockPractice: Practice = {
	id: 1,
	slug: "pr-description-quality",
	name: "PR Description Quality",
	bindings: [mockPullRequestBinding],
	criteria:
		"## The standard\n\nEvaluate whether the pull request description provides sufficient context, motivation, and testing steps.\n\n### Required elements\n- Summary of changes\n- Motivation / why\n- Testing steps\n- Link to issue",
	whyItMatters:
		"A clear description lets reviewers understand intent without reverse-engineering the diff, speeding up review and reducing back-and-forth.",
	whatGoodLooksLike:
		"A PR opens with a one-paragraph summary, links the issue, and lists the exact steps a reviewer ran to verify it.",
	artifactKind: "scm.pull_request",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	displayOrder: 0,
	autonomy: inheritedAutonomy(),
	createdAt: new Date("2025-06-01"),
	updatedAt: new Date("2025-06-15"),
};

export const mockPractices: Practice[] = [
	mockPractice,
	{
		id: 2,
		slug: "code-review-thoroughness",
		name: "Code Review Thoroughness",
		bindings: [
			{
				signals: ["scm.pull_request.reviewed"],
				needs: mockPullRequestBinding.needs,
			},
		],
		criteria:
			"## Code Review Thoroughness\n\nEvaluate depth and quality of code reviews. Reviewers should engage with logic and design, not just style.",
		artifactKind: "scm.pull_request",
		automatedReviewPolicy: mockPullRequestPolicy,
		automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
		displayOrder: 0,
		autonomy: inheritedAutonomy(),
		createdAt: new Date("2025-06-02"),
		updatedAt: new Date("2025-06-14"),
	},
	{
		id: 3,
		slug: "test-coverage",
		name: "Test Coverage",
		bindings: [
			{
				signals: ["scm.pull_request.opened", "scm.pull_request.synchronized"],
				needs: mockPullRequestBinding.needs,
			},
		],
		criteria:
			"## Test Coverage\n\nChecks that new code includes appropriate test coverage. Critical paths and edge cases should be tested.",
		artifactKind: "scm.pull_request",
		automatedReviewPolicy: mockPullRequestPolicy,
		automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
		displayOrder: 0,
		autonomy: chosenAutonomy("OFF"),
		createdAt: new Date("2025-06-03"),
		updatedAt: new Date("2025-06-10"),
	},
];

export const mockUnassignedPractice: Practice = {
	id: 5,
	slug: "error-state-handling",
	name: "Error State Handling",
	bindings: [
		{ signals: ["scm.pull_request.opened"], needs: mockPullRequestBinding.needs, onDrafts: true },
	],
	criteria:
		"## Error State Handling\n\nEvaluates whether the code properly handles and surfaces errors to the user instead of silently swallowing them.",
	artifactKind: "scm.pull_request",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	displayOrder: 0,
	autonomy: inheritedAutonomy(),
	createdAt: new Date("2025-06-05"),
	updatedAt: new Date("2025-06-17"),
};

export const mockPracticeLongText: Practice = {
	id: 6,
	slug: "very-long-practice-name-to-test-overflow-in-card-layouts",
	name: "Extremely Verbose Practice Name That Tests Text Wrapping and Overflow Behavior in Card Layouts",
	bindings: [mockPullRequestBinding, mockMergeBinding],
	criteria:
		"## Very Long Criteria\n\nThis is a multi-paragraph criteria block designed to test the line-clamp behavior on the card preview.\n\n### Section 1\nLorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n\n### Section 2\nUt enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n\n### Section 3\nDuis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.",
	artifactKind: "scm.pull_request",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	displayOrder: 0,
	autonomy: inheritedAutonomy(),
	createdAt: new Date("2025-06-06"),
	updatedAt: new Date("2025-06-18"),
};

export const mockPracticeWithAllTriggers: Practice = {
	id: 4,
	slug: "commit-discipline",
	name: "Commit Discipline",
	bindings: [mockPullRequestBinding, mockMergeBinding],
	criteria:
		"## Commit Discipline\n\nEach commit message must:\n- Start with a type prefix (feat, fix, refactor, etc.)\n- Have a descriptive subject (not just issue numbers)\n- Reference the related issue\n\n### Anti-patterns to Flag\n- `fixes #123` with no description\n- Branch-slug-format titles like `feature/ABC-123`\n- Single-word messages like `update` or `fix`",
	precomputeScript: [
		'import { readDiff } from "@/components/admin/lib/diff";',
		'import { parseDiffFiles } from "@/components/admin/lib/parse";',
		"",
		"const diff = await readDiff();",
		"const files = parseDiffFiles(diff);",
		"const findings: string[] = [];",
		"",
		"for (const file of files) {",
		'  if (file.path.includes("commit")) {',
		'    findings.push("Changed: " + file.path);',
		"  }",
		"}",
		"",
		"export default { findings };",
	].join("\n"),
	artifactKind: "scm.pull_request",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	displayOrder: 0,
	autonomy: inheritedAutonomy(),
	createdAt: new Date("2025-06-04"),
	updatedAt: new Date("2025-06-16"),
};

export const mockGroups: PracticeGroup[] = [
	{
		id: 1,
		slug: "review-ready-work",
		name: "Submitting review-ready work",
		description: "Make each change easy and fast to review.",
		visibleInPracticeDashboards: true,
		displayOrder: 1,
		autonomy: inheritedAutonomy(),
		createdAt: new Date("2025-06-01"),
		updatedAt: new Date("2025-06-01"),
	},
	{
		id: 2,
		slug: "actionable-issue-authoring",
		name: "Writing issues a maintainer can act on",
		description: "Give a maintainer enough to start work.",
		visibleInPracticeDashboards: true,
		displayOrder: 3,
		autonomy: inheritedAutonomy(),
		createdAt: new Date("2025-06-01"),
		updatedAt: new Date("2025-06-01"),
	},
];
