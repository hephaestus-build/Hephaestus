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
const adrs = await Promise.all(
	files.map(async (file) => {
		const content = await readFile(`${directory}/${file}`, "utf8");
		// The header is everything above the first `## ` section.
		const [header = ""] = content.split(/^## /mu, 1);
		const status = /^\*\*Status:\*\* (?<status>.+)$/mu.exec(header)?.groups?.status ?? "";

		return { file, number: file.slice(0, 4), content, header, status };
	}),
);
const readme = await readFile(`${directory}/README.md`, "utf8");
const indexRows = [
	...readme.matchAll(/^\| \[(?<number>\d{4})\]\((?<file>[^)]+)\) \| .+ \| (?<status>.+) \|$/gmu),
].map(({ groups }) => ({
	number: groups?.number ?? "",
	file: groups?.file ?? "",
	status: groups?.status ?? "",
}));

// The README § Status table is the vocabulary. A superseding link uses the relative file name.
const statusPattern =
	/^(?:Proposed|Accepted|Deprecated|Superseded by \[(?:ADR )?(?<target>\d{4})\]\((?<file>\d{4}-[^)]+\.md)\))$/u;
// ADRs before this number predate the Confirmation section and are corrected when they are next touched.
const firstConfirmedAdr = "0081";

void test("ADR numbers are unique", () => {
	const numbers = files.map((file) => file.slice(0, 4));
	const duplicated = numbers.filter((number, index) => numbers.indexOf(number) !== index);

	assert.deepEqual(duplicated, [], "two ADR files claim the same number");
});

void test("each ADR heading states its own number", () => {
	for (const { file, number, content } of adrs) {
		const [heading = ""] = content.split("\n", 1);

		assert.match(heading, new RegExp(`^# ADR ${number}: \\S`, "u"), file);
	}
});

void test("each ADR status is one value from the README vocabulary", () => {
	for (const { file, status } of adrs) {
		const match = statusPattern.exec(status);

		assert.ok(
			match,
			`${file}: status "${status}" is not Proposed, Accepted, Deprecated or "Superseded by [NNNN](file)"`,
		);
		const target = match.groups?.file;
		if (target !== undefined) {
			assert.ok(files.includes(target), `${file}: superseded by ${target}, which does not exist`);
			assert.equal(
				target.slice(0, 4),
				match.groups?.target,
				`${file}: the link text and file disagree`,
			);
		}
	}
});

void test("each ADR link in a header points to an existing ADR", () => {
	for (const { file, header } of adrs) {
		for (const { groups } of header.matchAll(/\]\((?<target>\d{4}-[^)#]+\.md)(?:#[^)]*)?\)/gu)) {
			const target = groups?.target ?? "";
			assert.ok(
				files.includes(target),
				`${file}: the header links ${target}, which does not exist`,
			);
		}
	}
});

void test("the ADR index lists exactly the ADR files, each once, under its own number", () => {
	assert.deepEqual(
		indexRows.map(({ number, file }) => `${number} ${file}`).toSorted(),
		files.map((file) => `${file.slice(0, 4)} ${file}`),
		"every ADR file needs exactly one index row, and a row's number must match the file it links",
	);
});

void test("the index shows the status of each ADR", () => {
	for (const { file, status } of adrs) {
		const row = indexRows.find((candidate) => candidate.file === file);
		const expected = status.replace(
			/^Superseded by \[ADR (?<target>\d{4})\]/u,
			"Superseded by [$<target>]",
		);

		assert.equal(
			row?.status,
			expected,
			`${file}: the index status differs from the ADR's status line`,
		);
	}
});

void test("an accepted ADR from 0081 names what confirms it", () => {
	for (const { file, number, status, content } of adrs) {
		if (number < firstConfirmedAdr || status !== "Accepted") {
			continue;
		}

		assert.match(content, /^## Confirmation$/mu, `${file}: add a "## Confirmation" section`);
	}
});
