import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

import { isSet } from "./lib/env.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asStringArray, parseJson } from "./lib/json.ts";
import { uiAlerts } from "./lib/ste-ui.ts";
import { steRoot } from "./lib/ste-words.ts";
import { prepareVale, valeAlerts } from "./lib/vale.ts";

export const enforcedList = ".vale/enforced-paths.json";

export function parsePaths(source: string): readonly string[] {
	const paths = asStringArray(parseJson(source), "enforced STE paths");
	if (paths.length === 0 || new Set(paths).size !== paths.length) {
		throw new Error("Write a nonempty list of unique STE paths.");
	}
	for (const file of paths) {
		if (!/^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$))[\w.$/-]+\.(?:md|mdx|tsx|ts)$/u.test(file)) {
			throw new Error(
				`Invalid STE path: ${file}. Write an exact repository-relative prose or UI source path.`,
			);
		}
		if (/\.tsx?$/u.test(file) && !/^webapp\/src\/.*\.tsx?$/u.test(file)) {
			throw new Error(
				`STE UI path is outside webapp/src: ${file}. Add a prose path or a UI source path.`,
			);
		}
	}
	return paths;
}

export function assertGrowth(before: readonly string[], after: readonly string[]): void {
	const lost = before.filter((file) => !after.includes(file));
	if (lost.length > 0) {
		throw new Error(
			`STE enforcement lost paths: ${lost.join(", ")}. Restore them; get maintainer review for an explicit rename or deletion.`,
		);
	}
}

export function checkRatchet(
	base = "origin/main",
	cwd = fileURLToPath(steRoot),
): readonly string[] {
	const paths = parsePaths(readFileSync(path.join(cwd, enforcedList), "utf8"));
	const options = {
		cwd,
		maxBuffer: CAPTURE_LIMIT_BYTES,
		encoding: "utf8",
		env: environmentWithoutGitRepository(),
	} as const;
	// A shallow checkout must not turn the historical check into a silent pass.
	const revision = execFileSync(
		"git",
		["rev-parse", "--verify", `${base}^{commit}`],
		options,
	).trim();
	const files = execFileSync(
		"git",
		["ls-tree", "--name-only", revision, "--", enforcedList],
		options,
	).trim();
	if (files !== "") {
		assertGrowth(
			parsePaths(execFileSync("git", ["show", `${revision}:${enforcedList}`], options)),
			paths,
		);
	}
	for (const file of paths) {
		readFileSync(path.join(cwd, file));
	}
	return paths;
}

if (process.argv[1] === import.meta.filename) {
	const args = process.argv.slice(2);
	const base = isSet(process.env.PR_BASE_SHA) ? process.env.PR_BASE_SHA : "origin/main";
	const paths = args.length > 0 ? args : [...checkRatchet(base)];
	const files = paths.filter((file) => /\.mdx?$/u.test(file));
	const vale = await prepareVale();
	try {
		let errors = 0;
		// Small batches stay below the Windows command-line limit as the list grows.
		for (let offset = 0; offset < files.length; offset += 50) {
			const alerts = valeAlerts(
				vale.binary,
				files.slice(offset, offset + 50),
				args.length > 0 ? "suggestion" : "error",
			);
			const located = [...alerts].flatMap(([file, list]) => list.map((alert) => ({ file, alert })));
			for (const { file, alert } of located) {
				console.log(`${file}:${alert.Line}: ${alert.Severity} ${alert.Check}: ${alert.Message}`);
				errors += Number(alert.Severity === "error");
			}
		}
		const ui = paths.filter((file) => /^webapp\/src\/.*\.tsx?$/u.test(file));
		for (let offset = 0; offset < ui.length; offset += 50) {
			for (const alert of await uiAlerts(ui.slice(offset, offset + 50))) {
				console.log(`${alert.filename}: ${alert.severity}: ${alert.message}`);
				errors += Number(alert.severity === "error");
			}
		}
		console.log(`STE: ${paths.length} prose paths checked; ${errors} errors.`);
		process.exitCode = errors === 0 ? 0 : 1;
	} finally {
		await vale.dispose();
	}
}
