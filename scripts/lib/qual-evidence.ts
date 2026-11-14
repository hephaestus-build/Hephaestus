/**
 * One evidence folder per run: `<out>/<item>-<UTC>/` with the raw data, `summary.json` (one status per check) and
 * `README.md` (command, commit, cluster, verdict). A check that could not measure is `not-run` with what it needs,
 * never `pass`.
 */
import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";

/** The page that defines each check and its bar; a check's `rule` points into it. */
export const RULES = "docs/contributor/qualification-checks.md";

export type Status = "pass" | "fail" | "recorded" | "not-run";

/** The status of the first case that holds, else `otherwise`. */
export function decide(cases: readonly (readonly [boolean, Status])[], otherwise: Status): Status {
	return cases.find(([holds]) => holds)?.[1] ?? otherwise;
}

export interface Check {
	id: string;
	title: string;
	status: Status;
	/** What was measured, or why it did not run. */
	detail: string;
	/** For `not-run`: the load, access or step that the check needs. */
	needs?: string;
	/** For `pass`/`fail`: the rule that decided it. */
	rule?: string;
	data?: unknown;
}

export interface RunContext {
	command: string;
	commit: string;
	cluster: Record<string, unknown>;
}

export type Verdict = "PASS" | "FAIL" | "PARTIAL";

/** FAIL if any check failed; PASS if every check passed or was recorded; else PARTIAL. */
export function verdictOf(checks: readonly Check[]): Verdict {
	if (checks.some((check) => check.status === "fail")) {
		return "FAIL";
	}
	return checks.every((check) => check.status === "pass" || check.status === "recorded")
		? "PASS"
		: "PARTIAL";
}

export function utcStamp(date = new Date()): string {
	return date
		.toISOString()
		.replaceAll(/[-:]/gu, "")
		.replace(/\.\d+Z$/u, "Z");
}

export class Evidence {
	readonly dir: string;
	readonly started = new Date();
	readonly checks: Check[] = [];

	constructor(root: string, item: string) {
		this.dir = path.join(root, `${item}-${utcStamp(this.started)}`);
		mkdirSync(path.join(this.dir, "raw"), { recursive: true, mode: 0o700 });
	}

	/** A raw file under `raw/`: a string as is, anything else as JSON. */
	raw(name: string, data: unknown): void {
		writeFileSync(
			path.join(this.dir, "raw", name),
			typeof data === "string" ? data : `${JSON.stringify(data, null, 2)}\n`,
			{
				mode: 0o600,
			},
		);
	}

	line(name: string, data: unknown): void {
		appendFileSync(
			path.join(this.dir, "raw", name),
			`${JSON.stringify({ at: new Date().toISOString(), ...asObject(data) })}\n`,
			{
				mode: 0o600,
			},
		);
	}

	log(message: string): void {
		const line = `${new Date().toISOString()} ${message}`;
		process.stdout.write(`${line}\n`);
		appendFileSync(path.join(this.dir, "raw", "run.log"), `${line}\n`, { mode: 0o600 });
	}

	check(check: Check): Check {
		this.checks.push(check);
		const needs =
			check.needs === undefined || check.detail.startsWith("needs ")
				? ""
				: ` (needs ${check.needs})`;
		this.log(`[${check.status}] ${check.id} ${check.title}: ${check.detail}${needs}`);
		return check;
	}

	notRun(id: string, title: string, needs: string, detail = `needs ${needs}`): Check {
		return this.check({ id, title, status: "not-run", needs, detail });
	}

	/** Writes summary.json and README.md, and returns the verdict. */
	finish(context: RunContext, notes: string[] = []): Verdict {
		const verdict = verdictOf(this.checks);
		const ended = new Date();
		const counts = Object.fromEntries(
			(["pass", "fail", "recorded", "not-run"] as const).map((status) => [
				status,
				this.checks.filter((check) => check.status === status).length,
			]),
		);
		writeFileSync(
			path.join(this.dir, "summary.json"),
			`${JSON.stringify({ verdict, counts, started: this.started.toISOString(), ended: ended.toISOString(), ...context, checks: this.checks }, null, 2)}\n`,
			{ mode: 0o600 },
		);
		const rows = this.checks.map(
			(check) =>
				`| ${check.id} | ${check.status} | ${escape(check.title)} | ${escape(check.detail)}${check.needs === undefined || check.detail.startsWith("needs ") ? "" : ` (needs ${escape(check.needs)})`} |`,
		);
		const readme = [
			`# ${path.basename(this.dir)}: ${verdict}`,
			"",
			`- Command: \`${context.command}\``,
			`- Commit of the scripts: \`${context.commit}\``,
			`- Cluster: ${Object.entries(context.cluster)
				.map(
					([key, value]) => `${key}=${typeof value === "string" ? value : JSON.stringify(value)}`,
				)
				.join("; ")}`,
			`- Window: ${this.started.toISOString()} → ${ended.toISOString()}`,
			`- Verdict: **${verdict}** (${Object.entries(counts)
				.map(([status, count]) => `${count} ${status}`)
				.join(", ")})`,
			"",
			`Verdict rule: FAIL if any check failed; PASS if every check passed or was recorded; PARTIAL when the rest is not-run. A not-run check is never a pass. ${RULES} defines each check.`,
			"",
			"| Check | Status | What | Detail |",
			"|---|---|---|---|",
			...rows,
			"",
			...(notes.length > 0 ? ["## Notes", "", ...notes.map((note) => `- ${note}`), ""] : []),
			"Raw data: `raw/`. Machine-readable result: `summary.json`.",
			"",
		];
		writeFileSync(path.join(this.dir, "README.md"), readme.join("\n"), { mode: 0o600 });
		this.log(`verdict ${verdict}; evidence in ${this.dir}`);
		return verdict;
	}
}

function escape(text: string): string {
	return text.replaceAll("|", String.raw`\|`).replaceAll("\n", " ");
}

function asObject(data: unknown): Record<string, unknown> {
	return data !== null && typeof data === "object" && !Array.isArray(data)
		? Object.fromEntries(Object.entries(data))
		: { value: data };
}
