/**
 * Every docs page and section that the web app links to, by a name that says what the reader gets.
 * A component names an entry (`<DocLink doc="upgradeGuide">`), never a URL, so a renamed page or
 * heading changes one line here. `docs/package.json` runs `scripts/check-doc-targets.ts` after
 * each docs build: a target that the built site does not have fails the build.
 *
 * This file is plain data with no imports, because that check imports it in Node.
 * `@/lib/docs-url` adds the docs site for the build: the latest release, or the next one.
 */

/** A page of the docs site, as its path below the site root, and optionally one heading on it. */
export interface DocTarget {
	readonly path: `${"user" | "admin" | "contributor"}/${string}`;
	/** The heading's `id` in the built page: its Docusaurus slug, or its explicit `{#id}`. */
	readonly anchor?: string;
}

export const DOC_TARGETS = {
	// The public pages of the site.
	contributorGuide: { path: "contributor/overview" },
	userGuide: { path: "user/overview" },

	// Setup steps that the reader finishes outside Hephaestus.
	installationGuide: { path: "admin/kubernetes" },

	// Sections of the two admin consoles.
	practiceScriptGuide: { path: "admin/practice-scripts" },
	upgradeGuide: { path: "admin/upgrade" },

	// Errors whose fix the docs give.
	practiceScriptTraceLine: { path: "admin/practice-review-operations", anchor: "the-trace-line" },
} as const satisfies Record<string, DocTarget>;

export type DocKey = keyof typeof DOC_TARGETS;
