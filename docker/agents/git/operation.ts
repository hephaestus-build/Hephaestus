import { spawn, type ChildProcess, type ChildProcessByStdio } from "node:child_process";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, stat, rm, open } from "node:fs/promises";
import { createInterface } from "node:readline";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { reviewCommits } from "./review-commits.ts";
import { reviewDiff } from "./review-diff.ts";
import { scanSecrets } from "./secret-scan.ts";

const repositoryDirectory = process.env.GIT_REPOSITORY_DIRECTORY ?? "/git/mirror.git";
const snapshotDirectory = process.env.GIT_SNAPSHOT_DIRECTORY ?? "/snapshot";
const temporaryDirectory = process.env.GIT_TEMP_DIRECTORY ?? "/tmp";

interface Request {
	operation:
		| "SCAN_SECRETS"
		| "CITED_BLOBS"
		| "HISTORICAL_BLOB"
		| "FETCH_COMMIT"
		| "STATUS"
		| "TREE_ID"
		| "TREE_ENTRIES"
		| "COMMIT_SUBJECTS"
        | "COMMIT_RANGE"
		| "COMMIT_DETAILS"
		| "SNAPSHOT"
		| "FETCH"
		| "RESOLVE"
		| "COMMIT_IDS"
		| "COMMIT_METADATA"
		| "FILE_CHANGES"
		| "RESOLVE_DIFF_RANGE"
		| "REVIEW_DIFF"
        | "REVIEW_COMMITS";
	revisions: string[];
	cloneUrl: string | null;
	token: string | null;
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
	if (
		operation !== "SCAN_SECRETS" &&
		operation !== "CITED_BLOBS" &&
		operation !== "HISTORICAL_BLOB" &&
		operation !== "FETCH_COMMIT" &&
		operation !== "STATUS" &&
		operation !== "TREE_ID" &&
		operation !== "TREE_ENTRIES" &&
		operation !== "COMMIT_SUBJECTS" &&
        operation !== "COMMIT_RANGE" &&
		operation !== "COMMIT_DETAILS" &&
		operation !== "SNAPSHOT" &&
		operation !== "FETCH" &&
		operation !== "RESOLVE" &&
		operation !== "COMMIT_IDS" &&
		operation !== "COMMIT_METADATA" &&
		operation !== "FILE_CHANGES" &&
		operation !== "RESOLVE_DIFF_RANGE" &&
		operation !== "REVIEW_DIFF" &&
        operation !== "REVIEW_COMMITS"
	)
		throw new Error("Unknown operation");
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
	if (
		operation !== "FETCH" &&
		operation !== "FETCH_COMMIT" &&
		(cloneUrl !== null || token !== null)
	)
		throw new Error("Offline operation received authorization");
	if (operation === "FETCH" || operation === "FETCH_COMMIT") {
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
		revisions.some((revision) => !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(revision))
	)
		throw new Error("Revisions must be pinned object IDs");
	const expected =
		operation === "HISTORICAL_BLOB"
			? 3
			: [
						"RESOLVE",
						"COMMIT_METADATA",
						"FILE_CHANGES",
						"TREE_ID",
						"TREE_ENTRIES",
						"SNAPSHOT",
				  ].includes(operation)
				? 1
				: ["REVIEW_COMMITS", "COMMIT_SUBJECTS", "SCAN_SECRETS", "REVIEW_DIFF", "RESOLVE_DIFF_RANGE", "FETCH_COMMIT"].includes(operation)
					? 2
					: 0;
	if (operation === "CITED_BLOBS") {
        if (revisions.length < 3 || revisions.length % 2 !== 1) throw new Error("Invalid citation batch");
        for (let index = 1; index < revisions.length; index += 2) parseRequest({operation: "HISTORICAL_BLOB", revisions: [revisions[index], revisions[0], revisions[index + 1]]});
    } else if (operation === "COMMIT_DETAILS") {
		if (revisions.length < 1 || revisions.length > 256) throw new Error("Invalid commit page");
	} else if (operation === "COMMIT_RANGE") {
		if (revisions.length < 1 || revisions.length > 2) throw new Error("Invalid commit range");
	} else if (revisions.length !== expected) throw new Error("Incorrect revision count");
	if (
		operation === "FETCH_COMMIT" &&
		(!revisions[0]?.startsWith("refs/") ||
			revisions[0].includes(":") ||
			!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(revisions[1] ?? ""))
	)
		throw new Error("Invalid pinned fetch");
	if (operation === "HISTORICAL_BLOB") {
		const [commit, head, path] = revisions;
		if (
			![commit, head].every((id) => /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(id ?? "")) ||
			!path ||
			path.startsWith("/") ||
			path.includes("\\") ||
			path.split("/").some((part) => part === ".." || part === "." || part === "")
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
            return ["log", "--no-decorate", "-z", "--format=%s", `${request.revisions[0]}..${request.revisions[1]}`, "--"];
        case "COMMIT_RANGE":
			return [
				"rev-list",
				...(request.revisions.length === 2
					? [`${request.revisions[0]}..${request.revisions[1]}`]
					: request.revisions),
			];
		case "SCAN_SECRETS":
		case "CITED_BLOBS":
		case "HISTORICAL_BLOB":
		case "COMMIT_DETAILS":
		case "SNAPSHOT":
		case "REVIEW_COMMITS":
        case "REVIEW_DIFF":
			throw new Error("Operation is not a single Git command");
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
		case "RESOLVE":
			return ["rev-parse", "--verify", "--end-of-options", `${request.revisions[0]}^{commit}`];
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
		case "RESOLVE_DIFF_RANGE":
			throw new Error("Range resolution is not a single Git command");
	}
	throw new Error("Unsupported Git operation");
}

function startGit(args: string[], token?: string | null, directory?: string): ChildProcessByStdio<null, Readable, null>;
function startGit(args: string[], token: string | null, directory: string, inputDescriptor: number): ChildProcess;
function startGit(args: string[], token: string | null = null, directory = repositoryDirectory, inputDescriptor?: number) {
	const configs = [
		["core.hooksPath", "/nonexistent"],
		["core.fsmonitor", "false"],
		["credential.helper", ""],
		["diff.external", ""],
		["core.attributesFile", "/dev/null"],
		["protocol.file.allow", "never"],
		["protocol.ext.allow", "never"],
		["http.followRedirects", "false"],
		["maintenance.auto", "false"],
		["gc.auto", "0"],
	];
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
	return spawn("git", [...(directory ? [`--git-dir=${directory}`] : []), ...args], {
		env,
		stdio: [inputDescriptor ?? "ignore", "pipe", "ignore"],
	});
}

async function runGit(
	args: string[],
	token: string | null = null,
	silent = false,
	directory = repositoryDirectory,
): Promise<void> {
	const child = startGit(args, token, directory);
	const exited = new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", (code) =>
			code === 0 ? resolve() : reject(new Error("Git operation failed")),
		);
	});
	if (silent) child.stdout.resume();
	await Promise.all([
		exited,
		silent ? Promise.resolve() : pipeline(child.stdout, process.stdout, { end: false }),
	]);
}

async function objectId(args: string[]): Promise<string | null> {
	const child = startGit(args);
	const exited = new Promise<number | null>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", resolve);
	});
	let output = "";
	for await (const chunk of child.stdout) {
		output += String(chunk);
		if (output.length > 128) {
			child.kill();
			throw new Error("Invalid object identity response");
		}
	}
	const code = await exited;
	if (code === 1) return null;
	if (code !== 0) throw new Error("Object identity lookup failed");
	const id = output.trim();
	if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(id)) throw new Error("Invalid object identity");
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
	const exited = new Promise<number | null>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", resolve);
	});
	for await (const line of createInterface({ input: child.stdout })) {
		const [, first, second] = line.split(" ");
		if (second === head && first !== undefined) {
			child.kill();
			await exited;
			process.stdout.write(`${first}\n${head}\n`);
			return;
		}
	}
	if ((await exited) !== 0) throw new Error("Merge traversal failed");
	const base = await objectId(["merge-base", target, head]);
	if (base !== null && base !== head) process.stdout.write(`${base}\n${head}\n`);
}

async function gitFile(args: string[], path: string): Promise<void> {
	const child = startGit(args);
	await Promise.all([
		pipeline(child.stdout, createWriteStream(path)),
		new Promise<void>((resolve, reject) => {
			child.once("error", reject);
			child.once("close", (code) =>
				code === 0 ? resolve() : reject(new Error("Git detail extraction failed")),
			);
		}),
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

async function validateUtf8(args: string[]): Promise<void> {
	const child = startGit(args);
	const decoder = new TextDecoder("utf-8", { fatal: true });
	const exited = new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", (code) =>
			code === 0 ? resolve() : reject(new Error("Repository path validation failed")),
		);
	});
	try {
		await Promise.all([
			exited,
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

async function snapshot(request: Request): Promise<void> {
	await validateUtf8(["ls-tree", "-rz", "--name-only", ...request.revisions]);
	await validateUtf8(["for-each-ref", "--format=%(refname)"]);
	await runGit(["init", "--bare", "--template=", `${snapshotDirectory}/.git`], null, true, "");
	await runGit(
		[
			"-c",
			"protocol.file.allow=always",
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
		`${snapshotDirectory}/.git`,
	);
	await rm(`${snapshotDirectory}/.git/FETCH_HEAD`, { force: true });
	await runGit(["config", "core.bare", "false"], null, true, `${snapshotDirectory}/.git`);
	await runGit(["config", "core.symlinks", "false"], null, true, `${snapshotDirectory}/.git`);
	await runGit(
		[`--work-tree=${snapshotDirectory}`, "checkout", "--force", "--detach", ...request.revisions],
		null,
		true,
		`${snapshotDirectory}/.git`,
	);
	await gitFile(["for-each-ref", "--format=%(objectname) %(refname)", "refs/remotes/origin", "refs/tags"], `${snapshotDirectory}/.git/hephaestus-captured-refs`);
	const tar = spawn("tar", ["-C", snapshotDirectory, "-cf", "-", "."], {
		stdio: ["ignore", "pipe", "ignore"],
	});
	await Promise.all([
		pipeline(tar.stdout, process.stdout, { end: false }),
		new Promise<void>((resolve, reject) => {
			tar.once("error", reject);
			tar.once("close", (code) =>
				code === 0 ? resolve() : reject(new Error("Snapshot archive failed")),
			);
		}),
	]);
}

async function* capturedRoots(head: string) {
	yield `${head}\n`;
	for await (const line of createInterface({ input: createReadStream(`${repositoryDirectory}/hephaestus-captured-refs`), crlfDelay: Infinity })) {
		const match = /^((?:[a-f0-9]{40}|[a-f0-9]{64})) (refs\/(?:remotes\/origin|tags)\/.+)$/.exec(line);
		if (!match?.[1] || !match[2] || Array.from(match[2], character => character.charCodeAt(0)).some(code => code <= 32 || code === 127)) throw new Error("Invalid captured Git roots");
		yield `${match[1]}\n`;
	}
}

async function requireCapturedCommit(commit: string, head: string): Promise<void> {
	const ancestor = startGit(["merge-base", "--is-ancestor", commit, head]);
	ancestor.stdout.resume();
	const code = await new Promise<number | null>((resolve, reject) => { ancestor.once("error", reject); ancestor.once("close", resolve); });
	if (code === 0) return;
	if (code !== 1) throw new Error("Cannot verify historical reachability");
	const roots = `${temporaryDirectory}/captured-roots`;
	await pipeline(Readable.from(capturedRoots(head)), createWriteStream(roots));
	const input = await open(roots, "r");
	try {
		const history = startGit(["rev-list", "--stdin"], null, repositoryDirectory, input.fd);
		if (history.stdout === null) { history.kill(); throw new Error("Git history output unavailable"); }
		const exited = new Promise<number | null>((resolve, reject) => { history.once("error", reject); history.once("close", resolve); });
		let found = false;
		for await (const line of createInterface({ input: history.stdout })) if (line === commit) found = true;
		if (await exited !== 0 || !found) throw new Error("Historical object is outside captured history");
	} finally { await input.close(); await rm(roots, { force: true }); }
}

async function historicalObject(request: Request, verifiedCommits = new Set<string>()): Promise<string> {
	const [commit, head, path] = request.revisions;
	if (!commit || !head || !path) throw new Error("Missing historical object");
	if (!verifiedCommits.has(commit)) {
        if (await objectId(["rev-parse", "--quiet", "--verify", "HEAD"]) !== head) throw new Error("Historical request does not match captured HEAD");
        await requireCapturedCommit(commit, head);
        verifiedCommits.add(commit);
    }
	const entry = startGit(["--literal-pathspecs", "ls-tree", "-z", commit, "--", path]);
	const exited = new Promise<number | null>((resolve, reject) => { entry.once("error", reject); entry.once("close", resolve); });
	let bytes = Buffer.alloc(0);
	try {
		for await (const chunk of entry.stdout) {
			if (!Buffer.isBuffer(chunk)) throw new Error("Invalid tree entry bytes");
			bytes = Buffer.concat([bytes, chunk]);
			if (bytes.length > Buffer.byteLength(path) + 256) throw new Error("Ambiguous historical tree path");
		}
		if (await exited !== 0) throw new Error("Historical path lookup failed");
	} catch (error) { entry.kill(); await exited; throw error; }
	const match = /^(100644|100755|120000) blob ([a-f0-9]{40}|[a-f0-9]{64})\t([\s\S]*)\0$/.exec(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
	if (!match || match[3] !== path || !match[2]) throw new Error("Historical path is not a regular captured file");
	return match[2];
}

async function citedBlobs(request: Request): Promise<void> {
    const head = request.revisions[0];
    if (!head) throw new Error("Missing captured HEAD");
    const directory = `${temporaryDirectory}/cited-blobs`;
    await mkdir(directory);
    const verifiedCommits = new Set<string>();
    const names: string[] = [];
    for (let index = 1; index < request.revisions.length; index += 2) {
        const revision = request.revisions[index]; const path = request.revisions[index + 1];
        if (!revision || !path) throw new Error("Invalid citation pair");
        const object = await historicalObject({operation: "HISTORICAL_BLOB", revisions: [revision, head, path], cloneUrl: null, token: null}, verifiedCommits);
        const name = String(names.length); names.push(name);
        await gitFile(["cat-file", "blob", object], `${directory}/${name}`);
    }
    const tar = spawn("tar", ["-C", directory, "-cf", "-", ...names], {stdio: ["ignore", "pipe", "ignore"]});
    await Promise.all([pipeline(tar.stdout, process.stdout, {end: false}), new Promise<void>((resolve, reject) => {
        tar.once("error", reject); tar.once("close", code => code === 0 ? resolve() : reject(new Error("Citation archive failed")));
    })]);
}

export async function main(): Promise<void> {
	process.stdin.setEncoding("utf8");
	let input = "";
	for await (const chunk of process.stdin) {
		input += String(chunk);
		if (Buffer.byteLength(input) > 64 * 1024) throw new Error("Operation request too large");
	}
	const request = parseRequest(JSON.parse(input));
	if (request.operation === "FETCH" || request.operation === "FETCH_COMMIT") {
		await mkdir(repositoryDirectory, { recursive: true });
		await runGit(["init", "--bare", "--template=", repositoryDirectory], null, true);
	}
	if (request.operation === "STATUS") {
		try {
			await stat(`${repositoryDirectory}/HEAD`);
		} catch (error) {
			if (
				typeof error === "object" &&
				error !== null &&
				"code" in error &&
				error.code === "ENOENT"
			) {
				process.stdout.write("false\n");
				return;
			}
			throw error;
		}
		await runGit(command(request));
	} else if (request.operation === "RESOLVE") {
		const id = await objectId([
			"rev-parse",
			"--quiet",
			"--verify",
			"--end-of-options",
			`${request.revisions[0]}^{commit}`,
		]);
		if (id !== null) process.stdout.write(`${id}\n`);
	} else if (request.operation === "SCAN_SECRETS") {
		const head = await objectId(["rev-parse", "--quiet", "--verify", "HEAD"]);
		if (head === null) throw new Error("Missing captured HEAD");
		for (const revision of request.revisions) await requireCapturedCommit(revision, head);
		process.stdout.write(JSON.stringify(await scanSecrets(request.revisions, startGit)));
	} else if (request.operation === "HISTORICAL_BLOB") await runGit(["cat-file", "blob", await historicalObject(request)]);
    else if (request.operation === "CITED_BLOBS") await citedBlobs(request);
	else if (request.operation === "RESOLVE_DIFF_RANGE") await resolveDiffRange(request);
	else if (request.operation === "COMMIT_DETAILS") await commitDetails(request);
	else if (request.operation === "REVIEW_COMMITS") await reviewCommits(request.revisions, startGit);
	else if (request.operation === "REVIEW_DIFF") await reviewDiff(request.revisions, startGit);
	else if (request.operation === "SNAPSHOT") await snapshot(request);
	else await runGit(command(request), request.token);
}

if (import.meta.main) {
	try {
		await main();
	} catch {
		process.stderr.write("Git operation failed\n");
		process.exitCode = 1;
	}
}
