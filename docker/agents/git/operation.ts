import { spawn, type ChildProcess, type ChildProcessByStdio } from "node:child_process";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, mkdtemp, open, readdir, rm, stat } from "node:fs/promises";
import { createInterface } from "node:readline";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { exited, succeeded } from "./process.ts";
import { reviewCommits } from "./review-commits.ts";
import { reviewDiff } from "./review-diff.ts";
import { scanSecrets } from "./secret-scan.ts";

const repositoryDirectory = process.env.GIT_REPOSITORY_DIRECTORY ?? "/git/mirror.git";
const snapshotDirectory = process.env.GIT_SNAPSHOT_DIRECTORY ?? "/snapshot";
const temporaryDirectory = process.env.GIT_TEMP_DIRECTORY ?? "/tmp";
const maxSnapshotBytes = Number(process.env.GIT_MAX_SNAPSHOT_BYTES ?? Number.MAX_SAFE_INTEGER);

const OBJECT_ID = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;
const MAX_COMMIT_PAGE = 256;
const MAX_CITATION_PAIRS = 256;
// NativeGitExecutor.MAX_REQUEST_BYTES on the server side: what it sends is what is accepted here.
const MAX_REQUEST_BYTES = 64 * 1024;

/** Every operation and the revision count it accepts, as an inclusive range. */
const OPERATIONS = {
	STATUS: [0, 0],
	FETCH: [0, 0],
	COMMIT_IDS: [0, 0],
	RESOLVE: [1, 1],
	COMMIT_METADATA: [1, 1],
	FILE_CHANGES: [1, 1],
	TREE_ID: [1, 1],
	TREE_ENTRIES: [1, 1],
	SNAPSHOT: [1, 1],
	COMMIT_RANGE: [1, 2],
	COMMIT_DETAILS: [1, MAX_COMMIT_PAGE],
	FETCH_COMMIT: [2, 2],
	SCAN_SECRETS: [2, 2],
	COMMIT_SUBJECTS: [2, 2],
	REVIEW_COMMITS: [2, 2],
	REVIEW_DIFF: [2, 2],
	RESOLVE_DIFF_RANGE: [2, 2],
	HISTORICAL_BLOB: [3, 3],
	CITED_BLOBS: [3, 1 + 2 * MAX_CITATION_PAIRS],
} as const satisfies Record<string, readonly [number, number]>;

type Operation = keyof typeof OPERATIONS;

interface Request {
	operation: Operation;
	revisions: string[];
	cloneUrl: string | null;
	token: string | null;
}

function isOperation(value: unknown): value is Operation {
	return typeof value === "string" && Object.hasOwn(OPERATIONS, value);
}

function isRepositoryPath(path: string): boolean {
	return (
		path !== "" &&
		!path.startsWith("/") &&
		!path.includes("\\") &&
		!path.split("/").some((part) => part === ".." || part === "." || part === "")
	);
}

export function parseRequest(value: unknown): Request {
	if (
		typeof value !== "object" ||
		value === null ||
		!("operation" in value) ||
		!("revisions" in value)
	)
		throw new Error("Invalid request");
	const { operation, revisions } = value;
	if (!isOperation(operation)) throw new Error("Unknown operation");
	if (
		!Array.isArray(revisions) ||
		!revisions.every(
			(revision): revision is string => typeof revision === "string" && !revision.includes("\0"),
		)
	)
		throw new Error("Invalid revisions");
	const cloneUrl = "cloneUrl" in value ? value.cloneUrl : null;
	const token = "token" in value ? value.token : null;
	if (
		(cloneUrl !== null && typeof cloneUrl !== "string") ||
		(token !== null && typeof token !== "string")
	)
		throw new Error("Invalid authorization");
	const fetches = operation === "FETCH" || operation === "FETCH_COMMIT";
	if (!fetches && (cloneUrl !== null || token !== null))
		throw new Error("Offline operation received authorization");
	if (fetches) {
		if (cloneUrl === null) throw new Error("Missing clone URL");
		const url = new URL(cloneUrl);
		if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash)
			throw new Error("Invalid clone URL");
	}
	if (
		operation !== "CITED_BLOBS" &&
		operation !== "HISTORICAL_BLOB" &&
		operation !== "RESOLVE" &&
		operation !== "FETCH_COMMIT" &&
		revisions.some((revision) => !OBJECT_ID.test(revision))
	)
		throw new Error("Revisions must be pinned object IDs");
	const [minimum, maximum] = OPERATIONS[operation];
	if (revisions.length < minimum || revisions.length > maximum)
		throw new Error("Incorrect revision count");
	if (operation === "CITED_BLOBS") {
		if (revisions.length % 2 !== 1) throw new Error("Invalid citation batch");
		for (let index = 1; index < revisions.length; index += 2)
			parseRequest({
				operation: "HISTORICAL_BLOB",
				revisions: [revisions[index], revisions[0], revisions[index + 1]],
			});
	}
	if (
		operation === "FETCH_COMMIT" &&
		(!revisions[0]?.startsWith("refs/") ||
			revisions[0].includes(":") ||
			!OBJECT_ID.test(revisions[1] ?? ""))
	)
		throw new Error("Invalid pinned fetch");
	if (operation === "HISTORICAL_BLOB") {
		const [commit, head, path] = revisions;
		if (
			![commit, head].every((id) => OBJECT_ID.test(id ?? "")) ||
			path === undefined ||
			!isRepositoryPath(path)
		)
			throw new Error("Invalid historical object");
	}
	return { operation, revisions, cloneUrl, token };
}

export function command(request: Request): string[] {
	switch (request.operation) {
		case "FETCH_COMMIT":
			return [
				"fetch",
				"--force",
				"--no-tags",
				"--no-recurse-submodules",
				request.cloneUrl ?? "",
				request.revisions[0] ?? "",
			];
		case "STATUS":
			return ["rev-parse", "--is-bare-repository"];
		case "TREE_ID":
			return ["rev-parse", "--verify", "--end-of-options", `${request.revisions[0]}^{tree}`];
		case "TREE_ENTRIES":
			return ["ls-tree", "-r", "-z", "--full-tree", ...request.revisions];
		case "COMMIT_SUBJECTS":
			return [
				"log",
				"--no-decorate",
				"-z",
				"--format=%s",
				`${request.revisions[0]}..${request.revisions[1]}`,
				"--",
			];
		case "COMMIT_RANGE":
			return [
				"rev-list",
				...(request.revisions.length === 2
					? [`${request.revisions[0]}..${request.revisions[1]}`]
					: request.revisions),
			];
		case "FETCH":
			return [
				"fetch",
				"--force",
				"--prune",
				"--no-recurse-submodules",
				"--no-tags",
				request.cloneUrl ?? "",
				"+refs/heads/*:refs/remotes/origin/*",
				"+refs/tags/*:refs/tags/*",
			];
		case "COMMIT_IDS":
			return ["rev-list", "--all"];
		case "COMMIT_METADATA":
			return [
				"show",
				"--no-patch",
				"--format=%H%x00%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI%x00%P%x00%B",
				...request.revisions,
				"--",
			];
		case "FILE_CHANGES":
			return [
				"diff-tree",
				"--root",
				"--no-commit-id",
				"--no-ext-diff",
				"--no-textconv",
				"--raw",
				"--numstat",
				"--diff-merges=first-parent",
				"-r",
				"-z",
				"-M",
				...request.revisions,
				"--",
			];
		default:
			throw new Error("Operation is not a single Git command");
	}
}

function startGit(
	args: string[],
	token?: string | null,
	directory?: string | null,
): ChildProcessByStdio<null, Readable, null>;
function startGit(
	args: string[],
	token: string | null,
	directory: string | null,
	inputDescriptor: number,
): ChildProcess;
function startGit(
	args: string[],
	token: string | null = null,
	directory: string | null = repositoryDirectory,
	inputDescriptor?: number,
) {
	const configs = [
		["core.hooksPath", "/nonexistent"],
		["core.fsmonitor", "false"],
		["credential.helper", ""],
		["diff.external", ""],
		["core.attributesFile", "/dev/null"],
		["protocol.file.allow", "never"],
		["protocol.ext.allow", "never"],
		// A redirect would carry the Authorization header to a host the clone URL never named.
		["http.followRedirects", "false"],
		["maintenance.auto", "false"],
		["gc.auto", "0"],
	];
	// The token rides a config value so that it appears in no argv and in no message Git prints.
	if (token !== null)
		configs.push([
			"http.extraHeader",
			`Authorization: Basic ${Buffer.from(`oauth2:${token}`).toString("base64")}`,
		]);
	const env: NodeJS.ProcessEnv = {
		PATH: "/usr/bin:/bin",
		HOME: "/tmp",
		LANG: "C.UTF-8",
		GIT_CONFIG_NOSYSTEM: "1",
		GIT_CONFIG_GLOBAL: "/dev/null",
		GIT_TERMINAL_PROMPT: "0",
		GIT_CONFIG_COUNT: String(configs.length),
	};
	configs.forEach(([key, value], index) => {
		env[`GIT_CONFIG_KEY_${index}`] = key;
		env[`GIT_CONFIG_VALUE_${index}`] = value;
	});
	// A null directory runs Git without --git-dir: `init` names its own target.
	return spawn("git", [...(directory === null ? [] : [`--git-dir=${directory}`]), ...args], {
		env,
		stdio: [inputDescriptor ?? "ignore", "pipe", "inherit"],
	});
}

async function runGit(
	args: string[],
	token: string | null = null,
	silent = false,
	directory: string | null = repositoryDirectory,
): Promise<void> {
	const child = startGit(args, token, directory);
	const finished = succeeded(child, "Git operation failed");
	if (silent) child.stdout.resume();
	await Promise.all([
		finished,
		silent ? Promise.resolve() : pipeline(child.stdout, process.stdout, { end: false }),
	]);
}

async function objectId(args: string[]): Promise<string | null> {
	const child = startGit(args);
	const finished = exited(child);
	let output = "";
	let code: number | null;
	try {
		for await (const chunk of child.stdout) {
			output += String(chunk);
			if (output.length > 128) {
				child.kill();
				throw new Error("Invalid object identity response");
			}
		}
	} finally {
		code = await finished;
	}
	if (code === 1) return null;
	if (code !== 0) throw new Error("Object identity lookup failed");
	const id = output.trim();
	if (!OBJECT_ID.test(id)) throw new Error("Invalid object identity");
	return id;
}

async function resolveDiffRange(request: Request): Promise<void> {
	const head = request.revisions[1];
	const target = await objectId([
		"rev-parse",
		"--quiet",
		"--verify",
		"--end-of-options",
		`${request.revisions[0]}^{commit}`,
	]);
	if (target === null || head === undefined) return;
	const child = startGit(["rev-list", "--parents", "--min-parents=2", target, `^${head}`, "--"]);
	const finished = exited(child);
	for await (const line of createInterface({ input: child.stdout })) {
		const [, first, second] = line.split(" ");
		if (second === head && first !== undefined) {
			child.kill();
			await finished;
			process.stdout.write(`${first}\n${head}\n`);
			return;
		}
	}
	if ((await finished) !== 0) throw new Error("Merge traversal failed");
	const base = await objectId(["merge-base", target, head]);
	if (base !== null && base !== head) process.stdout.write(`${base}\n${head}\n`);
}

async function gitFile(args: string[], path: string): Promise<void> {
	const child = startGit(args);
	await Promise.all([
		pipeline(child.stdout, createWriteStream(path)),
		succeeded(child, "Git detail extraction failed"),
	]);
}

async function commitDetails(request: Request): Promise<void> {
	for (const revision of request.revisions) {
		for (const operation of ["COMMIT_METADATA", "FILE_CHANGES"] as const) {
			const path = `${temporaryDirectory}/detail`;
			await gitFile(
				command({ operation, revisions: [revision], cloneUrl: null, token: null }),
				path,
			);
			const size = (await stat(path)).size;
			const length = Buffer.alloc(8);
			length.writeBigUInt64BE(BigInt(size));
			process.stdout.write(length);
			await pipeline(createReadStream(path), process.stdout, { end: false });
			await rm(path);
		}
	}
}

// Git prints a name it cannot decode with its bytes replaced, so a path or ref that is not UTF-8
// would be captured under a name that is not its own; it is refused before anything is written.
async function validateUtf8(args: string[]): Promise<void> {
	const child = startGit(args);
	const decoder = new TextDecoder("utf-8", { fatal: true });
	const finished = succeeded(child, "Repository path validation failed");
	try {
		await Promise.all([
			finished,
			(async () => {
				for await (const chunk of child.stdout) {
					if (!Buffer.isBuffer(chunk)) throw new Error("Expected native Git bytes");
					decoder.decode(chunk, { stream: true });
				}
				decoder.decode();
			})(),
		]);
	} catch (error) {
		child.kill("SIGKILL");
		throw error;
	}
}

// Measured before anything is written: a repository over the bound never touches the snapshot volume.
async function requireWithinSnapshotBound(revisions: string[]): Promise<void> {
	let bytes = 0;
	const listing = startGit(["ls-tree", "-r", "-l", "--full-tree", ...revisions]);
	const listed = succeeded(listing, "Snapshot size listing failed");
	for await (const line of createInterface({ input: listing.stdout })) {
		const size = Number(line.split("\t", 1)[0]?.split(/ +/)[3]);
		if (Number.isFinite(size)) bytes += size;
	}
	await listed;
	const directories = [`${repositoryDirectory}/objects`];
	for (const directory of directories) {
		for (const entry of await readdir(directory, { withFileTypes: true })) {
			const path = `${directory}/${entry.name}`;
			if (entry.isDirectory()) directories.push(path);
			else if (entry.isFile()) bytes += (await stat(path)).size;
		}
	}
	if (bytes > maxSnapshotBytes) throw new Error("Repository exceeds the snapshot bound");
}

async function snapshot(request: Request): Promise<void> {
	await requireWithinSnapshotBound(request.revisions);
	await validateUtf8(["ls-tree", "-rz", "--name-only", ...request.revisions]);
	await validateUtf8(["for-each-ref", "--format=%(refname)"]);
	const snapshotGitDirectory = `${snapshotDirectory}/.git`;
	await runGit(["init", "--bare", "--template=", snapshotGitDirectory], null, true, null);
	await runGit(
		[
			// The snapshot is filled from the mirror over the file transport, which the base config
			// disables for every other operation.
			"-c",
			"protocol.file.allow=always",
			// The reviewed commit may sit on no ref, so the fetch names it by id.
			"-c",
			"uploadpack.allowAnySHA1InWant=true",
			"fetch",
			"--no-recurse-submodules",
			repositoryDirectory,
			"+refs/remotes/origin/*:refs/remotes/origin/*",
			"+refs/tags/*:refs/tags/*",
			...request.revisions,
		],
		null,
		true,
		snapshotGitDirectory,
	);
	await rm(`${snapshotGitDirectory}/FETCH_HEAD`, { force: true });
	await runGit(["config", "core.bare", "false"], null, true, snapshotGitDirectory);
	// A symlink in the checkout would let the sandbox read outside the snapshot through it; it is
	// checked out as a file holding the target path instead.
	await runGit(["config", "core.symlinks", "false"], null, true, snapshotGitDirectory);
	await runGit(
		[`--work-tree=${snapshotDirectory}`, "checkout", "--force", "--detach", ...request.revisions],
		null,
		true,
		snapshotGitDirectory,
	);
	// Tags are peeled and every line carries its type, so a tag on a tree or blob is a witnessed
	// ref that history traversal can leave out rather than one that fails it.
	await gitFile(
		[
			"for-each-ref",
			"--format=%(if)%(*objecttype)%(then)%(*objecttype) %(*objectname)%(else)%(objecttype) %(objectname)%(end) %(refname)",
			"refs/remotes/origin",
			"refs/tags",
		],
		`${snapshotGitDirectory}/hephaestus-captured-refs`,
	);
	const tar = spawn("tar", ["-C", snapshotDirectory, "-cf", "-", "."], {
		stdio: ["ignore", "pipe", "ignore"],
	});
	await Promise.all([
		pipeline(tar.stdout, process.stdout, { end: false }),
		succeeded(tar, "Snapshot archive failed"),
	]);
}

async function* capturedRoots(head: string) {
	yield `${head}\n`;
	for await (const line of createInterface({
		input: createReadStream(`${repositoryDirectory}/hephaestus-captured-refs`),
		crlfDelay: Infinity,
	})) {
		const match =
			/^(commit|tree|blob) ((?:[a-f0-9]{40}|[a-f0-9]{64})) (refs\/(?:remotes\/origin|tags)\/.+)$/.exec(
				line,
			);
		if (
			!match?.[1] ||
			!match[2] ||
			!match[3] ||
			Array.from(match[3], (character) => character.charCodeAt(0)).some(
				(code) => code <= 32 || code === 127,
			)
		)
			throw new Error("Invalid captured Git roots");
		if (match[1] === "commit") yield `${match[2]}\n`;
	}
}

async function requireCapturedCommit(commit: string, head: string): Promise<void> {
	const ancestor = startGit(["merge-base", "--is-ancestor", commit, head]);
	ancestor.stdout.resume();
	const code = await exited(ancestor);
	if (code === 0) return;
	if (code !== 1) throw new Error("Cannot verify historical reachability");
	const roots = `${temporaryDirectory}/captured-roots`;
	await pipeline(Readable.from(capturedRoots(head)), createWriteStream(roots));
	const input = await open(roots, "r");
	try {
		const history = startGit(["rev-list", "--stdin"], null, repositoryDirectory, input.fd);
		if (history.stdout === null) {
			history.kill();
			throw new Error("Git history output unavailable");
		}
		const finished = exited(history);
		let found = false;
		for await (const line of createInterface({ input: history.stdout })) {
			if (line === commit) {
				found = true;
				history.kill();
				break;
			}
		}
		if (!found) {
			if ((await finished) !== 0) throw new Error("Historical traversal failed");
			throw new Error("Historical object is outside captured history");
		}
		await finished;
	} finally {
		await input.close();
		await rm(roots, { force: true });
	}
}

/** The blob at `path` in `commit`, or null when nothing regular is captured there. */
async function historicalObject(
	request: Request,
	verifiedCommits = new Set<string>(),
): Promise<string | null> {
	const [commit, head, path] = request.revisions;
	if (!commit || !head || !path) throw new Error("Missing historical object");
	if (!verifiedCommits.has(commit)) {
		if ((await objectId(["rev-parse", "--quiet", "--verify", "HEAD"])) !== head)
			throw new Error("Historical request does not match captured HEAD");
		await requireCapturedCommit(commit, head);
		verifiedCommits.add(commit);
	}
	const entry = startGit(["--literal-pathspecs", "ls-tree", "-z", commit, "--", path]);
	const finished = exited(entry);
	let bytes = Buffer.alloc(0);
	try {
		for await (const chunk of entry.stdout) {
			if (!Buffer.isBuffer(chunk)) throw new Error("Invalid tree entry bytes");
			bytes = Buffer.concat([bytes, chunk]);
			if (bytes.length > Buffer.byteLength(path) + 256)
				throw new Error("Ambiguous historical tree path");
		}
		if ((await finished) !== 0) throw new Error("Historical path lookup failed");
	} catch (error) {
		entry.kill();
		await finished;
		throw error;
	}
	if (bytes.length === 0) return null;
	const match = /^(100644|100755|120000) blob ([a-f0-9]{40}|[a-f0-9]{64})\t([\s\S]*)\0$/.exec(
		new TextDecoder("utf-8", { fatal: true }).decode(bytes),
	);
	return match && match[3] === path && match[2] ? match[2] : null;
}

async function historicalBlob(request: Request): Promise<void> {
	const object = await historicalObject(request);
	if (object === null) throw new Error("Historical path is not a regular captured file");
	await runGit(["cat-file", "blob", object]);
}

async function citedBlobs(request: Request): Promise<void> {
	const head = request.revisions[0];
	if (!head) throw new Error("Missing captured HEAD");
	const directory = await mkdtemp(`${temporaryDirectory}/cited-blobs-`);
	try {
		const verifiedCommits = new Set<string>();
		const names: string[] = [];
		for (let index = 1; index < request.revisions.length; index += 2) {
			const revision = request.revisions[index];
			const path = request.revisions[index + 1];
			if (!revision || !path) throw new Error("Invalid citation pair");
			const object = await historicalObject(
				{
					operation: "HISTORICAL_BLOB",
					revisions: [revision, head, path],
					cloneUrl: null,
					token: null,
				},
				verifiedCommits,
			);
			if (object === null) continue;
			const name = String((index - 1) / 2);
			await gitFile(["cat-file", "blob", object], `${directory}/${name}`);
			names.push(name);
		}
		const tar = spawn("tar", ["-C", directory, "-cf", "-", ...names], {
			stdio: ["ignore", "pipe", "ignore"],
		});
		await Promise.all([
			pipeline(tar.stdout, process.stdout, { end: false }),
			succeeded(tar, "Citation archive failed"),
		]);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

async function fetchCommit(request: Request): Promise<void> {
	await runGit(command(request), request.token);
	const expected = request.revisions[1] ?? "";
	const fetched = await objectId([
		"rev-parse",
		"--quiet",
		"--verify",
		"--end-of-options",
		`${expected}^{commit}`,
	]);
	if (fetched !== expected) throw new Error("Fetched ref does not carry the pinned commit");
}

async function repositoryStatus(request: Request): Promise<void> {
	try {
		await stat(`${repositoryDirectory}/HEAD`);
	} catch (error) {
		if (typeof error === "object" && error !== null && "code" in error && error.code === "ENOENT") {
			process.stdout.write("false\n");
			return;
		}
		throw error;
	}
	await runGit(command(request));
}

async function resolveRevision(request: Request): Promise<void> {
	const id = await objectId([
		"rev-parse",
		"--quiet",
		"--verify",
		"--end-of-options",
		`${request.revisions[0]}^{commit}`,
	]);
	if (id !== null) process.stdout.write(`${id}\n`);
}

async function secrets(request: Request): Promise<void> {
	const head = await objectId(["rev-parse", "--quiet", "--verify", "HEAD"]);
	if (head === null) throw new Error("Missing captured HEAD");
	for (const revision of request.revisions) await requireCapturedCommit(revision, head);
	process.stdout.write(JSON.stringify(await scanSecrets(request.revisions, startGit)));
}

export async function main(): Promise<void> {
	// One newline-terminated request: an attached container's stdin stays open after the client has
	// written, so reading to EOF would wait out the deadline.
	const lines = createInterface({ input: process.stdin });
	const input = (await lines[Symbol.asyncIterator]().next()).value ?? "";
	lines.close();
	if (Buffer.byteLength(input) > MAX_REQUEST_BYTES) throw new Error("Operation request too large");
	const request = parseRequest(JSON.parse(input));
	if (request.operation === "FETCH" || request.operation === "FETCH_COMMIT") {
		await mkdir(repositoryDirectory, { recursive: true });
		await runGit(["init", "--bare", "--template=", repositoryDirectory], null, true);
	}
	switch (request.operation) {
		case "STATUS":
			return repositoryStatus(request);
		case "RESOLVE":
			return resolveRevision(request);
		case "FETCH_COMMIT":
			return fetchCommit(request);
		case "SCAN_SECRETS":
			return secrets(request);
		case "HISTORICAL_BLOB":
			return historicalBlob(request);
		case "CITED_BLOBS":
			return citedBlobs(request);
		case "RESOLVE_DIFF_RANGE":
			return resolveDiffRange(request);
		case "COMMIT_DETAILS":
			return commitDetails(request);
		case "REVIEW_COMMITS":
			return reviewCommits(request.revisions, startGit);
		case "REVIEW_DIFF":
			return reviewDiff(request.revisions, startGit);
		case "SNAPSHOT":
			return snapshot(request);
		case "FETCH":
		case "TREE_ID":
		case "TREE_ENTRIES":
		case "COMMIT_SUBJECTS":
		case "COMMIT_RANGE":
		case "COMMIT_IDS":
		case "COMMIT_METADATA":
		case "FILE_CHANGES":
			return runGit(command(request), request.token);
	}
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		process.stderr.write(`${error instanceof Error ? error.message : "Git operation failed"}\n`);
		process.exitCode = 1;
	}
}
