import assert from "node:assert/strict";
import { test } from "node:test";

import { parseChannel } from "./reconcile-deployment.ts";
import {
	apiAnswers,
	apiSubject,
	freezeSubject,
	freezeVerdict,
	issueActions,
	reportVerdicts,
} from "./watch-hosts.ts";

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

await test("a freeze alerts after 24 h, and an undated freeze concludes nothing", () => {
	const now = new Date("2026-11-24T18:00:00Z");
	const frozen = parseChannel({ release: "v1.0.0-rc.10", freeze: true });
	assert.equal(freezeVerdict(frozen, new Date("2026-11-24T00:00:00Z"), now).verdict, "held");
	const old = freezeVerdict(frozen, new Date("2026-11-17T21:47:12Z"), now);
	assert.equal(old.verdict, "frozen");
	assert.match(old.text, /frozen on v1\.0\.0-rc\.10 since 2026-11-17T21:47:12\.000Z \(164 h\)/u);
	assert.equal(freezeVerdict(frozen, undefined, now).verdict, "undated");
	assert.equal(
		freezeVerdict(parseChannel({ release: "v1.0.0-rc.10" }), undefined, now).verdict,
		"following",
	);
});

const production = apiSubject("hephaestus.build");
const staging = apiSubject("staging.hephaestus.build");

await test("each host and the freeze notify once when they start failing, and close their issue when they pass", () => {
	assert.deepEqual(
		issueActions([{ subject: production, failing: true }], () => undefined),
		[{ subject: production, kind: "open" }],
	);
	// A deliberately stopped production keeps its issue open; staging going down still notifies.
	const open = (subject: { key: string }) => (subject.key === production.key ? 7 : undefined);
	assert.deepEqual(
		issueActions(
			[
				{ subject: production, failing: true },
				{ subject: staging, failing: true },
			],
			open,
		),
		[{ subject: staging, kind: "open" }],
	);
	assert.deepEqual(issueActions([{ subject: production, failing: false }], open), [
		{ subject: production, kind: "close", number: 7 },
	]);
	// An undated freeze neither closes nor opens the freeze issue.
	assert.deepEqual(
		issueActions([{ subject: freezeSubject, failing: undefined }], () => 9),
		[],
	);
});

await test("a check that ended without a verdict fails the report instead of becoming a claim", () => {
	const both = JSON.stringify({ "hephaestus.build": "down", "staging.hephaestus.build": "ready" });
	assert.deepEqual(reportVerdicts(both, "undated"), [
		{ subject: production, failing: true },
		{ subject: staging, failing: false },
		{ subject: freezeSubject, failing: undefined },
	]);
	// A crashed, timed-out or cancelled check job passes on an empty output.
	assert.throws(() => reportVerdicts("", "following"), /without a verdict for any host/u);
	assert.throws(() => reportVerdicts(both, ""), /freeze check ended without a verdict/u);
	assert.throws(() => reportVerdicts('{"hephaestus.build":"maybe"}', "held"), /no verdict/u);
});
