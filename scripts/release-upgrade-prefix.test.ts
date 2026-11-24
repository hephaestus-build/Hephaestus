import assert from "node:assert/strict";
import { test } from "node:test";

import {
	assertForwardReferenceRefused,
	assertPrefixHistory,
} from "./lib/release-upgrade-prefix.ts";

const failure = `Migration failed for changeset upgrade-prefix.xml::upgrade-test-forward-reference::upgrade-test:
Reason: liquibase.exception.DatabaseException: ERROR: column "staged_attempt" does not exist`;

void test("accepts the refusal only for the injected change set and its missing column", () => {
	assert.doesNotThrow(() => assertForwardReferenceRefused(1, failure));
	assert.doesNotThrow(() => assertForwardReferenceRefused(1, JSON.stringify({ message: failure })));
	assert.throws(() => assertForwardReferenceRefused(0, failure), /upgrade-test-forward-reference/u);
	assert.throws(
		() => assertForwardReferenceRefused(null, failure),
		/upgrade-test-forward-reference/u,
	);
	assert.throws(
		() => assertForwardReferenceRefused(1, failure.replace("::upgrade-test", "::some-other-test")),
		/upgrade-test-forward-reference/u,
	);
	assert.throws(
		() => assertForwardReferenceRefused(1, failure.replace("staged_attempt", "other_column")),
		/upgrade-test-forward-reference/u,
	);
});

void test("requires canonical baseline identities and refuses a later change set on the broken prefix", () => {
	const baseline = `db/changelog/1.0.0/baseline.xml::1.0.0-audit::hephaestus
db/changelog/1.0.0/baseline.xml::1.0.0-schema::hephaestus
db/changelog/1.0.0/baseline.xml::1.0.0-seed::hephaestus
db/changelog/1.0.0/baseline.xml::1.0.0-tag::hephaestus`;
	const later =
		"db/changelog/next/20261101110000-durable-practice-staging.xml::1793362753961-1::hephaestus";
	assert.doesNotThrow(() => assertPrefixHistory(baseline, true));
	assert.throws(
		() => assertPrefixHistory(baseline.replace("db/changelog/", "classpath:db/changelog/"), true),
		/Wrong migration history/u,
	);
	assert.throws(
		() => assertPrefixHistory(`${baseline}\n${later}`, true),
		/Wrong migration history/u,
	);
	assert.throws(() => assertPrefixHistory(baseline, false), /Wrong migration history/u);
});
