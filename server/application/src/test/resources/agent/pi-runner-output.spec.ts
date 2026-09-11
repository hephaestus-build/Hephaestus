import assert from "node:assert/strict";
import test from "node:test";

import { outputPath } from "../../../main/resources/agent/pi-runner-output.ts";

void test("a short ASCII name is written where the collector looks for it", () => {
	assert.equal(outputPath("/workspace/out", "result.json"), "/workspace/out/result.json");
	assert.equal(
		outputPath("/workspace/out", "observations/security/no-secrets.json"),
		"/workspace/out/observations/security/no-secrets.json",
	);
});

void test("a name the archive cannot hold fails here rather than at collection", () => {
	const tooLong = `${"a".repeat(97)}.json`;
	assert.throws(() => outputPath("/workspace/out", tooLong), /is 106 bytes; the limit is 100/);
});

void test("the limit counts the archive root the collector sees", () => {
	assert.equal(outputPath("/workspace/out", "b".repeat(96)).length, "/workspace/out/".length + 96);
	assert.throws(() => outputPath("/workspace/out", "b".repeat(97)), /the limit is 100/);
});

void test("a non-ASCII name is refused because Docker would encode it as an extension record", () => {
	assert.throws(
		() => outputPath("/workspace/out", "résumé.json"),
		/must start with an ASCII letter or digit/,
	);
	assert.throws(
		() => outputPath("/workspace/out", "報告.json"),
		/must start with an ASCII letter or digit/,
	);
});

void test("a name that would escape or hide itself is refused", () => {
	assert.throws(
		() => outputPath("/workspace/out", "../result.json"),
		/must start with an ASCII letter or digit/,
	);
	assert.throws(
		() => outputPath("/workspace/out", "/etc/passwd"),
		/must start with an ASCII letter or digit/,
	);
	assert.throws(() => outputPath("/workspace/out", ""), /must start with an ASCII letter or digit/);
});

void test("nested traversal and noncanonical member names are refused", () => {
	for (const name of [
		"group/../../result.json",
		"group/../result.json",
		"group/./result.json",
		"group//result.json",
		"group/",
	]) {
		assert.throws(() => outputPath("/workspace/out", name), /non-empty, relative path segments/);
	}
});
