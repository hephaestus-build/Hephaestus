import assert from "node:assert/strict";
import test from "node:test";

import { promotionCoverage } from "./report-promotion-coverage.ts";

await test("successful version observation never claims full-stack readiness", () => {
	const summary = promotionCoverage(false, "success");
	assert.match(summary, /public webapp reported the requested application version/);
	assert.match(summary, /Not verified by this workflow.*server, worker, webhook/);
	assert.match(summary, /Host verification runbook/);
});

await test("holds and failed or skipped observations remain explicitly unverified", () => {
	assert.match(promotionCoverage(true, "skipped"), /version was not checked/);
	for (const outcome of ["failure", "cancelled", "skipped", "unknown"]) {
		const summary = promotionCoverage(false, outcome);
		assert.match(summary, /verification did not succeed/);
		assert.doesNotMatch(summary, /webapp reported the requested/);
	}
});
