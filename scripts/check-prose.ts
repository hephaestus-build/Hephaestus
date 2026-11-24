import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
		if (
			!/^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$))[\w.$/-]+\.(?:md|mdx|ya?ml|json|properties)$/u.test(file)
		) {
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
		if (
			file.endsWith(".properties") &&
			!/^server\/application\/src\/main\/resources\/messages[\w-]*\.properties$/u.test(file)
		) {
			throw new Error(
				`STE properties path is outside the server message catalog: ${file}. Add a message catalog.`,
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

/**
 * Every enforced page that still exists stays enforced, under its new name after a rename. A deleted
 * page has no prose left to check, so it leaves the list with its file.
 */
export function assertGrowth(
	before: readonly string[],
	after: readonly string[],
	renames: ReadonlyMap<string, string> = new Map(),
	exists: (file: string) => boolean = () => true,
): void {
	const lost = before.filter((file) => {
		const current = renames.get(file) ?? file;
		return !after.includes(file) && !after.includes(current) && exists(current);
	});
	if (lost.length > 0) {
		throw new Error(
			`STE enforcement lost paths: ${lost.join(", ")}. Each page still exists, so restore its entry, or carry it to the page's new name.`,
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
		const fields = execFileSync(
			"git",
			[
				"diff",
				"--name-status",
				"-z",
				"--find-renames=40%",
				"-l0",
				"--diff-filter=R",
				revision,
				"HEAD",
			],
			options,
		).split("\0");
		const renames = new Map<string, string>();
		for (let index = 0; index + 1 < fields.length; index += 3) {
			const [status, previous, current] = fields.slice(index, index + 3);
			if (
				status === undefined ||
				!/^R\d{3}$/u.test(status) ||
				previous === undefined ||
				current === undefined
			) {
				throw new Error("Git returned an incomplete rename record.");
			}
			renames.set(previous, current);
		}
		assertGrowth(
			parsePaths(execFileSync("git", ["show", `${revision}:${enforcedList}`], options)),
			paths,
			renames,
			(file) => existsSync(path.join(cwd, file)),
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

/** Java reads `\uXXXX`, `\t`, `\n`, `\r` and `\f` as escapes. Any other escaped character stands for itself. */
function unescapeProperty(text: string) {
	const controls: Record<string, string> = { t: "\t", n: "\n", r: "\r", f: "\f" };
	return text.replaceAll(
		/\\(?:u(?<code>[\da-fA-F]{4})|(?<char>.))/gu,
		(_match, code: string | undefined, char: string) =>
			code === undefined
				? (controls[char] ?? char)
				: String.fromCodePoint(Number.parseInt(code, 16)),
	);
}

/** `java.util.Properties` counts only space, tab and form feed as blank, not a no-break space. */
function blank(char: string) {
	return char === " " || char === "\t" || char === "\f";
}

function withoutLeadingBlanks(line: string) {
	let start = 0;
	while (blank(line.charAt(start))) {
		start += 1;
	}
	return line.slice(start);
}

/** Each message of a server catalog, read as `java.util.Properties` reads it, with escapes decoded. */
export function catalogProse(source: string) {
	const fields: { field: string; text: string }[] = [];
	const lines = source.split(/\r\n|\r|\n/u);
	for (let index = 0; index < lines.length; index += 1) {
		let line = withoutLeadingBlanks(lines[index] ?? "");
		if (line === "" || line.startsWith("#") || line.startsWith("!")) {
			continue;
		}
		// An odd run of backslashes at the end continues the message on the next line.
		while (/(?:^|[^\\])(?:\\\\)*\\$/u.test(line)) {
			index += 1;
			const head = line.slice(0, -1);
			const next = withoutLeadingBlanks(lines[index] ?? "");
			// A line of only a backslash leaves nothing to continue, so the next line starts afresh.
			line = head === "" && (next.startsWith("#") || next.startsWith("!")) ? "" : head + next;
		}
		if (line === "") {
			continue;
		}
		let key = 0;
		while (key < line.length && !"=:".includes(line.charAt(key)) && !blank(line.charAt(key))) {
			key += line.charAt(key) === "\\" ? 2 : 1;
		}
		let text = key;
		while (blank(line.charAt(text))) {
			text += 1;
		}
		if (line.charAt(text) === "=" || line.charAt(text) === ":") {
			text += 1;
			while (blank(line.charAt(text))) {
				text += 1;
			}
		}
		fields.push({
			field: unescapeProperty(line.slice(0, key)),
			text: unescapeProperty(line.slice(text)),
		});
	}
	return fields;
}

/**
 * Catalog messages use the product voice. A `support.` message, such as a crisis reply, may say
 * "please", because warmth outweighs brevity there.
 */
export function catalogAlerts(
	vale: Pick<Vale, "binary" | "config">,
	file: string,
	level = "error",
) {
	return proseFieldAlerts(
		vale,
		file,
		catalogProse(readFileSync(file, "utf8")),
		level,
		"catalog",
	).filter(({ field, alert }) => !(field.startsWith("support.") && alert.Check === "STE.Please"));
}

function proseFieldAlerts(
	vale: Pick<Vale, "binary" | "config">,
	file: string,
	fields: readonly { field: string; text: string }[],
	level: string,
	kind: "markdown" | "catalog" = "markdown",
) {
	if (fields.length === 0) {
		return [];
	}
	const directory = mkdtempSync(path.join(tmpdir(), "ste-issue-form-"));
	try {
		// A catalog message is plain text in the product voice, which `.vale.ini` gives to `docs/user/`.
		const folder = kind === "catalog" ? path.join(directory, "docs", "user") : directory;
		const extension = kind === "catalog" ? "txt" : "md";
		mkdirSync(folder, { recursive: true });
		const inputs = fields.map(({ field, text }, index) => {
			const target = path.join(folder, `${index}.${extension}`);
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
		for (const file of paths.filter((item) => item.endsWith(".properties"))) {
			for (const { field, alert } of catalogAlerts(
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
