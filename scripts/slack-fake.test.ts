import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { test } from "node:test";

import {
	directMessageEvent,
	installCookie,
	installState,
	slackSignature,
} from "./lib/slack-turn.ts";
import { FAKE_INSTALL_CODE, FAKE_TEAM, slackFake } from "./slack-fake.ts";

async function withFake(body: (base: string, token: string) => Promise<void>): Promise<void> {
	const { server, token } = slackFake();
	server.listen(0, "127.0.0.1");
	await new Promise((resolve) => server.once("listening", resolve));
	try {
		await body(`http://127.0.0.1:${(server.address() as AddressInfo).port}`, token);
	} finally {
		server.close();
	}
}

const received = async (base: string) => (await fetch(`${base}/received`)).json();

await test("the fake installs the app for its code only, and hands out one token per start", async () => {
	await withFake(async (base, token) => {
		const post = (code: string) =>
			fetch(`${base}/api/oauth.v2.access`, {
				method: "POST",
				headers: { "content-type": "application/x-www-form-urlencoded" },
				body: new URLSearchParams({ code, client_id: "c", client_secret: "s" }),
			}).then((response) => response.json());
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
		const post = (headers: Record<string, string>, body: string, contentType: string) =>
			fetch(`${base}/api/chat.postMessage`, {
				method: "POST",
				headers: { "content-type": contentType, ...headers },
				body,
			}).then((response) => response.json());
		assert.deepEqual(
			await post(
				{ authorization: "Bearer xoxb-other" },
				new URLSearchParams({ channel: "D1", text: "no" }).toString(),
				"application/x-www-form-urlencoded",
			),
			{ ok: false, error: "invalid_auth" },
		);
		const posted = await post(
			{ authorization: `Bearer ${token}` },
			new URLSearchParams({ channel: "D1", text: "an answer", thread_ts: "1.2" }).toString(),
			"application/x-www-form-urlencoded",
		);
		assert.equal((posted as { ok: boolean }).ok, true);
		const json = await post(
			{ authorization: `Bearer ${token}` },
			JSON.stringify({ channel: "D2", text: "as JSON", blocks: [{ type: "markdown" }] }),
			"application/json; charset=utf-8",
		);
		assert.equal((json as { channel: string }).channel, "D2");
		assert.deepEqual(await received(base), [
			{
				method: "chat.postMessage",
				authorized: false,
				channel: "D1",
				threadTs: null,
				text: "no",
				ts: null,
			},
			{
				method: "chat.postMessage",
				authorized: true,
				channel: "D1",
				threadTs: "1.2",
				text: "an answer",
				ts: (posted as { ts: string }).ts,
			},
			{
				method: "chat.postMessage",
				authorized: true,
				channel: "D2",
				threadTs: null,
				text: "as JSON",
				ts: (json as { ts: string }).ts,
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
		assert.deepEqual(await answer.json(), { ok: false, error: "unknown_method" });
		assert.equal(((await received(base)) as { method: string }[])[0]?.method, "users.info");
		assert.equal((await fetch(`${base}/health`)).status, 200);
		assert.equal((await fetch(`${base}/nothing`)).status, 404);
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
	const event = JSON.parse(
		directMessageEvent({
			team: "T1",
			user: "U1",
			channel: "D1",
			text: "hi",
			ts: "1760000000.000100",
			eventId: "Ev1",
		}),
	) as Record<string, unknown>;
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
