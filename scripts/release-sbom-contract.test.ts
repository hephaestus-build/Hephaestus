import assert from "node:assert/strict";
import { test } from "node:test";

import { requiresSbomInventory } from "./lib/release-sbom-contract.ts";

await test("signed inventory declarations preserve older releases without a version cutoff", () => {
	for (const field of ["bundledNpmSbom", "sourceSbom"] as const) {
		assert.equal(requiresSbomInventory({}, field, false), false);
		assert.equal(requiresSbomInventory({ [field]: 1 }, field, false), true);
		assert.equal(requiresSbomInventory({ [field]: 1 }, field, true), true);
		assert.throws(() => requiresSbomInventory({}, field, true), /capture manifest must declare/u);
		for (const value of [0, 2, "1", null]) {
			assert.throws(() => requiresSbomInventory({ [field]: value }, field, false), /unsupported/u);
			assert.throws(() => requiresSbomInventory({ [field]: value }, field, true), /unsupported/u);
		}
	}
});
