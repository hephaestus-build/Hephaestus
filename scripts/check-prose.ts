import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "yaml";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

import { isSet } from "./lib/env.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, asStringArray, parseJson } from "./lib/json.ts";
import { steRoot } from "./lib/ste-words.ts";
import { prepareVale, valeAlerts, type Vale, type ValeAlert } from "./lib/vale.ts";

export const enforcedList = ".vale/enforced-paths.json";

export function parsePaths(source: string): readonly string[] {
	const paths = asStringArray(parseJson(source), "enforced STE paths");
	if (paths.length === 0 || new Set(paths).size !== paths.length) {
		throw new Error("Write a nonempty list of unique STE paths.");
	}
	for (const file of paths) {
		if (!/^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$))[\w.$/-]+\.(?:md|mdx|ya?ml|json)$/u.test(file)) {
			throw new Error(`Invalid STE path: ${file}. Write an exact repository-relative prose path.`);
		}
		if (
			/\.ya?ml$/u.test(file) &&
			!/^\.github\/(?:ISSUE|DISCUSSION)_TEMPLATE\/[^/]+\.ya?ml$/u.test(file)
		) {
			throw new Error(
				`STE YAML path is outside the form-template directories: ${file}. Add an issue or discussion form.`,
			);
		}
		if (file.endsWith(".json") && !/^\.claude\/skills\/[^/]+\/metadata\.json$/u.test(file)) {
			throw new Error(
				`STE JSON path is outside skill metadata: ${file}. Add a skill metadata file.`,
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

export function issueFormProse(source: string) {
	const form = asRecord(parse(source), "issue form");
	const fields: { field: string; text: string }[] = [];
	const add = (record: Record<string, unknown>, key: string, prefix = "") => {
		if (Object.hasOwn(record, key)) {
			const field = prefix + key;
			fields.push({ field, text: asString(record[key], field) });
		}
	};
	for (const key of ["name", "description"]) {
		add(form, key);
	}
	if (Object.hasOwn(form, "body")) {
		for (const [index, value] of asArray(form.body, "issue form body").entries()) {
			const block = asRecord(value, `body[${index}]`);
			if (!Object.hasOwn(block, "attributes")) {
				continue;
			}
			const prefix = `body[${index}].attributes.`;
			const attributes = asRecord(block.attributes, prefix);
			for (const key of ["label", "description", "placeholder"]) {
				add(attributes, key, prefix);
			}
			if (block.type === "markdown") {
				add(attributes, "value", prefix);
			}
			if (block.type === "checkboxes" && Object.hasOwn(attributes, "options")) {
				for (const [optionIndex, option] of asArray(
					attributes.options,
					`${prefix}options`,
				).entries()) {
					const optionPrefix = `${prefix}options[${optionIndex}].`;
					add(asRecord(option, optionPrefix), "label", optionPrefix);
				}
			}
		}
	}
	if (Object.hasOwn(form, "contact_links")) {
		for (const [index, value] of asArray(form.contact_links, "contact links").entries()) {
			add(asRecord(value, `contact_links[${index}]`), "name", `contact_links[${index}].`);
		}
	}
	return fields;
}

export function issueFormAlerts(
	vale: Pick<Vale, "binary" | "config">,
	file: string,
	level = "error",
) {
	return proseFieldAlerts(vale, file, issueFormProse(readFileSync(file, "utf8")), level);
}

export function skillMetadataAlerts(
	vale: Pick<Vale, "binary" | "config">,
	file: string,
	level = "error",
) {
	const metadata = asRecord(parseJson(readFileSync(file, "utf8")), "skill metadata");
	return proseFieldAlerts(
		vale,
		file,
		[{ field: "abstract", text: asString(metadata.abstract, "abstract") }],
		level,
	);
}

function proseFieldAlerts(
	vale: Pick<Vale, "binary" | "config">,
	file: string,
	fields: readonly { field: string; text: string }[],
	level: string,
) {
	if (fields.length === 0) {
		return [];
	}
	const directory = mkdtempSync(path.join(tmpdir(), "ste-issue-form-"));
	try {
		const inputs = fields.map(({ field, text }, index) => {
			const target = path.join(directory, `${index}.md`);
			writeFileSync(target, text);
			return { field, target };
		});
		const files = inputs.map(({ target }) => target);
		const alerts = new Map<string, ValeAlert[]>();
		for (let offset = 0; offset < files.length; offset += 50) {
			for (const [target, list] of valeAlerts(vale, files.slice(offset, offset + 50), level)) {
				alerts.set(target, list);
			}
		}
		return inputs.flatMap(({ field, target }) =>
			(alerts.get(target.replaceAll("\\", "/")) ?? []).map((alert) => ({
				field,
				alert,
			})),
		);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

if (process.argv[1] === import.meta.filename) {
	const args = process.argv.slice(2);
	const base = isSet(process.env.PR_BASE_SHA) ? process.env.PR_BASE_SHA : "origin/main";
	const paths = args.length > 0 ? parsePaths(JSON.stringify(args)) : [...checkRatchet(base)];
	for (const file of paths) {
		readFileSync(file);
	}
	const files = paths.filter((file) => /\.mdx?$/u.test(file));
	const vale = await prepareVale();
	try {
		let errors = 0;
		// Small batches stay below the Windows command-line limit as the list grows.
		for (let offset = 0; offset < files.length; offset += 50) {
			const alerts = valeAlerts(
				vale,
				files.slice(offset, offset + 50),
				args.length > 0 ? "suggestion" : "error",
			);
			const located = [...alerts].flatMap(([file, list]) => list.map((alert) => ({ file, alert })));
			for (const { file, alert } of located) {
				console.log(`${file}:${alert.Line}: ${alert.Severity} ${alert.Check}: ${alert.Message}`);
				errors += Number(alert.Severity === "error");
			}
		}
		for (const file of paths.filter((item) => /\.ya?ml$/u.test(item))) {
			for (const { field, alert } of issueFormAlerts(
				vale,
				file,
				args.length > 0 ? "suggestion" : "error",
			)) {
				console.log(
					`${file}:${field}:${alert.Line}: ${alert.Severity} ${alert.Check}: ${alert.Message}`,
				);
				errors += Number(alert.Severity === "error");
			}
		}
		for (const file of paths.filter((item) => item.endsWith(".json"))) {
			for (const { field, alert } of skillMetadataAlerts(
				vale,
				file,
				args.length > 0 ? "suggestion" : "error",
			)) {
				console.log(
					`${file}:${field}:${alert.Line}: ${alert.Severity} ${alert.Check}: ${alert.Message}`,
				);
				errors += Number(alert.Severity === "error");
			}
		}
		console.log(`STE: ${paths.length} prose paths checked; ${errors} errors.`);
		process.exitCode = errors === 0 ? 0 : 1;
	} finally {
		await vale.dispose();
	}
}
