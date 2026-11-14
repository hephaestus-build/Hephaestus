import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
	CHANGELOG_HEADINGS,
	MIGRATION_HEADINGS,
	foldUnpublished,
	releaseHistoryProblems,
} from "./release-headings.ts";

const published = new Set(["0.95.1", "1.0.0-rc.1", "1.0.0-rc.3"]);

const changelog = `# Changelog

## 1.0.0-rc.4

### Patch Changes

- Fix four.

## 1.0.0-rc.2

### Minor Changes

- Add two.

### Patch Changes

- Fix two.

## 1.0.0-rc.1

### Major Changes

- One.

## 0.94.0

- Old history below the baseline stays as written.
`;

void test("a burned number folds into the release that shipped it, group by group", () => {
	// rc.2 failed after its Version PR merged; rc.4 is the next cut.
	const candidates = new Set(["1.0.0-rc.1"]);
	const folded = foldUnpublished(changelog, CHANGELOG_HEADINGS, candidates, "1.0.0-rc.4");
	assert.equal(
		folded,
		changelog.replace(
			"## 1.0.0-rc.4\n\n### Patch Changes\n\n- Fix four.\n\n## 1.0.0-rc.2\n\n### Minor Changes\n\n- Add two.\n\n### Patch Changes\n\n- Fix two.\n\n",
			"## 1.0.0-rc.4\n\n### Minor Changes\n\n- Add two.\n\n### Patch Changes\n\n- Fix four.\n\n- Fix two.\n\n",
		),
	);
	assert.equal(foldUnpublished(folded, CHANGELOG_HEADINGS, candidates, "1.0.0-rc.4"), folded);
	assert.deepEqual(releaseHistoryProblems(folded, "", candidates, "1.0.0-rc.4"), []);
});

void test("burned numbers below a published release take that release's name when it has no section", () => {
	const migration =
		"### Next release\n\n### v1.0.0-rc.2\n\n#### 🔴 Two\n\n```md\n### v1.0.0-rc.0\n```\n\n### v1.0.0-rc.1\n\n#### 🔴 One\n\n## Automatic vs Manual Migrations\n";
	assert.equal(
		foldUnpublished(migration, MIGRATION_HEADINGS, published, "1.0.0-rc.4"),
		migration.replace("### v1.0.0-rc.2", "### v1.0.0-rc.3"),
	);
});

void test("the cut refuses a heading that no published release carries, and a published release without one", () => {
	assert.deepEqual(
		releaseHistoryProblems(changelog, "### v1.0.0-rc.2\n", published, "1.0.0-rc.4"),
		[
			'CHANGELOG.md has "## 1.0.0-rc.2", which no published release carries',
			'MIGRATION.md has "### v1.0.0-rc.2", which no published release carries',
			'CHANGELOG.md has no "## 1.0.0-rc.3" section for a published release',
		],
	);
});

const contentLines = (markdown: string) =>
	markdown
		.split("\n")
		.filter((line) => line.trim() !== "" && !line.startsWith("#"))
		.toSorted();

void test("the repository's release history folds to published releases only, without losing a line", () => {
	const tags = new Set([...published, "1.0.0-rc.4", "1.0.0-rc.5", "1.0.0-rc.8"]);
	for (const [file, style] of [
		["CHANGELOG.md", CHANGELOG_HEADINGS],
		["MIGRATION.md", MIGRATION_HEADINGS],
	] as const) {
		const text = readFileSync(new URL(`../../${file}`, import.meta.url), "utf8");
		const folded = foldUnpublished(text, style, tags, "1.0.0-rc.9");
		assert.deepEqual(contentLines(folded), contentLines(text), file);
	}
});
