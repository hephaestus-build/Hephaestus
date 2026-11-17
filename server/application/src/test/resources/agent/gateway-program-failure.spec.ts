import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	recordProgramFailure,
	takeProgramFailure,
} from "../../../main/resources/agent/gateway-capabilities.ts";

void test("a review program's crash reaches the review kind once, as closed codes only", () => {
	takeProgramFailure();
	recordProgramFailure("init", new TypeError("a path or a token the server must not see"));

	assert.equal(takeProgramFailure(), "init:TypeError");
	assert.equal(takeProgramFailure(), undefined);
});

void test("a recorded line that is not closed codes is dropped", () => {
	writeFileSync(path.join(tmpdir(), "hephaestus-program-failure"), "run:<b>secret</b>");

	assert.equal(takeProgramFailure(), undefined);
	assert.equal(takeProgramFailure(), undefined);
});
