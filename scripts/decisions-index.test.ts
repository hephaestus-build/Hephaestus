import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { test } from "node:test";

import { indexTable, isDecisionFile, readDecisions } from "./decisions-index.ts";

const directory = "docs/decisions";

const entries = await readdir(directory);
const files = entries.filter((entry) => isDecisionFile(entry)).toSorted();
const adrs = await Promise.all(
	files.map(async (file) => {
		const content = await readFile(`${directory}/${file}`, "utf8");
		// The header is everything above the first `## ` section.
		const [header = ""] = content.split(/^## /mu, 1);
		const status = /^\*\*Status:\*\* (?<status>.+)$/mu.exec(header)?.groups?.status ?? "";

		return { file, number: file.slice(0, 4), content, header, status };
	}),
);
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

void test("an accepted ADR from 0081 names what confirms it", () => {
	for (const { file, number, status, content } of adrs) {
		if (number < firstConfirmedAdr || status !== "Accepted") {
			continue;
		}

		assert.match(content, /^## Confirmation$/mu, `${file}: add a "## Confirmation" section`);
	}
});

void test("the printed index lists each ADR once, with the title and status of its own file", async () => {
	const decisions = await readDecisions(directory);
	assert.deepEqual(
		decisions.map(({ file }) => file),
		files,
	);
	for (const { file, title, status } of decisions) {
		assert.notEqual(title, "", `${file}: no "# ADR NNNN: title" heading`);
		assert.equal(status, adrs.find((adr) => adr.file === file)?.status, file);
	}
	const rows = indexTable(decisions).split("\n").slice(2);
	assert.equal(rows.length, files.length);
	assert.equal(
		rows[0],
		`| [${decisions[0]?.number ?? ""}](${decisions[0]?.file ?? ""}) | ${decisions[0]?.title ?? ""} | ${decisions[0]?.status ?? ""} |`,
	);
});

void test("the README keeps no hand-written index row, which every new ADR would conflict on", async () => {
	const readme = await readFile(`${directory}/README.md`, "utf8");
	assert.doesNotMatch(readme, /^\| \[\d{4}\]\(\d{4}-/mu);
});
