/**
 * Every docs page and section that the web app links to, by a name that says what the reader gets.
 * A component names an entry (`<DocLink doc="upgradeGuide">`), never a URL. When a docs page moves,
 * its entry here is the one line in the app to change. A reworded heading keeps its ID
 * (`docs/contributor/documentation-guide.md` § File names), so its entry stays.
 * `docs/package.json` runs `scripts/check-doc-targets.ts` after each docs build: a target that the
 * built site does not have fails the build.
 *
 * This file is plain data with no imports, because that check imports it in Node.
 * `@/lib/docs-url` adds the docs site for the build: the latest release, or the next one.
 */

/** A page of the docs site, as its path below the site root, and optionally one heading on it. */
export interface DocTarget {
	readonly path: `${"user" | "admin" | "contributor"}/${string}`;
	// The heading's `id` in the built page. A reworded heading keeps it with an explicit ID, in the
	// compact form that this MDX site builds and lints: `## New words {/*#old-id*/}`.
	readonly anchor?: string;
}

export const DOC_TARGETS = {
	// The public pages of the site.
	contributorGuide: { path: "contributor/overview" },
	userGuide: { path: "user/overview" },

	// Setup steps that the reader finishes outside Hephaestus.
	installationGuide: { path: "admin/kubernetes" },
	githubAppSetup: { path: "admin/github-integration", anchor: "github-app-mode" },
	outlineIntegration: { path: "admin/production-setup", anchor: "outline-integration" },
	slackPermissions: { path: "admin/integrations", anchor: "slack" },
	unreadableCredential: {
		path: "admin/credential-key-rotation",
		anchor: "a-credential-the-running-key-cannot-read",
	},

	// Sections of the two admin consoles.
	practiceScriptGuide: { path: "admin/practice-scripts" },
	upgradeGuide: { path: "admin/upgrade" },
	membersAiChoices: { path: "admin/ai-providers", anchor: "data-handling-and-members-ai-choices" },
	modelSetup: { path: "admin/ai-providers", anchor: "1-the-staged-flow-and-why-it-is-staged" },
	practiceGuideWriting: { path: "admin/writing-practices", anchor: "add-a-visual-and-a-guide" },

	// Errors whose fix the docs give.
	practiceScriptTraceLine: { path: "admin/practice-review-operations", anchor: "the-trace-line" },
} as const satisfies Record<string, DocTarget>;

export type DocKey = keyof typeof DOC_TARGETS;
