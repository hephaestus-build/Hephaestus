// A search that the tools side ran stands as proof of an absence only when the loop can confirm it.

import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import type { SearchClaim } from "../../../main/resources/agent/pi-search-worker.ts";
import { proveSearches } from "../../../main/resources/agent/pi-verified-search.ts";

const root = mkdtempSync(path.join(tmpdir(), "verified-search-"));
mkdirSync(path.join(root, "src"));
mkdirSync(path.join(root, ".git"));
writeFileSync(path.join(root, "src/a.ts"), "one\nTODO: first\nthree\n");
writeFileSync(path.join(root, "README.md"), "# readme\n");
writeFileSync(path.join(root, ".git/config"), "secret TODO that holds no work\n");
writeFileSync(path.join(root, "image.bin"), Buffer.from([0x54, 0x4f, 0x44, 0x4f, 0x00]));

const claim = (over: Partial<SearchClaim>): SearchClaim => ({
	pattern: "TODO",
	literal: false,
	ignoreCase: false,
	found: [],
	...over,
});

void test("a search that reported every match stands", async () => {
	assert.deepEqual(await proveSearches(root, [claim({ found: ["src/a.ts:2"] })]), [true]);
});

void test("an absence that is true stands", async () => {
	assert.deepEqual(await proveSearches(root, [claim({ pattern: "not-in-this-tree" })]), [true]);
});

void test("a tools side that hides a match gets no receipt", async () => {
	assert.deepEqual(await proveSearches(root, [claim({ found: [] })]), [false]);
});

void test("a literal pattern and case are read as the search read them", async () => {
	assert.deepEqual(
		await proveSearches(root, [
			claim({ pattern: "todo:", literal: true, ignoreCase: true, found: ["src/a.ts:2"] }),
			claim({ pattern: "todo:", literal: true, found: [] }),
			claim({ pattern: "t.do", ignoreCase: true, found: [] }),
		]),
		[true, true, false],
	);
});

void test("a case flag inside the pattern is read as `rg` reads it", async () => {
	assert.deepEqual(
		await proveSearches(root, [
			claim({ pattern: "(?i)todo", found: ["src/a.ts:2"] }),
			claim({ pattern: "(?i)todo", found: [] }),
		]),
		[true, false],
	);
});

void test("a match that only the tools side found proves nothing: the engines disagree", async () => {
	assert.deepEqual(await proveSearches(root, [claim({ found: ["src/a.ts:2", "README.md:1"] })]), [
		false,
	]);
});

void test("a pattern that JavaScript cannot read proves nothing", async () => {
	assert.deepEqual(await proveSearches(root, [claim({ pattern: "(?P<name>x)[" })]), [false]);
});

void test("each claim stands alone", async () => {
	assert.deepEqual(
		await proveSearches(root, [claim({ found: ["src/a.ts:2"] }), claim({ found: [] })]),
		[true, false],
	);
});

void test("a pattern that backtracks for ever ends at the bound and proves nothing", async () => {
	writeFileSync(path.join(root, "slow.txt"), `${"a".repeat(64)}!\n`);
	const started = Date.now();
	assert.deepEqual(await proveSearches(root, [claim({ pattern: "(a+)+$" })], { timeoutMs: 300 }), [
		false,
	]);
	assert.ok(Date.now() - started < 5000);
});

void test("a checkout past the size bound proves nothing", async () => {
	assert.deepEqual(
		await proveSearches(root, [claim({ pattern: "not-in-this-tree" })], { maxBytes: 4 }),
		[false],
	);
});

void test("no claims need no worker", async () => {
	assert.deepEqual(await proveSearches(root, []), []);
});
