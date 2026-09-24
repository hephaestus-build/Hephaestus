import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { test } from "node:test";

const directory = "docs/decisions";

// The template is the shape an ADR is copied from, not a decision: its `NNNN` is a placeholder, so
// it is neither numbered nor listed in the index.
const entries = await readdir(directory);
const files = entries
	.filter((entry) => /^\d{4}-.+\.md$/u.test(entry) && entry !== "0000-template.md")
	.toSorted();

void test("ADR numbers are unique", () => {
	const numbers = files.map((file) => file.slice(0, 4));
	const duplicated = numbers.filter((number, index) => numbers.indexOf(number) !== index);

	assert.deepEqual(duplicated, [], "two ADR files claim the same number");
});

void test("each ADR heading states its own number", async () => {
	for (const file of files) {
		const content = await readFile(`${directory}/${file}`, "utf8");
		const [heading = ""] = content.split("\n", 1);

		assert.match(heading, new RegExp(`^# ADR ${file.slice(0, 4)}: \\S`, "u"), file);
	}
});

void test("the ADR index lists exactly the ADR files, each under its own number", async () => {
	const index = await readFile(`${directory}/README.md`, "utf8");
	const rows = [...index.matchAll(/^\| \[\d{4}\]\(\d{4}-[^)]+\.md\)/gmu)].map(([row]) => row);

	assert.deepEqual(
		new Set(rows),
		new Set(files.map((file) => `| [${file.slice(0, 4)}](${file})`)),
		"every ADR file needs one index row, and a row's number must match the file it links",
	);
});
