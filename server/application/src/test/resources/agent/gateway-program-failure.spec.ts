import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	recordProgramFailure,
	takeProgramFailure,
} from "../../../main/resources/agent/gateway-capabilities.ts";

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
