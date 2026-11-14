import { parseArgs } from "node:util";

import { output } from "./lib/process.ts";

/** A commit as `git log --name-status` reports it: its subject and each changed path with its status letter. */
export interface Commit {
	subject: string;
	changes: { status: string; path: string }[];
}

export interface SuiteChurn {
	suite: string;
	commits: number;
	/** Commits whose Conventional Commit type declares unchanged behavior, and which still modified this suite. */
	behaviorPreserving: number;
}

// `refactor` and `perf` declare that behavior stays the same (commitlint.config.ts). A test they modify
// failed on a change that kept the behavior, so it is a change-detector candidate.
const BEHAVIOR_PRESERVING = /^(?:refactor|perf)(?:\([^)]*\))?:/u;
const TEST_FILE = /(?:Test\.java|\.test\.tsx?|\.spec\.ts|\.stories\.tsx)$/u;
const TEST_RESOURCES = /^(?<suite>.*\/src\/test\/resources\/[^/]+(?:\/[^/]+)?)\//u;

/** The suite a path belongs to: a test file itself, or a test-resource directory two levels deep. */
export function suiteOf(path: string): string | undefined {
	if (TEST_FILE.test(path)) {
		return path;
	}
	return TEST_RESOURCES.exec(path)?.groups?.suite;
}

export function rank(commits: Commit[]): SuiteChurn[] {
	const suites = new Map<string, SuiteChurn>();
	for (const commit of commits) {
		const preserving = BEHAVIOR_PRESERVING.test(commit.subject);
		const touched = new Map<string, boolean>();
		for (const { status, path } of commit.changes) {
			const suite = suiteOf(path);
			if (suite !== undefined) {
				touched.set(suite, (touched.get(suite) ?? false) || status === "M");
			}
		}
		for (const [suite, modified] of touched) {
			const churn = suites.get(suite) ?? { suite, commits: 0, behaviorPreserving: 0 };
			churn.commits += 1;
			if (preserving && modified) {
				churn.behaviorPreserving += 1;
			}
			suites.set(suite, churn);
		}
	}
	return [...suites.values()]
		.filter((churn) => churn.behaviorPreserving > 0)
		.toSorted(
			(a, b) =>
				b.behaviorPreserving - a.behaviorPreserving ||
				b.commits - a.commits ||
				a.suite.localeCompare(b.suite),
		);
}

const COMMIT_MARKER = "\u0001";

export function parseLog(log: string): Commit[] {
	return log
		.split(COMMIT_MARKER)
		.filter((entry) => entry.trim() !== "")
		.map((entry) => {
			const [subject = "", ...lines] = entry.split("\n");
			const changes = lines
				.filter((line) => line.includes("\t"))
				.map((line) => {
					const [status = "", path = ""] = line.split("\t");
					return { status: status.charAt(0), path };
				});
			return { subject, changes };
		});
}

if (import.meta.main) {
	const { values } = parseArgs({
		options: {
			since: { type: "string", default: "90 days ago" },
			top: { type: "string", default: "20" },
			ref: { type: "string", default: "origin/main" },
		},
	});
	const log = await output("git", [
		"log",
		values.ref,
		`--since=${values.since}`,
		"--no-merges",
		"--no-renames",
		"--name-status",
		`--format=${COMMIT_MARKER}%s`,
	]);
	const commits = parseLog(log);
	const preserving = commits.filter((commit) => BEHAVIOR_PRESERVING.test(commit.subject)).length;
	const ranked = rank(commits).slice(0, Number(values.top));
	console.log(
		`${commits.length} commits on ${values.ref} since ${values.since}; ${preserving} are refactor or perf.\n`,
	);
	console.log("| Suite | Commits | Modified by refactor or perf |\n| --- | ---: | ---: |");
	for (const churn of ranked) {
		console.log(`| \`${churn.suite}\` | ${churn.commits} | ${churn.behaviorPreserving} |`);
	}
}
