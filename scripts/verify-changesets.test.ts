import assert from "node:assert/strict";
import test from "node:test";

import {
	verifyChangesets,
	verifyChangesetMigration,
	verifyMigrationFragment,
} from "./verify-changesets.ts";

const status = (releases: unknown[], summary: string) => ({
	changesets: [{ id: "note", releases, summary }],
});
const rootRelease = (type = "patch") => [{ name: "hephaestus", type }];

void test("accepts root releases and explained opt-outs", () => {
	assert.doesNotThrow(() =>
		verifyChangesets(status(rootRelease(), "Fixes sign-in."), [".changeset/note.md"]),
	);
	assert.doesNotThrow(() =>
		verifyChangesets(status([], "No release note: CI only."), [".changeset/note.md"]),
	);
	assert.doesNotThrow(() =>
		verifyChangesets(
			status(rootRelease("major"), "Stable API. **Operators:** follow the migration guide."),
			[".changeset/note.md"],
			false,
		),
	);
});

void test("rejects blank notes, non-root releases, and pre-1.0 majors", () => {
	assert.throws(() => verifyChangesets(status([], ""), [".changeset/note.md"]), /must explain/u);
	assert.throws(
		() =>
			verifyChangesets(status([{ name: "webapp", type: "patch" }], "Fix."), [".changeset/note.md"]),
		/must release only/u,
	);
	assert.throws(
		() =>
			verifyChangesets(status([...rootRelease(), ...rootRelease()], "Fix."), [
				".changeset/note.md",
			]),
		/must release only/u,
	);
	assert.throws(
		() => verifyChangesets(status(rootRelease("major"), "Breaks API."), [".changeset/note.md"]),
		/pre-1.0 major/u,
	);
	assert.throws(
		() =>
			verifyChangesets(status(rootRelease("major"), "Breaks API."), [".changeset/note.md"], false),
		/must contain \*\*Operators:/u,
	);
});

void test("rejects files Changesets did not parse and release-note trailers", () => {
	assert.throws(
		() => verifyChangesets({ changesets: [] }, [".changeset/note.md"]),
		/did not parse/u,
	);
	assert.throws(
		() =>
			verifyChangesets(status(rootRelease(), "Fix.\n\nCo-authored-by: Bot"), [
				".changeset/note.md",
			]),
		/must not contain Co-authored-by/u,
	);
});

void test("migration fragments require an operator marker and one entry", () => {
	assert.doesNotThrow(() =>
		verifyMigrationFragment(
			".migration/note.md",
			"Change. **Operators:** act before upgrading.",
			"#### 🔴 Do the thing\n\n**Migration**: act.\n",
		),
	);
	assert.doesNotThrow(() =>
		verifyMigrationFragment(
			".migration/note.md",
			"**Operators:** act.",
			"#### 🔴 Pin the image\n\n```bash\n# either: remove the line entirely\n#### not a heading here\nVERSION=1\n```\n",
		),
	);
	assert.throws(
		() => verifyMigrationFragment(".migration/note.md", "Change.", "#### 🔴 Act\n"),
		/must contain \*\*Operators:/u,
	);
	assert.throws(
		() =>
			verifyMigrationFragment(
				".migration/note.md",
				"**Operators:** act.",
				"### Next release\n\n#### 🔴 Act\n",
			),
		/no level 1-3 headings/u,
	);
	assert.throws(
		() =>
			verifyMigrationFragment(
				".migration/note.md",
				"**Operators:** act.",
				"#### 🔴 First\n\n#### 🔴 Second\n",
			),
		/exactly one/u,
	);
	assert.throws(
		() => verifyChangesetMigration(".changeset/note.md", "**Operators:** act.", undefined),
		/requires \.migration\/note\.md/u,
	);
	assert.throws(
		() => verifyChangesetMigration(".changeset/note.md", "No action.", "#### 🔴 Act\n"),
		/must contain \*\*Operators:/u,
	);
	assert.doesNotThrow(() =>
		verifyChangesetMigration(".changeset/note.md", "No action.", undefined),
	);
});
