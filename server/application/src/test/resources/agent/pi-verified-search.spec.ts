// A search that the tools side ran stands as proof of an absence only when the loop can confirm it.

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import type { SearchClaim } from "../../../main/resources/agent/pi-search-worker.ts";
import { proveSearches as prove } from "../../../main/resources/agent/pi-verified-search.ts";

const proveSearches = async (...args: Parameters<typeof prove>) => {
	const { proved } = await prove(...args);
	return proved;
};

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

void test("a hostile side cannot hide text that `rg` would have found", async () => {
	writeFileSync(
		path.join(root, "wide.txt"),
		Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from("WIDE here\n", "utf16le")]),
	);
	const big = Buffer.from("BIG here\n", "utf16le").swap16();
	writeFileSync(path.join(root, "big.txt"), Buffer.concat([Buffer.from([0xfe, 0xff]), big]));
	// `rg` reads on until a NUL past its first buffer, so this match is one it reports.
	writeFileSync(
		path.join(root, "late.bin"),
		Buffer.concat([
			Buffer.from("EARLY\n"),
			Buffer.alloc(100_000, 0x78),
			Buffer.from([0]),
			Buffer.from("tail\n"),
		]),
	);
	assert.deepEqual(
		await proveSearches(root, [
			claim({ pattern: "WIDE" }),
			claim({ pattern: "BIG" }),
			claim({ pattern: "EARLY" }),
		]),
		[false, false, false],
	);
});

void test("text that `rg` does not search is no match to hide", async () => {
	writeFileSync(
		path.join(root, "early.bin"),
		Buffer.concat([Buffer.from("BINARYTEXT\n"), Buffer.from([0]), Buffer.from("MORE\n")]),
	);
	writeFileSync(
		path.join(root, "after.bin"),
		Buffer.concat([Buffer.alloc(100_000, 0x78), Buffer.from([0]), Buffer.from("AFTERNUL\n")]),
	);
	assert.deepEqual(
		await proveSearches(root, [claim({ pattern: "BINARYTEXT" }), claim({ pattern: "AFTERNUL" })]),
		[true, true],
	);
});

void test("`rg`'s Unicode classes are read as `rg` reads them", async () => {
	writeFileSync(path.join(root, "words.txt"), "eine übung hier\nzahl ٣ steht\n");
	assert.deepEqual(
		await proveSearches(root, [
			claim({ pattern: String.raw`\bübung\b` }),
			claim({ pattern: String.raw`\w+ung` }),
			claim({ pattern: String.raw`zahl \d` }),
			claim({ pattern: String.raw`[\w]+ung` }),
		]),
		[false, false, false, false],
	);
	assert.deepEqual(
		await proveSearches(root, [claim({ pattern: String.raw`\bübung\b`, found: ["words.txt:1"] })]),
		[true],
	);
});

void test("a pattern across lines, or in a form that JavaScript reads otherwise, proves nothing", async () => {
	assert.deepEqual(
		await proveSearches(root, [
			claim({ pattern: "alpha\nbeta" }),
			claim({ pattern: String.raw`alpha\nbeta` }),
			claim({ pattern: "[[:alpha:]]+zzz" }),
		]),
		[false, false, false],
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
	assert.deepEqual(await prove(root, []), { proved: [], checkoutDigest: null });
});

/**
 * The checkout that `JobEvidenceFilesCheckoutDigestTest` stages too: both sides must name this digest for it, or
 * admission refuses every search.
 */
const CHECKOUT_DIGEST = "bda91db5849fd6d92795d99bb6646b48c8e92d78ee215d2e661222554b8e73f5";

function pinnedCheckout(): string {
	const checkout = mkdtempSync(path.join(tmpdir(), "verified-search-digest-"));
	mkdirSync(path.join(checkout, "a"));
	mkdirSync(path.join(checkout, "empty"));
	mkdirSync(path.join(checkout, ".git"));
	mkdirSync(path.join(checkout, "nested/.git"), { recursive: true });
	writeFileSync(path.join(checkout, "a/x.txt"), "one\n");
	writeFileSync(path.join(checkout, "a.b"), "dot\n");
	writeFileSync(path.join(checkout, "b.txt"), "two\n");
	writeFileSync(path.join(checkout, "nested/ü.txt"), "umlaut\n");
	writeFileSync(path.join(checkout, ".git/HEAD"), "ref: refs/heads/main\n");
	writeFileSync(path.join(checkout, "nested/.git/config"), "[core]\n");
	symlinkSync("b.txt", path.join(checkout, "link"));
	return checkout;
}

async function digest(checkout: string): Promise<string | null> {
	const { checkoutDigest } = await prove(checkout, [claim({ pattern: "absent" })]);
	return checkoutDigest;
}

void test("a proof names the digest of the checkout it searched, as the server computes it", async () => {
	const proof = await prove(pinnedCheckout(), [claim({ pattern: "absent" })]);
	assert.deepEqual(proof, { proved: [true], checkoutDigest: CHECKOUT_DIGEST });
});

void test("a changed, added or removed file changes the digest, and .git does not", async () => {
	const changed = pinnedCheckout();
	writeFileSync(path.join(changed, "b.txt"), "TWO\n");
	const added = pinnedCheckout();
	writeFileSync(path.join(added, "c.txt"), "");
	const git = pinnedCheckout();
	writeFileSync(path.join(git, ".git/HEAD"), "changed\n");
	assert.notEqual(await digest(changed), CHECKOUT_DIGEST);
	assert.notEqual(await digest(added), CHECKOUT_DIGEST);
	assert.equal(await digest(git), CHECKOUT_DIGEST);
});

void test("a proof that stops at the size bound names no digest", async () => {
	assert.deepEqual(await prove(pinnedCheckout(), [claim({ pattern: "absent" })], { maxBytes: 4 }), {
		proved: [false],
		checkoutDigest: null,
	});
});

const hasRg = spawnSync("rg", ["--version"]).status === 0;
const texts = mkdtempSync(path.join(tmpdir(), "verified-search-rg-"));
writeFileSync(path.join(texts, "crlf.txt"), "foo bar\r\nabc def\r\n");
writeFileSync(
	path.join(texts, "marked.cs"),
	Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from("using System;\n")]),
);
writeFileSync(
	path.join(texts, "two-marks.txt"),
	Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf, 0xff, 0xfe]), Buffer.from("hidden\n")]),
);
const utf16 = (text: string) => Buffer.from(text, "utf16le");
writeFileSync(
	path.join(texts, "doubled.txt"),
	Buffer.concat([Buffer.from([0xff, 0xfe, 0xff, 0xfe]), utf16("import os\n")]),
);
writeFileSync(
	path.join(texts, "doubled-big.txt"),
	Buffer.concat([
		Buffer.from([0xfe, 0xff, 0xfe, 0xff]),
		Buffer.from(utf16("import be\n")).swap16(),
	]),
);
writeFileSync(
	path.join(texts, "odd.txt"),
	Buffer.concat([Buffer.from([0xff, 0xfe]), utf16("evil"), Buffer.from([0x41])]),
);
writeFileSync(path.join(texts, "cased.txt"), "ſecret\nσασ\n");
writeFileSync(path.join(texts, "marks.txt"), "Ⓐ\u200Cx²\nnext\u0085line\nsep\u2028line\n");

const searches: [pattern: string, flags: readonly string[]][] = [
	["foo.*$", []],
	["^abc.+$", []],
	["^using ", []],
	["sep.line", []],
	[String.raw`\w+$`, []],
	[String.raw`\bx\b`, []],
	[String.raw`next\sline`, []],
	[String.raw`\S\s\S`, []],
	["secret", ["--ignore-case", "--fixed-strings"]],
	["ΣΑΣ", ["--ignore-case", "--fixed-strings"]],
	["hidden", []],
	["^import os", []],
	["^import be", []],
	["evil.", []],
	["a.c", ["--fixed-strings"]],
];
for (const [pattern, flags] of searches) {
	void test(
		`what rg finds for ${pattern} is what the loop finds`,
		// CI must not lose this comparison to a missing install.
		{ skip: hasRg || process.env.CI !== undefined ? false : "rg is not installed" },
		async () => {
			const rg = spawnSync(
				"rg",
				[
					"--hidden",
					"--line-number",
					"--no-heading",
					"--with-filename",
					...flags,
					"--",
					pattern,
					".",
				],
				{ cwd: texts, encoding: "utf8" },
			);
			const found = rg.stdout
				.split("\n")
				.filter(Boolean)
				.map((line) => /^(?:\.\/)?(?<at>[^:]+:\d+):/u.exec(line)?.groups?.at ?? line);
			assert.deepEqual(
				await proveSearches(texts, [
					claim({
						pattern,
						literal: flags.includes("--fixed-strings"),
						ignoreCase: flags.includes("--ignore-case"),
						found,
					}),
				]),
				[true],
			);
			// A side that hides what `rg` found earns no receipt.
			if (found.length > 0) {
				assert.deepEqual(
					await proveSearches(texts, [
						claim({
							pattern,
							literal: flags.includes("--fixed-strings"),
							ignoreCase: flags.includes("--ignore-case"),
							found: [],
						}),
					]),
					[false],
				);
			}
		},
	);
}

// In this file each pattern matches for `rg`, so a loop that read it and found less would earn a false receipt.
const apart = mkdtempSync(path.join(tmpdir(), "verified-search-apart-"));
writeFileSync(path.join(apart, "a.txt"), "x\r\n&\n~\naA\n[]\nZ\n5\n \n");
for (const [pattern, ignoreCase] of [
	["(?-s:x.)$", false],
	["^[^a&&b]$", false],
	["^[^a~~b]$", false],
	["^[^!--b]$", false],
	[String.raw`^[^\W]$`, false],
	[String.raw`^[^\D]$`, false],
	[String.raw`^[^\S]$`, false],
	[String.raw`^a[^\P{Lu}]$`, true],
	["[]A-[]", false],
	["[^]A-[x]", false],
] as const) {
	void test(`a pattern that rg and the loop read apart proves nothing: ${pattern}`, async () => {
		assert.deepEqual(await proveSearches(apart, [claim({ pattern, ignoreCase })]), [false]);
	});
}

void test("a match in .git is left out of the proof, as the loop leaves out .git", async () => {
	assert.deepEqual(
		await proveSearches(root, [claim({ pattern: "holds no work", found: [".git/config:1"] })]),
		[true],
	);
});
