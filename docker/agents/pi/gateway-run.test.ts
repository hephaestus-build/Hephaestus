import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdir, mkdtemp, readdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { type TestContext } from "node:test";
import { collectTraces, upload } from "./gateway-run.ts";

function traceManifest(text: string) {
	const value: unknown = JSON.parse(text);
	assert.ok(typeof value === "object" && value !== null);
	assert.ok("sessionScanTruncated" in value && typeof value.sessionScanTruncated === "boolean");
	assert.ok("sessions" in value && Array.isArray(value.sessions));
	const sessions: unknown[] = value.sessions;
	return { sessionScanTruncated: value.sessionScanTruncated, sessions };
}

function omission(session: unknown) {
	assert.ok(typeof session === "object" && session !== null);
	assert.ok("file" in session && "omitted" in session && "summary" in session);
	return { file: session.file, omitted: session.omitted, summary: session.summary };
}

const CREDENTIAL = "eyJhbGciOiJIUzI1NiJ9.attempt-credential.signature";
/** Words a session read or wrote about a person; none of them may reach the metadata a transcript keeps alone. */
const PERSONAL = [
	"Jane Roe",
	"/workspace/inputs/people/7/person.json",
	"jane-roe-practice",
	"jane_roe_tool",
];

/** One native session file as Pi 1.0 writes it: header, binding, opening, tool call and result, compaction. */
function nativeSession(): string {
	const at = "2026-10-09T12:00:00.000Z";
	return `${[
		{ type: "session", version: 3, id: "s1", timestamp: at, cwd: "/workspace" },
		{
			type: "custom",
			id: "e1",
			parentId: null,
			timestamp: at,
			customType: "hephaestus.review-session",
			data: {
				phase: "practice",
				practiceSlug: "jane-roe-practice",
				practiceRevisionId: 12,
				model: "Jane Roe",
			},
		},
		{
			type: "message",
			id: "e2",
			parentId: "e1",
			timestamp: at,
			message: {
				role: "user",
				content: [{ type: "text", text: "Review the work of Jane Roe" }],
				timestamp: 0,
			},
		},
		{
			type: "message",
			id: "e3",
			parentId: "e2",
			timestamp: at,
			message: {
				role: "assistant",
				content: [
					{ type: "text", text: "Jane Roe wrote this" },
					{
						type: "toolCall",
						id: "c1",
						name: "read",
						arguments: { path: "/workspace/inputs/people/7/person.json", token: CREDENTIAL },
					},
					{ type: "toolCall", id: "c2", name: "jane_roe_tool", arguments: {} },
				],
				api: "openai-responses",
				provider: "hephaestus",
				model: "Jane Roe",
				usage: { input: 100, output: 20, cacheRead: 5, cacheWrite: 1, totalTokens: 126 },
				stopReason: "toolUse",
				timestamp: 0,
			},
		},
		{
			type: "message",
			id: "e4",
			parentId: "e3",
			timestamp: at,
			message: {
				role: "toolResult",
				toolCallId: "c1",
				toolName: "read",
				content: [{ type: "text", text: "Jane Roe <jane@example.com>" }],
				isError: true,
				timestamp: 0,
			},
		},
		{
			type: "compaction",
			id: "e5",
			parentId: "e4",
			timestamp: at,
			summary: "Jane Roe was reviewed",
			firstKeptEntryId: "e2",
			tokensBefore: 1000,
		},
	]
		.map((entry) => JSON.stringify(entry))
		.join("\n")}\n`;
}

async function collected(context: TestContext) {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-traces-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const sessions = path.join(directory, ".sessions");
	const out = path.join(directory, "out");
	await mkdir(sessions);
	await mkdir(path.join(out, "traces"), { recursive: true });
	await writeFile(path.join(out, "result.json"), "{}");
	return { sessions, out };
}

void test("copies whole native sessions without the attempt credential and summarizes them as enums and numbers", async (context) => {
	const { sessions, out } = await collected(context);
	const native = nativeSession();
	await writeFile(
		path.join(sessions, "2026-10-09_s1.jsonl"),
		`${native}{"type":"message","id":"torn`,
	);
	await writeFile(path.join(out, "traces", "planted.jsonl"), "written in the sandbox");

	await collectTraces(sessions, out, CREDENTIAL);

	const copiedFiles = await readdir(path.join(out, "traces"));
	assert.deepEqual(copiedFiles.toSorted(), ["0001.jsonl", "manifest.json"]);
	const copy = await readFile(path.join(out, "traces", "0001.jsonl"), "utf8");
	assert.equal(copy, native.replaceAll(CREDENTIAL, "[attempt-credential]"));
	assert.ok(!copy.includes(CREDENTIAL));
	assert.ok(copy.includes('"type":"compaction"') && copy.includes('"role":"toolResult"'));
	const text = await readFile(path.join(out, "traces", "manifest.json"), "utf8");
	const manifest = traceManifest(text);
	assert.equal(manifest.sessionScanTruncated, false);
	assert.deepEqual(manifest.sessions, [
		{
			file: "0001.jsonl",
			bytes: Buffer.byteLength(native) + '{"type":"message","id":"torn'.length,
			redacted: true,
			partialLineDropped: true,
			omitted: null,
			summary: {
				phase: "practice",
				practiceRevisionId: 12,
				entries: 6,
				assistantCalls: 1,
				stopReasons: { toolUse: 1 },
				usage: { input: 100, output: 20, cacheRead: 5, cacheWrite: 1 },
				toolCalls: { read: 1, other: 1 },
				toolErrors: 1,
				compactions: 1,
				modelFailures: {},
				lastModelFailureAt: null,
				finalModelFailure: null,
			},
		},
	]);
	for (const words of [...PERSONAL, CREDENTIAL]) {
		assert.ok(!text.includes(words), `the manifest carries ${words}`);
	}
	assert.equal(await readFile(path.join(out, "result.json"), "utf8"), "{}");
});

/** A native assistant message that failed, as the adapter wrote it, with text that must never be kept. */
function failedCall(id: string, stopReason: string, diagnostics: unknown): string {
	return JSON.stringify({
		type: "message",
		id,
		parentId: null,
		timestamp: "2026-10-09T12:00:00.000Z",
		message: {
			role: "assistant",
			content: [],
			api: "openai-completions",
			provider: "hephaestus",
			model: "m",
			usage: { input: 1, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 1 },
			stopReason,
			errorMessage: `503 ${CREDENTIAL}`,
			diagnostics,
			timestamp: 0,
		},
	});
}

void test("keeps only the adapter's closed failure facts, and clears the final failure after a success", async (context) => {
	const { sessions, out } = await collected(context);
	const header = JSON.stringify({
		type: "session",
		version: 3,
		id: "s1",
		timestamp: "2026-10-09T12:00:00.000Z",
		cwd: "/workspace",
	});
	const safe = [
		{
			type: "openai_completions_failure",
			timestamp: 1_760_000_000_000,
			details: { kind: "HTTP_ERROR", phase: "request", status: 503 },
		},
	];
	const malicious = [
		{
			type: "openai_completions_failure",
			timestamp: "now",
			error: { message: CREDENTIAL, stack: CREDENTIAL },
			details: { kind: CREDENTIAL, phase: CREDENTIAL, status: 200, body: CREDENTIAL },
		},
	];
	const success = failedCall("e3", "stop", undefined);
	await writeFile(
		path.join(sessions, "a.jsonl"),
		`${[header, failedCall("e1", "error", safe), failedCall("e2", "aborted", malicious)].join("\n")}\n`,
	);
	await writeFile(
		path.join(sessions, "b.jsonl"),
		`${[header, failedCall("e1", "error", safe), success].join("\n")}\n`,
	);
	await collectTraces(sessions, out, CREDENTIAL);
	const text = await readFile(path.join(out, "traces", "manifest.json"), "utf8");
	assert.ok(!text.includes(CREDENTIAL));
	const summaries = traceManifest(text).sessions.map((session) => omission(session).summary);
	assert.deepEqual(
		summaries.map((summary) => {
			assert.ok(typeof summary === "object" && summary !== null);
			const failures: unknown = Reflect.get(summary, "modelFailures");
			const lastAt: unknown = Reflect.get(summary, "lastModelFailureAt");
			const final: unknown = Reflect.get(summary, "finalModelFailure");
			return [failures, lastAt, final];
		}),
		[
			[
				{ HTTP_ERROR: 1, UNKNOWN: 1 },
				1_760_000_000_000,
				{ kind: "UNKNOWN", phase: null, status: null, at: null },
			],
			[{ HTTP_ERROR: 1 }, 1_760_000_000_000, null],
		],
	);
});

void test("omits a session past the per-file bound and keeps nothing when the result leaves no room", async (context) => {
	const { sessions, out } = await collected(context);
	await writeFile(path.join(sessions, "large.jsonl"), Buffer.alloc(2 * 1024 * 1024 + 1, 0x0a));
	await collectTraces(sessions, out, CREDENTIAL);
	const manifest = traceManifest(await readFile(path.join(out, "traces", "manifest.json"), "utf8"));
	assert.deepEqual(manifest.sessions.map(omission), [
		{ file: null, omitted: "oversize", summary: null },
	]);

	await writeFile(path.join(out, "observations.json"), Buffer.alloc(49 * 1024 * 1024, 0x20));
	await collectTraces(sessions, out, CREDENTIAL);
	const resultFiles = await readdir(out);
	assert.deepEqual(resultFiles.toSorted(), ["observations.json", "result.json"]);
});

void test("bounds session traversal and reports an incomplete scan without counting unseen entries", async (context) => {
	const { sessions, out } = await collected(context);
	await Promise.all(
		Array.from({ length: 1001 }, async (_, index) =>
			writeFile(path.join(sessions, `${index}.jsonl`), ""),
		),
	);
	await collectTraces(sessions, out, CREDENTIAL);
	const manifest = traceManifest(await readFile(path.join(out, "traces", "manifest.json"), "utf8"));
	assert.equal(manifest.sessionScanTruncated, true);
	assert.equal(manifest.sessions.length, 1000);
});

void test("marks an absent or linked session root as incomplete instead of a complete empty session set", async (context) => {
	const { sessions, out } = await collected(context);
	await collectTraces(sessions, out, CREDENTIAL);
	const empty = traceManifest(await readFile(path.join(out, "traces", "manifest.json"), "utf8"));
	assert.equal(empty.sessionScanTruncated, false);
	assert.deepEqual(empty.sessions, []);
	await rm(sessions, { recursive: true });
	await collectTraces(sessions, out, CREDENTIAL);
	const absent = traceManifest(await readFile(path.join(out, "traces", "manifest.json"), "utf8"));
	assert.equal(absent.sessionScanTruncated, true);
	const elsewhere = path.join(path.dirname(sessions), "elsewhere");
	await mkdir(elsewhere);
	await writeFile(path.join(elsewhere, "private.jsonl"), nativeSession());
	await symlink(elsewhere, sessions);
	await collectTraces(sessions, out, CREDENTIAL);
	const linked = traceManifest(await readFile(path.join(out, "traces", "manifest.json"), "utf8"));
	assert.equal(linked.sessionScanTruncated, true);
	assert.deepEqual(linked.sessions, []);
	assert.deepEqual(await readdir(path.join(out, "traces")), ["manifest.json"]);
});

void test("does not add trace members when the result already occupies the archive entry budget", async (context) => {
	const { sessions, out } = await collected(context);
	await writeFile(path.join(sessions, "review.jsonl"), nativeSession());
	await Promise.all(
		Array.from({ length: 9998 }, async (_, index) => mkdir(path.join(out, `entry-${index}`))),
	);
	await collectTraces(sessions, out, CREDENTIAL);
	const names = await readdir(out);
	assert.equal(names.length, 9999);
	assert.ok(!names.includes("traces"));
	assert.equal(await readFile(path.join(out, "result.json"), "utf8"), "{}");
});

for (const scenario of [
	{ name: "retries an unfinished upload", responses: [503, 204] },
	{
		name: "accepts an already committed result",
		responses: [409],
		converged: true,
	},
	{
		name: "rejects a non-owning worker conflict",
		responses: [409],
		expectedError: "Result upload was not confirmed: 409",
	},
	{
		name: "rejects a different admitted result",
		responses: [409],
		expectedError: "Result upload was not confirmed: 409",
		wrongDigest: true,
	},
	{
		name: "rejects an unconfirmed successful upload",
		responses: [204],
		expectedError: "Result upload was not confirmed: 204",
		wrongDigest: true,
	},
	{
		name: "does not retry a refused archive",
		responses: [422],
		expectedError: "Result upload refused: 422",
	},
	{ name: "retries a disconnected response", responses: [null, 204] },
]) {
	void test(scenario.name, async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "gateway-upload-"));
		const requests: {
			method: string | undefined;
			authorization: string | undefined;
			contentType: string | undefined;
			contentDigest: string | string[] | undefined;
			length: string | undefined;
			body: Buffer;
		}[] = [];
		const server = createServer((request, response) => {
			const chunks: Buffer[] = [];
			request.on("data", (chunk: Buffer) => {
				chunks.push(chunk);
			});
			request.on("end", () => {
				const status = scenario.responses[requests.length];
				requests.push({
					method: request.method,
					authorization: request.headers.authorization,
					contentType: request.headers["content-type"],
					contentDigest: request.headers["content-digest"],
					length: request.headers["content-length"],
					body: Buffer.concat(chunks),
				});
				if (status === null) {
					request.socket.destroy();
				} else {
					response.statusCode = status ?? 500;
					if (
						status === 204 ||
						(status === 409 && ("converged" in scenario || "wrongDigest" in scenario))
					) {
						response.setHeader(
							"ETag",
							`"${createHash("sha256")
								.update("wrongDigest" in scenario ? "other" : Buffer.concat(chunks))
								.digest("hex")}"`,
						);
					}
					response.end();
				}
			});
		});
		try {
			await writeFile(path.join(directory, "observations.json"), "{}");
			const archive = path.join(directory, "result.tar");
			execFileSync("tar", ["-cf", archive, "-C", directory, "observations.json"]);
			const bytes = await readFile(archive);
			server.listen(0, "127.0.0.1");
			await once(server, "listening");
			const address = server.address();
			assert.ok(address !== null && typeof address !== "string");
			const endpoint = new URL(`http://127.0.0.1:${address.port}/result`);
			context.diagnostic(endpoint.href);
			const uploaded = upload(endpoint, "test-credential", archive);
			if ("expectedError" in scenario) {
				await assert.rejects(uploaded, { message: scenario.expectedError });
			} else {
				await uploaded;
			}
			assert.equal(requests.length, scenario.responses.length);
			for (const request of requests) {
				assert.equal(request.method, "POST");
				assert.equal(request.authorization, "Bearer test-credential");
				assert.equal(request.contentType, "application/x-tar");
				assert.equal(
					request.contentDigest,
					`sha-256=:${createHash("sha256").update(bytes).digest("base64")}:`,
				);
				assert.equal(request.length, String(bytes.length));
				assert.deepEqual(request.body, bytes);
			}
		} finally {
			const closed = once(server, "close");
			server.close();
			server.closeAllConnections();
			await closed;
			await rm(directory, { recursive: true, force: true });
		}
	});
}

void test("stops retries at the upload deadline", async () => {
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-deadline-"));
	const server = createServer((_request, response) => {
		response.statusCode = 503;
		response.end();
	});
	try {
		const archive = path.join(directory, "result.tar");
		await writeFile(archive, "test archive");
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		await assert.rejects(
			upload(
				new URL(`http://127.0.0.1:${address.port}/result`),
				"token",
				archive,
				Date.now() + 150,
			),
			{ message: "Result upload deadline expired" },
		);
	} finally {
		const closed = once(server, "close");
		server.close();
		server.closeAllConnections();
		await closed;
		await rm(directory, { recursive: true, force: true });
	}
});
