import assert from "node:assert/strict";
import { once } from "node:events";
import { test } from "node:test";

import { asRecord, parseJson } from "./lib/json.ts";
import {
	directMessageEvent,
	installCookie,
	installState,
	receivedCalls,
	slackSignature,
} from "./lib/slack-turn.ts";
import { FAKE_INSTALL_CODE, FAKE_TEAM, postRefusal, slackFake } from "./slack-fake.ts";

async function withFake(body: (base: string, token: string) => Promise<void>): Promise<void> {
	const { server, token } = slackFake();
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new TypeError("the fake listens on no port");
	}
	try {
		await body(`http://127.0.0.1:${address.port}`, token);
	} finally {
		server.close();
	}
}

async function json(response: Response): Promise<unknown> {
	return (await response.json()) as unknown;
}

/** The calls the fake listed, from the `after`th on, waiting at most `timeoutMs` for one. */
async function received(base: string, query = ""): Promise<ReturnType<typeof receivedCalls>> {
	const response = await fetch(`${base}/received${query}`);
	return receivedCalls(await response.text());
}

const FORM = "application/x-www-form-urlencoded";

async function postMessage(
	base: string,
	headers: Record<string, string>,
	body: string,
	contentType = FORM,
): Promise<Record<string, unknown>> {
	return asRecord(
		await json(
			await fetch(`${base}/api/chat.postMessage`, {
				method: "POST",
				headers: { "content-type": contentType, ...headers },
				body,
			}),
		),
		"chat.postMessage answer",
	);
}

await test("the fake installs the app for its code only, and hands out one token per start", async () => {
	await withFake(async (base, token) => {
		const post = async (code: string) =>
			json(
				await fetch(`${base}/api/oauth.v2.access`, {
					method: "POST",
					headers: { "content-type": FORM },
					body: new URLSearchParams({ code, client_id: "c", client_secret: "s" }),
				}),
			);
		assert.deepEqual(await post("wrong"), { ok: false, error: "invalid_code" });
		assert.deepEqual(await post(FAKE_INSTALL_CODE), {
			ok: true,
			access_token: token,
			token_type: "bot",
			team: FAKE_TEAM,
			bot_user_id: "U0TURNROLLBOT",
		});
	});
	const tokens = new Set<string>();
	for (let start = 0; start < 2; start += 1) {
		await withFake(async (_base, token) => {
			tokens.add(token);
		});
	}
	assert.equal(tokens.size, 2);
});

await test("the fake lists a posted message with its channel, its text and whether the install's token sent it", async () => {
	await withFake(async (base, token) => {
		const auth = { authorization: `Bearer ${token}` };
		assert.deepEqual(
			await postMessage(
				base,
				{ authorization: "Bearer xoxb-other" },
				new URLSearchParams({ channel: "D1", text: "no" }).toString(),
			),
			{ ok: false, error: "invalid_auth" },
		);
		const posted = await postMessage(
			base,
			auth,
			new URLSearchParams({
				channel: "D1",
				text: "an answer",
				thread_ts: "1760000000.000100",
			}).toString(),
		);
		assert.equal(posted.ok, true);
		const asJson = await postMessage(
			base,
			auth,
			JSON.stringify({ channel: "D2", text: "as JSON", blocks: [{ type: "markdown", text: "x" }] }),
			"application/json; charset=utf-8",
		);
		assert.equal(asJson.channel, "D2");
		assert.deepEqual(await received(base), [
			{
				method: "chat.postMessage",
				authorized: false,
				channel: "D1",
				threadTs: null,
				text: "no",
				blocksText: null,
				ts: null,
				error: "invalid_auth",
			},
			{
				method: "chat.postMessage",
				authorized: true,
				channel: "D1",
				threadTs: "1760000000.000100",
				text: "an answer",
				blocksText: null,
				ts: posted.ts,
				error: null,
			},
			{
				method: "chat.postMessage",
				authorized: true,
				channel: "D2",
				threadTs: null,
				text: "as JSON",
				blocksText: "x",
				ts: asJson.ts,
				error: null,
			},
		]);
	});
});

await test("the fake refuses another method, and lists it", async () => {
	await withFake(async (base, token) => {
		const answer = await fetch(`${base}/api/users.info`, {
			method: "POST",
			headers: { authorization: `Bearer ${token}` },
			body: "user=U1",
		});
		assert.deepEqual(await json(answer), { ok: false, error: "unknown_method" });
		const calls = await received(base);
		assert.equal(calls[0]?.method, "users.info");
		const health = await fetch(`${base}/health`);
		assert.equal(health.status, 200);
		const nothing = await fetch(`${base}/nothing`);
		assert.equal(nothing.status, 404);
	});
});

await test("Slack's signature is the v0 HMAC over the timestamp and the exact body", () => {
	// The example of https://api.slack.com/authentication/verifying-requests-from-slack.
	assert.equal(
		slackSignature(
			"8f742231b10e8888abcd99yyyzzz85a5",
			1_531_420_618,
			"token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c",
		),
		"v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503",
	);
});

await test("a direct message event names its team, member, channel and delivery", () => {
	const event = asRecord(
		parseJson(
			directMessageEvent({
				team: "T1",
				user: "U1",
				channel: "D1",
				text: "hi",
				ts: "1760000000.000100",
				eventId: "Ev1",
			}),
		),
		"event",
	);
	assert.equal(event.team_id, "T1");
	assert.equal(event.event_id, "Ev1");
	assert.deepEqual(event.event, {
		type: "message",
		channel_type: "im",
		channel: "D1",
		user: "U1",
		text: "hi",
		ts: "1760000000.000100",
	});
});

await test("the install's state and its browser cookie are what the server's callback expects", () => {
	assert.equal(
		installState("https://slack.com/oauth/v2/authorize?client_id=c&state=a.b-c&scope=x"),
		"a.b-c",
	);
	assert.throws(
		() => installState("https://slack.com/oauth/v2/authorize?client_id=c"),
		/no state/u,
	);
	// sha256("abc"), as `OAuthBrowserBinding.cookieName` and `cookieValue` derive them.
	assert.equal(
		installCookie("abc"),
		"__Host-CONNECT_ba7816bf8f01cfea=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
	);
});

await test("the fake refuses what chat.postMessage refuses, with Slack's error, and lists the refusal", async () => {
	const good: Record<string, string> = {
		channel: "D1",
		text: "an answer",
		blocks: JSON.stringify([{ type: "markdown", text: "the visible answer" }]),
		thread_ts: "1760000000.000100",
		metadata: JSON.stringify({ event_type: "heph_entry", event_payload: { entry_id: "e" } }),
	};
	const refused = (changes: Record<string, string | undefined>) =>
		postRefusal(
			new Map(
				Object.entries({ ...good, ...changes }).flatMap(([key, value]) =>
					value === undefined ? [] : [[key, value] as const],
				),
			),
		).error;
	assert.equal(refused({}), null);
	assert.equal(refused({ blocks: "not-json" }), "invalid_blocks_format");
	assert.equal(refused({ blocks: '{"type":"markdown"}' }), "invalid_blocks_format");
	assert.equal(refused({ blocks: '[{"text":"no type"}]' }), "invalid_blocks");
	assert.equal(refused({ blocks: "[1]" }), "invalid_blocks");
	assert.equal(refused({ channel: undefined }), "channel_not_found");
	assert.equal(refused({ channel: "" }), "channel_not_found");
	assert.equal(refused({ text: undefined, blocks: undefined }), "no_text");
	assert.equal(refused({ text: "", blocks: "[]" }), "no_text");
	assert.equal(refused({ text: undefined }), null);
	assert.equal(refused({ blocks: undefined }), null);
	for (const threadTs of [
		"",
		"1.2",
		"not-a-ts",
		"1760000000",
		"1760000000.0001",
		"x760000000.000100",
	]) {
		assert.equal(refused({ thread_ts: threadTs }), "invalid_arguments", threadTs);
	}
	assert.equal(refused({ metadata: "{" }), "invalid_metadata_format");
	assert.equal(refused({ metadata: "[]" }), "invalid_metadata_schema");
	assert.equal(refused({ metadata: '{"event_type":"x"}' }), "invalid_metadata_schema");
	assert.equal(refused({ metadata: '{"event_payload":{}}' }), "invalid_metadata_schema");

	await withFake(async (base, token) => {
		const post = async (fields: Record<string, string>) =>
			postMessage(
				base,
				{ authorization: `Bearer ${token}` },
				new URLSearchParams(fields).toString(),
			);
		assert.deepEqual(await post({ channel: "D1", text: "x", blocks: "not-json" }), {
			ok: false,
			error: "invalid_blocks_format",
		});
		assert.deepEqual(await post({ channel: "D1", text: "x", thread_ts: "1.2" }), {
			ok: false,
			error: "invalid_arguments",
		});
		assert.deepEqual(await post({ channel: "D1" }), { ok: false, error: "no_text" });
		const calls = await received(base);
		assert.deepEqual(
			calls.map((call) => [call.error, call.ts]),
			[
				["invalid_blocks_format", null],
				["invalid_arguments", null],
				["no_text", null],
			],
		);
	});
});

const markdown = (text: string) => ({ type: "markdown", text });
const mrkdwn = (text: string) => ({ type: "mrkdwn", text });
const plain = (text: string) => ({ type: "plain_text", text });
const feedbackButtons = {
	type: "feedback_buttons",
	action_id: "heph_feedback",
	positive_button: { text: plain("Helpful"), value: "HELPFUL:1", accessibility_label: "Helpful" },
	negative_button: { text: plain("Not helpful"), value: "NOT_HELPFUL:1" },
};
const confirm = {
	title: plain("Sure?"),
	text: plain("Do not use my messages."),
	confirm: plain("Yes"),
	deny: plain("No"),
};
const button = {
	type: "button",
	text: plain("Link account"),
	action_id: "link",
	url: "https://x.example",
};

/** The error that `chat.postMessage` answers for these blocks, or null when it accepts them. */
const blocksError = (blocks: unknown) =>
	postRefusal(
		new Map([
			["channel", "D1"],
			["text", "fallback"],
			["blocks", JSON.stringify(blocks)],
		]),
	).error;

await test("the fake accepts the blocks that the server sends", () => {
	for (const blocks of [
		[markdown("an answer")],
		[
			markdown("an answer"),
			{ type: "context", elements: [plain("Hephaestus answers with an AI model.")] },
		],
		[markdown("an answer"), { type: "context_actions", elements: [feedbackButtons] }],
		[
			{ type: "section", text: mrkdwn("*Your weekly digest is ready*") },
			{ type: "actions", elements: [{ ...button, style: "primary", confirm }] },
			{ type: "context", elements: [mrkdwn("Reply in this thread to go through it.")] },
		],
		[
			{
				type: "section",
				fields: [mrkdwn("one"), plain("two")],
				accessory: button,
				block_id: "b".repeat(255),
			},
		],
	]) {
		assert.equal(blocksError(blocks), null, JSON.stringify(blocks));
	}
});

await test("the fake refuses a block that lacks what Slack requires of its type", () => {
	const invalid: [string, unknown][] = [
		// A section needs a text object, not a string: the shape that once passed every assertion.
		["a section with a text string", [{ type: "section", text: "The commands printed" }]],
		["a section with no text and no fields", [{ type: "section" }]],
		["a section whose text is no text object", [{ type: "section", text: { text: "x" } }]],
		[
			"a section whose text type is image",
			[{ type: "section", text: { type: "image", text: "x" } }],
		],
		["a section with an empty text", [{ type: "section", text: mrkdwn("") }]],
		[
			"a section whose text is 3001 characters",
			[{ type: "section", text: mrkdwn("x".repeat(3001)) }],
		],
		[
			"a section with more than 10 fields",
			[{ type: "section", fields: Array.from({ length: 11 }, () => mrkdwn("x")) }],
		],
		["a section with no field", [{ type: "section", fields: [] }]],
		[
			"a section field of 2001 characters",
			[{ type: "section", fields: [mrkdwn("x".repeat(2001))] }],
		],
		["a section field that is a string", [{ type: "section", fields: ["x"] }]],
		[
			"a section accessory that is no button",
			[{ type: "section", text: mrkdwn("x"), accessory: { type: "image" } }],
		],
		["a markdown block with no text", [{ type: "markdown" }]],
		["a markdown block with an empty text", [markdown("")]],
		[
			"a markdown block whose text is no string",
			[{ type: "markdown", text: { type: "mrkdwn", text: "x" } }],
		],
		["a context block with no elements", [{ type: "context" }]],
		["a context block with an empty list", [{ type: "context", elements: [] }]],
		[
			"a context block with 11 elements",
			[{ type: "context", elements: Array.from({ length: 11 }, () => plain("x")) }],
		],
		["a context element that is a string", [{ type: "context", elements: ["x"] }]],
		[
			"a context element of 3001 characters",
			[{ type: "context", elements: [plain("x".repeat(3001))] }],
		],
		["a context_actions block with no elements", [{ type: "context_actions" }]],
		[
			"a context_actions block with 6 elements",
			[{ type: "context_actions", elements: Array.from({ length: 6 }, () => feedbackButtons) }],
		],
		[
			"a context_actions element of another type",
			[{ type: "context_actions", elements: [{ type: "icon_button" }] }],
		],
		[
			"feedback buttons without a negative button",
			[{ type: "context_actions", elements: [{ ...feedbackButtons, negative_button: undefined }] }],
		],
		[
			"a feedback button whose text is mrkdwn",
			[
				{
					type: "context_actions",
					elements: [
						{ ...feedbackButtons, positive_button: { text: mrkdwn("Helpful"), value: "v" } },
					],
				},
			],
		],
		[
			"a feedback button without a value",
			[
				{
					type: "context_actions",
					elements: [{ ...feedbackButtons, positive_button: { text: plain("Helpful") } }],
				},
			],
		],
		[
			"a feedback button value of 2001 characters",
			[
				{
					type: "context_actions",
					elements: [
						{
							...feedbackButtons,
							positive_button: { text: plain("Helpful"), value: "v".repeat(2001) },
						},
					],
				},
			],
		],
		[
			"a feedback button text of 76 characters",
			[
				{
					type: "context_actions",
					elements: [
						{ ...feedbackButtons, positive_button: { text: plain("t".repeat(76)), value: "v" } },
					],
				},
			],
		],
		[
			"a feedback button label of 76 characters",
			[
				{
					type: "context_actions",
					elements: [
						{
							...feedbackButtons,
							positive_button: {
								text: plain("Helpful"),
								value: "v",
								accessibility_label: "l".repeat(76),
							},
						},
					],
				},
			],
		],
		["an actions block with no elements", [{ type: "actions" }]],
		[
			"an actions block with 26 buttons",
			[{ type: "actions", elements: Array.from({ length: 26 }, () => button) }],
		],
		[
			"an actions element that is no button",
			[{ type: "actions", elements: [{ type: "static_select" }] }],
		],
		[
			"a button whose text is mrkdwn",
			[{ type: "actions", elements: [{ ...button, text: mrkdwn("Link") }] }],
		],
		[
			"a button with an unknown style",
			[{ type: "actions", elements: [{ ...button, style: "loud" }] }],
		],
		[
			"a button value of 2001 characters",
			[{ type: "actions", elements: [{ ...button, value: "v".repeat(2001) }] }],
		],
		[
			"a button confirmation without a deny label",
			[{ type: "actions", elements: [{ ...button, confirm: { ...confirm, deny: undefined } }] }],
		],
		["a block id of 256 characters", [{ ...markdown("x"), block_id: "b".repeat(256) }]],
		["a block type that the server never sends", [{ type: "divider" }]],
		["a header block", [{ type: "header", text: plain("Hephaestus") }]],
	];
	for (const [name, blocks] of invalid) {
		assert.equal(blocksError(blocks), "invalid_blocks", name);
	}
	// All markdown blocks of a payload share 12,000 characters.
	assert.equal(blocksError([markdown("x".repeat(12_000))]), null);
	assert.equal(
		blocksError([markdown("x".repeat(6000)), markdown("x".repeat(6001))]),
		"msg_blocks_too_long",
	);
});

await test("the fake lists the words of the blocks that a post carried", async () => {
	await withFake(async (base, token) => {
		const posted = await postMessage(
			base,
			{ authorization: `Bearer ${token}` },
			JSON.stringify({
				channel: "D1",
				text: "fallback",
				blocks: [
					{ type: "markdown", text: "the visible answer" },
					{ type: "context", elements: [{ type: "plain_text", text: "an AI label" }] },
				],
			}),
			"application/json",
		);
		assert.equal(posted.ok, true);
		const calls = await received(base);
		assert.equal(calls[0]?.blocksText, "the visible answer\nan AI label");
	});
});

await test("a request for the received calls returns as soon as one arrives, or empty at its deadline", async () => {
	await withFake(async (base, token) => {
		const started = Date.now();
		assert.deepEqual(await received(base, "?after=0&timeoutMs=100"), []);
		assert.ok(Date.now() - started >= 90);

		const waiting = received(base, "?after=0&timeoutMs=20000");
		await postMessage(
			base,
			{ authorization: `Bearer ${token}` },
			new URLSearchParams({ channel: "D1", text: "hello" }).toString(),
		);
		const arrived = await waiting;
		assert.deepEqual(
			arrived.map((call) => call.text),
			["hello"],
		);
		assert.ok(Date.now() - started < 10_000, "it did not wait for its deadline");
		// A call that arrived before the request is not held back, and `after` skips what the caller has seen.
		const heldBack = await received(base, "?after=0&timeoutMs=20000");
		assert.equal(heldBack.length, 1);
		assert.deepEqual(await received(base, "?after=1"), []);
	});
});
