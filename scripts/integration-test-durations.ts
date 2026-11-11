import { appendFile, glob, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { XMLParser } from "fast-xml-parser";

import { COMMITTED, parseDurations, record } from "./lib/integration-durations.ts";
import { isRecord, parseJson } from "./lib/json.ts";
import { run } from "./lib/process.ts";

/**
 * The top-level class and seconds of one Gradle JUnit report, which holds one class. The suite's
 * name is its display name and Gradle shortens long report file names, so the class comes from the
 * test cases. A `@Nested` class (`Outer$Inner`) counts towards its top-level class, which is what a
 * shard selects. A report without test cases measures nothing.
 */
export function classSeconds(xml: string): [string, number] | undefined {
	const parsed: unknown = new XMLParser({ ignoreAttributes: false }).parse(xml);
	if (!isRecord(parsed) || !isRecord(parsed.testsuite)) {
		throw new Error("Expected a JUnit testsuite");
	}
	const time = Number(parsed.testsuite["@_time"]);
	if (!Number.isFinite(time) || time < 0) {
		throw new Error("A JUnit testsuite lacks its time");
	}
	const cases: unknown = parsed.testsuite.testcase;
	const first: unknown = Array.isArray(cases) ? cases[0] : cases;
	if (first === undefined) {
		return undefined;
	}
	const className = isRecord(first) ? first["@_classname"] : undefined;
	if (typeof className !== "string" || className === "") {
		throw new Error("A JUnit testcase lacks its class");
	}
	return [className.split("$")[0] ?? className, time];
}

/**
 * Measured seconds per top-level class below `directory`. When a run was attempted again, the report
 * of the later attempt (`…-reports-<attempt>/`) replaces the earlier one.
 */
async function measure(directory: string): Promise<Map<string, number>> {
	const latest = new Map<string, { attempt: number; name: string; seconds: number }>();
	for await (const file of glob(`${directory}/**/TEST-*.xml`)) {
		const attempt = Number(
			/-reports-(?<attempt>\d+)\//u.exec(path.relative(directory, file))?.groups?.attempt ?? 0,
		);
		const known = latest.get(path.basename(file));
		if (known !== undefined && known.attempt > attempt) {
			continue;
		}
		const measured = classSeconds(await readFile(file, "utf8"));
		if (measured !== undefined) {
			latest.set(path.basename(file), { attempt, name: measured[0], seconds: measured[1] });
		}
	}
	const measured = new Map<string, number>();
	for (const { name, seconds } of latest.values()) {
		measured.set(name, (measured.get(name) ?? 0) + seconds);
	}
	return measured;
}

async function summary(shard: string, directory: string): Promise<void> {
	const measured = [...(await measure(directory))].toSorted(([, a], [, b]) => b - a);
	const total = measured.reduce((sum, [, seconds]) => sum + seconds, 0);
	const lines = [
		`### Integration shard ${shard}: ${measured.length} classes, ${(total / 60).toFixed(1)} min`,
		"",
		"Run one class locally: `./gradlew :application:integrationTest --tests <class>` from `server/`.",
		"",
		"| Class | Seconds |",
		"|---|---:|",
		...measured.map(([name, seconds]) => `| \`${name}\` | ${seconds.toFixed(1)} |`),
	];
	const rendered = `${lines.join("\n")}\n`;
	if (process.env.GITHUB_STEP_SUMMARY === undefined) {
		process.stdout.write(rendered);
	} else {
		await appendFile(process.env.GITHUB_STEP_SUMMARY, rendered);
	}
}

/**
 * Records the integration reports of one CI/CD run, such as a recent merge group's, into the
 * committed file. A pull request carries the new partition, and its run tests every shard with it.
 */
async function refresh(runId: string): Promise<void> {
	if (!/^\d+$/u.test(runId)) {
		throw new Error("Usage: refresh <run-id>");
	}
	const directory = await mkdtemp(path.join(tmpdir(), "integration-reports-"));
	try {
		await run("gh", [
			"run",
			"download",
			runId,
			"--pattern",
			"server-integration-*-reports-*",
			"--dir",
			directory,
		]);
		const measured = await measure(directory);
		if (measured.size === 0) {
			throw new Error(`Run ${runId} has no integration reports`);
		}
		const committed = parseDurations(parseJson(await readFile(COMMITTED, "utf8")), COMMITTED);
		const durations = record(measured, committed, committed.shards, new Date());
		await writeFile(COMMITTED, `${JSON.stringify(durations, null, "\t")}\n`);
		console.log(`Recorded ${measured.size} integration classes from run ${runId}.`);
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

async function main(): Promise<void> {
	const [command, ...args] = process.argv.slice(2);
	switch (command ?? "") {
		// refresh <run-id>
		case "refresh": {
			await refresh(args[0] ?? "");
			return;
		}
		// summary <shard> <report-directory>
		case "summary": {
			const [shard, directory] = args;
			if (shard === undefined || directory === undefined) {
				throw new Error("Usage: summary <shard> <report-directory>");
			}
			await summary(shard, directory);
			return;
		}
		default: {
			throw new Error(
				"Usage: integration-test-durations.ts refresh <run-id> | summary <shard> <reports>",
			);
		}
	}
}

if (import.meta.main) {
	await main();
}
