import { appendFile, mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { XMLParser } from "fast-xml-parser";

type TestCase = {
	className: string;
	name: string;
	timeSeconds: number;
	failed: boolean;
	errored: boolean;
	skipped: boolean;
};

export type TestSummary = {
	schemaVersion: 3;
	name: string;
	files: number;
	tests: number;
	failures: number;
	errors: number;
	skipped: number;
	testTimeSeconds: number;
	slowest: Array<{ test: string; seconds: number }>;
	performance?: {
		wallTimeSeconds: number;
		contextStarts: number;
		contextStartupSeconds: number;
		contextCacheMisses: number;
	};
};

const xmlParser = new XMLParser({
	ignoreAttributes: false,
	attributeNamePrefix: "",
	parseAttributeValue: true,
});

type JUnitNode = Record<string, unknown>;

const isJUnitNode = (value: unknown): value is JUnitNode =>
	typeof value === "object" && value !== null && !Array.isArray(value);

const asArray = (value: unknown): JUnitNode[] => {
	if (Array.isArray(value)) return value.filter(isJUnitNode);
	return isJUnitNode(value) ? [value] : [];
};

function testCases(node: unknown): JUnitNode[] {
	if (Array.isArray(node)) return node.flatMap(testCases);
	if (!isJUnitNode(node)) return [];
	return [
		...asArray(node.testcase),
		...Object.entries(node)
			.filter(([key]) => key !== "testcase")
			.flatMap(([, value]) => testCases(value)),
	];
}

export function parseJUnit(xml: string): TestCase[] {
	return testCases(xmlParser.parse(xml)).map((testCase) => {
		const skipped = "skipped" in testCase;
		const time = testCase.time === undefined && skipped ? 0 : testCase.time;
		if (typeof time !== "number" || !Number.isFinite(time) || time < 0)
			throw new Error(`Invalid JUnit testcase time: ${String(testCase.time)}`);
		return {
			className: typeof testCase.classname === "string" ? testCase.classname : "unknown class",
			name: typeof testCase.name === "string" ? testCase.name : "unnamed test",
			timeSeconds: time,
			failed: "failure" in testCase,
			errored: "error" in testCase,
			skipped,
		};
	});
}

export function summarize(name: string, documents: string[]): TestSummary {
	const cases = documents.flatMap(parseJUnit);
	return {
		schemaVersion: 3,
		name,
		files: documents.length,
		tests: cases.length,
		failures: cases.filter((test) => test.failed).length,
		errors: cases.filter((test) => test.errored).length,
		skipped: cases.filter((test) => test.skipped).length,
		testTimeSeconds: cases.reduce((total, test) => total + test.timeSeconds, 0),
		slowest: cases
			.toSorted((left, right) => right.timeSeconds - left.timeSeconds)
			.slice(0, 10)
			.map((test) => ({ test: `${test.className}.${test.name}`, seconds: test.timeSeconds })),
	};
}

export function parsePerformance(
	log: string,
	resourceUsage: string,
): NonNullable<TestSummary["performance"]> {
	const starts = [...log.matchAll(/Started .+? in ([0-9.]+) seconds/g)].map((match) =>
		Number(match[1]),
	);
	const cacheMisses = new Map<string, number>();
	for (const match of log.matchAll(/DefaultContextCache@(\w+).*missCount = (\d+)/g)) {
		const cache = match[1];
		if (cache !== undefined) {
			const misses = Number(match[2]);
			cacheMisses.set(cache, Math.max(cacheMisses.get(cache) ?? 0, misses));
		}
	}
	const elapsed = resourceUsage.match(
		/Elapsed \(wall clock\) time .*: (?:(\d+):)?(\d+):(\d+(?:\.\d+)?)/,
	);
	if (elapsed === null) throw new Error("Missing or invalid elapsed resource time");

	return {
		wallTimeSeconds: Number(elapsed[1] ?? 0) * 3600 + Number(elapsed[2]) * 60 + Number(elapsed[3]),
		contextStarts: starts.length,
		contextStartupSeconds: starts.reduce((total, seconds) => total + seconds, 0),
		contextCacheMisses: [...cacheMisses.values()].reduce((total, misses) => total + misses, 0),
	};
}

export function validateProfile(
	summary: TestSummary,
	kind: "integration" | "verification" = "integration",
): void {
	const counts = [summary.files, summary.tests, summary.failures, summary.errors, summary.skipped];
	if (counts.some((value) => !Number.isSafeInteger(value) || value < 0))
		throw new Error("Invalid profile test counts");
	if (summary.files === 0 || summary.tests <= summary.skipped)
		throw new Error("Profile contains no executed tests");
	if (summary.failures !== 0 || summary.errors !== 0)
		throw new Error("Failed tests cannot enter performance history");
	const performance = summary.performance;
	if (performance === undefined) throw new Error("Performance metrics are missing");
	if (
		[summary.testTimeSeconds, ...Object.values(performance)].some(
			(value) => !Number.isFinite(value) || value < 0,
		)
	)
		throw new Error("Profile metrics must be finite and nonnegative");
	if (performance.wallTimeSeconds === 0) throw new Error("Profile wall time must be positive");
	if (
		kind === "integration" &&
		(performance.contextStarts === 0 || performance.contextCacheMisses === 0)
	)
		throw new Error("Profile contains no Spring context measurements");
	if (![performance.contextStarts, performance.contextCacheMisses].every(Number.isSafeInteger))
		throw new Error("Invalid profile context counts");
}

export function markdown(summary: TestSummary): string {
	const lines = [
		`### ${summary.name}`,
		"",
		`**${summary.tests} tests** · ${summary.failures} failed · ${summary.errors} errors · ${summary.skipped} skipped · ${summary.testTimeSeconds.toFixed(1)}s summed test time`,
	];
	if (summary.slowest.length > 0) {
		lines.push("", "| Slowest tests | Time |", "|---|---:|");
		for (const test of summary.slowest) {
			lines.push(`| ${test.test.replaceAll("|", "\\|")} | ${test.seconds.toFixed(2)}s |`);
		}
	}
	if (summary.performance !== undefined) {
		const performance = summary.performance;
		lines.push(
			"",
			`**Wall:** ${performance.wallTimeSeconds.toFixed(1)}s · **Contexts:** ${performance.contextStarts} starts / ${performance.contextCacheMisses} misses / ${performance.contextStartupSeconds.toFixed(1)}s startup`,
		);
	}
	return `${lines.join("\n")}\n`;
}

async function xmlFiles(path: string): Promise<string[]> {
	const entries = await readdir(path, { withFileTypes: true });
	const nested = await Promise.all(
		entries.map(async (entry) => {
			const child = resolve(path, entry.name);
			if (entry.isDirectory()) return xmlFiles(child);
			return entry.isFile() && entry.name.endsWith(".xml") ? [child] : [];
		}),
	);
	return nested.flat().toSorted();
}

async function main(): Promise<void> {
	const [name, input, output, logPath, resourcePath, kind = "integration"] = process.argv.slice(2);
	if (kind !== "integration" && kind !== "verification")
		throw new Error(`Unknown profile kind: ${kind}`);
	if (name === undefined || input === undefined || output === undefined) {
		throw new Error("Usage: summarize-test-results <name> <report-directory> <output-json>");
	}
	const files = await xmlFiles(resolve(input));
	const documents = await Promise.all(files.map((file) => readFile(file, "utf8")));
	const summary = summarize(name, documents);
	if ((logPath === undefined) !== (resourcePath === undefined))
		throw new Error("Profiling requires both log and resource usage files");
	if (logPath !== undefined && resourcePath !== undefined) {
		summary.performance = parsePerformance(
			await readFile(logPath, "utf8"),
			await readFile(resourcePath, "utf8"),
		);
	}
	await mkdir(dirname(output), { recursive: true });
	await writeFile(output, `${JSON.stringify(summary, null, 2)}\n`);
	const rendered = markdown(summary);
	if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
		await appendFile(process.env.GITHUB_STEP_SUMMARY, rendered);
	}
	process.stdout.write(rendered);
	if (summary.performance !== undefined) validateProfile(summary, kind);
	if (files.length === 0) {
		process.stderr.write(`No JUnit XML reports found below ${input}\n`);
	}
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
	await main();
}
