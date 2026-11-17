import assert from "node:assert/strict";
import { test } from "node:test";

import { sbomInventoryVersion } from "./lib/release-sbom-contract.ts";

await test("signed inventory declarations preserve older releases without a version cutoff", () => {
	for (const [field, current] of [
		["bundledNpmSbom", 2],
		["sourceSbom", 1],
	] as const) {
		assert.equal(sbomInventoryVersion({}, field, false), 0);
		for (let version = 1; version <= current; version += 1) {
			assert.equal(sbomInventoryVersion({ [field]: version }, field, false), version);
		}
		assert.equal(sbomInventoryVersion({ [field]: current }, field, true), current);
		assert.throws(() => sbomInventoryVersion({}, field, true), /capture manifest must declare/u);
		for (const value of [0, current + 1, "1", null]) {
			assert.throws(() => sbomInventoryVersion({ [field]: value }, field, false), /unsupported/u);
			assert.throws(() => sbomInventoryVersion({ [field]: value }, field, true), /unsupported/u);
		}
	}
});

await test("a capture older than the server's web app cannot be written", () => {
	assert.throws(
		() => sbomInventoryVersion({ bundledNpmSbom: 1 }, "bundledNpmSbom", true),
		/must declare bundledNpmSbom: 2/u,
	);
});
