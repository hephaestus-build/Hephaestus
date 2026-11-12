import assert from "node:assert/strict";
import { test } from "node:test";

import { partialMigration, preservationSql } from "./lib/release-upgrade-fingerprint.ts";

void test("preserved people ids reject SQL-shaped values", () => {
	assert.throws(
		() => preservationSql({ accounts: ["1); DROP TABLE account"], identities: [] }),
		/decimal/u,
	);
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
