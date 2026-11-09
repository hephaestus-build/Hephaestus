// ADR 0068: coverage comes only from bytes that the loop read or verified. A hostile tools side answers `bash` and `grep`
// here, and each case shows what it can and cannot claim. The same work done honestly counts.

import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { SessionReads } from "../../../main/resources/agent/pi-session-reads.ts";
import { proveSearches } from "../../../main/resources/agent/pi-verified-search.ts";

const TREE = {
	root: "repos/reviewed",
	sourcePath: "repos/reviewed/.git/HEAD",
	version: "4154fb8c",
	complete: true,
};
const FILE = "repos/reviewed/src/Controller.java";
const SOURCE = "class Controller {\n\t@ControllerAdvice\n}\n";

function session() {
	const root = mkdtempSync(path.join(tmpdir(), "coverage-authority-"));
	mkdirSync(path.join(root, "repos/reviewed/src"), { recursive: true });
	mkdirSync(path.join(root, "repos/reviewed/.git"), { recursive: true });
	writeFileSync(path.join(root, FILE), SOURCE);
	return new SessionReads(root, {}, undefined, [], TREE);
}

/** One tool call that the tools side answered with `text`. */
function answered(
	reads: SessionReads,
	toolName: string,
	args: Record<string, unknown>,
	text: string,
) {
	reads.note({ type: "tool_execution_start", toolCallId: "call", toolName, args });
	reads.note({
		type: "tool_execution_end",
		toolCallId: "call",
		toolName,
		isError: false,
		result: { content: [{ type: "text", text }], details: {} },
	});
}

const complete = (reads: SessionReads) =>
	reads
		.coverage()
		.receipts.filter((receipt) => receipt.status === "COMPLETE")
		.map((receipt) => receipt.path);

const searched = (reads: SessionReads) =>
	reads
		.coverage()
		.receipts.filter((receipt) => receipt.status === "SEARCHED")
		.map((receipt) => receipt.pattern);

void test("a forged full read through bash gets no coverage", () => {
	const reads = session();
	answered(reads, "bash", { command: `cat ${FILE}` }, "class Forged {\n}\n");
	assert.deepEqual(complete(reads), []);
});

void test("the same read with the file's real bytes counts: the loop verified them", () => {
	const reads = session();
	answered(reads, "bash", { command: `cat ${FILE}` }, SOURCE);
	assert.deepEqual(complete(reads), ["src/Controller.java"]);
});

void test("the same read through the loop counts", () => {
	const reads = session();
	answered(reads, "read", { path: FILE }, SOURCE);
	assert.deepEqual(complete(reads), ["src/Controller.java"]);
});

void test("a grep alone counts as no read of the files it searched", () => {
	const reads = session();
	answered(
		reads,
		"grep",
		{ pattern: "class", path: TREE.root },
		"src/Controller.java:1: class Controller {",
	);
	assert.deepEqual(complete(reads), []);
});

void test("a search that hides a match proves no absence", async () => {
	const reads = session();
	answered(reads, "grep", { pattern: "ControllerAdvice", path: TREE.root }, "No matches found");
	assert.deepEqual(searched(reads), [], "not before the loop looked");
	await reads.proveSearches(proveSearches);
	assert.deepEqual(searched(reads), []);
});

void test("the same search with its real matches stands", async () => {
	const reads = session();
	answered(
		reads,
		"grep",
		{ pattern: "ControllerAdvice", path: TREE.root },
		"src/Controller.java:2: \t@ControllerAdvice",
	);
	await reads.proveSearches(proveSearches);
	assert.deepEqual(searched(reads), ["ControllerAdvice"]);
});

void test("a true absence stands, from the top of the workspace too", async () => {
	const reads = session();
	answered(reads, "grep", { pattern: "RestControllerAdvice" }, "No matches found");
	await reads.proveSearches(proveSearches);
	assert.deepEqual(searched(reads), ["RestControllerAdvice"]);
});

void test("a proof fails closed", async () => {
	const reads = session();
	answered(reads, "grep", { pattern: "RestControllerAdvice", path: TREE.root }, "No matches found");
	await reads.proveSearches(async (_root, claims) => ({
		proved: claims.map(() => false),
		checkoutDigest: null,
	}));
	assert.deepEqual(searched(reads), []);
});
