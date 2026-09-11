import assert from "node:assert/strict";
import test from "node:test";
import { command, parseRequest } from "./operation.ts";

const head = "a".repeat(40);
const base = "b".repeat(40);

await test("offline operations refuse credentials and unpinned revisions", () => {
	assert.throws(() =>
		parseRequest({ operation: "REVIEW_DIFF", revisions: [base, head], token: "secret" }),
	);
	assert.throws(() =>
		parseRequest({ operation: "REVIEW_DIFF", revisions: [base, "--output=/etc/passwd"] }),
	);
	assert.throws(() => parseRequest({ operation: "COMMIT_METADATA", revisions: ["HEAD"] }));
});

await test("ref resolution treats an option-shaped ref as an operand", () => {
	assert.deepEqual(
		command(parseRequest({ operation: "RESOLVE", revisions: ["--upload-pack=evil"] })),
		["rev-parse", "--verify", "--end-of-options", "--upload-pack=evil^{commit}"],
	);
});

await test("fetch accepts only credential-free HTTPS URLs and keeps token out of argv", () => {
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

