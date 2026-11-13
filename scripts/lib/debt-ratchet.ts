import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

import { environmentWithoutGitRepository } from "./git-environment.ts";
import { asArray, asRecord, asString, parseJson } from "./json.ts";
import { CAPTURE_LIMIT_BYTES } from "./process.ts";

export function baselineIds(source: string, file: string): string[] {
	const entries = asArray(parseJson(source), file).map((value, index) => {
		const entry = asRecord(value, `${file}:${index + 1}`);
		for (const key of ["id", "owner", "reason", "removeWhen"]) {
			if (asString(entry[key], `${file}:${index + 1} ${key}`).trim() === "") {
				throw new Error(`${file}:${index + 1}: ${key} must not be empty.`);
			}
		}
		return asString(entry.id, `${file}:${index + 1} id`);
	});
	if (new Set(entries).size !== entries.length) {
		throw new Error(`${file}: remove duplicate baseline entries.`);
	}
	return entries;
}

/** A debt identity cannot be replaced by an unrelated exception, even at the same count. */
export function ratchetProblems(
	actual: readonly string[],
	baseline: readonly string[],
	previous: readonly string[] | undefined,
): string[] {
	const measured = new Set(actual);
	const allowed = new Set(baseline);
	const problems = actual.filter((id) => !allowed.has(id)).map((id) => `${id}: fix new debt.`);
	for (const id of baseline) {
		if (!measured.has(id)) {
			problems.push(`${id}: debt is gone. Delete its baseline entry.`);
		}
		if (previous !== undefined && !previous.includes(id)) {
			problems.push(`${id}: baseline growth is forbidden. Fix the debt and delete this entry.`);
		}
	}
	return problems;
}

/** The first PR establishes a baseline. Later PRs may only remove identities from it. */
export function checkDebtRatchet(actual: readonly string[], file: string, cwd: string): void {
	const options = {
		cwd,
		encoding: "utf8",
		env: environmentWithoutGitRepository(),
		maxBuffer: CAPTURE_LIMIT_BYTES,
	} as const;
	const configuredBase = process.env.PR_BASE_SHA;
	const base =
		configuredBase === undefined || configuredBase === "" ? "origin/main" : configuredBase;
	// Fail closed when the base is unavailable, including a shallow CI checkout.
	let revision: string;
	try {
		revision = execFileSync("git", ["rev-parse", "--verify", `${base}^{commit}`], options).trim();
	} catch (error) {
		throw new Error(
			`${file}: base ${base} is unavailable. Run git fetch origin main, or set PR_BASE_SHA to the checked-out base commit.`,
			{ cause: error },
		);
	}
	const exists = execFileSync(
		"git",
		["ls-tree", "--name-only", revision, "--", file],
		options,
	).trim();
	const previous =
		exists === ""
			? undefined
			: baselineIds(execFileSync("git", ["show", `${revision}:${file}`], options), file);
	const baseline = baselineIds(readFileSync(`${cwd}/${file}`, "utf8"), file);
	const problems = ratchetProblems(actual, baseline, previous);
	if (problems.length > 0) {
		throw new Error(`${file}:\n${problems.join("\n")}`);
	}
	console.log(`${file}: ${actual.length} debt entries; no growth.`);
}
