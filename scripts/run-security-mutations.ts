import { spawnSync } from "node:child_process";
import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

const VALID_STATUSES = new Set(["KILLED", "SURVIVED", "NO_COVERAGE", "EQUIVALENT"]);
const REPORTED_STATUSES = [
	"KILLED",
	"SURVIVED",
	"NO_COVERAGE",
	"EQUIVALENT",
	"RUN_ERROR",
	"TIMED_OUT",
	"MEMORY_ERROR",
	"NON_VIABLE",
	"NOT_STARTED",
	"STARTED",
] as const;

type Summary = {
	actionable: MutationDetail[];
	counts: Map<string, number>;
	error?: string;
	total: number;
	valid: boolean;
};

type MutationDetail = {
	className: string;
	description: string;
	line: string;
	method: string;
	mutator: string;
	status: string;
};

const parser = new XMLParser({ ignoreAttributes: false, attributeNamePrefix: "" });

export function summarizePitXml(xml: string): Summary {
	try {
		SyntaxValidator.validate(xml);
	} catch (error) {
		return invalidSummary(
			`invalid XML: ${error instanceof Error ? error.message : "unknown error"}`,
		);
	}
	const document: unknown = parser.parse(xml);
	const root = isRecord(document) ? document.mutations : undefined;
	const rawMutations = isRecord(root) ? root.mutation : undefined;
	if (rawMutations === undefined) return invalidSummary("report contains no mutations");
	if (!isRecord(rawMutations) && !isRecordArray(rawMutations)) {
		return invalidSummary("report contains a malformed mutation entry");
	}
	const mutations = isRecordArray(rawMutations) ? rawMutations : [rawMutations];
	const counts = new Map<string, number>();
	const actionable: MutationDetail[] = [];
	for (const mutation of mutations) {
		const status = typeof mutation.status === "string" ? mutation.status : "INVALID";
		counts.set(status, (counts.get(status) ?? 0) + 1);
		if (status === "SURVIVED" || status === "NO_COVERAGE") {
			actionable.push({
				className: textField(mutation.mutatedClass),
				description: textField(mutation.description),
				line: textField(mutation.lineNumber),
				method: textField(mutation.mutatedMethod),
				mutator: shortMutator(textField(mutation.mutator)),
				status,
			});
		}
	}
	const unexpected = [...counts.keys()].filter((status) => !VALID_STATUSES.has(status));
	return {
		actionable,
		counts,
		error:
			unexpected.length > 0 ? `unexpected mutation status: ${unexpected.join(", ")}` : undefined,
		total: mutations.length,
		valid: mutations.length > 0 && unexpected.length === 0,
	};
}

function invalidSummary(error: string): Summary {
	return { actionable: [], counts: new Map(), error, total: 0, valid: false };
}

function textField(value: unknown): string {
	return typeof value === "string" || typeof value === "number" ? String(value) : "?";
}

function shortMutator(mutator: string): string {
	return mutator.slice(mutator.lastIndexOf(".") + 1);
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isRecordArray(value: unknown): value is Record<string, unknown>[] {
	return Array.isArray(value) && value.every(isRecord);
}

function runGradle(server: string, args: string[]): { exitCode: number; seconds: number } {
	const started = performance.now();
	const result = spawnSync(
		process.execPath,
		[resolve(import.meta.dirname, "run-gradlew.ts"), ...args],
		{ cwd: server, stdio: "inherit" },
	);
	return {
		exitCode: result.status ?? 1,
		seconds: Math.round((performance.now() - started) / 1000),
	};
}

function markdown(summary: Summary, elapsedSeconds: number, passed: boolean) {
	const analyzed = summary.total > 0;
	const lines = [
		`# Security mutation testing: ${passed ? "PASS" : "FAIL"}`,
		"",
		"| Metric | Value |",
		"| --- | ---: |",
		`| Build and mutation analysis | ${elapsedSeconds}s |`,
		`| Generated mutants | ${analyzed ? summary.total : "N/A"} |`,
		...REPORTED_STATUSES.map(
			(status) => `| ${status} | ${analyzed ? (summary.counts.get(status) ?? 0) : "N/A"} |`,
		),
		"",
		passed
			? "PIT completed without technical analysis errors. Review the rows below; use the HTML report for source detail."
			: `The run is invalid: ${summary.error ?? "Gradle or PIT failed"}. Do not interpret its mutation score.`,
		"",
	];
	if (summary.actionable.length > 0) {
		lines.push(
			"## Mutations to review",
			"",
			"| Status | Location | Mutator | Mutation |",
			"| --- | --- | --- | --- |",
			...summary.actionable.map(
				(item) =>
					`| ${item.status} | ${markdownCell(item.className)}.${markdownCell(item.method)}:${markdownCell(item.line)} | ${markdownCell(item.mutator)} | ${markdownCell(item.description)} |`,
			),
			"",
		);
	}
	return lines.join("\n");
}

function markdownCell(value: string): string {
	return value.replaceAll("|", "\\|").replaceAll(/[\r\n]+/g, " ");
}

function main() {
	const repo = resolve(import.meta.dirname, "..");
	const server = resolve(repo, "server");
	const reportDirectory = resolve(server, "application/build/reports/pitest");
	const xmlPath = resolve(reportDirectory, "mutations.xml");
	rmSync(reportDirectory, { recursive: true, force: true });
	mkdirSync(reportDirectory, { recursive: true });

	// Rerun the analysis, not its unchanged compilation dependencies.
	const analysis = runGradle(server, [":application:pitest", "--rerun"]);

	let summary = invalidSummary(
		analysis.exitCode !== 0
			? `Mutation build failed (Gradle exit ${analysis.exitCode})`
			: "mutation report was not produced",
	);
	if (analysis.exitCode === 0) {
		try {
			summary = summarizePitXml(readFileSync(xmlPath, "utf8"));
		} catch (error) {
			summary = invalidSummary(
				error instanceof Error ? error.message : "mutation report could not be read",
			);
		}
	}
	const passed = analysis.exitCode === 0 && summary.valid;
	const output = markdown(summary, analysis.seconds, passed);
	writeFileSync(resolve(reportDirectory, "summary.md"), output);
	process.stdout.write(output);
	if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, output);
	if (!passed) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === import.meta.filename) main();
