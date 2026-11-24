import assert from "node:assert/strict";
import { test } from "node:test";

import { parseChannel } from "./reconcile-deployment.ts";
import { apiAnswers, freezeVerdict } from "./watch-hosts.ts";

await test("the API answers when one readiness request returns 200, and is down after three failures", async () => {
	const requests: string[] = [];
	const answers = [503, 200];
	assert.deepEqual(
		await apiAnswers(
			"example.org",
			async (url) => {
				requests.push(url);
				return { status: answers.shift() ?? 503 };
			},
			3,
			0,
		),
		{ ok: true },
	);
	assert.deepEqual(requests, ["https://example.org/api/readyz", "https://example.org/api/readyz"]);

	let calls = 0;
	assert.deepEqual(
		await apiAnswers(
			"example.org",
			async () => {
				calls += 1;
				if (calls === 3) {
					throw new Error("The operation was aborted due to timeout");
				}
				return { status: 503 };
			},
			3,
			0,
		),
		{ ok: false, last: "The operation was aborted due to timeout" },
	);
	assert.equal(calls, 3);
});

await test("a freeze alerts after 24 h, or at once when deploy-state cannot date it", () => {
	const now = new Date("2026-11-24T18:00:00Z");
	const frozen = parseChannel({ release: "v1.0.0-rc.10", freeze: true });
	assert.equal(freezeVerdict(frozen, new Date("2026-11-24T00:00:00Z"), now).alert, false);
	const old = freezeVerdict(frozen, new Date("2026-11-17T21:47:12Z"), now);
	assert.equal(old.alert, true);
	assert.match(old.text, /frozen on v1\.0\.0-rc\.10 since 2026-11-17T21:47:12\.000Z \(164 h\)/u);
	assert.equal(freezeVerdict(frozen, undefined, now).alert, true);
	const following = parseChannel({ release: "v1.0.0-rc.10" });
	assert.equal(freezeVerdict(following, undefined, now).alert, false);
});
