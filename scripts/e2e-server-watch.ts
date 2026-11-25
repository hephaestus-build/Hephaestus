/**
 * Watches the packaged server while a browser suite runs against it:
 * `node scripts/e2e-server-watch.ts <server pid> <page url> <dump prefix>`.
 *
 * A server that stops answering leaves each test to wait out its own timeout, so the suite holds its
 * step until the step's deadline and nothing records what the server was doing. When the page has not
 * answered three probes in a row, this writes the server's threads to `<prefix>.json` (virtual threads
 * included, which hold the requests) and `<prefix>.txt` (platform threads with their locks, and any
 * Java-level deadlock), reports the hang, and stops the server, so every remaining test fails at once.
 */
import { spawnSync } from "node:child_process";
import { appendFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { setTimeout } from "node:timers/promises";

import { annotation } from "./ci-deadline.ts";

export interface Miss {
	at: Date;
	detail: string;
}

export type Verdict = { kind: "ended" } | { kind: "hung"; misses: Miss[] };

export interface WatchOptions {
	/** `undefined` when the page answered, or else why it did not. */
	probe: () => Promise<string | undefined>;
	alive: () => boolean;
	intervalMs: number;
	misses: number;
	now?: () => Date;
}

/** Probes until the server ends, or until it misses `misses` probes in a row. */
export async function watch({
	probe,
	alive,
	intervalMs,
	misses,
	now = () => new Date(),
}: WatchOptions): Promise<Verdict> {
	let missed: Miss[] = [];
	while (alive()) {
		const at = now();
		const detail = await probe();
		if (detail === undefined) {
			missed = [];
		} else {
			// A server that ended while the probe waited did not hang.
			if (!alive()) {
				break;
			}
			missed = [...missed, { at, detail }];
			if (missed.length >= misses) {
				return { kind: "hung", misses: missed };
			}
		}
		await setTimeout(intervalMs);
	}
	return { kind: "ended" };
}

/** A whole answer, any status, is an answer: the page's content is the suite's to judge. */
export async function probePage(url: string, timeoutMs: number): Promise<string | undefined> {
	try {
		const response = await fetch(url, {
			redirect: "manual",
			signal: AbortSignal.timeout(timeoutMs),
		});
		await response.arrayBuffer();
		return undefined;
	} catch (error) {
		const cause =
			error instanceof Error && error.cause instanceof Error ? `: ${error.cause.message}` : "";
		return error instanceof Error ? `${error.name}: ${error.message}${cause}` : String(error);
	}
}

export function hangReport(url: string, misses: readonly Miss[], dumps: readonly string[]): string {
	const since = misses[0]?.at.toISOString() ?? "unknown";
	return [
		`The server stopped answering ${url}: ${misses.length} probes in a row had no answer, from ${since}. It was stopped, so the remaining tests fail at once.`,
		...dumps,
		"",
		...misses.map(({ at, detail }) => `${at.toISOString()}  ${detail}`),
	].join("\n");
}

/** The server's threads, through the JDK's `jcmd`. Each line says where a dump went, or why it is missing. */
function dumpThreads(pid: number, prefix: string): string[] {
	const jcmd =
		process.env.JAVA_HOME === undefined ? "jcmd" : path.join(process.env.JAVA_HOME, "bin", "jcmd");
	const json = path.resolve(`${prefix}.json`);
	const text = path.resolve(`${prefix}.txt`);
	// A wedged JVM can also leave the attach waiting, so each dump has its own limit.
	const options = { encoding: "utf8", timeout: 60_000, maxBuffer: 64 * 1024 * 1024 } as const;
	const lines: string[] = [];
	const all = spawnSync(
		jcmd,
		[String(pid), "Thread.dump_to_file", "-overwrite", "-format=json", json],
		options,
	);
	lines.push(
		all.status === 0
			? `Every thread, virtual threads included: ${json}`
			: `The thread dump to ${json} failed: ${all.error?.message ?? all.stderr.trim()}${all.stdout.trim()}`,
	);
	const locks = spawnSync(jcmd, [String(pid), "Thread.print", "-l"], options);
	if (locks.status === 0) {
		writeFileSync(text, locks.stdout);
		lines.push(`Platform threads with their locks: ${text}`);
	} else {
		lines.push(
			`The lock dump failed: ${locks.error?.message ?? locks.stderr.trim()}${locks.stdout.trim()}`,
		);
	}
	return lines;
}

function running(pid: number): boolean {
	try {
		process.kill(pid, 0);
		return true;
	} catch {
		return false;
	}
}

if (import.meta.main) {
	const [pidArgument, url, prefix] = process.argv.slice(2);
	const pid = Number(pidArgument);
	if (!Number.isInteger(pid) || pid <= 0 || url === undefined || prefix === undefined) {
		throw new Error(
			"usage: node scripts/e2e-server-watch.ts <server pid> <page url> <dump prefix>",
		);
	}
	// The page is a static document: well under a second when the server answers at all.
	const verdict = await watch({
		probe: async () => probePage(url, 10_000),
		alive: () => running(pid),
		intervalMs: 5000,
		misses: 3,
	});
	if (verdict.kind === "hung") {
		const report = hangReport(url, verdict.misses, dumpThreads(pid, prefix));
		process.stderr.write(
			`\n::error title=The server stopped answering::${annotation(report)}\n${report}\n`,
		);
		const summary = process.env.GITHUB_STEP_SUMMARY;
		if (summary !== undefined && summary !== "") {
			appendFileSync(summary, `\n### The server stopped answering\n\n\`\`\`\n${report}\n\`\`\`\n`);
		}
		// The server runs in its own session (setsid), so its process group goes with it.
		try {
			process.kill(-pid, "SIGKILL");
		} catch {
			process.kill(pid, "SIGKILL");
		}
		process.exitCode = 1;
	}
}
