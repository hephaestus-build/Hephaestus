// tools-server.ts — the tools side of tool protocol v1 (tool-protocol.ts).
//
// It runs as its own user, with no credential, and serves one loop over a Unix socket. It never starts a request: it
// answers `hello`, runs `exec` calls in their own process groups, pages `read`s inside its roots, extracts `put`
// archives read-only, and ends calls on `cancel` and `reset`. Every call is bounded: its time, its streamed and spilled
// output, and the paths it may name, which it resolves through symlinks before it compares them. A failure of one call
// ends that call with a named refusal; only a broken frame ends the connection.

import { type ChildProcess, spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { once } from "node:events";
import {
	createWriteStream,
	readdirSync,
	readFileSync,
	realpathSync,
	statSync,
	type WriteStream,
} from "node:fs";
import {
	chmod,
	lstat,
	mkdir,
	open,
	readdir,
	readFile,
	realpath,
	rename,
	rm,
	stat,
} from "node:fs/promises";
import { createServer, type Server, type Socket } from "node:net";
import path from "node:path";
import { finished } from "node:stream/promises";
import { setTimeout as sleep } from "node:timers/promises";

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
	type ToolEndpoint,
	TOOL_WORKSPACE,
	TOOL_CONTEXT_ROOT,
	TOOL_SCRATCH_ROOT,
} from "./tool-protocol.ts";

export interface ToolsServerOptions {
	endpoint: ToolEndpoint;
	/** The nonce that the worker wrote into this Pod's spec: `hello` returns it, and the loop compares it. */
	nonce: string;
	/** The mutable directory inside a volume. Its parent belongs to the tools user; reset replaces this directory. */
	workspace: string;
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

/** How long reset waits for active operations to stop before refusing to replace their workspace. */
const RESET_WAIT_MS = 5000;

/** Failed reclamation must leave room for the active workspace; the Pod's storage limit bounds bytes. */
const MAX_TRASH_TREES = 4;

/** File buffers; an upload adds at most one frame body before waiting for the file's drain. */
const FILE_BUFFER_BYTES = 64 * 1024;

interface Call {
	id: string;
	child: ChildProcess;
	/** Settles once the call sent its final frame. */
	ended: Promise<undefined>;
}

interface Put {
	dir: string;
	file: string;
	sink: WriteStream;
	hash: ReturnType<typeof createHash>;
	size: number;
	seen: number;
	sha256: string;
	/** Whether the archive file could not be written. */
	failed: boolean;
	abort: AbortController;
	/** Writers stop and a failed destination is detached before the final reply. */
	ended: PromiseWithResolvers<undefined>;
	ending: boolean;
	stopForReset: () => Promise<void>;
	refusal?: { error: string; detail: string };
}

function within(root: string, absolute: string): boolean {
	const rel = path.relative(root, absolute);
	return rel === "" || (rel !== ".." && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel));
}

/** `candidate` with every symlink resolved, when it exists and lies inside one of `roots`. */
async function contained(candidate: string, roots: string[]): Promise<string | undefined> {
	if (!path.isAbsolute(candidate) || candidate.includes("\0")) {
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

/**
 * Ends every process of one call: its group, and every process that carries its marker. A process that both left the
 * group and cleared its environment escapes both; the end of the tools Pod ends it, and a `reset` of the sidecar's
 * own user does too.
 */
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
		if (!Number.isInteger(pid) || pid <= 1 || pid === process.pid) {
			continue;
		}
		const status = await readFile(`/proc/${name}/status`, "utf8").catch(() => "");
		if (
			new RegExp(String.raw`^Uid:\s+${uid}\s`, "mu").test(status) &&
			!/^State:\s+Z/mu.test(status)
		) {
			pids.push(pid);
		}
	}
	return pids;
}

async function stopOwnProcesses(): Promise<void> {
	if (process.platform !== "linux") {
		throw new ToolProtocolError("reset", "A sole-user reset requires Linux.");
	}
	// Linux kill(-1) excludes the caller and PID 1. The private PID namespace and kernel UID check bound it.
	try {
		process.kill(-1, "SIGKILL");
	} catch (error) {
		if (!(error instanceof Error && "code" in error && error.code === "ESRCH")) {
			throw error;
		}
	}
	const deadline = Date.now() + RESET_WAIT_MS;
	while (Date.now() < deadline) {
		const remaining = await ownProcesses();
		if (remaining.length === 0) {
			return;
		}
		await sleep(10);
	}
	throw new ToolProtocolError("reset", "The tools user's processes could not stop.");
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

/** The tools and every command have one authority; the trusted loop is outside it (ADR 0068, trust split). */
export function checkAuthority(): void {
	// 9a.2: Keep the tools-container command UID equal to the server UID; add the chart assertion for that contract.
	if (process.getuid?.() === 0 || process.geteuid?.() === 0) {
		throw new Error("The tools server must not run as root.");
	}
	if (process.platform === "linux") {
		const status = readFileSync("/proc/self/status", "utf8");
		const capabilities = status
			.split("\n")
			.filter((line) => /^Cap(?:Inh|Prm|Eff|Bnd|Amb):/u.test(line));
		if (capabilities.length !== 5 || capabilities.some((line) => !/:\s+0+$/u.test(line))) {
			throw new Error("The tools server must have no capabilities.");
		}
		if (!/^NoNewPrivs:\s+1$/mu.test(status)) {
			throw new Error("The tools server requires no_new_privs.");
		}
	}
}

/** The put directory being created now; the next creation waits for it. */
let creatingDirectory: Promise<unknown> = Promise.resolve();

/**
 * Creates a put's new directory. Its parent can be one that an earlier put made read-only, as a repository's owner
 * directory inside the documents is: only this mkdir gets owner write on it, and the parent keeps its mode otherwise.
 * Commands run as the same user, so the moment grants them nothing they lack. One creation runs at a time, so that two
 * puts into one parent never restore its mode under each other.
 */
async function createPutDirectory(dir: string): Promise<void> {
	const previous = creatingDirectory;
	const { promise, resolve } = Promise.withResolvers<undefined>();
	creatingDirectory = promise;
	await previous;
	try {
		const parent = path.dirname(dir);
		const { mode } = await lstat(parent);
		// oxlint-disable-next-line no-bitwise -- a file mode is a bitmask
		const permissions = mode & 0o7777;
		// oxlint-disable-next-line no-bitwise -- a file mode is a bitmask
		const writable = (permissions & 0o200) !== 0;
		if (!writable) {
			// oxlint-disable-next-line no-bitwise -- a file mode is a bitmask
			await chmod(parent, permissions | 0o200);
		}
		try {
			await mkdir(dir);
		} finally {
			if (!writable) {
				await chmod(parent, permissions);
			}
		}
	} finally {
		resolve(undefined);
	}
}

/** Best-effort reclamation of a detached tree, under the same UID, groups and mounts as every command. */
async function removeTrash(file: string, root: string): Promise<void> {
	const info = await lstat(file).catch((error: unknown) => {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") {
			return null;
		}
		throw error;
	});
	if (info === null) {
		return;
	}
	const checked = info.isSymbolicLink() ? path.dirname(file) : file;
	if (checked !== path.dirname(root) && !within(root, await realpath(checked))) {
		return;
	}
	// A directory can move after a check. This is defense in depth, not a privilege boundary: ADR 0068's
	// tools server and commands share one non-root, capability-free authority; the loop's volume is not writable.
	if (info.isDirectory()) {
		await chmod(file, 0o700);
		for (const name of await readdir(file)) {
			try {
				await removeTrash(path.join(file, name), root);
			} catch {
				// A protected child must not retain the other children too.
			}
		}
	}
	if (!info.isSymbolicLink() && !within(root, await realpath(file))) {
		return;
	}
	await rm(file, { recursive: true, force: true });
}

async function reclaimTrash(root: string): Promise<boolean> {
	try {
		await removeTrash(root, root);
		return await lstat(root).then(
			() => false,
			(error: unknown) => error instanceof Error && "code" in error && error.code === "ENOENT",
		);
	} catch {
		return false;
	}
}

/** Whether `byte` continues a UTF-8 character: 0x80 to 0xBF. */
function continues(byte: number | undefined): boolean {
	return byte !== undefined && byte >= 0x80 && byte < 0xc0;
}

/** A call's spill file, opened new, or `undefined` when it cannot be: the call then runs without one. */
async function openSpill(scratch: string, id: string): Promise<WriteStream | undefined> {
	const dir = path.join(scratch, ".tool-output");
	try {
		await mkdir(dir, { recursive: true });
		// "wx": never through a planted symlink, and never over an earlier call's file.
		const spill = createWriteStream(path.join(dir, `${id}.log`), { flags: "wx" });
		await once(spill, "open");
		return spill;
	} catch {
		return undefined;
	}
}

/**
 * The lines `offset` to `offset + limit - 1` of a file, at most one frame body of them. It reads the file in chunks,
 * so a read holds one page and one chunk, whatever the file's size. A page that the body bound ends early holds the
 * whole lines that fit; a first line past the bound alone gives its start, cut at a character boundary.
 */
async function readPage(file: string, offset: number, limit: number) {
	const last = offset + limit - 1;
	const kept: Buffer[] = [];
	let keptBytes = 0;
	/** Where the current line starts in `kept`: a line that does not fit is taken off the page again. */
	let lineStart = { parts: 0, bytes: 0 };
	let line = 1;
	/** Whether bytes follow the last newline: they are a last line without one. */
	let lastLineOpen = false;
	/** Where the body bound ended the page, if it did, and whether it cut the page's only line. */
	const stop: { nextOffset?: number; cutLine: boolean } = { cutLine: false };
	/** Adds a piece of the current line to the page, or ends the page where the body bound falls. */
	const take = (piece: Buffer) => {
		if (keptBytes + piece.length <= LIMITS.bodyBytes) {
			kept.push(Buffer.from(piece));
			keptBytes += piece.length;
		} else if (line === offset) {
			const cut = LIMITS.bodyBytes - keptBytes;
			kept.push(Buffer.from(piece.subarray(0, cut)));
			keptBytes += cut;
			stop.cutLine = true;
			stop.nextOffset = line + 1;
		} else {
			kept.length = lineStart.parts;
			keptBytes = lineStart.bytes;
			stop.nextOffset = line;
		}
	};
	const handle = await open(file, "r");
	try {
		const buffer = Buffer.alloc(FILE_BUFFER_BYTES);
		for (;;) {
			const { bytesRead } = await handle.read(buffer, 0, buffer.length);
			if (bytesRead === 0) {
				break;
			}
			const chunk = buffer.subarray(0, bytesRead);
			let start = 0;
			while (start < chunk.length) {
				const newline = chunk.indexOf(0x0a, start);
				const end = newline === -1 ? chunk.length : newline + 1;
				if (line >= offset && line <= last && stop.nextOffset === undefined) {
					take(chunk.subarray(start, end));
				}
				if (newline === -1) {
					lastLineOpen = true;
					start = chunk.length;
				} else {
					line += 1;
					lastLineOpen = false;
					start = end;
					lineStart = { parts: kept.length, bytes: keptBytes };
				}
			}
		}
	} finally {
		await handle.close();
	}
	const totalLines = lastLineOpen ? line : line - 1;
	let body = Buffer.concat(kept);
	if (stop.cutLine && body.length > 0) {
		// A character may start in an earlier read chunk. Trim the joined prefix rather than only the last chunk.
		let start = body.length - 1;
		while (start > 0 && continues(body[start])) {
			start -= 1;
		}
		const lead = body[start] ?? 0;
		let width = 1;
		if (lead >= 0xf0) {
			width = 4;
		} else if (lead >= 0xe0) {
			width = 3;
		} else if (lead >= 0xc0) {
			width = 2;
		}
		if (body.length - start < width) {
			body = body.subarray(0, start);
		}
	}
	if (!stop.cutLine && body.at(-1) === 0x0a) {
		body = body.subarray(0, -1);
	}
	return {
		body,
		totalLines,
		truncated: stop.nextOffset !== undefined,
		nextOffset: stop.nextOffset ?? Math.max(offset, Math.min(last, totalLines) + 1),
	};
}

export function startToolsServer(options: ToolsServerOptions): Server {
	if (options.soleUser) {
		checkAuthority();
	}
	const workspace = realpathSync(options.workspace);
	const workspaceParent = path.dirname(workspace);
	const owner = statSync(workspaceParent);
	if (owner.uid !== process.getuid?.() || owner.mode % 0o100 !== 0) {
		throw new Error("The workspace parent must belong only to the tools user.");
	}
	if (
		!within(workspace, realpathSync(options.scratch)) ||
		options.putRoots.some((root) => !within(workspace, realpathSync(root))) ||
		(typeof options.endpoint === "string" && within(workspace, path.resolve(options.endpoint)))
	) {
		throw new Error(
			"Scratch and upload roots belong inside the mutable workspace, and the socket outside it.",
		);
	}
	const bootId = randomUUID();
	/** The running calls of every connection: a reset ends them all. */
	const calls = new Set<Call>();
	const uploads = new Set<Put>();
	const operations = new Set<Promise<void>>();
	let resetting = false;
	/** Bytes streamed to the loop by every call of this attempt; the tools program serves one attempt. */
	let attemptStreamed = 0;
	/** How many resets ran: a call that arrived before a reset and would start after it does not start. */
	let resets = 0;
	const trash = new Set<string>();
	const retiring = new Set<string>();
	let reclaiming = false;
	let sweepRequested = false;

	async function reclaimBatch(): Promise<void> {
		if (reclaiming) {
			return;
		}
		reclaiming = true;
		try {
			while (sweepRequested) {
				sweepRequested = false;
				for (const directory of [...trash]
					.filter((entry) => !retiring.has(entry))
					.slice(0, MAX_TRASH_TREES)) {
					if (await reclaimTrash(directory)) {
						trash.delete(directory);
					} else {
						// Rotate a failed tree so a later sweep also reaches older trees from before startup.
						trash.delete(directory);
						trash.add(directory);
					}
				}
			}
		} finally {
			reclaiming = false;
		}
	}

	function sweepTrash(): void {
		for (const name of readdirSync(workspaceParent)) {
			if (name.startsWith(".tools-trash-")) {
				trash.add(path.join(workspaceParent, name));
			}
		}
		sweepRequested = true;
		void reclaimBatch();
	}

	function requireTrashRoom(): void {
		sweepTrash();
		if (trash.size >= MAX_TRASH_TREES) {
			throw new ToolProtocolError(
				"storage",
				"Workspace trash is full. Retry after reclamation frees storage.",
			);
		}
	}

	sweepTrash();

	function reserveTrash(): string {
		requireTrashRoom();
		const retired = path.join(workspaceParent, `.tools-trash-${randomUUID()}`);
		trash.add(retired);
		retiring.add(retired);
		return retired;
	}

	async function retire(directory: string, reserved?: string): Promise<void> {
		const retired = reserved ?? reserveTrash();
		try {
			if (directory !== workspace && !within(workspace, await realpath(path.dirname(directory)))) {
				throw new ToolProtocolError("path", "The upload parent is outside the mutable workspace.");
			}
			await rename(directory, retired);
		} catch (error) {
			trash.delete(retired);
			if (error instanceof Error && "code" in error && error.code === "ENOENT") {
				return;
			}
			throw error;
		} finally {
			retiring.delete(retired);
		}
		sweepTrash();
	}

	const server = createServer({ highWaterMark: LIMITS.bodyBytes }, (socket: Socket) => {
		const reader = frameReader();
		/** The ids of this connection's requests that have not sent their final frame. */
		const inFlight = new Set<string>();
		const running = new Map<string, Call>();
		const puts = new Map<string, Put>();
		/** Puts that were refused: the loop may have streamed some of their archive before it read the refusal. */
		const refusedPuts = new Set<string>();
		/** In-flight calls that were cancelled before they started. */
		const cancelled = new Set<string>();
		/** Aborted when the connection ends: it ends the connection's extractions. */
		const closing = new AbortController();
		let helloDone = false;

		/** Writes one frame; `false` when the socket's buffer is full and the writer should wait for `drain`. */
		const send = (header: Header, body?: Buffer): boolean => {
			if (socket.destroyed) {
				return true;
			}
			return socket.write(encode(header, body));
		};
		/** Sends a request's final frame: its id is free again. */
		const final = (id: string, header: Header, body?: Buffer) => {
			inFlight.delete(id);
			cancelled.delete(id);
			send({ ...header, id }, body);
		};
		const refuse = (id: string, error: string, detail: string) => {
			final(id, { type: "error", error, detail });
		};
		/** Runs one request's handler; a handler that fails ends its request with a refusal, never the program. */
		async function settle(id: string, work: Promise<void>, tracked = true): Promise<void> {
			if (tracked) {
				operations.add(work);
			}
			try {
				await work;
			} catch (error) {
				if (error instanceof ToolProtocolError && error.code === "protocol") {
					socket.destroy();
					return;
				}
				if (inFlight.has(id)) {
					refuse(
						id,
						error instanceof ToolProtocolError ? error.code : "tools",
						error instanceof ToolProtocolError ? error.message : "The tools side failed this call.",
					);
				}
			} finally {
				operations.delete(work);
			}
		}

		async function exec(id: string, header: Header, arrivedAfter: number): Promise<void> {
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
			if ([file, ...args].some((part) => part.includes("\0"))) {
				refuse(id, "request", "A command or an argument contains a NUL byte.");
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
			const spill = await openSpill(options.scratch, id);

			// The connection ended while the call was checked: nothing would end what it starts.
			if (socket.destroyed) {
				spill?.destroy();
				return;
			}
			if (resets !== arrivedAfter) {
				spill?.destroy();
				refuse(id, "reset", "A reset ended this call before it started.");
				return;
			}
			if (cancelled.has(id)) {
				spill?.destroy();
				final(id, {
					type: "exit",
					code: null,
					signal: "SIGKILL",
					timedOut: false,
					truncated: false,
					fullOutputPath: "",
					outputLost: false,
				});
				return;
			}
			let child: ChildProcess;
			try {
				child = spawn(file, args, {
					cwd,
					env: { ...ENV, HOME: options.scratch, [CALL_MARKER]: id },
					detached: true,
					stdio: ["ignore", "pipe", "pipe"],
				});
			} catch {
				spill?.destroy();
				refuse(id, "spawn", "The program could not start.");
				return;
			}
			const ended = Promise.withResolvers<undefined>();
			const call: Call = { id, child, ended: ended.promise };
			running.set(id, call);
			calls.add(call);
			let streamed = 0;
			let spilled = 0;
			let spillFailed = spill === undefined;
			let truncated = false;
			let outputLost = false;
			let spawnFailed = false;
			/** Output waits while the spill file or the socket is full, so a fast program cannot grow either buffer. */
			let spillWaits = false;
			let socketWaits = false;
			const pause = () => {
				child.stdout?.pause();
				child.stderr?.pause();
			};
			const resume = () => {
				if (!spillWaits && !socketWaits) {
					child.stdout?.resume();
					child.stderr?.resume();
				}
			};
			const waitForSpill = () => {
				if (spillWaits || spill === undefined) {
					return;
				}
				spillWaits = true;
				pause();
				spill.once("drain", () => {
					spillWaits = false;
					resume();
				});
			};
			const waitForSocket = () => {
				if (socketWaits) {
					return;
				}
				socketWaits = true;
				pause();
				socket.once("drain", () => {
					socketWaits = false;
					resume();
				});
			};
			spill?.on("error", () => {
				spillFailed = true;
				spillWaits = false;
				resume();
			});
			const onData = (data: Buffer) => {
				let kept = 0;
				if (!spillFailed && spill !== undefined) {
					kept = Math.min(data.length, LIMITS.spillBytes - spilled);
					spilled += kept;
					if (kept > 0 && !spill.write(data.subarray(0, kept))) {
						waitForSpill();
					}
				}
				const room = Math.min(maxStream - streamed, LIMITS.attemptStreamBytes - attemptStreamed);
				const part = data.subarray(0, Math.max(0, room));
				if (part.length < data.length) {
					truncated = true;
				}
				if (Math.max(kept, part.length) < data.length) {
					outputLost = true;
				}
				streamed += part.length;
				attemptStreamed += part.length;
				for (let at = 0; at < part.length; at += LIMITS.bodyBytes) {
					if (!send({ type: "out", id }, part.subarray(at, at + LIMITS.bodyBytes))) {
						waitForSocket();
					}
				}
			};
			child.stdout?.on("data", onData);
			child.stderr?.on("data", onData);
			let timedOut = false;
			const timer = setTimeout(() => {
				timedOut = true;
				void endCall(id, child);
			}, timeoutMs);
			// A program that cannot start emits `error` and then `close`: the call then ends with one refusal.
			child.once("error", () => {
				if (child.pid === undefined) {
					spawnFailed = true;
				}
			});
			/** Whether the spill file holds the whole output once it is closed. */
			const closeSpill = async (): Promise<boolean> => {
				if (spill === undefined || spillFailed) {
					spill?.destroy();
					return false;
				}
				spill.end();
				try {
					await finished(spill);
					return !spillFailed;
				} catch {
					return false;
				}
			};
			const finish = async (code: number | null, signal: NodeJS.Signals | null) => {
				clearTimeout(timer);
				try {
					// A process that left the group with setsid ends here too, by its marker.
					await endCall(id, child);
					const whole = await closeSpill();
					if (spawnFailed) {
						refuse(id, "spawn", "The program could not start.");
					} else {
						final(id, {
							type: "exit",
							code,
							signal,
							timedOut,
							truncated,
							fullOutputPath: whole && spill !== undefined ? String(spill.path) : "",
							outputLost: outputLost || (truncated && !whole),
						});
					}
				} finally {
					running.delete(id);
					calls.delete(call);
					ended.resolve(undefined);
				}
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
				const page = await readPage(file, offset, limit);
				final(
					id,
					{
						type: "read",
						offset,
						totalLines: page.totalLines,
						truncated: page.truncated,
						nextOffset: page.nextOffset,
					},
					page.body,
				);
			} catch {
				refuse(id, "path", "The file cannot be read.");
			}
		}

		function rememberRefusedPut(id: string): void {
			if (closing.signal.aborted) {
				return;
			}
			if (refusedPuts.size >= LIMITS.inFlight && !refusedPuts.has(id)) {
				throw new ToolProtocolError("protocol", "too many refused puts without their put-end");
			}
			refusedPuts.add(id);
		}

		/** Stops an upload or extraction and waits for its cleanup before its final refusal. */
		async function dropPut(id: string, error: string, detail: string): Promise<void> {
			const entry = puts.get(id);
			if (entry === undefined || !entry.ending) {
				rememberRefusedPut(id);
			}
			if (entry === undefined) {
				refuse(id, error, detail);
				return;
			}
			entry.refusal = { error, detail };
			entry.abort.abort();
			entry.sink.destroy();
			if (entry.ending) {
				await entry.ended.promise;
			} else {
				await finishPut(id, true);
			}
		}

		async function reset(id: string): Promise<void> {
			resetting = true;
			try {
				requireTrashRoom();
				resets += 1;
				const stoppingUploads = [...uploads].map(async (entry) => entry.stopForReset());
				const stoppingCalls = [...calls].map(async (call) => {
					await endCall(call.id, call.child);
					await call.ended;
				});
				const stoppingUser = options.soleUser ? stopOwnProcesses() : Promise.resolve();
				const stopped = await Promise.race([
					Promise.all([
						Promise.allSettled([...operations, ...stoppingUploads, ...stoppingCalls]),
						stoppingUser,
					]).then(() => true),
					sleep(RESET_WAIT_MS, false, { ref: false }),
				]);
				if (!stopped) {
					throw new ToolProtocolError("reset", "The active tool operations could not stop.");
				}
				await retire(workspace);
				await mkdir(workspace, { mode: 0o700 });
				for (const directory of [options.scratch, ...options.putRoots]) {
					await mkdir(directory, { recursive: true, mode: 0o700 });
				}
				sweepTrash();
				final(id, { type: "reset" });
			} finally {
				resetting = false;
			}
		}

		async function startPut(id: string, header: Header, arrivedAfter: number): Promise<void> {
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
				await dropPut(
					id,
					"request",
					"A put names a new directory inside the put roots, a bounded size and a sha256.",
				);
				return;
			}
			try {
				const incoming = path.join(options.scratch, ".puts");
				await mkdir(incoming, { recursive: true });
				const file = path.join(incoming, `${id}.tar`);
				const sink = createWriteStream(file, { flags: "wx", highWaterMark: FILE_BUFFER_BYTES });
				await once(sink, "open");
				if (closing.signal.aborted || resets !== arrivedAfter) {
					sink.destroy();
					await finished(sink).catch(() => undefined);
					await rm(file, { force: true });
					await dropPut(id, "reset", "A reset ended this put before it started.");
					return;
				}
				const entry: Put = {
					dir: path.join(parent, path.basename(requested)),
					file,
					sink,
					hash: createHash("sha256"),
					size,
					seen: 0,
					sha256,
					failed: false,
					abort: new AbortController(),
					ended: Promise.withResolvers<undefined>(),
					ending: false,
					stopForReset: async () => dropPut(id, "reset", "A reset ended this put."),
				};
				sink.on("error", () => {
					entry.failed = true;
				});
				puts.set(id, entry);
				uploads.add(entry);
			} catch {
				await dropPut(id, "put", "The tools side cannot store the archive.");
			}
		}

		async function putData(id: string, body: Buffer): Promise<void> {
			if (refusedPuts.has(id)) {
				return;
			}
			const entry = puts.get(id);
			if (entry === undefined || entry.ending) {
				throw new ToolProtocolError("protocol", "put data for no put in flight");
			}
			entry.seen += body.length;
			if (entry.failed || entry.seen > entry.size) {
				await dropPut(id, "put", "The archive cannot be stored within its announced size.");
				return;
			}
			entry.hash.update(body);
			if (!entry.sink.write(body)) {
				try {
					await once(entry.sink, "drain", { signal: entry.abort.signal });
				} catch {
					await dropPut(id, "put", "The tools side cannot store the archive.");
				}
			}
		}

		/** Extracts the archive only once it arrived whole: its size and hash match what the loop announced. */
		async function finishPut(id: string, aborted = false): Promise<void> {
			const entry = puts.get(id);
			if (entry === undefined) {
				if (refusedPuts.delete(id)) {
					return;
				}
				throw new ToolProtocolError("protocol", "put end for no put in flight");
			}
			if (entry.ending) {
				throw new ToolProtocolError("protocol", "a second put end");
			}
			entry.ending = true;
			let created = false;
			let extracted = false;
			let retirement: string | undefined;
			try {
				if (aborted) {
					throw new ToolProtocolError("put", "The archive source stopped before the put ended.");
				}
				entry.sink.end();
				await finished(entry.sink);
				if (entry.failed) {
					throw new ToolProtocolError("put", "The tools side cannot store the archive.");
				}
				if (entry.seen !== entry.size || entry.hash.digest("hex") !== entry.sha256) {
					throw new ToolProtocolError("put", "The archive did not arrive whole.");
				}
				// Reserve cleanup capacity before creating a tree, including parallel failed extractions.
				retirement = reserveTrash();
				await createPutDirectory(entry.dir);
				created = true;
				// GNU tar and bsdtar both strip leading slashes, skip members with `..`, and never write through a
				// symlink that the archive made: GNU tar creates such a symlink last, and bsdtar refuses. The spec
				// proves it with hostile archives on the tar of the machine that runs it.
				const tar = spawn(
					"tar",
					["-x", "--no-same-owner", "--no-same-permissions", "-f", entry.file, "-C", entry.dir],
					{
						stdio: "ignore",
						signal: entry.abort.signal,
						timeout: LIMITS.extractMs,
						killSignal: "SIGKILL",
					},
				);
				const closed = Promise.withResolvers<number | null>();
				let failed = false;
				tar.once("error", () => {
					failed = true;
				});
				tar.once("close", (exit) => {
					closed.resolve(failed ? -1 : exit);
				});
				const code = await closed.promise;
				if (code !== 0) {
					throw new ToolProtocolError("put", "The archive could not be extracted.");
				}
				await makeReadOnly(entry.dir);
				entry.abort.signal.throwIfAborted();
				extracted = true;
			} catch (error) {
				entry.refusal ??= {
					error: error instanceof ToolProtocolError ? error.code : "put",
					detail:
						error instanceof ToolProtocolError
							? error.message
							: "The archive could not be extracted.",
				};
			} finally {
				entry.sink.destroy();
				await finished(entry.sink).catch(() => undefined);
				try {
					await rm(entry.file, { force: true });
				} catch {
					entry.refusal ??= {
						error: "put",
						detail: "The tools side could not remove the archive.",
					};
				}
				try {
					if (
						created &&
						(!extracted || entry.abort.signal.aborted || entry.refusal !== undefined)
					) {
						await retire(entry.dir, retirement);
						retirement = undefined;
					}
				} catch {
					entry.refusal ??= {
						error: "put",
						detail: "The tools side could not retire the extraction directory.",
					};
				}
				if (retirement !== undefined) {
					trash.delete(retirement);
					retiring.delete(retirement);
				}
				puts.delete(id);
				uploads.delete(entry);
				if (entry.refusal) {
					refuse(id, entry.refusal.error, entry.refusal.detail);
				} else {
					final(id, { type: "put" });
				}
				entry.ended.resolve(undefined);
			}
		}

		function hello(header: Header): void {
			if (helloDone) {
				throw new ToolProtocolError("protocol", "a second hello");
			}
			if (integer(header, "protocol") !== PROTOCOL) {
				send({
					type: "error",
					id: "hello",
					error: "version",
					detail: `The tools side speaks tool protocol ${PROTOCOL}.`,
				});
				socket.end();
				return;
			}
			helloDone = true;
			send({ type: "hello", nonce: options.nonce, protocol: PROTOCOL, bootId, limits: LIMITS });
		}

		async function handle(frame: Frame): Promise<void> {
			const { header } = frame;
			if (header.type === "hello") {
				hello(header);
				return;
			}
			if (header.type === "ping") {
				send({ type: "pong" });
				return;
			}
			const id = text(header, "id");
			if (!helloDone || id === undefined || !ID_PATTERN.test(id)) {
				throw new ToolProtocolError("protocol", "a request before hello or without a valid id");
			}
			if (header.type === "cancel") {
				const call = running.get(id);
				if (call !== undefined) {
					void endCall(id, call.child);
				} else if (inFlight.has(id)) {
					cancelled.add(id);
				}
				return;
			}
			if (header.type === "put-data") {
				await putData(id, frame.body);
				return;
			}
			if (header.type === "put-end") {
				const entry = puts.get(id);
				if (entry?.ending === true || (entry === undefined && !refusedPuts.has(id))) {
					throw new ToolProtocolError("protocol", "put end for no receiving put");
				}
				// Extraction keeps its request slot, but does not hold up another upload or the connection's pings.
				void settle(id, finishPut(id, header.abort === true));
				return;
			}
			// Counted when the frame arrives, before any handler awaits: a burst of frames in one chunk cannot pass.
			if (inFlight.has(id)) {
				throw new ToolProtocolError("protocol", "a request id that is already in flight");
			}
			if (!["exec", "read", "put", "reset"].includes(header.type)) {
				refuse(id, "unknown", `The tools side has no operation ${header.type}.`);
				return;
			}
			if (resetting) {
				if (header.type === "put") {
					rememberRefusedPut(id);
				}
				refuse(id, "busy", "A reset is already running.");
				return;
			}
			if (inFlight.size >= LIMITS.inFlight) {
				if (header.type === "put") {
					rememberRefusedPut(id);
				}
				refuse(id, "busy", "Too many tool calls at once.");
				return;
			}
			inFlight.add(id);
			if (header.type === "exec") {
				void settle(id, exec(id, header, resets));
			} else if (header.type === "read") {
				void settle(id, read(id, header));
			} else if (header.type === "reset") {
				await settle(id, reset(id), false);
			} else {
				await settle(id, startPut(id, header, resets));
			}
		}

		/**
		 * One bounded socket chunk is processed at a time. Waiting for a file's drain stops reading the
		 * socket, so neither this call nor all concurrent puts can queue an archive in memory.
		 */
		async function receive(input: AsyncIterable<unknown>): Promise<void> {
			try {
				for await (const chunk of input) {
					if (!Buffer.isBuffer(chunk)) {
						throw new ToolProtocolError("protocol", "non-byte input");
					}
					for (const frame of reader.push(chunk)) {
						await handle(frame);
					}
				}
			} catch {
				socket.destroy();
			}
		}
		void receive(socket);
		socket.on("close", () => {
			closing.abort();
			for (const call of running.values()) {
				void endCall(call.id, call.child);
			}
			for (const id of puts.keys()) {
				void settle(id, dropPut(id, "connection", "The connection ended this put."));
			}
		});
		socket.on("error", () => {
			socket.destroy();
		});
	});
	return typeof options.endpoint === "string"
		? server.listen(options.endpoint)
		: server.listen(options.endpoint);
}

if (import.meta.main) {
	if (process.platform !== "linux") {
		throw new Error("The tools program requires Linux.");
	}
	checkAuthority();
	const nonce = process.env.TOOLS_NONCE;
	if (nonce === undefined || nonce === "") {
		throw new Error("TOOLS_NONCE is required: the worker writes the Pod's nonce into its spec");
	}
	const workspace = TOOL_WORKSPACE;
	const scratch = TOOL_SCRATCH_ROOT;
	await mkdir(path.dirname(workspace), { recursive: true, mode: 0o700 });
	// The context directory is the loop's first put, which names a new directory: it is not made here.
	for (const directory of [workspace, scratch]) {
		await mkdir(directory, { recursive: true, mode: 0o700 });
	}
	const portText = process.env.TOOLS_PORT;
	const port = portText === undefined ? undefined : Number(portText);
	if (port !== undefined && (!Number.isSafeInteger(port) || port < 1 || port > 65_535)) {
		throw new Error("TOOLS_PORT must be an integer from 1 to 65535");
	}
	startToolsServer({
		endpoint:
			port === undefined
				? (process.env.TOOLS_SOCKET ?? "/run/tools/tools.sock")
				: { host: "0.0.0.0", port },
		nonce,
		workspace,
		roots: [TOOL_CONTEXT_ROOT, scratch],
		scratch,
		putRoots: [workspace],
		soleUser: true,
	});
}
