import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
	existsSync,
	lstatSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { type TestContext } from "node:test";

import {
	recordProgramFailure,
	takeProgramFailure,
	UNREADABLE_PROGRAM_FAILURE,
} from "../../../main/resources/agent/gateway-capabilities.ts";

function scratch(t: TestContext): string {
	const directory = mkdtempSync(path.join(tmpdir(), "program-failure-"));
	t.after(() => {
		rmSync(directory, { recursive: true, force: true });
	});
	return directory;
}

void test("a review program's crash reaches the review kind once, as closed codes only", (t) => {
	const directory = mkdtempSync(path.join(tmpdir(), "program-failure-"));
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	const file = path.join(directory, "failure");

	recordProgramFailure("init", new TypeError("a path or a token the server must not see"), file);

	assert.equal(takeProgramFailure(file), "init:TypeError");
	assert.equal(takeProgramFailure(file), undefined);
});

void test("a recorded line that is not closed codes is dropped", (t) => {
	const directory = mkdtempSync(path.join(tmpdir(), "program-failure-"));
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	const file = path.join(directory, "failure");
	writeFileSync(file, "run:<b>secret</b>");

	assert.equal(takeProgramFailure(file), undefined);
	assert.equal(takeProgramFailure(file), undefined);
});

// A blocked open would hang the test until its timeout instead of failing an assertion.
void test(
	"a FIFO at the record's path is reported as unreadable, never opened for a blocking read",
	{ timeout: 5000 },
	(t) => {
		const file = path.join(scratch(t), "failure");
		execFileSync("mkfifo", [file]);

		assert.equal(takeProgramFailure(file), UNREADABLE_PROGRAM_FAILURE);
		assert.equal(existsSync(file), false, "the FIFO is removed");
		assert.equal(takeProgramFailure(file), undefined);
	},
);

void test("a link at the record's path is reported as unreadable, and its target is neither read nor removed", (t) => {
	const directory = scratch(t);
	const target = path.join(directory, "elsewhere");
	writeFileSync(target, "run:TypeError");
	const file = path.join(directory, "failure");
	symlinkSync(target, file);

	assert.equal(takeProgramFailure(file), UNREADABLE_PROGRAM_FAILURE);
	assert.equal(readFileSync(target, "utf8"), "run:TypeError");
	assert.equal(existsSync(file), false, "the link is removed");
});

void test("an oversized record is reported as unreadable", (t) => {
	const file = path.join(scratch(t), "failure");
	writeFileSync(file, `run:${"A".repeat(1000)}`);

	assert.equal(takeProgramFailure(file), UNREADABLE_PROGRAM_FAILURE);
});

void test(
	"a crashing program replaces a FIFO or a link at the record's path instead of writing through it",
	{ timeout: 5000 },
	(t) => {
		const directory = scratch(t);
		const fifo = path.join(directory, "fifo-failure");
		execFileSync("mkfifo", [fifo]);
		recordProgramFailure("init", new TypeError("boom"), fifo);
		assert.equal(lstatSync(fifo).isFile(), true);
		assert.equal(takeProgramFailure(fifo), "init:TypeError");

		const target = path.join(directory, "elsewhere");
		writeFileSync(target, "untouched");
		const link = path.join(directory, "link-failure");
		symlinkSync(target, link);
		recordProgramFailure("run", new TypeError("boom"), link);
		assert.equal(readFileSync(target, "utf8"), "untouched");
		assert.equal(takeProgramFailure(link), "run:TypeError");
	},
);
