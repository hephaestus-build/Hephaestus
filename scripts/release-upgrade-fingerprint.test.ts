import assert from "node:assert/strict";
import { test } from "node:test";

import {
	deliveryDifference,
	partialMigration,
	preservationSql,
} from "./lib/release-upgrade-fingerprint.ts";

void test("preserved people ids reject SQL-shaped values", () => {
	assert.throws(
		() => preservationSql({ accounts: ["1); DROP TABLE account"], identities: [] }),
		/decimal/u,
	);
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
