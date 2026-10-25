// Synthetic samples for the Practice profile's pattern label and folds: the stories and tests render
// them from these. Nothing here comes from a workspace; every name, number and piece of work is made up, so a
// story can never show a real developer's feedback.

import type { ItemRef } from "@/api/types.gen";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";

import {
	type PracticePattern,
	practicePatternOf,
} from "@/components/practice-profile/practice-pattern";

const HOUR = 3_600_000;

const pullRequest = (number: number, title: string): ItemRef => ({
	kind: "scm.pull_request",
	id: String(9000 + number),
	parentId: null,
	label: `#${number}`,
	title,
	provider: "GITHUB",
	url: null,
	container: null,
	path: null,
	removedAt: null,
});

const DESCRIBE = { slug: "describe-what-and-why", name: "Describe what changed and why" };
const CRITERIA = {
	slug: "honours-linked-issue-acceptance-criteria",
	name: "Say which acceptance criteria are done",
};
const PACKAGING = { slug: "review-ready-work", name: "Packaging work for review", color: "sky" };

function card(
	now: number,
	feedbackId: string,
	practice: { slug: string; name: string },
	hoursAgo: number,
	headline: string,
	work: ItemRef[],
	clean: ItemRef[] = [],
): PracticeFeedbackCardEntry {
	return {
		feedbackId,
		practiceSlug: practice.slug,
		practiceName: practice.name,
		group: { slug: PACKAGING.slug, name: PACKAGING.name, color: PACKAGING.color },
		headline,
		body: "Sample text.",
		reviewedWork: work.map((ref, index) => ({
			ref,
			date: new Date(now - (hoursAgo + 24 * (work.length - index)) * HOUR),
			outcome: "NOT_MET" as const,
		})),
		cleanWork: clean.map((ref, index) => ({
			ref,
			date: new Date(now - (hoursAgo - 2 * (index + 1)) * HOUR),
		})),
		cleanNeeded: 3,
		nextStep: "Sample next step.",
		condition: [],
		state: "new",
		timestamp: new Date(now - hoursAgo * HOUR),
		preparedAt: new Date(now - hoursAgo * HOUR),
	};
}

/** Two open cards, each resting on two or more pull requests; the second has one clean since. */
export function sampleOpenCards(now: number): PracticeFeedbackCardEntry[] {
	return [
		card(
			now,
			"sample-1",
			CRITERIA,
			2,
			"Merge requests close their issue without naming the criteria they meet",
			[pullRequest(12, "Add the login form"), pullRequest(14, "Show the course list")],
		),
		card(
			now,
			"sample-2",
			DESCRIBE,
			26,
			"Descriptions list the files, not the reason for the change",
			[
				pullRequest(9, "Fix the build"),
				pullRequest(12, "Add the login form"),
				pullRequest(15, "Rename the service"),
			],
			[pullRequest(16, "Cache the course list")],
		),
	];
}

const SAMPLE_NOW = Date.UTC(2026, 9, 8, 12);

function samplePattern(practiceSlug: string): PracticePattern {
	const pattern = practicePatternOf(sampleOpenCards(SAMPLE_NOW), practiceSlug);
	if (pattern === undefined) {
		throw new Error(`The samples carry an open card for ${practiceSlug}`);
	}
	return pattern;
}

/** The pattern of the description practice, as its card has it: three pull requests, one clean since. */
export const SAMPLE_PATTERN: PracticePattern = samplePattern(DESCRIBE.slug);

/** The pattern of the criteria practice, before any clean work came in: two pull requests. */
export const SAMPLE_PATTERN_NONE_CLEAN: PracticePattern = samplePattern(CRITERIA.slug);

/** Practices that never applied to the sample work. */
export const SAMPLE_NEVER_APPLIED = [
	{ slug: "write-api-docs", name: "Document the public API" },
	{ slug: "wiki-pages", name: "Keep the wiki page current" },
	{ slug: "platform-logger", name: "Log through the platform logger" },
];

export const SAMPLE_NOT_OBSERVED = [
	{ slug: "names-the-risk", name: "Name the risk a change carries" },
	{ slug: "rollback-plan", name: "Say how to roll the change back" },
];
