import assert from "node:assert/strict";
import { test } from "node:test";
import { annotateDiff } from "./review-diff.ts";

async function annotated(input: Buffer, chunkSize = 7) {
	function* chunks() {
		for (let offset = 0; offset < input.length; offset += chunkSize)
			yield input.subarray(offset, offset + chunkSize);
	}
	const output: Buffer[] = [];
	for await (const chunk of annotateDiff(chunks())) output.push(chunk);
	return Buffer.concat(output);
}

void test("annotations preserve source bytes and advance the applicable side only", async () => {
	const header = "diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -5,3 +5,3 @@\n";
	const input = Buffer.from(
		`${header} context\r\n-old\r\n+new\r\n last\r\n\\ No newline at end of file\n`,
	);
	const result = await annotated(input);
	assert.equal(
		result.toString(),
		`${header}[L5]  context\r\n[L6] -old\r\n[L6] +new\r\n[L7]  last\r\n\\ No newline at end of file\n`,
	);
});

void test("metadata stays unannotated and each file resets line counters", async () => {
	const first = "diff --git a/one b/one\n@@ -1 +1 @@\n+one\n";
	const second =
		"diff --git a/two b/two\nnew file mode 100644\n+++ b/two\n@@ -0,0 +100,2 @@\n+two\n+\n";
	assert.equal(
		(await annotated(Buffer.from(first + second), 1)).toString(),
		"diff --git a/one b/one\n@@ -1 +1 @@\n[L1] +one\ndiff --git a/two b/two\nnew file mode 100644\n+++ b/two\n@@ -0,0 +100,2 @@\n[L100] +two\n[L101] +\n",
	);
});

void test("long and non-UTF8 source lines are not truncated or decoded", async () => {
	const header = Buffer.from("diff --git a/file b/file\n@@ -0,0 +1 @@\n");
	const source = Buffer.concat([
		Buffer.from("+"),
		Buffer.alloc(2 * 1024 * 1024, 0xff),
		Buffer.from("\n"),
	]);
	assert.deepEqual(
		await annotated(Buffer.concat([header, source]), 4096),
		Buffer.concat([header, Buffer.from("[L1] "), source]),
	);
});

void test("empty input stays empty and an unterminated line is not given a new newline", async () => {
	assert.equal((await annotated(Buffer.alloc(0))).length, 0);
	assert.equal(
		(await annotated(Buffer.from("@@ -0,0 +1 @@\n+last"))).toString(),
		"@@ -0,0 +1 @@\n[L1] +last",
	);
});

void test("invalid source positions fail preparation", async () => {
	await assert.rejects(
		annotated(Buffer.from("@@ -999999999999999999999 +1 @@\n+x\n")),
		/Invalid diff line number/,
	);
});
