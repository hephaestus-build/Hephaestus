#!/usr/bin/env node
import { existsSync } from "node:fs";
import { mkdir, readdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { parseArgs } from "node:util";

import { boundModelsSchema, isFailed, PRACTICE_SLUG, type BoundModels } from "./lib/contract.ts";
import { globFilesSync } from "./lib/files.ts";

import { parseDiff } from "./lib/diff-parser.ts";
import { isJsonObject } from "./lib/json.ts";
import { hostModels, ModelBudget } from "./lib/models-host.ts";
import {
	POSITIONAL_MS,
	practiceFiles,
	readableRoots,
	runScript,
	type ScriptOutcome,
} from "./lib/script-run.ts";
import type { ArtifactMetadata, DiffFile } from "./lib/types.ts";

const DEFAULT_OUTPUT_DIR = ".precompute";
/** Scripts that run at once: each is its own Node process, and the sandbox has two CPUs. */
const CONCURRENCY = 4;
/** Model calls in flight across all scripts. */
const MODEL_CONCURRENCY = 4;

const { values } = parseArgs({
	args: process.argv.slice(2),
	options: {
		repo: { type: "string" },
		diff: { type: "string" },
		metadata: { type: "string" },
		context: { type: "string" },
		"context-reference": { type: "string" },
		change: { type: "string" },
		practices: { type: "string" },
		output: { type: "string", default: DEFAULT_OUTPUT_DIR },
		timeout: { type: "string", default: String(POSITIONAL_MS) },
		// The stage's own deadline: a script that starts late gets only what is left of it.
		"stage-ms": { type: "string" },
		// The models the server bound for this job (precompute-models.json). Calls go to
		// LLM_PROXY_URL with PRECOMPUTE_PROXY_TOKEN, which no script's process holds.
		models: { type: "string" },
		// The tokens all scripts may spend on models together: the proxy's cap for the attempt.
		tokens: { type: "string" },
	},
});

if (values.repo === undefined || values.repo === "") {
	console.error(
		"Usage: node runner.ts --repo <path> --diff <path> [--metadata <path>] [--context <dir>] [--change <dir>] [--practices <dir>] [--output <dir>]",
	);
	process.exit(1);
}

const globalStart = Date.now();
const repoPath = values.repo;
const outputDir = values.output;
// A non-numeric or non-positive --timeout would make every race timer fire immediately and time out
// every practice on the spot, so an unusable value falls back to the default instead of silently
// disabling precompute.
const requestedTimeoutMs = Number.parseInt(values.timeout, 10);
const timeoutIsUsable = Number.isFinite(requestedTimeoutMs) && requestedTimeoutMs > 0;
if (!timeoutIsUsable) {
	console.error(`Ignoring unusable --timeout ${values.timeout}; using ${POSITIONAL_MS}ms`);
}
const timeoutMs = timeoutIsUsable ? requestedTimeoutMs : POSITIONAL_MS;
const contextDir = values.context ?? "";
const contextReference = values["context-reference"] ?? "";
if (contextDir !== "" && contextReference === "") {
	throw new Error("--context-reference is required with --context");
}
const changeDir = values.change ?? "";
const stageMs = Number.parseInt(values["stage-ms"] ?? "", 10);
const stageEnd =
	Number.isFinite(stageMs) && stageMs > 0 ? globalStart + stageMs : Number.POSITIVE_INFINITY;
// Without a ceiling here, the proxy still refuses a call past its cap with 402, unrated as "budget".
const requestedTokens = Number.parseInt(values.tokens ?? "", 10);
const stageTokens =
	Number.isFinite(requestedTokens) && requestedTokens > 0
		? requestedTokens
		: Number.POSITIVE_INFINITY;

let diffFiles = new Map<string, DiffFile>();
if (values.diff !== undefined && values.diff !== "") {
	try {
		const diffContent = await readFile(values.diff, "utf8");
		diffFiles = parseDiff(diffContent);
		console.error(`Parsed diff: ${diffFiles.size} files`);
	} catch (error) {
		console.error(`Could not parse diff: ${String(error)}`);
	}
}

let metadata: ArtifactMetadata = {};
if (values.metadata !== undefined && values.metadata !== "") {
	try {
		const parsed: unknown = JSON.parse(await readFile(values.metadata, "utf8"));
		if (isJsonObject(parsed)) {
			metadata = parsed;
		} else {
			console.error(`Metadata ${values.metadata} is not a JSON object; scripts will see {}`);
		}
	} catch (error) {
		console.error(`Could not load metadata: ${String(error)}`);
	}
}

const practicesDir = values.practices ?? `${outputDir}/practices`;
const practiceModules: [string, string][] = [];

if (existsSync(practicesDir)) {
	for (const file of globFilesSync("*.ts", practicesDir)) {
		const slug = file.replace(/\.ts$/u, "");
		if (PRACTICE_SLUG.test(slug)) {
			practiceModules.push([slug, `${practicesDir}/${file}`]);
		} else {
			console.error(`Not running ${file}: its name is not a practice slug`);
		}
	}
}

if (practiceModules.length === 0) {
	console.error("No practice scripts found. Exiting.");
	await mkdir(outputDir, { recursive: true });
	process.exit(0);
}

console.error(`Running ${practiceModules.length} practice analyzer(s)...`);

// Each practice's files are written as soon as it finishes, so a stage that is stopped at its
// deadline keeps every practice that finished before it.
await mkdir(outputDir, { recursive: true });
// Only an earlier run's files go; a folder inside the output (scripts staged there) stays.
for (const entry of await readdir(outputDir, { withFileTypes: true })) {
	if (entry.isFile()) {
		await rm(path.join(outputDir, entry.name), { force: true });
	}
}

/** Write a file whole or not at all: a reader never sees half a section. */
async function writeWhole(file: string, content: string): Promise<void> {
	const partial = `${file}.partial`;
	await writeFile(partial, content);
	await rename(partial, file);
}

// A child may read the workspace inputs and the precompute install. Node's permission model guards
// against mistakes and is no isolation boundary: it follows a symbolic link out of a granted folder
// and does not restrict the network. The container is the boundary.
// The stage holds the copied scripts and the `lib` link they import through (`../lib/…`).
const readable = readableRoots([path.dirname(practicesDir), repoPath, contextDir, changeDir]);

/** The models the server bound, or none when the file is missing or does not parse. */
async function boundModels(file: string | undefined): Promise<BoundModels> {
	if (file === undefined || file === "") {
		return {};
	}
	try {
		return boundModelsSchema.parse(JSON.parse(await readFile(file, "utf8")));
	} catch (error) {
		console.error(`Ignoring ${file}: ${String(error)}`);
		return {};
	}
}

const proxyUrl = process.env.LLM_PROXY_URL ?? "";
const proxyToken = process.env.PRECOMPUTE_PROXY_TOKEN ?? "";
const callable = proxyUrl !== "" && proxyToken !== "";
const bound = callable ? await boundModels(values.models) : {};
const environment = {
	readable,
	sources: { change: diffFiles, repo: repoPath, contextDir, contextReference },
	metadata,
	changeDir,
	now: new Date(globalStart).toISOString(),
	stageEnd,
	positionalMs: timeoutMs,
	models: (practice: string) => hostModels(bound, proxyUrl, proxyToken, practice),
	budget: new ModelBudget(stageTokens, MODEL_CONCURRENCY),
	log: (line: string) => console.error(line),
};

const queue = [...practiceModules];
const outcomes: ScriptOutcome[] = [];
await Promise.all(
	Array.from({ length: Math.min(CONCURRENCY, queue.length) }, async () => {
		for (let next = queue.shift(); next !== undefined; next = queue.shift()) {
			const [slug, modulePath] = next;
			const start = Date.now();
			const outcome = await runScript(slug, modulePath, environment);
			const durationMs = Date.now() - start;
			const { result } = outcome;
			const files = practiceFiles(
				outcome,
				durationMs,
				`${outputDir}/${slug}.json`,
				contextReference,
			);
			await writeWhole(`${outputDir}/${slug}.json`, files.json);
			await writeWhole(`${outputDir}/${slug}.md`, files.section);
			console.error(
				isFailed(result.status)
					? `  FAIL ${slug}: ${result.directions[0] ?? ""} (${durationMs}ms)`
					: `  ${result.status} ${slug} (${outcome.contract}): ${outcome.contract === "definition" ? outcome.result.leads.length : outcome.result.hints.length} leads (${durationMs}ms)`,
			);
			outcomes.push(outcome);
		}
	}),
);

const positionalResults = outcomes.flatMap((o) => (o.contract === "definition" ? [] : [o.result]));
const definitionResults = outcomes.flatMap((o) => (o.contract === "definition" ? [o.result] : []));
const summary = {
	durationMs: Date.now() - globalStart,
	practices: outcomes.length,
	positionalHints: positionalResults.reduce((s, r) => s + r.hints.length, 0),
	inDiffHints: positionalResults.reduce((s, r) => s + r.hints.filter((h) => h.inDiff).length, 0),
	leads: definitionResults.reduce((s, r) => s + r.leads.length, 0),
	errors: outcomes.filter((o) => isFailed(o.result.status)).length,
};
await writeFile(`${outputDir}/.timing.json`, JSON.stringify(summary));
await writeFile(`${outputDir}/.complete`, new Date().toISOString());
console.error(JSON.stringify({ event: "precompute_complete", ...summary }));
