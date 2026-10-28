/**
 * The sandbox footprint benchmark: what a practice review sandbox uses, measured, not assumed.
 *
 * It runs the review Pod's two containers from the agent image with the Pod's file systems (disk
 * volumes for the attempt folders, /tmp and ~/.local, memory for /run, a read-only root) and its
 * limits, against a stand-in gateway in this process. The gateway serves a workspace that
 * `ReviewFootprintStagingLiveTest` staged with the real adapter, the bundled practices and a real
 * repository change. Only the model is a stand-in: it answers each turn with reads, searches and
 * Git commands on the reviewed repository, then a result, after a set latency.
 *
 * A privileged sampler container reads each sandbox's cgroup (memory.current, memory.peak,
 * memory.stat, cpu.stat, pids.current) and the RSS of each of its processes, every 250 ms. cgroup v2
 * uses the same counters on Docker and Kubernetes. Placement and node overhead require a cluster run.
 *
 *   node scripts/sandbox-footprint.ts --stage <dir> [--image <ref>] [--reviews <n>] [--memory 3g]
 *     [--tool-calls 12] [--latency-ms 1500] [--node-options "--max-old-space-size=256"]
 *     [--practice-concurrency <n>] [--work <dir>] [--out <file>]
 *
 * The work directory receives the container logs, `samples.jsonl` and, without `--out`,
 * `footprint.json`. The run exits 1 unless its verdict shows that every review was measured.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { once } from "node:events";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { isIPv4 } from "node:net";
import { cpus, loadavg, tmpdir } from "node:os";
import path from "node:path";
import { createInterface } from "node:readline";
import { pipeline } from "node:stream/promises";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { asRecord, asString, asStringArray, isRecord, parseJson } from "./lib/json.ts";
import { exitStatus, output, run, succeeds } from "./lib/process.ts";
import {
	BUDGETS,
	largest,
	NOT_STARTED,
	parseSample,
	positiveInteger,
	REPORT_MAP_LIMIT,
	SAMPLE_MS,
	verdict,
	type Sample,
} from "./lib/sandbox-footprint-report.ts";
import { percentile } from "./lib/statistics.ts";

const { values: options } = parseArgs({
	options: {
		stage: { type: "string" },
		image: { type: "string", default: "hephaestus-footprint/agent-pi:bench" },
		reviews: { type: "string", default: "1" },
		memory: { type: "string", default: "3g" },
		cpus: { type: "string", default: "1" },
		"tool-calls": { type: "string", default: "12" },
		"latency-ms": { type: "string", default: "1500" },
		"work-seconds": { type: "string", default: "1500" },
		"budget-seconds": { type: "string", default: "1200" },
		"node-options": { type: "string", default: "" },
		"practice-concurrency": { type: "string", default: "" },
		label: { type: "string", default: "baseline" },
		work: { type: "string" },
		out: { type: "string" },
	},
});

const stage = path.resolve(required(options.stage, "--stage"));
const { image } = options;
const reviews = positiveInteger(options.reviews, "--reviews");
const toolCalls = positiveInteger(options["tool-calls"], "--tool-calls");
const latencyMs = positiveInteger(options["latency-ms"], "--latency-ms");
const workSeconds = positiveInteger(options["work-seconds"], "--work-seconds");
const budgetSeconds = positiveInteger(options["budget-seconds"], "--budget-seconds");
const practiceConcurrency =
	options["practice-concurrency"] === ""
		? null
		: positiveInteger(options["practice-concurrency"], "--practice-concurrency");
const runId = `fp-${randomUUID().slice(0, 8)}`;

/** The memory volumes of a review Pod (KubernetesPods.MEMORY_VOLUMES) and its disk volumes (attemptVolumes). */
const MEMORY_VOLUMES = {
	"/run": "16777216",
};
const DISK_VOLUMES = [
	"/workspace",
	"/workspace/work",
	"/workspace/out",
	"/workspace/.pi",
	"/workspace/.sessions",
	"/tmp",
	"/home/agent/.local",
];
const GIT_SECURITY = [
	["core.hooksPath", "/nonexistent"],
	["core.fsmonitor", "false"],
	["core.sshCommand", ""],
	["core.askPass", ""],
	["core.editor", ""],
	["core.pager", "cat"],
	["core.gitProxy", ""],
	["sequence.editor", ""],
	["credential.helper", ""],
	["diff.external", ""],
	["protocol.ext.allow", "never"],
];
const RLIMITS = ["/usr/bin/prlimit", "--nofile=1024:1024", "--core=0:0", "--"];
const REPO = "/workspace/repos/reviewed";

function required(value: string | undefined, name: string): string {
	if (value === undefined || value === "") {
		throw new Error(`${name} is required`);
	}
	return value;
}

// ---------------------------------------------------------------- staged workspace

const spec = asRecord(parseJson(await readFile(`${stage}.spec.json`, "utf8")), "spec");
const specCommand = asStringArray(spec.command, "spec.command");
const specEnvironment = asRecord(spec.environment, "spec.environment");
const change = asRecord(
	parseJson(await readFile(path.join(stage, "context/change.json"), "utf8")),
	"change",
);
const baseSha = asString(change.base_sha, "base_sha");
const headSha = asString(change.head_sha, "head_sha");
const repoFiles = await readableFiles();
const firstLines = new Map<string, { line: number; text: string }>();
for (const file of repoFiles) {
	const content = await readFile(path.join(stage, "repos/reviewed", file), "utf8");
	const lines = content.split("\n");
	const index = lines.findIndex((line) => line.trim().length > 3);
	firstLines.set(file, { line: index + 1, text: (lines[index] ?? "").trim() });
}

async function readableFiles(): Promise<string[]> {
	const listing = await output("git", ["-C", path.join(stage, "repos/reviewed"), "ls-files", "-z"]);
	const files: string[] = [];
	for (const file of listing.split("\0").filter(Boolean)) {
		const stats = await stat(path.join(stage, "repos/reviewed", file)).catch(() => null);
		const size = stats?.size ?? 0;
		if (
			size > 1024 &&
			size < 64 * 1024 &&
			/\.(?<extension>java|kt|swift|ts|tsx|js|py|go|rs|md|ya?ml|json)$/u.test(file)
		) {
			files.push(file);
		}
	}
	return files;
}

const practices = asStringArray(spec.practices, "spec.practices").length;

const work = await workDirectory(options.work);
const archive = path.join(work, "workspace.tar");

async function workDirectory(dir: string | undefined): Promise<string> {
	if (dir === undefined) {
		return mkdtemp(path.join(tmpdir(), `${runId}-`));
	}
	const resolved = path.resolve(dir);
	await mkdir(resolved, { recursive: true });
	const present = await readdir(resolved);
	if (present.length > 0) {
		throw new Error(`--work ${resolved} must be empty or absent`);
	}
	return resolved;
}

/** Writes the staged workspace as the archive that the gateway serves; returns its size. */
async function packWorkspace(): Promise<number> {
	const listing = await output("ls", ["-A", stage]);
	const entries = listing.split("\n").filter(Boolean);
	// The image's GNU tar writes the archive as the gateway does: plain relative names, no host metadata.
	const tar = spawn("docker", [
		"run",
		"--rm",
		"-v",
		`${stage}:/src:ro`,
		"-w",
		"/src",
		"--entrypoint",
		"tar",
		image,
		"--create",
		"--format=gnu",
		"--file",
		"-",
		...entries,
	]);
	tar.stderr.pipe(process.stderr);
	await Promise.all([once(tar, "exit"), pipeline(tar.stdout, createWriteStream(archive))]);
	if (tar.exitCode !== 0) {
		throw new Error(`tar exited with ${exitStatus(tar.exitCode, tar.signalCode)}`);
	}
	const { size } = await stat(archive);
	return size;
}

// ---------------------------------------------------------------- stand-in gateway and model

interface ModelStats {
	calls: number;
	toolCalls: number;
	submits: number;
	promptBytesMax: number;
}
const modelStats: ModelStats = { calls: 0, toolCalls: 0, submits: 0, promptBytesMax: 0 };
const sessions = new Set<string>();
/** The result uploads per session, which the verdict reads. */
const uploads = new Map<string, number>();
function recordUpload(session = "") {
	uploads.set(session, (uploads.get(session) ?? 0) + 1);
}

async function body(request: IncomingMessage): Promise<Buffer> {
	const chunks: Buffer[] = [];
	for await (const part of request) {
		chunks.push(Buffer.isBuffer(part) ? part : Buffer.from(String(part)));
	}
	return Buffer.concat(chunks);
}

/** One deterministic next step from what the conversation already holds: read, search, Git, then the result. */
function nextStep(
	messages: unknown[],
	tools: string[],
	seed: number,
): { name: string; args: unknown } | null {
	const done = messages.filter(
		(m) => isRecord(m) && m.role === "assistant" && Array.isArray(m.tool_calls),
	).length;
	const has = (name: string) => tools.includes(name);
	if (done < toolCalls && (has("read") || has("bash"))) {
		const pick = repoFiles[(seed + done * 7) % Math.max(1, repoFiles.length)] ?? "README.md";
		const commands = [
			`rg -n --max-count 40 "TODO|FIXME|catch|password|secret|token|print" ${REPO} | head -400`,
			`git -C ${REPO} log --stat -n 30 --format=medium`,
			`git -C ${REPO} diff ${baseSha} ${headSha} | head -3000`,
			`git -C ${REPO} log --oneline --all | wc -l && rg --files ${REPO} | wc -l`,
			`git -C ${REPO} blame -w ${headSha} -- ${pick} | head -300`,
		];
		// A model reads the change first; the runner refuses a result from a session that did not.
		if (done === 0 && has("read")) {
			return { name: "read", args: { path: "work/change/diff.patch" } };
		}
		if (has("read") && (done % 2 === 1 || !has("bash"))) {
			return { name: "read", args: { path: `${REPO}/${pick}` } };
		}
		return { name: "bash", args: { command: commands[done % commands.length] } };
	}
	if (has("submit_result") && done < toolCalls + 3) {
		// Cites the first line of the file this session read first, so the runner admits the result.
		const first = repoFiles[(seed + 7) % Math.max(1, repoFiles.length)] ?? "README.md";
		const cited = firstLines.get(first) ?? { line: 1, text: "" };
		return {
			name: "submit_result",
			args: {
				outcome: "COULD_NOT_DETERMINE",
				summary: "The stand-in model cannot decide this practice.",
				explanation: "The benchmark stand-in model does not judge the practice.",
				citations: [{ path: first, startLine: cited.line, quote: cited.text }],
				evidence: {
					openQuestions: [
						{
							openQuestion: "Does the change meet the practice?",
							wouldSettleIt: "A real model's reading of the change.",
						},
					],
				},
			},
		};
	}
	return null;
}

function chunk(
	response: ServerResponse,
	model: string,
	delta: unknown,
	finish: string | null,
	usage?: unknown,
) {
	const payload = {
		id: "fp",
		object: "chat.completion.chunk",
		created: Math.floor(Date.now() / 1000),
		model,
		choices: [{ index: 0, delta, finish_reason: finish }],
		...(usage === undefined ? {} : { usage }),
	};
	response.write(`data: ${JSON.stringify(payload)}\n\n`);
}

async function completions(request: IncomingMessage, response: ServerResponse) {
	const raw = await body(request);
	const requestBody = asRecord(parseJson(raw.toString("utf8")), "completion request");
	const messages = Array.isArray(requestBody.messages) ? requestBody.messages : [];
	const tools = (Array.isArray(requestBody.tools) ? requestBody.tools : []).flatMap((tool) =>
		isRecord(tool) && isRecord(tool.function) && typeof tool.function.name === "string"
			? [tool.function.name]
			: [],
	);
	const model = typeof requestBody.model === "string" ? requestBody.model : "footprint-model";
	modelStats.calls += 1;
	modelStats.promptBytesMax = Math.max(modelStats.promptBytesMax, raw.length);
	const seed = createHash("sha256")
		.update(JSON.stringify(messages[0] ?? ""))
		.digest()
		.readUInt32BE(0);
	const step = nextStep(messages, tools, seed);
	if (step?.name === "submit_result") {
		modelStats.submits += 1;
	} else if (step !== null) {
		modelStats.toolCalls += 1;
	}
	const usage = {
		prompt_tokens: Math.ceil(raw.length / 4),
		completion_tokens: 400,
		total_tokens: Math.ceil(raw.length / 4) + 400,
	};
	// A model's reasoning before it acts: about 2 KB of text per turn.
	const text = "I check the change against the practice and read what it touches next. ".repeat(28);
	await sleep(latencyMs);
	if (requestBody.stream !== true) {
		const message =
			step === null
				? { role: "assistant", content: text }
				: {
						role: "assistant",
						content: text,
						tool_calls: [
							{
								id: `call_${randomUUID()}`,
								type: "function",
								function: { name: step.name, arguments: JSON.stringify(step.args) },
							},
						],
					};
		response.writeHead(200, { "content-type": "application/json" });
		response.end(
			JSON.stringify({
				id: "fp",
				object: "chat.completion",
				created: Math.floor(Date.now() / 1000),
				model,
				choices: [{ index: 0, message, finish_reason: step === null ? "stop" : "tool_calls" }],
				usage,
			}),
		);
		return;
	}
	response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
	chunk(response, model, { role: "assistant", content: text }, null);
	if (step !== null) {
		chunk(
			response,
			model,
			{
				tool_calls: [
					{
						index: 0,
						id: `call_${randomUUID()}`,
						type: "function",
						function: { name: step.name, arguments: JSON.stringify(step.args) },
					},
				],
			},
			null,
		);
	}
	chunk(response, model, {}, step === null ? "stop" : "tool_calls", usage);
	response.end("data: [DONE]\n\n");
}

async function serve(request: IncomingMessage, response: ServerResponse) {
	const url = new URL(request.url ?? "/", "http://gateway");
	const runtime = /^\/internal\/llm\/runtime\/(?<session>[^/]+)(?<route>\/.*)?$/u.exec(
		url.pathname,
	)?.groups;
	try {
		if (runtime !== undefined && sessions.has(runtime.session ?? "")) {
			const route = runtime.route ?? "";
			if (request.method === "GET" && route === "") {
				response.writeHead(200, { "content-type": "application/json" });
				response.end(
					JSON.stringify({ protocolVersion: 3, workspaceByteBudget: 512 * 1024 * 1024 }),
				);
			} else if (request.method === "GET" && route === "/workspace") {
				const { size } = await stat(archive);
				response.writeHead(200, {
					"content-type": "application/x-tar",
					"content-length": String(size),
				});
				createReadStream(archive).pipe(response);
			} else if (request.method === "POST" && route === "/result") {
				const uploaded = await body(request);
				recordUpload(runtime.session);
				response.writeHead(204, {
					etag: `"${createHash("sha256").update(uploaded).digest("hex")}"`,
				});
				response.end();
			} else {
				response.writeHead(404).end();
			}
		} else if (request.method === "POST" && url.pathname.endsWith("/admit-results")) {
			// Admission is the server's; the stand-in admits nothing and withholds nothing.
			const digest = createHash("sha256")
				.update(await body(request))
				.digest("hex");
			response.writeHead(200, { "content-type": "application/json" });
			response.end(
				JSON.stringify({ contractVersion: 4, admissionDigest: digest, admitted: [], withheld: [] }),
			);
		} else if (request.method === "POST" && url.pathname.endsWith("/chat/completions")) {
			await completions(request, response);
		} else {
			console.error(`gateway: unknown ${String(request.method)} ${url.pathname}`);
			response.writeHead(404).end();
		}
	} catch (error) {
		console.error(error);
		if (!response.headersSent) {
			response.writeHead(500);
		}
		response.end();
	}
}

const server = createServer((request, response) => {
	void serve(request, response);
});

let fixtureGateway: string | undefined;
let fixtureNetwork: string | undefined;

function gatewayUrl(): string {
	if (fixtureGateway !== undefined) {
		return fixtureGateway;
	}
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("The stand-in gateway has no TCP port");
	}
	return `http://host.docker.internal:${address.port}`;
}

/**
 * The fixture stays on a private container network. A Unix socket joins its gateway to this process
 * without exposing repository archives on a host interface or depending on host firewall policy.
 */
async function startGateway(): Promise<void> {
	if (process.platform !== "linux") {
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		return;
	}
	server.listen(path.join(benchDir, "gateway.sock"));
	await once(server, "listening");
	fixtureNetwork = `${runId}-network`;
	await run("docker", ["network", "create", "--internal", fixtureNetwork], { stdout: "ignore" });
	const created = await output("docker", [
		"run",
		"-d",
		"--name",
		`${runId}-gateway`,
		"--network",
		fixtureNetwork,
		"--read-only",
		"--cap-drop",
		"ALL",
		"--security-opt",
		"no-new-privileges",
		"-v",
		`${benchDir}:/bench:ro`,
		"alpine/socat@sha256:c5a091e1e735a90aa941a5828529dbe6c6157407a8047a9aa473faa133361b82",
		"TCP-LISTEN:8080,fork,reuseaddr",
		"UNIX-CONNECT:/bench/gateway.sock",
	]);
	const id = created.trim();
	const inspected = await output("docker", [
		"inspect",
		"--format",
		"{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
		id,
	]);
	const address = inspected.trim();
	if (!isIPv4(address)) {
		throw new Error("The fixture gateway has no IPv4 address");
	}
	fixtureGateway = `http://${address}:8080`;
}

function errorMessage(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

// ---------------------------------------------------------------- sampler

/** Runs as root in the host's PID and cgroup namespaces; reads only. One JSON line per container per tick. */
const SAMPLER = String.raw`
const fs = require("node:fs");
const read = (f) => { try { return fs.readFileSync(f, "utf8"); } catch { return null; } };
const paths = new Map();
function find(id) {
  if (paths.has(id)) return paths.get(id);
  const roots = ["/sys/fs/cgroup/docker/" + id, "/sys/fs/cgroup/system.slice/docker-" + id + ".scope"];
  const found = roots.find((p) => fs.existsSync(p)) ?? null;
  if (found) paths.set(id, found);
  return found;
}
function du(dir) {
  let bytes = 0; const stack = [dir];
  while (stack.length) { const d = stack.pop(); let list; try { list = fs.readdirSync(d, { withFileTypes: true }); } catch { continue; }
    for (const e of list) { const p = d + "/" + e.name; if (e.isDirectory()) stack.push(p); else { try { bytes += fs.lstatSync(p).blocks * 512; } catch {} } } }
  return bytes;
}
let tick = 0;
setInterval(() => {
  tick++;
  const t = Date.now();
  const ids = (read("/bench/ids") ?? "").split("\n").filter(Boolean);
  for (const line of ids) {
    const [label, id] = line.split(" ");
    const cg = find(id); if (!cg) continue;
    // A container that exits between these reads takes its cgroup with it: skip that tick.
    const current = read(cg + "/memory.current"); const memoryStat = read(cg + "/memory.stat"); const cpuStat = read(cg + "/cpu.stat");
    if (current === null || memoryStat === null || cpuStat === null) continue;
    const stat = Object.fromEntries(memoryStat.trim().split("\n").map((l) => { const [k, v] = l.split(" "); return [k, Number(v)]; }));
    const cpu = Object.fromEntries(cpuStat.trim().split("\n").map((l) => { const [k, v] = l.split(" "); return [k, Number(v)]; }));
    const procs = (read(cg + "/cgroup.procs") ?? "").split("\n").filter(Boolean).map((pid) => {
      const status = read("/proc/" + pid + "/status") ?? "";
      const kb = (k) => Number((new RegExp("^" + k + ":\\s+(\\d+)", "m").exec(status) ?? [0, 0])[1]) * 1024;
      const cmd = (read("/proc/" + pid + "/cmdline") ?? "").split("\0").filter(Boolean).filter((a) => !a.startsWith("--") && a !== "/usr/bin/prlimit").map((a) => a.split("/").pop()).slice(0, 2).join(" ");
      return { pid: Number(pid), cmd, rss: kb("VmRSS"), hwm: kb("VmHWM"), anon: kb("RssAnon"), file: kb("RssFile"), shmem: kb("RssShmem") };
    });
    let tmpfs;
    if (tick % 8 === 0 && procs.length) {
      const root = "/proc/" + procs[0].pid + "/root";
      tmpfs = {"/run": du(root + "/run")};
    }
    process.stdout.write(JSON.stringify({ t, label, current: Number(current), peak: Number(read(cg + "/memory.peak") ?? 0),
      anon: stat.anon, file: stat.file, shmem: stat.shmem, kernel: stat.kernel, sock: stat.sock, activeFile: stat.active_file, inactiveFile: stat.inactive_file,
      cpuUsec: cpu.usage_usec, throttledUsec: cpu.throttled_usec, pids: Number(read(cg + "/pids.current") ?? 0), procs, tmpfs }) + "\n");
  }
}, ${SAMPLE_MS});
`;

const benchDir = path.join(work, "bench");
const idsFile = path.join(benchDir, "ids");
const watched: string[] = [];
async function watch(label: string, id: string) {
	watched.push(`${label} ${id}`);
	await writeFile(idsFile, `${watched.join("\n")}\n`);
}

const samples: Sample[] = [];
/** Lines the report cannot read: they fail the verdict instead of vanishing. */
const malformed: string[] = [];
/** What stopped the run or a review before it ended: each fails the verdict. */
const failures: string[] = [];

function startSampler(): ChildProcess {
	const sampler = spawn("docker", [
		"run",
		"--rm",
		"--name",
		`${runId}-sampler`,
		"--privileged",
		"--pid=host",
		"--cgroupns=host",
		"--user",
		"0",
		"-v",
		"/sys/fs/cgroup:/sys/fs/cgroup:ro",
		"-v",
		`${benchDir}:/bench:ro`,
		"--entrypoint",
		"node",
		image,
		"-e",
		SAMPLER,
	]);
	sampler.once("error", (error) => {
		failures.push(`the sampler did not start: ${error.message}`);
	});
	sampler.stderr.pipe(process.stderr);
	const lines = createInterface({ input: sampler.stdout });
	let lastLine = "";
	lines.on("line", (line) => {
		lastLine = line;
		const sample = parseSample(line);
		if (sample === null) {
			malformed.push(line);
		} else {
			samples.push(sample);
		}
	});
	// Stopping the sampler can cut its last write short; that line is not a reading error.
	samplerClosed = (async () => {
		await once(lines, "close");
		// A readable line never equals an unreadable one, so this drops only an unreadable last line.
		if (lastLine !== "" && malformed.at(-1) === lastLine) {
			malformed.pop();
		}
	})();
	return sampler;
}

// ---------------------------------------------------------------- one review

interface ReviewRun {
	index: number;
	initExit: number;
	runtimeExit: number;
	initMs: number;
	runtimeMs: number;
	startMs: number;
	diskBytes: Record<string, number>;
	uploads: number;
	initializeLog: string;
	log: string;
}

/** What the run created in Docker and has not removed yet, for the cleanup after a failure. */
const containers = new Set<string>();
const volumesCreated = new Set<string>();

function environment(sessionId: string): string[] {
	const env: Record<string, string> = {};
	for (const [key, value] of Object.entries(specEnvironment)) {
		env[key] = String(value);
	}
	for (const [index, [key, value]] of GIT_SECURITY.entries()) {
		env[`GIT_CONFIG_KEY_${index}`] = key ?? "";
		env[`GIT_CONFIG_VALUE_${index}`] = value ?? "";
	}
	env.GIT_CONFIG_COUNT = String(GIT_SECURITY.length);
	env.GIT_TERMINAL_PROMPT = "0";
	env.GIT_ATTR_NOSYSTEM = "1";
	const gateway = gatewayUrl();
	env.LLM_PROXY_URL = `${gateway}/internal/llm`;
	env.LLM_PROXY_TOKEN = "footprint-token";
	env.SANDBOX_RUNTIME_URL = `${gateway}/internal/llm/runtime/${sessionId}`;
	const deadline = Date.now() + workSeconds * 1000;
	env.SANDBOX_WORK_DEADLINE_MS = String(deadline);
	env.SANDBOX_UPLOAD_DEADLINE_MS = String(deadline + 120_000);
	// The live staging's budget is a test's; a review's is the binding's.
	env.AGENT_BUDGET_MS = String(budgetSeconds * 1000);
	if (options["node-options"] !== "") {
		env.NODE_OPTIONS = options["node-options"];
	}
	if (practiceConcurrency !== null) {
		env.PI_PRACTICE_CONCURRENCY = String(practiceConcurrency);
	}
	return Object.entries(env).flatMap(([key, value]) => ["--env", `${key}=${value}`]);
}

function podFlags(name: string, volumes: string[], workspaceReadOnly: boolean): string[] {
	const flags = [
		"--name",
		name,
		"--read-only",
		"--cap-drop",
		"ALL",
		"--security-opt",
		"no-new-privileges",
		"--user",
		"1000:1000",
		"--group-add",
		"1000",
		"--memory",
		options.memory,
		"--memory-swap",
		options.memory,
		"--cpus",
		options.cpus,
		"--pids-limit",
		"256",
		"-w",
		"/workspace",
	];
	if (fixtureNetwork === undefined) {
		flags.push("--add-host", "host.docker.internal:host-gateway");
	} else {
		flags.push("--network", fixtureNetwork);
	}
	for (const [target, size] of Object.entries(MEMORY_VOLUMES)) {
		flags.push("--mount", `type=tmpfs,dst=${target},tmpfs-size=${size},tmpfs-mode=1777`);
	}
	for (const [index, target] of DISK_VOLUMES.entries()) {
		const readOnly = workspaceReadOnly && target === "/workspace" ? ",readonly" : "";
		flags.push(
			"--mount",
			`type=volume,src=${volumes[index] ?? ""},dst=${target},volume-nocopy${readOnly}`,
		);
	}
	return flags;
}

async function container(
	label: string,
	name: string,
	args: string[],
): Promise<{ exit: number; ms: number }> {
	const started = Date.now();
	// Created first, so the sampler knows the container before its first process runs.
	const created = await output("docker", ["create", ...args]);
	const id = created.trim();
	containers.add(id);
	await watch(label, id);
	await sleep(SAMPLE_MS);
	await run("docker", ["start", id], { stdout: "ignore" });
	const waited = await output("docker", ["wait", id]);
	const exit = Number(waited.trim());
	const ms = Date.now() - started;
	await sleep(SAMPLE_MS * 2);
	// The sandbox writes to stderr, which `docker logs` passes through as its own stderr.
	await writeFile(
		path.join(work, `${name}.log`),
		await output("sh", ["-c", `docker logs ${id} 2>&1 | tail -n 400`]).catch(() => ""),
	);
	await run("docker", ["rm", "-f", id], { stdout: "ignore" });
	containers.delete(id);
	return { exit, ms };
}

async function review(index: number): Promise<ReviewRun> {
	const sessionId = randomUUID();
	sessions.add(sessionId);
	const volumes = DISK_VOLUMES.map((_, v) => `${runId}-${index}-v${v}`);
	for (const volume of volumes) {
		await run("docker", ["volume", "create", volume], { stdout: "ignore" });
		volumesCreated.add(volume);
	}
	// As a Pod's fsGroup leaves an emptyDir: root's, writable by group 1000, new files in that group.
	await run("docker", [
		"run",
		"--rm",
		"--user",
		"0",
		...volumes.flatMap((v, i) => ["-v", `${v}:/v${i}`]),
		"--entrypoint",
		"sh",
		image,
		"-c",
		volumes.map((_, i) => `chown 0:1000 /v${i} && chmod 2775 /v${i}`).join(" && "),
	]);
	const startMs = Date.now();
	const init = await container(`review-${index}/initialize`, `${runId}-${index}-init`, [
		...podFlags(`${runId}-${index}-init`, volumes, false),
		...environment(sessionId),
		"--entrypoint",
		RLIMITS[0] ?? "",
		image,
		...RLIMITS.slice(1),
		"sh",
		"-c",
		"ln -s /opt/pi-sdk/node_modules /workspace/node_modules && exec node /opt/pi-sdk/gateway-init.js",
	]);
	let runtime = { exit: NOT_STARTED, ms: 0 };
	if (init.exit === 0) {
		runtime = await container(`review-${index}/runtime`, `${runId}-${index}-runtime`, [
			...podFlags(`${runId}-${index}-runtime`, volumes, true),
			...environment(sessionId),
			"--entrypoint",
			RLIMITS[0] ?? "",
			image,
			...RLIMITS.slice(1),
			"node",
			"--disable-sigusr1",
			"/opt/pi-sdk/gateway-run.js",
			...specCommand,
		]);
	}
	const disk: Record<string, number> = {};
	const du = await output("docker", [
		"run",
		"--rm",
		"--user",
		"0",
		...volumes.flatMap((v, i) => ["-v", `${v}:/v${i}:ro`]),
		"--entrypoint",
		"du",
		image,
		"-sb",
		...volumes.map((_, i) => `/v${i}`),
	]);
	for (const line of du.trim().split("\n")) {
		const [bytes, mount] = line.split("\t");
		disk[DISK_VOLUMES[Number(mount?.slice(2))] ?? String(mount)] = Number(bytes);
	}
	for (const volume of volumes) {
		await run("docker", ["volume", "rm", "-f", volume], { stdout: "ignore" });
		volumesCreated.delete(volume);
	}
	sessions.delete(sessionId);
	return {
		index,
		initExit: init.exit,
		runtimeExit: runtime.exit,
		initMs: init.ms,
		runtimeMs: runtime.ms,
		startMs,
		diskBytes: disk,
		uploads: uploads.get(sessionId) ?? 0,
		initializeLog: `${runId}-${index}-init.log`,
		log: `${runId}-${index}-runtime.log`,
	};
}

// ---------------------------------------------------------------- run and report

const MiB = (bytes: number) => Math.round((bytes / 1_048_576) * 10) / 10;
function summarize(label: string) {
	const mine = samples.filter((s) => s.label === label);
	const [first] = mine;
	const last = mine.at(-1);
	if (first === undefined || last === undefined) {
		return null;
	}
	const pick = (f: (s: Sample) => number) => mine.map(f);
	const stats = (values: number[]) => ({
		p50: MiB(percentile(values, 0.5) ?? 0),
		p95: MiB(percentile(values, 0.95) ?? 0),
		max: MiB(Math.max(...values)),
	});
	// Anonymous memory per command, summed over its processes at each tick: RSS would count the shared
	// pages of the Node binary once per process, the cgroup counts them once.
	const byProcess = new Map<string, { anon: number; rss: number; count: number }>();
	for (const s of mine) {
		const sum = new Map<string, { anon: number; rss: number; count: number }>();
		for (const p of s.procs) {
			const entry = sum.get(p.cmd) ?? { anon: 0, rss: 0, count: 0 };
			sum.set(p.cmd, {
				anon: entry.anon + p.anon,
				rss: Math.max(entry.rss, p.rss),
				count: entry.count + 1,
			});
		}
		for (const [cmd, value] of sum) {
			const peak = byProcess.get(cmd) ?? { anon: 0, rss: 0, count: 0 };
			byProcess.set(cmd, {
				anon: Math.max(peak.anon, value.anon),
				rss: Math.max(peak.rss, value.rss),
				count: Math.max(peak.count, value.count),
			});
		}
	}
	const tmpfs = new Map<string, number>();
	for (const s of mine) {
		for (const [dir, bytes] of Object.entries(s.tmpfs ?? {})) {
			tmpfs.set(dir, Math.max(tmpfs.get(dir) ?? 0, bytes));
		}
	}
	return {
		samples: mine.length,
		seconds: Math.round((last.t - first.t) / 100) / 10,
		memoryPeak: MiB(Math.max(...pick((s) => s.peak))),
		current: stats(pick((s) => s.current)),
		anon: stats(pick((s) => s.anon)),
		file: stats(pick((s) => s.file)),
		fileCache: stats(pick((s) => s.file - s.shmem)),
		shmem: stats(pick((s) => s.shmem)),
		kernel: stats(pick((s) => s.kernel)),
		pidsMax: Math.max(...pick((s) => s.pids)),
		cpuSeconds: Math.round(last.cpuUsec / 1e4) / 100,
		throttledSeconds: Math.round(last.throttledUsec / 1e4) / 100,
		processes: Object.fromEntries(
			largest(
				[...byProcess].filter(([, v]) => v.anon > 1_048_576),
				([, v]) => v.anon,
				REPORT_MAP_LIMIT,
			).map(([cmd, v]) => [
				cmd,
				{ anonMiB: MiB(v.anon), largestRssMiB: MiB(v.rss), atOnce: v.count },
			]),
		),
		tmpfsPeak: Object.fromEntries(
			largest(
				[...tmpfs].filter(([, b]) => b > 0),
				([, bytes]) => bytes,
				REPORT_MAP_LIMIT,
			).map(([dir, bytes]) => [dir, MiB(bytes)]),
		),
	};
}

function sampleCount(label: string): number {
	return samples.filter((s) => s.label === label).length;
}

let sampler: ChildProcess | undefined;
let samplerClosed: Promise<void> = Promise.resolve();
async function stopSampler() {
	sampler?.kill("SIGTERM");
	await succeeds("docker", ["rm", "-f", `${runId}-sampler`]);
	// A sampler that never started may never close its output.
	await Promise.race([samplerClosed, sleep(5000)]);
}

/** Removes what the run left in Docker: a review that failed midway leaves containers and volumes. */
async function cleanUp() {
	await stopSampler();
	await succeeds("docker", ["rm", "-f", `${runId}-gateway`]);
	for (const id of containers) {
		await succeeds("docker", ["rm", "-f", id]);
	}
	for (const volume of volumesCreated) {
		await succeeds("docker", ["volume", "rm", "-f", volume]);
	}
	if (fixtureNetwork !== undefined) {
		await succeeds("docker", ["network", "rm", fixtureNetwork]);
	}
	server.close();
	await rm(archive, { force: true });
	await rm(benchDir, { recursive: true, force: true });
}

const outFile =
	options.out === undefined ? path.join(work, "footprint.json") : path.resolve(options.out);
const load = loadavg();
const startedAt = new Date().toISOString();
const runs: ReviewRun[] = [];
// What a failed run still reports; a run that ends replaces it with the full report.
let report: Record<string, unknown> = { label: options.label, startedAt, options };
try {
	await mkdir(benchDir);
	await writeFile(idsFile, "");
	const archiveBytes = await packWorkspace();
	await startGateway();
	sampler = startSampler();
	await sleep(1500);
	const settled = await Promise.allSettled(
		Array.from({ length: reviews }, async (_, index) => review(index)),
	);
	for (const [index, result] of settled.entries()) {
		if (result.status === "fulfilled") {
			runs.push(result.value);
		} else {
			failures.push(`review ${String(index)}: ${errorMessage(result.reason)}`);
		}
	}
	await stopSampler();

	// These are only the benchmark containers. Host services and other Pods require the cluster sampler.
	const ticks = new Map<number, number>();
	for (const s of samples) {
		const bucket = s.t;
		ticks.set(bucket, (ticks.get(bucket) ?? 0) + s.current);
	}
	report = {
		label: options.label,
		startedAt,
		host: {
			loadavg: load.map((l) => Math.round(l * 10) / 10),
			cpus: cpus().length,
			platform: process.platform,
		},
		image,
		volumes: { memory: MEMORY_VOLUMES, disk: DISK_VOLUMES },
		stage,
		practices,
		workspaceArchiveMiB: MiB(archiveBytes),
		limits: { memory: options.memory, cpus: options.cpus, nodeOptions: options["node-options"] },
		model: { toolCallsPerSession: toolCalls, latencyMs, ...modelStats },
		reviews: runs.map((r) => ({
			...r,
			diskMiB: Object.fromEntries(Object.entries(r.diskBytes).map(([k, v]) => [k, MiB(v)])),
			initialize: summarize(`review-${r.index}/initialize`),
			runtime: summarize(`review-${r.index}/runtime`),
		})),
		concurrentPeakMiB: MiB(Math.max(0, ...ticks.values())),
	};
} catch (error) {
	failures.push(`the run stopped: ${errorMessage(error)}`);
} finally {
	await cleanUp();
	const decided = verdict({
		reviews: runs.map((r) => ({
			...r,
			initSamples: sampleCount(`review-${r.index}/initialize`),
			runtimeSamples: sampleCount(`review-${r.index}/runtime`),
		})),
		practices,
		submits: modelStats.submits,
		malformed,
		errors: failures,
	});
	await writeFile(
		path.join(work, "samples.jsonl"),
		samples.map((sample) => JSON.stringify(sample)).join("\n"),
	);
	const text = JSON.stringify({ ...report, verdict: decided, budgets: BUDGETS }, null, 2);
	await writeFile(outFile, `${text}\n`);
	process.stdout.write(`${text}\n`);
	for (const reason of decided.reasons) {
		process.stderr.write(`sandbox footprint: ${reason}\n`);
	}
	process.exitCode = decided.passed ? 0 : 1;
}
process.exit();
