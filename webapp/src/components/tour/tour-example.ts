/**
 * The small examples that the tour's scenes draw where the reader has nothing of their own. Each
 * appears under a caption that says it is an example, and never inside the reader's page. They belong
 * to the tour, not to the stories: a change to a test fixture must not change what the product teaches.
 */

export const EXAMPLE_WORK = {
	number: 42,
	title: "Add caching to the project list",
	description: "Adds caching.",
	commits: [
		{ sha: "a1f3c9e", subject: "wip" },
		{ sha: "4b72d10", subject: "fix" },
		{ sha: "9e05b44", subject: "add cache to the project service" },
	],
	files: [
		{ path: "src/projects/ProjectService.ts", added: 42, removed: 6 },
		{ path: "src/projects/ProjectList.tsx", added: 18, removed: 9 },
		{ path: "src/projects/cache.ts", added: 37, removed: 0 },
		{ path: "src/projects/ProjectService.test.ts", added: 12, removed: 2 },
	],
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
