import assert from "node:assert/strict";
import { test } from "node:test";

import {
	deliveryDifference,
	partialMigration,
	peopleLayout,
	preservationSql,
} from "./lib/release-upgrade-fingerprint.ts";

void test("native comparison requires exact preserved ids and rejects SQL-shaped ids", () => {
	assert.throws(() => preservationSql("native"), /preserved people ids/u);
	assert.throws(
		() => preservationSql("native", { accounts: ["1); DROP TABLE account"], identities: [] }),
		/decimal/u,
	);
});

void test("people layout is decided by the exact table set in any order", () => {
	assert.equal(peopleLayout("workspace_membership\nuser\nidentity_link"), "legacy");
	assert.equal(peopleLayout("user\nmembership\nidentity"), "native");
});

void test("people layout refuses a mixed, partial, or empty table set", () => {
	for (const tables of [
		"",
		"identity\nidentity_link\nmembership\nuser\nworkspace_membership",
		"identity_link\nworkspace_membership",
		"identity\nmembership",
		"identity\nuser",
		"identity\nuser\nworkspace_membership",
	]) {
		assert.throws(() => peopleLayout(tables), /Unknown or ambiguous people layout/u, tables);
	}
});

void test("the candidate may add a webhook header, but not change the subject, body or a header", () => {
	const previous = {
		subject: "github.o.r.issues",
		headers: ["X-GitHub-Event: issues"],
		body: "{}",
	};
	assert.equal(deliveryDifference(previous, previous), undefined);
	assert.equal(
		deliveryDifference(previous, { ...previous, headers: [...previous.headers, "X-New: 1"] }),
		undefined,
	);
	assert.match(
		deliveryDifference(previous, { ...previous, headers: ["X-GitHub-Event: issue"] }) ?? "",
		/headers removed or changed: X-GitHub-Event: issues/u,
	);
	assert.match(
		deliveryDifference(previous, { ...previous, headers: [] }) ?? "",
		/headers removed/u,
	);
	assert.match(
		deliveryDifference(previous, { ...previous, subject: "github.o.r.issue" }) ?? "",
		/subject/u,
	);
	assert.match(deliveryDifference(previous, { ...previous, body: "[]" }) ?? "", /body/u);
});

void test("a partial migration stops at the trigger, or completes when no later change set runs DDL", () => {
	assert.deepEqual(partialMigration(1, true, 3, 3, 6), { kind: "stopped", reached: 3 });
	assert.deepEqual(partialMigration(1, true, 5, 3, 6), { kind: "stopped", reached: 5 });
	assert.deepEqual(partialMigration(0, false, 6, 3, 6), { kind: "complete" });
	assert.equal(partialMigration(0, false, 4, 3, 6).kind, "failed");
	assert.equal(partialMigration(1, false, 3, 3, 6).kind, "failed");
	assert.equal(partialMigration(1, true, 2, 3, 6).kind, "failed");
	assert.equal(partialMigration(null, true, 6, 3, 6).kind, "failed");
});
