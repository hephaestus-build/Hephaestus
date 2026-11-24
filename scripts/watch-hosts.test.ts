import assert from "node:assert/strict";
import { test } from "node:test";

import { parseChannel } from "./reconcile-deployment.ts";
import { apiAnswers, freezeVerdict, issueActions } from "./watch-hosts.ts";

const reply = (status: number, body: string) => ({ status, text: async () => body });

await test("the server is ready when one readiness request returns its health body, and down after three failures", async () => {
	const requests: string[] = [];
	const answers = [reply(503, "no available server"), reply(200, '{"status":"UP"}')];
	assert.deepEqual(
		await apiAnswers(
			"example.org",
			async (url) => {
				requests.push(url);
				return answers.shift() ?? reply(503, "");
			},
			3,
			0,
		),
		{ ok: true },
	);
	assert.deepEqual(requests, ["https://example.org/readyz", "https://example.org/readyz"]);

	// A redirect is never the server's readiness.
	assert.deepEqual(await apiAnswers("example.org", async () => reply(308, ""), 1, 0), {
		ok: false,
		last: "HTTP 308",
	});

	// The web app of an older release answers every path with its page while the server is stopped.
	assert.deepEqual(
		await apiAnswers("example.org", async () => reply(200, "<!doctype html>"), 2, 0),
		{ ok: false, last: "HTTP 200 without the server's health body" },
	);

	let calls = 0;
	assert.deepEqual(
		await apiAnswers(
			"example.org",
			async () => {
				calls += 1;
				if (calls === 3) {
					throw new Error("The operation was aborted due to timeout");
				}
				return reply(503, "no available server");
			},
			3,
			0,
		),
		{ ok: false, last: "The operation was aborted due to timeout" },
	);
	assert.equal(calls, 3);
});

await test("a freeze alerts after 24 h, and an undated freeze only warns", () => {
	const now = new Date("2026-11-24T18:00:00Z");
	const frozen = parseChannel({ release: "v1.0.0-rc.10", freeze: true });
	assert.equal(freezeVerdict(frozen, new Date("2026-11-24T00:00:00Z"), now).alert, false);
	const old = freezeVerdict(frozen, new Date("2026-11-17T21:47:12Z"), now);
	assert.equal(old.alert, true);
	assert.match(old.text, /frozen on v1\.0\.0-rc\.10 since 2026-11-17T21:47:12\.000Z \(164 h\)/u);
	assert.equal(freezeVerdict(frozen, undefined, now).alert, false);
	const following = parseChannel({ release: "v1.0.0-rc.10" });
	assert.equal(freezeVerdict(following, undefined, now).alert, false);
});

await test("a check notifies once when it starts failing and closes its issue when it passes", () => {
	const none = () => undefined;
	assert.deepEqual(issueActions({ api: "failure", freeze: "success" }, none), [
		{ check: "api", kind: "open" },
	]);
	// Every later failed run finds the issue open and notifies no one.
	assert.deepEqual(
		issueActions({ api: "failure", freeze: "success" }, (check) =>
			check === "api" ? 7 : undefined,
		),
		[],
	);
	assert.deepEqual(
		issueActions({ api: "success", freeze: "failure" }, (check) => (check === "api" ? 7 : 8)),
		[{ check: "api", kind: "close", number: 7 }],
	);
	assert.deepEqual(
		issueActions({ api: "cancelled", freeze: "skipped" }, () => 9),
		[],
	);
});
