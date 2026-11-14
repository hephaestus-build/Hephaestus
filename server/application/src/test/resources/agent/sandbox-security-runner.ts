/** Runs inside the agent image. Only the HTTP model and admission responses are scripted. */
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { chmodSync, cpSync, mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";

import { INJECTION } from "./prompt-injection-corpus.ts";

const artifacts = "/artifacts";
const requests: unknown[] = [];
const failures: string[] = [];
const toolResults = new Map<string, string>();
const postUrl = process.env.SECURITY_POST_URL ?? "";
assert.match(postUrl, /^http:\/\/(?:\d{1,3}\.){3}\d{1,3}:8080\/outside-work\/99$/u);
const postScript = `try { const reply = await fetch(${JSON.stringify(postUrl)}, {method: "POST", body: "outside-work-probe", signal: AbortSignal.timeout(2000)}); console.log(reply.ok ? "posted" : "refused"); } catch { console.log("blocked"); }`;
const quote = (value: string) => `'${value.replaceAll("'", String.raw`'\''`)}'`;
const steps = [
	{ id: "metadata", name: "read", args: { path: "evidence/metadata.json" } },
	{ id: "body", name: "read", args: { path: "evidence/description.md" } },
	{ id: "comments", name: "read", args: { path: "evidence/comments.json" } },
	{ id: "file", name: "read", args: { path: "repos/primary/INSTRUCTIONS.md" } },
	{ id: "diff", name: "read", args: { path: "work/change/diff.patch" } },
	// The replay deliberately follows the injected request across the attempt boundary.
	{ id: "cross-read", name: "read", args: { path: "/workspace-a/evidence/metadata.json" } },
	{
		id: "cross-shell",
		name: "bash",
		args: {
			command:
				"if [ -e /workspace-a/evidence/metadata.json ]; then cat /workspace-a/evidence/metadata.json; else printf cross-workspace-unmounted; fi",
		},
	},
	{
		id: "outside-post",
		name: "bash",
		args: { command: `node --input-type=module -e ${quote(postScript)}` },
	},
	{
		id: "submit",
		name: "submit_result",
		args: {
			outcome: "COULD_NOT_DETERMINE",
			summary: "The scripted model makes no practice judgment.",
			explanation: "The replay checks tool boundaries.",
			citations: [{ path: "evidence/metadata.json", startLine: 1, quote: "Add login" }],
			evidence: {
				openQuestions: [
					{
						openQuestion: "Does the change meet the practice?",
						wouldSettleIt: "A practice assessment.",
					},
				],
			},
		},
	},
];
let calls = 0;
let sawInjection = false;
let sawInstruction = false;
let staged = 0;
let admitted = 0;

function containsText(value: unknown, expected: string): boolean {
	if (typeof value === "string") {
		return value.includes(expected);
	}
	if (Array.isArray(value)) {
		return value.some((item: unknown) => containsText(item, expected));
	}
	if (typeof value === "object" && value !== null) {
		return Object.values(value).some((item: unknown) => containsText(item, expected));
	}
	return false;
}

function observeMessages(messages: readonly unknown[]) {
	for (const message of messages) {
		assert.ok(typeof message === "object" && message !== null && !Array.isArray(message));
		if ("role" in message && (message.role === "system" || message.role === "developer")) {
			sawInstruction = true;
			assert.equal(
				containsText(message, INJECTION.split("\n")[0] ?? INJECTION),
				false,
				"Captured content must not become a system instruction",
			);
		}
		if (
			"role" in message &&
			message.role === "tool" &&
			"tool_call_id" in message &&
			typeof message.tool_call_id === "string"
		) {
			toolResults.set(message.tool_call_id, JSON.stringify(message));
		}
	}
}

// The host can have a different UID. Exported directories must not retain sticky tmpfs modes.
function exportPermissions(directory: string) {
	if (directory !== artifacts) {
		chmodSync(directory, 0o777);
	}
	for (const entry of readdirSync(directory, { withFileTypes: true })) {
		const target = `${directory}/${entry.name}`;
		if (entry.isDirectory()) {
			exportPermissions(target);
		} else {
			chmodSync(target, 0o666);
		}
	}
}

const server = createServer((request, response) => {
	void handle();
	async function handle() {
		try {
			const chunks: Buffer[] = [];
			for await (const chunk of request) {
				assert.ok(Buffer.isBuffer(chunk));
				chunks.push(chunk);
				assert.ok(chunks.reduce((size, part) => size + part.length, 0) <= 8 * 1024 * 1024);
			}
			const raw = Buffer.concat(chunks).toString("utf8");
			requests.push({ method: request.method, path: request.url, body: raw });
			const body: unknown = JSON.parse(raw);
			assert.ok(typeof body === "object" && body !== null && !Array.isArray(body));
			if (request.url === "/v1/chat/completions") {
				assert.ok("messages" in body && Array.isArray(body.messages));
				observeMessages(body.messages);
				sawInjection ||= containsText(body.messages, INJECTION.split("\n")[0] ?? INJECTION);
				if (calls >= 5 && process.env.SECURITY_SURFACE !== "control") {
					assert.ok(sawInjection, "The replay must receive the injection before it follows it");
				}
				const step = steps[calls];
				calls += 1;
				assert.ok(calls <= steps.length + 2, "The replay must terminate");
				const message =
					step === undefined
						? { role: "assistant", content: "Replay complete." }
						: {
								role: "assistant",
								content: null,
								tool_calls: [
									{
										id: step.id,
										type: "function",
										function: { name: step.name, arguments: JSON.stringify(step.args) },
									},
								],
							};
				response.writeHead(200, { "content-type": "text/event-stream" });
				const data = {
					id: `security-${calls}`,
					object: "chat.completion.chunk",
					created: 0,
					model: "test-model",
				};
				response.write(
					`data: ${JSON.stringify({ ...data, choices: [{ index: 0, delta: { ...message, tool_calls: step === undefined ? undefined : [{ index: 0, ...message.tool_calls?.[0] }] }, finish_reason: null }] })}\n\n`,
				);
				response.write(
					`data: ${JSON.stringify({ ...data, choices: [{ index: 0, delta: {}, finish_reason: step === undefined ? "stop" : "tool_calls" }], usage: { prompt_tokens: 100, completion_tokens: 30, total_tokens: 130 } })}\n\n`,
				);
				response.end("data: [DONE]\n\n");
			} else if (request.url === "/v1/start-practice") {
				const practiceSlug: unknown = Reflect.get(body, "practiceSlug");
				assert.ok(typeof practiceSlug === "string");
				response.writeHead(200, { "content-type": "application/json" });
				response.end(JSON.stringify({ practiceSlug, current: true }));
			} else if (request.url === "/v1/stage-result") {
				staged += 1;
				response
					.writeHead(200, { "content-type": "application/json" })
					.end(JSON.stringify({ practiceSlug: "test-practice", digest: "a".repeat(64) }));
			} else if (request.url === "/v1/admit-results") {
				admitted += 1;
				response.writeHead(200, { "content-type": "application/json" }).end(
					JSON.stringify({
						contractVersion: 4,
						admissionDigest: "a".repeat(64),
						admitted: [],
						withheld: [],
					}),
				);
			} else {
				failures.push("A request left the reviewed attempt's routes");
				response.writeHead(403).end();
			}
		} catch {
			failures.push("The replay received an invalid request");
			response.writeHead(400).end();
		}
	}
});
server.listen(0, "127.0.0.1");
await once(server, "listening");
const address = server.address();
assert.ok(address !== null && typeof address !== "string");
cpSync("/workspace/AGENTS.md", "/workspace/.pi/AGENTS.md");
const git = {
	GIT_CONFIG_COUNT: "1",
	GIT_CONFIG_KEY_0: "safe.directory",
	GIT_CONFIG_VALUE_0: "/workspace/repos/primary",
};
// As the workspace initializer does, before anything untrusted runs.
const view = spawnSync(process.execPath, ["/workspace/pi-change-view.js"], {
	cwd: "/workspace",
	env: { PATH: process.env.PATH, ...git },
	stdio: "inherit",
});
assert.equal(view.status, 0);
const child = spawn(process.execPath, ["--max-old-space-size=256", "/workspace/pi-runner.js"], {
	cwd: "/workspace",
	env: {
		...process.env,
		PI_RUNNER_CWD: "/workspace",
		PI_CODING_AGENT_DIR: "/workspace/.pi",
		AGENT_BUDGET_MS: "60000",
		PI_PRACTICE_MODEL_CALLS: "36",
		PI_PRACTICE_OUTPUT_TOKENS: "48000",
		PI_PRACTICE_INPUT_TOKENS: "342000",
		LLM_PROXY_URL: `http://127.0.0.1:${address.port}/v1`,
		LLM_PROXY_TOKEN: "synthetic-attempt",
		...git,
	},
	stdio: ["ignore", "pipe", "pipe"],
});
const output: Buffer[] = [];
child.stdout.on("data", (chunk: Buffer) => {
	output.push(chunk);
});
child.stderr.on("data", (chunk: Buffer) => {
	output.push(chunk);
});
const timer = setTimeout(() => {
	child.kill("SIGKILL");
}, 90_000);
try {
	await once(child, "close");
} finally {
	clearTimeout(timer);
	server.closeAllConnections();
	server.close();
	mkdirSync(artifacts, { recursive: true });
	writeFileSync(`${artifacts}/process.log`, Buffer.concat(output));
	writeFileSync(`${artifacts}/requests.json`, JSON.stringify(requests));
	for (const directory of ["out", ".sessions", ".pi", "work/notes"]) {
		cpSync(`/workspace/${directory}`, `${artifacts}/${directory}`, { recursive: true });
	}
	exportPermissions(artifacts);
}
assert.equal(child.exitCode, 0, "The real review runner must finish");
assert.ok(calls >= steps.length, "Every adversarial step must execute");
assert.equal(staged, 1, "The practice must reach staging");
assert.equal(admitted, 1, "The review must reach admission");
assert.deepEqual(failures, []);
// This replay checks workspace mounts and network isolation.
const readRefused = /ENOENT/u.test(toolResults.get("cross-read") ?? "");
const shellRefused = containsText(
	JSON.parse(toolResults.get("cross-shell") ?? "null"),
	"cross-workspace-unmounted",
);
const postBlocked = containsText(JSON.parse(toolResults.get("outside-post") ?? "null"), "blocked");
assert.ok(sawInstruction, "The replay must check an instruction message");
if (process.env.SECURITY_SURFACE !== "control") {
	assert.ok(sawInjection, "The model must receive the injected content");
}
writeFileSync(
	`${artifacts}/qualification.json`,
	JSON.stringify({ calls, staged, admitted, sawInjection, readRefused, shellRefused, postBlocked }),
);
assert.equal(readRefused, true, "The cross-workspace read must be refused");
assert.equal(shellRefused, true, "The shell must not see the other workspace");
assert.equal(postBlocked, true, "The outside-work post must be refused");
