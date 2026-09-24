import assert from "node:assert/strict";
import { test } from "node:test";

import { researchConsentFields } from "./lib/research-consent.ts";

await test("answers the configured research question with the same organisation", () => {
	assert.deepEqual(researchConsentFields("AET"), {
		participateInResearch: false,
		researchOrganization: "AET",
	});
});

await test("omits research fields when the notice has no research question", () => {
	assert.deepEqual(researchConsentFields(undefined), {});
});
