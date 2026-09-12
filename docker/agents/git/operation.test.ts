import assert from "node:assert/strict";
import test from "node:test";
import { command, parseRequest } from "./operation.ts";

const head = "a".repeat(40);
const base = "b".repeat(40);

void test("offline operations refuse credentials and unpinned revisions", () => {
	assert.throws(() =>
		parseRequest({ operation: "REVIEW_DIFF", revisions: [base, head], token: "secret" }),
	);
	assert.throws(() =>
		parseRequest({ operation: "REVIEW_DIFF", revisions: [base, "--output=/etc/passwd"] }),
	);
	assert.throws(() => parseRequest({ operation: "COMMIT_METADATA", revisions: ["HEAD"] }));
});

void test("citation batches are odd-length pairs under a fixed ceiling", () => {
	assert.throws(() => parseRequest({ operation: "CITED_BLOBS", revisions: [head, base] }));
	assert.throws(() =>
		parseRequest({
			operation: "CITED_BLOBS",
			revisions: [head, ...Array.from({ length: 257 }, () => [base, "file.txt"]).flat()],
		}),
	);
	assert.equal(
		parseRequest({ operation: "CITED_BLOBS", revisions: [head, base, "file.txt"] }).operation,
		"CITED_BLOBS",
	);
});

void test("fetch accepts only credential-free HTTPS URLs and keeps token out of argv", () => {
	for (const cloneUrl of [
		"file:///etc",
		"ext::evil",
		"https://token@example.com/repo",
		"https://example.com/repo?secret=x",
	]) {
		assert.throws(() => parseRequest({ operation: "FETCH", revisions: [], cloneUrl }));
	}
	const request = parseRequest({
		operation: "FETCH",
		revisions: [],
		cloneUrl: "https://example.com/repo.git",
		token: "secret",
	});
	assert.equal(
		command(request).some((argument) => argument.includes("secret")),
		false,
	);
	assert.ok(command(request).includes("--no-recurse-submodules"));
});
