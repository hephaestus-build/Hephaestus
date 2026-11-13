/**
 * TEST ONLY. The Slack Web API of the turn-roll drill: it installs the app, takes the messages that the server
 * posts, and lists them at `GET /received`. It holds no secret: the bot token is new on each start.
 */
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

/** What the fake tells the install: the team that the drill's Slack identity belongs to. */
export const FAKE_TEAM = { id: "T0TURNROLL", name: "Turn roll" } as const;
/** The code that the drill hands to the server's OAuth callback, as Slack would. */
export const FAKE_INSTALL_CODE = "turn-roll-install-code";

/** One call that reached the fake, with what the drill asserts on. */
export interface ReceivedCall {
	method: string;
	/** Whether the call carried the bot token that the install handed out. */
	authorized: boolean;
	channel: string | null;
	threadTs: string | null;
	text: string | null;
	/** The `ts` that `chat.postMessage` answered with. */
	ts: string | null;
}

const MAX_BODY_BYTES = 1024 * 1024;

async function readBody(request: IncomingMessage): Promise<string> {
	const chunks: Buffer[] = [];
	let size = 0;
	for await (const part of request) {
		const chunk = Buffer.isBuffer(part) ? part : Buffer.from(String(part));
		size += chunk.length;
		if (size > MAX_BODY_BYTES) {
			throw new RangeError("the request body is too large");
		}
		chunks.push(chunk);
	}
	return Buffer.concat(chunks).toString("utf8");
}

/** The fields of a request body: Slack's SDK posts forms, and a JSON object is as good. */
function fieldsOf(contentType: string | undefined, raw: string): Map<string, string> {
	const fields = new Map<string, string>();
	if (contentType?.startsWith("application/json") === true) {
		const parsed: unknown = JSON.parse(raw);
		if (typeof parsed === "object" && parsed !== null) {
			for (const [key, value] of Object.entries(parsed)) {
				fields.set(key, typeof value === "string" ? value : JSON.stringify(value));
			}
		}
		return fields;
	}
	for (const [key, value] of new URLSearchParams(raw)) {
		fields.set(key, value);
	}
	return fields;
}

/** A Slack Web API that lists what it received. Close the returned server to stop it. */
export function slackFake(): { server: Server; token: string } {
	const token = `xoxb-${randomUUID()}`;
	const received: ReceivedCall[] = [];
	const answer = (response: ServerResponse, body: unknown) => {
		response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(body));
	};
	const server = createServer((request, response) => {
		void (async () => {
			try {
				const path = new URL(request.url ?? "/", "http://fake").pathname;
				if (request.method === "GET" && path === "/health") {
					response.end("ok");
				} else if (request.method === "GET" && path === "/received") {
					answer(response, received);
				} else if (request.method === "POST" && path.startsWith("/api/")) {
					const method = path.slice("/api/".length);
					const fields = fieldsOf(request.headers["content-type"], await readBody(request));
					const authorized = request.headers.authorization === `Bearer ${token}`;
					const call: ReceivedCall = {
						method,
						authorized,
						channel: fields.get("channel") ?? null,
						threadTs: fields.get("thread_ts") ?? null,
						text: fields.get("text") ?? null,
						ts: null,
					};
					received.push(call);
					if (method === "oauth.v2.access") {
						answer(
							response,
							fields.get("code") === FAKE_INSTALL_CODE
								? {
										ok: true,
										access_token: token,
										token_type: "bot",
										team: FAKE_TEAM,
										bot_user_id: "U0TURNROLLBOT",
									}
								: { ok: false, error: "invalid_code" },
						);
					} else if (method === "chat.postMessage" && authorized && call.channel !== null) {
						call.ts = `${1_760_000_000 + received.length}.000100`;
						answer(response, { ok: true, channel: call.channel, ts: call.ts });
					} else {
						// Any other call is on the list for the drill's failure message, and Slack refuses it.
						answer(response, { ok: false, error: authorized ? "unknown_method" : "invalid_auth" });
					}
				} else {
					response.writeHead(404).end();
				}
			} catch {
				if (!response.headersSent) {
					response.writeHead(400);
				}
				response.end();
			}
		})();
	});
	return { server, token };
}

if (import.meta.main) {
	const { server } = slackFake();
	server.listen(8080, "0.0.0.0");
	for (const signal of ["SIGTERM", "SIGINT"] as const) {
		process.once(signal, () => {
			server.close();
		});
	}
}
