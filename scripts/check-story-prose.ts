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
import { join, relative, resolve } from "node:path";

/** Resolved from this file, so the gate answers the same from the repo root or from webapp/. */
const REPO_ROOT = resolve(import.meta.dirname, "..");
const STORIES = join(REPO_ROOT, "webapp/src");
const HTML_PARAGRAPH = /<\/?p>/i;
/** Only comment lines: inside a story's own JSX, `<p>` is an element and correct. */
const COMMENT_LINE = /^\s*(\/\/|\/\*|\*)/;
/** Markdown renders a backticked span as text, so prose *about* the tag is not prose using it. */
const CODE_SPAN = /`[^`]*`/g;

const entries = await readdir(STORIES, { recursive: true, withFileTypes: true });
const files = entries
	.filter((entry) => entry.isFile() && entry.name.endsWith(".stories.tsx"))
	.map((entry) => relative(STORIES, join(entry.parentPath, entry.name)));
if (files.length === 0) {
	console.error(`No story files found under ${STORIES} — this check would pass without checking.`);
	process.exit(1);
}

/**
 * Storybook files a story by its path, so two stems that differ only in case or punctuation, or a
 * stem that is also a sibling directory's name, are one sidebar node for two things.
 */
const sidebarKey = (name: string) => name.replaceAll(/[^a-z0-9]/giu, "").toLowerCase();
const collisions: string[] = [];
const nodes = new Map<string, Map<string, string>>();
for (const entry of entries) {
	if (!entry.isDirectory() && !entry.name.endsWith(".stories.tsx")) continue;
	const siblings = nodes.get(entry.parentPath) ?? new Map<string, string>();
	nodes.set(entry.parentPath, siblings);
	const key = sidebarKey(entry.name.replace(/\.stories\.tsx$/u, ""));
	const path = relative(REPO_ROOT, join(entry.parentPath, entry.name));
	const taken = siblings.get(key);
	if (taken === undefined) siblings.set(key, path);
	else collisions.push(`${path} shares a sidebar node with ${taken}`);
}
if (collisions.length > 0) {
	console.error("Storybook files a story by its path; these would share one sidebar node:\n");
	for (const collision of collisions) console.error(`  ${collision}`);
	process.exit(1);
}

const offenders: string[] = [];
for (const file of files) {
	const path = join(STORIES, file);
	const lines = (await readFile(path, "utf8")).split("\n");
	lines.forEach((line, index) => {
		if (COMMENT_LINE.test(line) && HTML_PARAGRAPH.test(line.replace(CODE_SPAN, ""))) {
			offenders.push(`webapp/src/${file}:${index + 1}`);
		}
	});
}

if (offenders.length > 0) {
	console.error("Story prose renders as Markdown, where <p> emits a stray empty paragraph.");
	console.error("Separate paragraphs with a blank comment line instead:\n");
	for (const offender of offenders) console.error(`  ${offender}`);
	process.exit(1);
}

console.log(
	`check-story-prose: ${files.length} story files, no HTML paragraphs in published prose.`,
);
