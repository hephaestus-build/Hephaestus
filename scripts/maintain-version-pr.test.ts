import assert from "node:assert/strict";
import { test } from "node:test";

import { newestValidated, skipReason, versionPullRequest } from "./maintain-version-pr.ts";

const changelog = `# hephaestus

## 1.0.0-rc.1

### Major Changes

- Hephaestus 1.0 starts the 1.x upgrade guarantee.

## 0.95.1

### Patch Changes

- An older fix.
`;

void test("the Version PR carries the new version's notes and marks a prerelease in its title", () => {
	const prerelease = versionPullRequest(changelog, "1.0.0-rc.1", "rc");
	assert.equal(prerelease.title, "chore(release): version packages (rc)");
	assert.match(
		prerelease.body,
		/## hephaestus@1\.0\.0-rc\.1\n\n### Major Changes\n\n- Hephaestus 1\.0/u,
	);
	assert.doesNotMatch(prerelease.body, /An older fix/u);

	const stable = versionPullRequest(changelog, "0.95.1", undefined);
	assert.equal(stable.title, "chore(release): version packages");
	assert.match(stable.body, /- An older fix\.\n$/u);
});

void test("a version without a changelog entry stops maintenance", () => {
	assert.throws(() => versionPullRequest(changelog, "1.0.1", undefined), /no ## 1\.0\.1 entry/u);
});

void test("maintenance versions a validated base only while its changesets are still unreleased", () => {
	const state = {
		base: "b".repeat(40),
		baseVersion: "1.0.0-rc.0",
		tipVersion: "1.0.0-rc.0",
		versionedBase: "a".repeat(40),
		baseIsBehind: false,
		pendingChangesets: true,
	};
	assert.equal(skipReason(state), undefined);
	assert.equal(skipReason({ ...state, versionedBase: undefined }), undefined);
	// Main's tip released after the base: the base's changesets went out with that release.
	assert.match(skipReason({ ...state, tipVersion: "1.0.0-rc.1" }) ?? "", /released 1\.0\.0-rc\.1/u);
	assert.match(skipReason({ ...state, versionedBase: state.base }) ?? "", /already versions/u);
	assert.match(skipReason({ ...state, pendingChangesets: false }) ?? "", /no pending changesets/u);
	// A re-run that fails drops the newest success, so the newest validated commit can move back.
	assert.match(skipReason({ ...state, baseIsBehind: true }) ?? "", /newer validated commit/u);
});

void test("the base is the newest first-parent commit with a successful push run", () => {
	const [tip, merged, older, oldest] = [
		"d".repeat(40),
		"c".repeat(40),
		"b".repeat(40),
		"a".repeat(40),
	];
	assert.equal(newestValidated([tip, merged, older, oldest], new Set([oldest, merged])), merged);
	assert.equal(newestValidated([tip, merged], new Set(["e".repeat(40)])), undefined);
});

void test("a body above GitHub's limit keeps its header and points to the changelog", () => {
	const long = `# Changelog\n\n## 2.0.0\n\n${"- A change.\n".repeat(10_000)}`;
	const { body } = versionPullRequest(long, "2.0.0", undefined);
	assert.ok(body.length <= 60_000);
	assert.match(body, /^Merging this pull request releases `2\.0\.0`/u);
	assert.match(body, /The notes continue in this pull request's `CHANGELOG\.md`\.\n$/u);
});
