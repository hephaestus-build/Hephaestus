// tools-server.ts — the tools side of tool protocol v1 (tool-protocol.ts).
//
// It runs as its own user, with no credential, and serves one loop over a Unix socket. It never starts a request: it
// answers `hello`, runs `exec` calls in their own process groups, pages `read`s inside its roots, extracts `put`
// archives read-only, and ends calls on `cancel` and `reset`. Every call is bounded: its time, its streamed and spilled
// output, and the paths it may name, which it resolves through symlinks before it compares them.

import { type ChildProcess, spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { once } from "node:events";
import { createWriteStream, mkdirSync, type WriteStream } from "node:fs";
import { chmod, lstat, mkdir, readdir, readFile, realpath, rm, stat } from "node:fs/promises";
import { createServer, type Server, type Socket } from "node:net";
import path from "node:path";
import { finished } from "node:stream/promises";

import {
	encode,
	type Frame,
	frameReader,
	type Header,
	ID_PATTERN,
	integer,
	LIMITS,
	PROTOCOL,
	strings,
	text,
	ToolProtocolError,
} from "./tool-protocol.ts";

export interface ToolsServerOptions {
	socketPath: string;
	/** The roots that `read` and an `exec`'s working directory may name. */
	roots: string[];
	/** The scratch root that `reset` clears; spill files and incoming archives live under it. */
	scratch: string;
	/** The roots that `put` may create a directory in. */
	putRoots: string[];
	/**
	 * Whether this user runs nothing but the tools: then `reset` also ends processes that left their call's group.
	 * Only the sidecar's own user does; a test shares its user with the test runner.
	 */
	soleUser: boolean;
}

/** Every process of a call carries this variable, with the call's id: it finds a process that left the call's group. */
const CALL_MARKER = "HEPHAESTUS_TOOL_CALL";

/** Only these variables reach a program: no credential ever does. */
const ENV = {
	PATH: "/usr/local/bin:/usr/bin:/bin",
	LANG: "C.UTF-8",
	GIT_TERMINAL_PROMPT: "0",
};

interface Put {
	dir: string;
	file: string;
	sink: WriteStream;
	hash: ReturnType<typeof createHash>;
	size: number;
	seen: number;
	sha256: string;
}

function within(root: string, absolute: string): boolean {
	const rel = path.relative(root, absolute);
	return rel === "" || (rel !== ".." && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel));
}

/** `candidate` with every symlink resolved, when it exists and lies inside one of `roots`. */
async function contained(candidate: string, roots: string[]): Promise<string | undefined> {
	if (!path.isAbsolute(candidate)) {
		return undefined;
	}
	try {
		const real = await realpath(candidate);
		for (const root of roots) {
			if (within(await realpath(root), real)) {
				return real;
			}
		}
	} catch {
		// It does not exist, or a root does not.
	}
	return undefined;
}

function killGroup(child: ChildProcess): void {
	if (child.pid === undefined) {
		return;
	}
	try {
		process.kill(-child.pid, "SIGKILL");
	} catch {
		// The group already ended.
	}
}

/** The processes of this user whose environment carries `marker`, on Linux, where `/proc` lists them. */
async function markedProcesses(marker: string): Promise<number[]> {
	const pids: number[] = [];
	for (const name of await readdir("/proc").catch(() => [])) {
		const pid = Number(name);
		if (!Number.isInteger(pid) || pid === process.pid) {
			continue;
		}
		// Only a process of this user lets it read the environment; any other one fails here and is skipped.
		const environment = await readFile(`/proc/${name}/environ`, "latin1").catch(() => "");
		if (`\0${environment}`.includes(`\0${marker}\0`)) {
			pids.push(pid);
		}
	}
	return pids;
}

/** Ends every process of one call: its group, and every process that carries its marker. */
async function endCall(id: string, child: ChildProcess): Promise<void> {
	killGroup(child);
	for (const pid of await markedProcesses(`${CALL_MARKER}=${id}`)) {
		try {
			process.kill(pid, "SIGKILL");
		} catch {
			// It ended meanwhile.
		}
	}
}

/** Every process of this user but the tools program itself, on Linux, where `/proc` lists them. */
async function ownProcesses(): Promise<number[]> {
	const uid = process.getuid?.();
	if (uid === undefined) {
		return [];
	}
	const pids: number[] = [];
	for (const name of await readdir("/proc").catch(() => [])) {
		const pid = Number(name);
		if (!Number.isInteger(pid) || pid === process.pid) {
			continue;
		}
		const status = await readFile(`/proc/${name}/status`, "utf8").catch(() => "");
		if (new RegExp(String.raw`^Uid:\s+${uid}\s`, "mu").test(status)) {
			pids.push(pid);
		}
	}
	return pids;
}

/** Takes write permission from everything under `dir`, never following a symlink; executables stay executable. */
async function makeReadOnly(dir: string): Promise<void> {
	for (const name of await readdir(dir, { recursive: true })) {
		const entry = path.join(dir, name);
		const info = await lstat(entry);
		if (info.isDirectory()) {
			await chmod(entry, 0o555);
		} else if (!info.isSymbolicLink()) {
			const ownerExecutes = Math.floor((info.mode % 0o1000) / 0o100) % 2 === 1;
			await chmod(entry, ownerExecutes ? 0o555 : 0o444);
		}
	}
	await chmod(dir, 0o555);
}

/** Whether `byte` continues a UTF-8 character: 0x80 to 0xBF. */
function continues(byte: number | undefined): boolean {
	return byte !== undefined && byte >= 0x80 && byte < 0xc0;
}

/** The first `bytes` of `text` encoded, cut at a character boundary. */
function utf8Prefix(value: string, bytes: number): { body: Buffer; cut: boolean } {
	const whole = Buffer.from(value, "utf8");
	if (whole.length <= bytes) {
		return { body: whole, cut: false };
	}
	let end = bytes;
	// Step back to the start of the character that the cut falls in.
	while (end > 0 && continues(whole[end])) {
		end -= 1;
	}
	return { body: whole.subarray(0, end), cut: true };
}

export function startToolsServer(options: ToolsServerOptions): Server {
	const bootId = randomUUID();
	const running = new Map<string, ChildProcess>();
	/** Bytes streamed to the loop by every call of this attempt; the tools program serves one attempt. */
	let attemptStreamed = 0;

	return createServer((socket: Socket) => {
		const reader = frameReader();
		const puts = new Map<string, Put>();
		/** Puts that were refused: the loop streams their archive before it reads the refusal. */
		const refusedPuts = new Set<string>();
		/** Put frames run in order: a put's directory checks finish before its data arrives at the archive. */
		const putFrames: Frame[] = [];
		let puttingFrames = false;
		/** Calls cancelled before they started. */
		const cancelled = new Set<string>();
		let helloDone = false;

		/** Writes one frame; `false` when the socket's buffer is full and the writer should wait for `drain`. */
		const send = (header: Header, body?: Buffer): boolean => {
			if (socket.destroyed) {
				return true;
			}
			return socket.write(encode(header, body));
		};
		const refuse = (id: string, error: string, detail: string) => {
			send({ type: "error", id, error, detail });
		};

		async function exec(id: string, header: Header): Promise<void> {
			if (running.size >= LIMITS.inFlight) {
				refuse(id, "busy", "Too many tool calls at once.");
				return;
			}
			const cwd = await contained(text(header, "cwd") ?? "", options.roots);
			if (cwd === undefined) {
				refuse(id, "path", "The working directory is outside the workspace.");
				return;
			}
			const command = text(header, "command");
			const argv = strings(header, "argv");
			let program: string[] | undefined;
			if (command === undefined) {
				program = argv;
			} else if (argv === undefined) {
				program = ["bash", "-c", command];
			}
			const [file, ...args] = program ?? [];
			if (file === undefined) {
				refuse(id, "request", "An exec names exactly one of a command or an argv.");
				return;
			}
			const requested = integer(header, "timeoutMs");
			if (requested === undefined || requested <= 0) {
				refuse(id, "request", "An exec needs a positive timeoutMs.");
				return;
			}
			const timeoutMs = Math.min(requested, LIMITS.execTimeoutMs);
			const maxStream = Math.min(
				integer(header, "maxOutputBytes") ?? LIMITS.execStreamBytes,
				LIMITS.execStreamBytes,
			);

			if (cancelled.delete(id)) {
				send({
					type: "exit",
					id,
					code: null,
					signal: "SIGKILL",
					timedOut: false,
					truncated: false,
					fullOutputPath: "",
				});
				return;
			}
			const spillDir = path.join(options.scratch, ".tool-output");
			mkdirSync(spillDir, { recursive: true });
			const fullOutputPath = path.join(spillDir, `${id}.log`);
			const spill = createWriteStream(fullOutputPath);
			const child = spawn(file, args, {
				cwd,
				env: { ...ENV, HOME: options.scratch, [CALL_MARKER]: id },
				detached: true,
				stdio: ["ignore", "pipe", "pipe"],
			});
			running.set(id, child);
			let streamed = 0;
			let spilled = 0;
			let truncated = false;
			/** Output waits while the spill file or the socket is full, so a fast program cannot grow either buffer. */
			let waiting = 0;
			const wait = (target: NodeJS.EventEmitter) => {
				waiting += 1;
				child.stdout.pause();
				child.stderr.pause();
				target.once("drain", () => {
					waiting -= 1;
					if (waiting === 0) {
						child.stdout.resume();
						child.stderr.resume();
					}
				});
			};
			const onData = (data: Buffer) => {
				const spillRoom = LIMITS.spillBytes - spilled;
				if (data.length > spillRoom) {
					truncated = true;
				}
				const kept = data.subarray(0, Math.max(0, spillRoom));
				spilled += kept.length;
				if (kept.length > 0 && !spill.write(kept)) {
					wait(spill);
				}
				const room = Math.min(maxStream - streamed, LIMITS.attemptStreamBytes - attemptStreamed);
				const part = data.subarray(0, Math.max(0, room));
				if (part.length < data.length) {
					truncated = true;
				}
				streamed += part.length;
				attemptStreamed += part.length;
				for (let at = 0; at < part.length; at += LIMITS.bodyBytes) {
					if (!send({ type: "out", id }, part.subarray(at, at + LIMITS.bodyBytes))) {
						wait(socket);
					}
				}
			};
			child.stdout.on("data", onData);
			child.stderr.on("data", onData);
			let timedOut = false;
			const timer = setTimeout(() => {
				timedOut = true;
				void endCall(id, child);
			}, timeoutMs);
			child.once("error", () => {
				clearTimeout(timer);
				running.delete(id);
				spill.end();
				refuse(id, "spawn", "The program could not start.");
			});
			const finish = async (code: number | null, signal: NodeJS.Signals | null) => {
				clearTimeout(timer);
				running.delete(id);
				// No call leaves a process behind, also one that left the group with setsid.
				await endCall(id, child);
				spill.end();
				await finished(spill);
				send({ type: "exit", id, code, signal, timedOut, truncated, fullOutputPath });
			};
			// A process that outlived the call holds its pipes open, so the call ends it as soon as its program exits.
			child.once("exit", () => {
				void endCall(id, child);
			});
			child.once("close", (code: number | null, signal: NodeJS.Signals | null) => {
				void finish(code, signal);
			});
		}

		async function read(id: string, header: Header): Promise<void> {
			const file = await contained(text(header, "path") ?? "", options.roots);
			if (file === undefined) {
				refuse(id, "path", "The path is outside the workspace or does not exist.");
				return;
			}
			const offset = Math.max(1, integer(header, "offset") ?? 1);
			const limit = Math.max(1, integer(header, "limit") ?? 2000);
			try {
				const info = await stat(file);
				if (!info.isFile()) {
					refuse(id, "path", "The path is not a file.");
					return;
				}
				if (info.size > LIMITS.readFileBytes) {
					refuse(id, "large", "The file is too large to read; search it with grep instead.");
					return;
				}
				const content = await readFile(file, "utf8");
				const lines = content.split("\n");
				if (content.endsWith("\n")) {
					lines.pop();
				}
				const page = lines.slice(offset - 1, offset - 1 + limit).join("\n");
				const { body, cut } = utf8Prefix(page, LIMITS.bodyBytes);
				send({ type: "read", id, offset, totalLines: lines.length, truncated: cut }, body);
			} catch {
				refuse(id, "path", "The file cannot be read.");
			}
		}

		async function reset(id: string): Promise<void> {
			for (const child of running.values()) {
				killGroup(child);
			}
			for (const pid of options.soleUser ? await ownProcesses() : []) {
				try {
					process.kill(pid, "SIGKILL");
				} catch {
					// It ended meanwhile.
				}
			}
			for (const name of await readdir(options.scratch).catch(() => [])) {
				await rm(path.join(options.scratch, name), { recursive: true, force: true });
			}
			send({ type: "reset", id });
		}

		async function startPut(id: string, header: Header): Promise<void> {
			const requested = text(header, "dir") ?? "";
			const parent = await contained(path.dirname(requested), options.putRoots);
			const size = integer(header, "size");
			const sha256 = text(header, "sha256");
			const fresh = await lstat(requested).then(
				() => false,
				() => true,
			);
			if (
				parent === undefined ||
				!path.isAbsolute(requested) ||
				!fresh ||
				size === undefined ||
				size < 0 ||
				size > LIMITS.putBytes ||
				sha256 === undefined
			) {
				refusedPuts.add(id);
				refuse(
					id,
					"request",
					"A put names a new directory inside the put roots, a bounded size and a sha256.",
				);
				return;
			}
			const incoming = path.join(options.scratch, ".puts");
			await mkdir(incoming, { recursive: true });
			const file = path.join(incoming, `${id}.tar`);
			puts.set(id, {
				dir: path.join(parent, path.basename(requested)),
				file,
				sink: createWriteStream(file),
				hash: createHash("sha256"),
				size,
				seen: 0,
				sha256,
			});
		}

		function putData(id: string, body: Buffer): void {
			if (refusedPuts.has(id)) {
				return;
			}
			const entry = puts.get(id);
			if (entry === undefined) {
				throw new ToolProtocolError("protocol", "put data for no put in flight");
			}
			entry.seen += body.length;
			if (entry.seen > entry.size) {
				return;
			}
			entry.hash.update(body);
			if (!entry.sink.write(body)) {
				socket.pause();
				entry.sink.once("drain", () => {
					socket.resume();
				});
			}
		}

		/** Extracts the archive only once it arrived whole: its size and hash match what the loop announced. */
		async function finishPut(id: string): Promise<void> {
			if (refusedPuts.delete(id)) {
				return;
			}
			const entry = puts.get(id);
			if (entry === undefined) {
				throw new ToolProtocolError("protocol", "put end for no put in flight");
			}
			puts.delete(id);
			try {
				entry.sink.end();
				await finished(entry.sink);
				if (entry.seen !== entry.size || entry.hash.digest("hex") !== entry.sha256) {
					refuse(id, "put", "The archive did not arrive whole.");
					return;
				}
				await mkdir(entry.dir);
				// tar strips leading slashes, skips members with `..`, and never writes through a symlink that the
				// archive itself made.
				const tar = spawn("tar", ["-x", "--no-same-owner", "-f", entry.file, "-C", entry.dir], {
					stdio: "ignore",
				});
				const [code]: unknown[] = await Promise.race([
					once(tar, "close"),
					once(tar, "error").then(() => [-1]),
				]);
				if (code !== 0) {
					refuse(id, "put", "The archive could not be extracted.");
					return;
				}
				await makeReadOnly(entry.dir);
				send({ type: "put", id });
			} catch {
				refuse(id, "put", "The archive could not be extracted.");
			} finally {
				await rm(entry.file, { force: true });
			}
		}

		async function handlePutFrames(): Promise<void> {
			if (puttingFrames) {
				return;
			}
			puttingFrames = true;
			try {
				for (let frame = putFrames.shift(); frame !== undefined; frame = putFrames.shift()) {
					const id = text(frame.header, "id") ?? "";
					if (frame.header.type === "put") {
						await startPut(id, frame.header);
					} else if (frame.header.type === "put-data") {
						putData(id, frame.body);
					} else {
						await finishPut(id);
					}
				}
			} catch {
				socket.destroy();
			} finally {
				puttingFrames = false;
			}
		}

		function handle(frame: Frame): void {
			const { header } = frame;
			const id = text(header, "id");
			if (header.type === "hello") {
				send({
					type: "hello",
					nonce: text(header, "nonce") ?? "",
					protocol: PROTOCOL,
					bootId,
					limits: LIMITS,
				});
				helloDone = true;
				return;
			}
			if (header.type === "ping") {
				send({ type: "pong" });
				return;
			}
			if (!helloDone || id === undefined || !ID_PATTERN.test(id)) {
				throw new ToolProtocolError("protocol", "a request before hello or without a valid id");
			}
			switch (header.type) {
				case "exec": {
					void exec(id, header);
					break;
				}
				case "read": {
					void read(id, header);
					break;
				}
				case "cancel": {
					const child = running.get(id);
					if (child) {
						void endCall(id, child);
					} else {
						cancelled.add(id);
					}
					break;
				}
				case "reset": {
					void reset(id);
					break;
				}
				case "put":
				case "put-data":
				case "put-end": {
					putFrames.push(frame);
					void handlePutFrames();
					break;
				}
				default: {
					refuse(id, "unknown", `The tools side has no operation ${header.type}.`);
				}
			}
		}

		socket.on("data", (chunk: Buffer) => {
			try {
				for (const frame of reader.push(chunk)) {
					handle(frame);
				}
			} catch {
				socket.destroy();
			}
		});
		socket.on("close", () => {
			for (const child of running.values()) {
				killGroup(child);
			}
			for (const put of puts.values()) {
				put.sink.destroy();
			}
		});
		socket.on("error", () => {
			socket.destroy();
		});
	}).listen(options.socketPath);
}

if (import.meta.main) {
	startToolsServer({
		socketPath: process.env.TOOLS_SOCKET ?? "/run/tools/tools.sock",
		roots: ["/workspace", "/tmp"],
		scratch: "/tmp",
		putRoots: ["/workspace/repos"],
		soleUser: true,
	});
}
