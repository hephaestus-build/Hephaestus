import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { Readable } from "node:stream";
import test from "node:test";
import { addedSecretVerdicts } from "./secret-scan.ts";

function finding(start: number, end = start) {
	return { File: "config.txt", StartLine: start, EndLine: end, RuleID: "test-rule" };
}

void test("secret verdicts cover final added lines, not removed or unchanged secrets", async () => {
	const diff = Buffer.from(
		"--- a/config.txt\n+++ b/config.txt\n@@ -2,1 +2,1 @@\n-old-secret\n+new-secret\n",
	);
	const result = await addedSecretVerdicts(
		"config.txt",
		[finding(1), finding(2), finding(3)],
		Readable.from([diff]),
	);
	assert.deepEqual(result, [
		{
			path: "config.txt",
			line: 2,
			ruleId: "test-rule",
			lineHash: createHash("sha256").update("new-secret").digest("hex"),
		},
	]);
	assert.ok(!JSON.stringify(result).includes("new-secret"));
});

void test("secret line identities preserve whitespace and CR bytes across stream boundaries", async () => {
	const line = " \tsecret-value  \r";
	const diff = Buffer.from(`@@ -0,0 +1,1 @@\n+${line}\n\\ No newline at end of file\n`);
	const chunks = Array.from(diff, (byte) => Buffer.from([byte]));
	const result = await addedSecretVerdicts("config.txt", [finding(1)], Readable.from(chunks));
	assert.equal(result[0]?.lineHash, createHash("sha256").update(line).digest("hex"));
});

void test("multiline scanner matches retain only added lines in each hunk", async () => {
	const diff = Buffer.from("@@ -2,1 +2,1 @@\n-removed\n+first\n@@ -6,1 +6,1 @@\n-another\n+last\n");
	const result = await addedSecretVerdicts("config.txt", [finding(1, 6)], Readable.from([diff]));
	assert.deepEqual(
		result.map((verdict) => verdict.line),
		[2, 6],
	);
});

void test("a scanner match outside final additions does not become an observation", async () => {
	const result = await addedSecretVerdicts(
		"config.txt",
		[finding(10)],
		Readable.from([Buffer.from("@@ -1,1 +1,1 @@\n-old\n+new")]),
	);
	assert.deepEqual(result, []);
});

void test("a long added source line is hashed incrementally across bounded chunks", async () => {
	const chunk = Buffer.alloc(64 * 1024, 120);
	const expected = createHash("sha256");
	function* diff() {
		yield Buffer.from("@@ -0,0 +1,1 @@\n+");
		for (let index = 0; index < 640; index++) {
			expected.update(chunk);
			yield chunk;
		}
		yield Buffer.from("\n");
	}
	const result = await addedSecretVerdicts("config.txt", [finding(1)], Readable.from(diff()));
	assert.equal(result[0]?.lineHash, expected.digest("hex"));
});
