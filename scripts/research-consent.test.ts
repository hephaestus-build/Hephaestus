import assert from "node:assert/strict";
import { test } from "node:test";

import { completeTransparencyNotice, researchConsentFields } from "./lib/research-consent.ts";

await test("answers the configured research question with the same organisation", () => {
	assert.deepEqual(researchConsentFields("AET"), {
		participateInResearch: false,
		researchOrganization: "AET",
	});
});

await test("omits research fields when the notice has no research question", () => {
	assert.deepEqual(researchConsentFields(undefined), {});
});

await test("completes an open notice with the version and research question the server names", async () => {
	const puts: unknown[] = [];
	await completeTransparencyNotice(async (method, _path, body) => {
		if (method === "PUT") {
			puts.push(body);
			return;
		}
		return { completed: false, noticeVersion: "2026-09", researchOrganization: "AET" };
	});
	assert.deepEqual(puts, [
		{
			noticeVersion: "2026-09",
			termsAccepted: true,
			participateInResearch: false,
			researchOrganization: "AET",
		},
	]);
});

await test("leaves a completed notice alone", async () => {
	await completeTransparencyNotice(async (method) => {
		assert.equal(method, "GET");
		return { completed: true, noticeVersion: "2026-09" };
	});
});
