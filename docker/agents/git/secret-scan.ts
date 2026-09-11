import { spawn, type ChildProcessByStdio } from "node:child_process";
import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join, relative, isAbsolute } from "node:path";
import type { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";

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

function completed(child: ReturnType<Git>) {
	return new Promise<void>((resolve, reject) => {
		child.once("error", reject);
		child.once("close", (code) =>
			code === 0 ? resolve() : reject(new Error("Secret scan Git operation failed")),
		);
	});
}

async function* records(source: Readable, separator: number) {
	let fragments: Buffer[] = [];
	for await (const chunk of source) {
		if (!Buffer.isBuffer(chunk)) throw new Error("Expected Git bytes");
		let start = 0;
		let end: number;
		while ((end = chunk.indexOf(separator, start)) !== -1) {
			const part = chunk.subarray(start, end);
			yield fragments.length === 0 ? part : Buffer.concat([...fragments, part]);
			fragments = [];
			start = end + 1;
		}
		if (start < chunk.length) fragments.push(chunk.subarray(start));
	}
	if (fragments.length !== 0) yield Buffer.concat(fragments);
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
	for await (const chunk of source) {
		if (!Buffer.isBuffer(chunk)) throw new Error("Expected Git bytes");
		let start = 0;
		while (start < chunk.length) {
			const newline = chunk.indexOf(10, start);
			const end = newline === -1 ? chunk.length : newline;
			const part = chunk.subarray(start, end);
			const beginning = first === -1 && part.length !== 0;
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
			if (newline !== -1) finishLine();
			start = end + 1;
		}
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

export async function scanSecrets(revisions: string[], git: Git): Promise<Verdict[]> {
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
		for await (const record of records(changes.stdout, 0)) {
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
		if (files.length === 0) return [];
		for (const path of files) {
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
		const capturedPaths = new Set(files);
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
			const finished = completed(diff);
			const [, added] = await Promise.all([
				finished,
				addedSecretVerdicts(path, matches, diff.stdout),
			]);
			for (const verdict of added) verdicts.push(verdict);
		}
		return verdicts;
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}
