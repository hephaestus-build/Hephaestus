import assert from "node:assert/strict";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";

import {
	createAgentSession,
	createBashTool,
	createCodemodeExtension,
	createReadTool,
	createFindTool,
	createGrepTool,
	createWriteTool,
	createEditTool,
	DefaultResourceLoader,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

async function checkNativeTools() {
	const cwd = "/workspace/inputs/sources/scm/repo";
	const bash = await createBashTool(cwd).execute("bash", {
		command:
			"git log -1 --format=%s && rg --hidden --no-ignore --only-matching SENTINEL .hidden/large.txt && find .hidden -type f && ! git -c user.name=fixture -c user.email=fixture@example.invalid commit --allow-empty -m forbidden && ! touch tracked.txt && ! mv /workspace/inputs /workspace/replaced-inputs && ! touch /opt/pi-sdk/package.json",
		timeout: 10,
	});
	assert.match(JSON.stringify(bash.content), /captured/u);
	assert.match(JSON.stringify(bash.content), /SENTINEL/u);
	assert.doesNotMatch(JSON.stringify(bash.content), /Command exited with code/u);
	const read = await createReadTool(cwd).execute("read", { path: "tracked.txt" });
	assert.match(JSON.stringify(read.content), /searchable/u);
	const scratch = "/workspace/work/analysis.txt";
	await createWriteTool(cwd).execute("write", { path: scratch, content: "before\n" });
	await createEditTool(cwd).execute("edit", {
		path: scratch,
		edits: [{ oldText: "before", newText: "after" }],
	});
	assert.equal(await readFile(scratch, "utf8"), "after\n");
	await assert.rejects(
		createWriteTool(cwd).execute("write", { path: "tracked.txt", content: "forbidden\n" }),
	);
	await assert.rejects(
		createEditTool(cwd).execute("edit", {
			path: "tracked.txt",
			edits: [{ oldText: "searchable", newText: "forbidden" }],
		}),
	);
	assert.equal(await readFile(`${cwd}/tracked.txt`, "utf8"), "searchable\n");
	const grep = await createGrepTool(cwd).execute("grep", { pattern: "searchable" });
	assert.match(JSON.stringify(grep.content), /tracked.txt/u);
	const find = await createFindTool(cwd).execute("find", { pattern: "*.txt" });
	assert.match(JSON.stringify(find.content), /tracked.txt/u);
	// Outside a repository Pi passes fd `--no-require-git`, which fd has only since 8.7.
	const scratchFind = await createFindTool("/workspace/work").execute("find", { pattern: "*.txt" });
	assert.match(JSON.stringify(scratchFind.content), /analysis.txt/u);
}

function chunk(body: object): string {
	return `data: ${JSON.stringify({ id: "c", object: "chat.completion.chunk", created: 0, model: "m", choices: [body] })}\n\n`;
}

/** One streamed Chat Completions answer: a tool call, or text that ends the run. */
function completion(delta: object, finishReason: string): string {
	return `${chunk({ index: 0, delta, finish_reason: null })}${chunk({ index: 0, delta: {}, finish_reason: finishReason })}data: [DONE]\n\n`;
}

/**
 * A session with the codemode extension, as the practice runner builds it, runs one script that reads
 * a checkout file through a nested tool call: the worker and the QuickJS wasm load in this image, as
 * this user, under the runner's heap flag.
 */
async function checkCodemode() {
	const cwd = "/workspace/inputs/sources/scm/repo";
	const agentDir = "/workspace/work/.pi";
	const answers = [
		completion(
			{
				role: "assistant",
				tool_calls: [
					{
						index: 0,
						id: "call_1",
						type: "function",
						function: {
							name: "codemode",
							arguments: JSON.stringify({
								code: 'text(await tools.read({ path: "tracked.txt" }));',
							}),
						},
					},
				],
			},
			"tool_calls",
		),
		completion({ role: "assistant", content: "done" }, "stop"),
	];
	const server = createServer((request, response) => {
		request.resume();
		response.writeHead(200, { "content-type": "text/event-stream" });
		response.end(answers.shift() ?? completion({ role: "assistant", content: "done" }, "stop"));
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	try {
		const address = server.address();
		assert.ok(address !== null && typeof address === "object");
		const { port } = address;
		const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
		const resourceLoader = new DefaultResourceLoader({
			cwd,
			agentDir,
			settingsManager,
			noContextFiles: true,
			extensionFactories: [createCodemodeExtension({ mode: "on", models: false })],
		});
		await resourceLoader.reload();
		const modelRuntime = await ModelRuntime.create({
			authPath: `${agentDir}/auth.json`,
			modelsPath: `${agentDir}/models.json`,
			allowModelNetwork: false,
		});
		modelRuntime.registerProvider("check", {
			name: "check",
			baseUrl: `http://127.0.0.1:${port}/v1`,
			apiKey: "check",
			api: "openai-completions",
			models: [
				{
					id: "m",
					name: "m",
					reasoning: false,
					input: ["text"],
					cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
					contextWindow: 32_000,
					maxTokens: 1000,
				},
			],
		});
		const model = modelRuntime.getModel("check", "m");
		assert.ok(model);
		const { session } = await createAgentSession({
			cwd,
			agentDir,
			tools: ["read", "codemode"],
			sessionManager: SessionManager.inMemory(cwd),
			settingsManager,
			resourceLoader,
			modelRuntime,
			model,
		});
		const results: string[] = [];
		session.subscribe((event) => {
			if (event.type === "tool_execution_end") {
				results.push(
					`${event.parentToolCallId === undefined ? "" : "nested "}${event.toolName}: ${JSON.stringify(event.result)}`,
				);
			}
		});
		await session.prompt("Read tracked.txt through codemode.");
		session.dispose();
		assert.ok(
			results.some((result) => result.startsWith("nested read:")),
			results.join("\n"),
		);
		assert.match(
			results.find((result) => result.startsWith("codemode:")) ?? "",
			/Script completed[\s\S]*searchable/u,
		);
	} finally {
		server.close();
	}
}

await checkNativeTools();
await checkCodemode();
