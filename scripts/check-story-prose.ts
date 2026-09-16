/**
 * Storybook publishes the comment above `meta` and above each exported story as that component's Docs
 * page, rendering it through `markdown-to-jsx`. A Java-style `<p>` opens a paragraph the renderer has
 * already opened, so each one puts an empty paragraph on the published page — visible only there,
 * which is why this needs a gate at all.
 *
 * A script rather than a Vitest case: scanning the whole tree inside a worker starves the route tests
 * that share it.
 */
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

/** Resolved from this file, so the gate answers the same from the repo root or from webapp/. */
const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const STORIES = path.join(REPO_ROOT, "webapp/src");
const HTML_PARAGRAPH = /<\/?p>/iu;
/** Only comment lines: inside a story's own JSX, `<p>` is an element and correct. */
const COMMENT_LINE = /^\s*(?:\/\/|\/\*|\*)/u;
/** Markdown renders a backticked span as text, so prose *about* the tag is not prose using it. */
const CODE_SPAN = /`[^`]*`/gu;

const entries = await readdir(STORIES, { recursive: true, withFileTypes: true });
const files = entries
	.filter((entry) => entry.isFile() && entry.name.endsWith(".stories.tsx"))
	.map((entry) => path.relative(STORIES, path.join(entry.parentPath, entry.name)));
if (files.length === 0) {
	console.error(`No story files found under ${STORIES} — this check would pass without checking.`);
	process.exit(1);
}

/**
 * Storybook files a story by its path, so two stems that differ only in case or punctuation, or a
 * stem that is also a sibling directory's name, are one sidebar node for two things.
 */
const sidebarKey = (name: string) => name.replaceAll(/[^a-z0-9]/giu, "").toLowerCase();
/**
 * Storybook's auto-title also folds a story into its parent directory when the stem repeats the
 * directory's name, case-insensitively, or is `index` (`autoTitle.ts` → `sanitize`): `Dir/Dir.stories.tsx`
 * is titled `…/Dir`, not `…/Dir/Dir`. That is fine for a directory holding one story file, and a
 * folder and a leaf under one name for a directory holding anything else. A story directly under a
 * root never folds, because the root segment is stripped before the title is built.
 */
const foldsIntoParent = (stem: string, parentPath: string) =>
	path.dirname(parentPath) !== STORIES &&
	(stem.toLowerCase() === path.basename(parentPath).toLowerCase() || /^index$/iu.test(stem));
const collisions: string[] = [];
const nodes = new Map<string, Map<string, string>>();
for (const entry of entries) {
	if (!entry.isDirectory() && !entry.name.endsWith(".stories.tsx")) {
		continue;
	}
	const siblings = nodes.get(entry.parentPath) ?? new Map<string, string>();
	nodes.set(entry.parentPath, siblings);
	const stem = entry.name.replace(/\.stories\.tsx$/u, "");
	const key = sidebarKey(stem);
	const file = path.relative(STORIES, path.join(entry.parentPath, entry.name));
	const location = `webapp/src/${file}`;
	const taken = siblings.get(key);
	if (taken === undefined) {
		siblings.set(key, location);
	} else {
		collisions.push(`${location} shares a sidebar node with ${taken}`);
	}
	if (entry.isFile() && foldsIntoParent(stem, entry.parentPath)) {
		const directory = `${path.relative(STORIES, entry.parentPath)}/`;
		const shadowed = files.find((other) => other !== file && other.startsWith(directory));
		if (shadowed !== undefined) {
			collisions.push(
				`${location} is titled as its directory, which also holds webapp/src/${shadowed}`,
			);
		}
	}
}
if (collisions.length > 0) {
	console.error("Storybook files a story by its path; these would share one sidebar node:\n");
	for (const collision of collisions) {
		console.error(`  ${collision}`);
	}
	process.exit(1);
}

const offenders: string[] = [];
for (const file of files) {
	const source = await readFile(path.join(STORIES, file), "utf8");
	for (const [index, line] of source.split("\n").entries()) {
		if (COMMENT_LINE.test(line) && HTML_PARAGRAPH.test(line.replace(CODE_SPAN, ""))) {
			offenders.push(`webapp/src/${file}:${index + 1}`);
		}
	}
}

if (offenders.length > 0) {
	console.error("Story prose renders as Markdown, where <p> emits a stray empty paragraph.");
	console.error("Separate paragraphs with a blank comment line instead:\n");
	for (const offender of offenders) {
		console.error(`  ${offender}`);
	}
	process.exit(1);
}

console.log(
	`check-story-prose: ${files.length} story files, no HTML paragraphs in published prose.`,
);
