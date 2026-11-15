import { PackageIcon } from "lucide-react";

import type { ItemRef, WorkspaceSplit } from "@/api/types.gen";
import { text } from "@/components/practice-vocabulary/feedback-text";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { ITEM_KIND, type ItemProvider } from "@/lib/item-kinds";

/**
 * What the tour shows where the reader has nothing of their own. Everything here appears under a
 * caption that says it is an example, and never inside the reader's page. It belongs to the tour, not
 * to the stories: a change to a test fixture must not change what the product teaches.
 */

const DAY_MS = 86_400_000;

/** How the provider names a piece of work: #17 on GitHub, !17 on GitLab. */
export function exampleWorkLabel(number: number, provider: ItemProvider | undefined): string {
	return `${provider === "GITLAB" ? "!" : "#"}${number}`;
}

function exampleWork(number: number, provider: ItemProvider | undefined): ItemRef {
	return {
		id: String(1000 + number),
		kind: ITEM_KIND.pullRequest,
		provider: provider ?? "GITHUB",
		label: exampleWorkLabel(number, provider),
		url: "",
		container: "example/web-shop",
	};
}

/**
 * One open card about a made-up practice and made-up work. Takes the time from its caller, so the
 * dates stay recent without a clock read during render.
 */
export function exampleCard(now: Date, provider?: ItemProvider): PracticeFeedbackCardEntry {
	const daysAgo = (days: number) => new Date(now.getTime() - days * DAY_MS);
	return {
		feedbackId: "tour-example",
		practiceSlug: "tour-example-describe-why",
		practiceName: "Describe what changed and why",
		group: {
			slug: "tour-example-packaging",
			name: "Packaging work for review",
			color: "sky",
			icon: PackageIcon,
		},
		headline: "Descriptions say what changed, not why",
		body: `The description of ${exampleWorkLabel(17, provider)} says only what was added, and ${exampleWorkLabel(19, provider)} has no description. A reviewer has to guess which problem each change solves.`,
		reviewedWork: [
			{ ref: exampleWork(17, provider), date: daysAgo(9), outcome: "NOT_MET" },
			{ ref: exampleWork(19, provider), date: daysAgo(4), outcome: "NOT_MET" },
		],
		cleanWork: [{ ref: exampleWork(21, provider), date: daysAgo(1) }],
		cleanNeeded: 3,
		nextStep:
			"Start your next description with one sentence on the problem that the change solves.",
		condition: [text("Resolves when three pieces of work in a row come back clean.")],
		state: "open",
		timestamp: daysAgo(3),
		preparedAt: daysAgo(3),
	};
}

export const EXAMPLE_WORK = {
	number: 42,
	title: "Add caching to the project list",
	description: "Adds caching.",
	commits: ["wip", "fix", "add cache to the project service"],
	files: 4,
};

/** A few practices, for a workspace that has chosen none yet. */
export const EXAMPLE_PRACTICES = [
	"Describe what changed and why",
	"Keep the diff reviewable in one sitting",
	"Write commit subjects a reviewer can follow",
	"Test the behaviour you changed",
];

export const EXAMPLE_GROUPS = [
	{
		name: "Packaging work for review",
		practices: [
			"Describe what changed and why",
			"Keep the diff reviewable in one sitting",
			"Write commit subjects a reviewer can follow",
		],
	},
	{ name: "Testing your changes", practices: ["Test the behaviour you changed"] },
];

export const EXAMPLE_OBSERVATIONS = [
	{
		outcome: "NOT_MET",
		practice: "Describe what changed and why",
		evidence: "The description says “Adds caching.”: what changed, not why it was needed.",
	},
	{
		outcome: "NOT_MET",
		practice: "Write commit subjects a reviewer can follow",
		evidence: "Two of three commit subjects say only “wip” or “fix”.",
	},
	{
		outcome: "MET",
		practice: "Keep the diff reviewable in one sitting",
		evidence: "4 files and 120 changed lines about one concern.",
	},
] as const;

/** Counts only, with no reader in them: a split that counted the reader would state their standing. */
export const EXAMPLE_SPLIT: WorkspaceSplit = {
	developers: 12,
	noneYet: 2,
	parts: [
		{ standing: "DEVELOPING", developers: 2 },
		{ standing: "MIXED", developers: 3 },
		{ standing: "STRENGTH", developers: 5 },
	],
};
