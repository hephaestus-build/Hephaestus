import assert from "node:assert/strict";
import { test } from "node:test";

import {
	CHANGELOG_HEADINGS,
	MIGRATION_HEADINGS,
	foldUnpublished,
	releaseHistoryProblems,
} from "./release-headings.ts";

// rc.2 failed after its Version PR merged; rc.3 shipped its changes; rc.4 is being cut.
const published = new Set(["0.95.1", "1.0.0-rc.1", "1.0.0-rc.3"]);

const changelog = `# Changelog

## 1.0.0-rc.4

### Patch Changes

- Fix four.

## 1.0.0-rc.3

### Patch Changes

- Fix three.

## 1.0.0-rc.2

### Minor Changes

- Add two.

### Patch Changes

- Fix two.
- Fix two again.

## 1.0.0-rc.1

### Major Changes

- One.

## 0.94.0

- Old history below the baseline stays as written.
`;

const folded = changelog.replace(
	"## 1.0.0-rc.3\n\n### Patch Changes\n\n- Fix three.\n\n## 1.0.0-rc.2\n\n### Minor Changes\n\n- Add two.\n\n### Patch Changes\n\n- Fix two.\n- Fix two again.\n\n",
	"## 1.0.0-rc.3\n\n### Minor Changes\n\n- Add two.\n\n### Patch Changes\n\n- Fix three.\n- Fix two.\n- Fix two again.\n\n",
);

const contentLines = (markdown: string) =>
	markdown
		.split("\n")
		.filter((line) => line.trim() !== "" && !line.startsWith("#"))
		.toSorted();

void test("a burned number folds into the published release that shipped it, group by group", () => {
	const result = foldUnpublished(changelog, CHANGELOG_HEADINGS, published);
	assert.equal(result, folded);
	assert.deepEqual(contentLines(result), contentLines(changelog));
	assert.equal(foldUnpublished(result, CHANGELOG_HEADINGS, published), result);
	assert.deepEqual(releaseHistoryProblems(result, "", published, "1.0.0-rc.4"), []);
});

void test("a version without a later published release keeps its section: its cut can still be running", () => {
	const running = new Set(["0.95.1", "1.0.0-rc.1"]);
	assert.equal(foldUnpublished(changelog, CHANGELOG_HEADINGS, running), changelog);
});

void test("burned numbers take the name of the published release when it has no section", () => {
	const migration =
		"### Next release\n\n### v1.0.0-rc.2\n\n#### 🔴 Two\n\n````md\n```js\n### v1.0.0-rc.2\n```\n````\n\n### v1.0.0-rc.1\n\n#### 🔴 One\n\n## Automatic vs Manual Migrations\n";
	assert.equal(
		foldUnpublished(migration, MIGRATION_HEADINGS, published),
		migration.replace("### Next release\n\n### v1.0.0-rc.2", "### Next release\n\n### v1.0.0-rc.3"),
	);
});

void test("the cut refuses a burned heading, and a published release without its section", () => {
	assert.deepEqual(
		releaseHistoryProblems(
			changelog.replace("## 1.0.0-rc.3\n\n### Patch Changes\n\n- Fix three.\n\n", ""),
			"### v1.0.0-rc.2\n",
			published,
			"1.0.0-rc.4",
		),
		[
			'CHANGELOG.md has "## 1.0.0-rc.2", which no release carries',
			'MIGRATION.md has "### v1.0.0-rc.2", which no release carries',
			'CHANGELOG.md has no "## 1.0.0-rc.3" section for a published release',
		],
	);
});
