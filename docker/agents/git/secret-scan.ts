import { spawn, type ChildProcessByStdio } from "node:child_process";
import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join, relative, isAbsolute } from "node:path";
import type { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fragments, records } from "./lines.ts";

type Git = (args: string[]) => ChildProcessByStdio<null, Readable, null>;
interface Finding {
	File: string;
	StartLine: number;
	EndLine: number;
	RuleID: string;
}
interface Verdict {
	path: string;
	line: number;
	ruleId: string;
	lineHash: string;
}
export interface SecretScan {
	verdicts: Verdict[];
	/** Changed files left out of the scan because their blob is over the ceiling. */
	skipped: string[];
}

/** A blob above this is not materialised; the scanner's own limit is disabled for what it does read. */
export const MAX_BLOB_BYTES = 8 * 1024 * 1024;

function completed(child: ReturnType<Git>) {
	return new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", (code) =>
			code === 0 ? resolve() : reject(new Error("Secret scan Git operation failed")),
		);
	});
}

export async function addedSecretVerdicts(
	path: string,
	matches: Finding[],
	source: Readable,
): Promise<Verdict[]> {
	const verdicts: Verdict[] = [];
	const prefix = Buffer.alloc(256);
	let prefixLength = 0;
	let first = -1;
	let line: number | undefined;
	let hash: ReturnType<typeof createHash> | undefined;
	let matched: Finding[] = [];
	function finishLine() {
		const header = prefix.subarray(0, prefixLength).toString("utf8");
		const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(header);
		if (hunk) {
			line = Number(hunk[1]);
			if (!Number.isSafeInteger(line)) throw new Error("Invalid diff line number");
		} else if (header.startsWith("diff --git ")) line = undefined;
		else if (line !== undefined && first === 43) {
			if (hash) {
				const lineHash = hash.digest("hex");
				for (const match of matched) verdicts.push({ path, line, ruleId: match.RuleID, lineHash });
			}
			line++;
		} else if (line !== undefined && first === 32) line++;
		first = -1;
		prefixLength = 0;
		hash = undefined;
		matched = [];
	}
	for await (const fragment of fragments(source)) {
		const part = fragment.bytes;
		const beginning = fragment.start && part.length !== 0;
		if (beginning) {
			first = part[0] ?? -1;
			if (first === 43 && line !== undefined) {
				const number = line;
				matched = matches.filter((match) => number >= match.StartLine && number <= match.EndLine);
				if (matched.length !== 0) hash = createHash("sha256");
			}
		}
		const copied = part.copy(
			prefix,
			prefixLength,
			0,
			Math.min(part.length, prefix.length - prefixLength),
		);
		prefixLength += copied;
		hash?.update(beginning ? part.subarray(1) : part);
		if (fragment.end) finishLine();
	}
	if (first !== -1) finishLine();
	return verdicts;
}

function findings(value: unknown): Finding[] {
	if (value === null) return [];
	if (!Array.isArray(value)) throw new Error("Invalid secret scanner report");
	return value.map((item: unknown) => {
		if (
			typeof item !== "object" ||
			item === null ||
			!("File" in item) ||
			typeof item.File !== "string" ||
			!("RuleID" in item) ||
			typeof item.RuleID !== "string" ||
			!("StartLine" in item) ||
			typeof item.StartLine !== "number" ||
			!Number.isSafeInteger(item.StartLine) ||
			item.StartLine < 1 ||
			!("EndLine" in item) ||
			typeof item.EndLine !== "number" ||
			!Number.isSafeInteger(item.EndLine) ||
			item.EndLine < item.StartLine
		)
			throw new Error("Invalid secret scanner finding");
		return {
			File: item.File,
			StartLine: item.StartLine,
			EndLine: item.EndLine,
			RuleID: item.RuleID,
		};
	});
}

/** The size of every listed path's blob at `revision`, from one tree listing. */
export async function blobSizes(
	git: Git,
	revision: string,
	paths: ReadonlySet<string>,
): Promise<Map<string, number>> {
	const sizes = new Map<string, number>();
	const tree = git(["ls-tree", "-r", "-l", "-z", "--full-tree", revision]);
	const finished = completed(tree);
	for await (const { bytes } of records(tree.stdout, 0)) {
		const tab = bytes.indexOf(9);
		if (tab === -1) throw new Error("Invalid tree listing");
		const path = bytes.subarray(tab + 1).toString("utf8");
		if (!paths.has(path)) continue;
		const size = /^\d{6} blob [a-f0-9]+ +(\d+)$/.exec(bytes.subarray(0, tab).toString("utf8"))?.[1];
		if (size === undefined) throw new Error("Changed source path is not a blob");
		sizes.set(path, Number(size));
	}
	await finished;
	for (const path of paths)
		if (!sizes.has(path)) throw new Error("Changed source path is not in the tree");
	return sizes;
}

export async function scanSecrets(
	revisions: string[],
	git: Git,
	maxBlobBytes = MAX_BLOB_BYTES,
): Promise<SecretScan> {
	const directory = await mkdtemp(join(process.env.GIT_TEMP_DIRECTORY ?? "/tmp", "secret-scan-"));
	const sources = join(directory, "sources");
	const files: string[] = [];
	try {
		await mkdir(sources);
		const changes = git([
			"diff",
			"--raw",
			"--no-ext-diff",
			"--no-textconv",
			"-z",
			"--no-renames",
			"--diff-filter=AMT",
			...revisions,
			"--",
		]);
		const exited = completed(changes);
		let regular = false;
		let metadata = true;
		for await (const { bytes: record } of records(changes.stdout, 0)) {
			if (metadata) {
				regular = /^:\d{6} 100(?:644|755) /.test(record.toString("utf8"));
			} else if (regular) {
				const path = record.toString("utf8");
				if (
					!Buffer.from(path).equals(record) ||
					isAbsolute(path) ||
					path.split("/").some((part) => part === ".." || part === "." || part === "")
				)
					throw new Error("Invalid changed source path");
				files.push(path);
			}
			metadata = !metadata;
		}
		await exited;
		if (!metadata) throw new Error("Incomplete changed source record");
		const head = revisions[1];
		if (head === undefined) throw new Error("Missing scan head");
		const sizes = await blobSizes(git, head, new Set(files));
		const skipped = files.filter((path) => (sizes.get(path) ?? 0) > maxBlobBytes);
		const scanned = files.filter((path) => !skipped.includes(path));
		if (scanned.length === 0) return { verdicts: [], skipped };
		for (const path of scanned) {
			const destination = join(sources, path);
			await mkdir(dirname(destination), { recursive: true });
			const blob = git(["cat-file", "blob", `${revisions[1]}:${path}`]);
			await Promise.all([completed(blob), pipeline(blob.stdout, createWriteStream(destination))]);
		}
		const config = join(directory, "scanner.toml");
		const ignore = join(directory, "ignore");
		const report = join(directory, "report.json");
		await writeFile(config, "[extend]\nuseDefault = true\n");
		await writeFile(ignore, "");
		const scanner = spawn(
			"gitleaks",
			[
				"dir",
				sources,
				"--config",
				config,
				"--gitleaks-ignore-path",
				ignore,
				"--ignore-gitleaks-allow",
				"--max-target-megabytes=0",
				"--redact=100",
				"--no-banner",
				"--log-level=fatal",
				"--report-format=json",
				"--report-path",
				report,
				"--exit-code=10",
			],
			{
				stdio: "ignore",
				env: { PATH: process.env.PATH, HOME: directory },
			},
		);
		await new Promise<void>((resolve, reject) => {
			scanner.once("error", reject);
			scanner.once("close", (code) =>
				code === 0 || code === 10 ? resolve() : reject(new Error("Secret scanner failed")),
			);
		});
		const detected = findings(JSON.parse(await readFile(report, "utf8")));
		const byPath = new Map<string, Finding[]>();
		const capturedPaths = new Set(scanned);
		for (const finding of detected) {
			const path = isAbsolute(finding.File) ? relative(sources, finding.File) : finding.File;
			if (!capturedPaths.has(path)) throw new Error("Secret scanner returned an uncaptured path");
			const list = byPath.get(path) ?? [];
			list.push(finding);
			byPath.set(path, list);
		}
		const verdicts: Verdict[] = [];
		for (const [path, matches] of byPath) {
			const diff = git([
				"--literal-pathspecs",
				"diff",
				"--no-ext-diff",
				"--no-textconv",
				"--text",
				"--no-color",
				"--no-renames",
				"--unified=0",
				...revisions,
				"--",
				path,
			]);
			const [, added] = await Promise.all([
				completed(diff),
				addedSecretVerdicts(path, matches, diff.stdout),
			]);
			for (const verdict of added) verdicts.push(verdict);
		}
		return { verdicts, skipped };
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}
