import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";

import { isSet } from "./lib/env.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";

export interface Finding {
	rule: string;
	file: string;
	line: number;
	text: string;
	digest: string;
}
export interface BaselineEntry {
	rule: string;
	file: string;
	text: string;
	digest: string;
	count: number;
	owner: string;
}
const sourceDigest = (source: string): string =>
	createHash("sha256").update(source.replaceAll("\r\n", "\n")).digest("hex");
const baselineFile = "scripts/no-legacy-baseline.json";
const code = /\.(?:java|kt|[cm]?[jt]sx?)$/u;
const fixtures =
	/(?:^|\/)(?:test|tests|testing|fixtures|snapshots|corpus|generated-clients)(?:\/|$)|\.(?:test|spec|stories)\.[^.]+$|(?:routeTree|types|sdk)\.gen\./u;
const ownSource =
	/^(?:server\/application\/src\/main\/|webapp\/src\/|extension\/src\/|scripts\/|docker\/agents\/|docs\/)/u;

function redirectOnlyRoute(
	file: string,
	source: string,
	files: readonly string[],
): string | undefined {
	if (!file.startsWith("webapp/src/routes/")) {
		return undefined;
	}
	const directory = file.slice(0, file.lastIndexOf("/") + 1);
	if (
		file.endsWith("/index.tsx") &&
		files.some(
			(other) => other !== file && other.startsWith(directory) && !other.includes(".test."),
		)
	) {
		return undefined;
	}
	const start = source.indexOf("beforeLoad:");
	if (start === -1) {
		return undefined;
	}
	const arrow = source.indexOf("=>", start);
	if (arrow === -1) {
		return undefined;
	}
	const alias = /\bredirect\s+as\s+(?<name>\w+)/u.exec(source)?.groups?.name ?? "redirect";
	const open = source.indexOf("{", arrow);
	const expression = source
		.slice(arrow + 2)
		.trimStart()
		.replace(/^(?:\(\s*)+/u, "");
	if (new RegExp(`^${alias}\\s*\\(`, "u").test(expression)) {
		return alias;
	}
	// Strings and comments cannot add a branch or change the callback's brace depth.
	const masked = source.replaceAll(
		/\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`/gu,
		(value) => value.replaceAll(/[^\n]/gu, " "),
	);
	let depth = 1;
	let close = open + 1;
	for (; close < masked.length && depth > 0; close += 1) {
		if (masked[close] === "{") {
			depth += 1;
		}
		if (masked[close] === "}") {
			depth -= 1;
		}
	}
	const body = masked.slice(open + 1, close - 1);
	return new RegExp(`\\b(?:throw|return)\\s+${alias}\\s*\\(`, "u").test(body) &&
		!/\b(?:if|switch|catch)\b|\?(?![.?])/u.test(body)
		? alias
		: undefined;
}

function canonicalWorkspaceRedirect(file: string, source: string, statement: string): boolean {
	if (
		file !== "deploy/chart/hephaestus/templates/workspace-routing.yaml" ||
		!/^return\s+308\s+\$workspace_redirect;$/u.test(statement)
	) {
		return false;
	}
	const map = /map[^\n]*\$workspace_redirect\s*\{(?<body>[\s\S]*?)^\s*\}/mu.exec(source);
	if (map === null) {
		return false;
	}
	const entries = (map.groups?.body ?? "")
		.split("\n")
		.map((line) => line.trim())
		.filter((line) => line !== "" && line !== 'default "";');
	const canonicalEntry =
		'"~^{{ $domain }}:(?:GET|HEAD):/w/(?<workspace_slug>(?!(?:{{ $reserved }})(?:/|[?]|$))(?![a-z0-9-]*--)[a-z0-9][a-z0-9-]{1,49}[a-z0-9])(?:/(?<workspace_tail>[^?]*))?(?<workspace_query>[?].*)?$" "https://$workspace_slug.{{ .Values.host }}/$workspace_tail$workspace_query";';
	return entries.length === 1 && entries[0] === canonicalEntry;
}

/** Find obsolete paths, not authentication, current landing pages or provider schema directives. */
export function findings(file: string, source: string, files: readonly string[] = []): Finding[] {
	if (fixtures.test(file) || /^(?:webapp|extension)\/src\/api\//u.test(file)) {
		return [];
	}
	const result: Finding[] = [];
	const add = (rule: string, offset: number) => {
		const start = source.lastIndexOf("\n", offset - 1) + 1;
		const end = source.indexOf("\n", offset);
		result.push({
			rule,
			file,
			line: source.slice(0, offset).split("\n").length,
			text: source.slice(start, end === -1 ? undefined : end).trim(),
			digest: sourceDigest(source),
		});
	};
	const matches = (rule: string, pattern: RegExp) => {
		for (const match of source.matchAll(pattern)) {
			add(rule, match.index);
		}
	};
	if (code.test(file) && ownSource.test(file)) {
		matches("source-deprecation", /@(?:java\.lang\.)?Deprecated\b/gu);
		matches("source-deprecation", /^\s*\/\/[^\n]*@deprecated\b/gmu);
		for (const comment of source.matchAll(/\/\*\*[\s\S]*?\*\//gu)) {
			const index = comment[0].search(/@deprecated\b/iu);
			if (index >= 0) {
				add("source-deprecation", comment.index + index);
			}
		}
		matches(
			"config-alias",
			/@(?:DeprecatedConfigurationProperty)\b|\b(?:new|import)\s+(?:[\w.]+\.)?ConfigurationPropertyNameAliases\b/gu,
		);
		if (!/\/integration\/scm\/(?:github|gitlab)\/jackson\//u.test(file)) {
			matches("config-alias", /@(?:JsonAlias)\b/gu);
		}
		matches(
			"config-alias",
			/process\.env\.(?<name>\w+)\s*(?:\?\?|\|\|)\s*process\.env\.(?!\k<name>\b)\w+/gu,
		);
		matches(
			"config-alias",
			/(?:getProperty|getenv)\(\s*"[^"]+"\s*,\s*(?:\w+\.)*(?:getProperty|getenv)\(/gu,
		);
		if (file.endsWith(".java")) {
			matches("path-alias", /\b(?:SC_MOVED_PERMANENTLY|MOVED_PERMANENTLY|PERMANENT_REDIRECT)\b/gu);
			matches("path-alias", /["'](?:redirect|forward):(?:\/|https?:\/\/)/gu);
			matches("path-alias", /\b(?:setStatus|status|valueOf)\(\s*30[178]\s*\)/gu);
			if (!/\/(?:core\/auth|integration\/core\/oauth)\//u.test(file)) {
				matches("path-alias", /(?:\.sendRedirect|new\s+RedirectView)\(\s*["']\//gu);
			}
		}
		const routeRedirect = redirectOnlyRoute(file, source, files);
		if (routeRedirect !== undefined) {
			matches("route-alias", new RegExp(`\\b${routeRedirect}\\s*\\(`, "gu"));
		}
	}
	if (/\.(?:ya?ml|properties|env|sh|tpl|conf)$/u.test(file) || file.endsWith(".env.example")) {
		matches(
			"config-alias",
			/\$\{[A-Z][A-Z\d_]*:-?\$(?:\{[A-Z][A-Z\d_]*(?::[^{}]*)?\}\}|[A-Z][A-Z\d_]*\})/gu,
		);
		matches(
			"config-alias",
			/\.Values\.[\w.]+\s*\|\s*default\s+\.Values\.|\bcoalesce\s+\.Values\.[\w.]+\s+\.Values\./gu,
		);
		for (const match of source.matchAll(/\breturn\s+30[178]\s+[^;]+;/gu)) {
			if (!canonicalWorkspaceRedirect(file, source, match[0])) {
				add("path-alias", match.index);
			}
		}
		matches("path-alias", /\brewrite\s+[^;]+\s+permanent\s*;/gu);
		matches("path-alias", /^\s*(?:-\s*)?type:\s*URLRewrite\b/gmu);
	}
	if (file.startsWith("docs/") && !file.startsWith("docs/decisions/")) {
		matches("docs-redirect", /^\s*(?:redirect_to|redirect_from|redirects|aliases)\s*:/gmu);
		matches("docs-redirect", /["'](?:redirect_to|redirect_from|redirects|aliases)["']\s*:/gu);
		matches(
			"docs-redirect",
			/<\s*(?:Redirect|Navigate)\b|http-equiv\s*=\s*["']?refresh\b|(?:window\.)?location\.(?:(?:replace|assign)\s*\(|href\s*=)/giu,
		);
		matches("docs-redirect", /^\s*(?:redirects|createRedirects)\s*:/gmu);
		matches("docs-redirect", /@docusaurus\/plugin-client-redirects/gu);
		matches(
			"docs-redirect",
			/^(?:#+\s+(?:This page has moved|Moved)|This (?:page|document) has moved\b)/gimu,
		);
	}
	return result.filter(
		(finding, index) =>
			result.findIndex((other) => other.rule === finding.rule && other.line === finding.line) ===
			index,
	);
}

export function parseBaseline(source: string): BaselineEntry[] {
	const result = asArray(parseJson(source), "baseline").map((value) => {
		const entry = asRecord(value, "baseline entry");
		const rule = asString(entry.rule, "rule");
		const file = asString(entry.file, "file");
		const text = asString(entry.text, "text");
		const digest = asString(entry.digest, "digest");
		const owner = asString(entry.owner, "owner");
		const { count } = entry;
		if (
			!/^[a-f0-9]{64}$/u.test(digest) ||
			owner.trim() === "" ||
			typeof count !== "number" ||
			!Number.isSafeInteger(count) ||
			count < 1
		) {
			throw new Error("Invalid baseline digest, owner or count.");
		}
		return { rule, file, text, digest, owner, count };
	});
	if (new Set(result.map(key)).size !== result.length) {
		throw new Error("Duplicate no-legacy baseline entry.");
	}
	return result;
}

const key = (entry: Pick<BaselineEntry, "rule" | "file" | "text">): string =>
	JSON.stringify([entry.rule, entry.file, entry.text]);

export function violations(
	actual: Finding[],
	baseline: BaselineEntry[],
	previous: BaselineEntry[],
	version: string,
): string[] {
	const errors: string[] = [];
	const counts = new Map<string, number>();
	for (const item of actual) {
		counts.set(key(item), (counts.get(key(item)) ?? 0) + 1);
	}
	const allowed = new Map(baseline.map((entry) => [key(entry), entry]));
	const before = new Map(previous.map((entry) => [key(entry), entry]));
	for (const entry of baseline) {
		if (
			entry.count > (before.get(key(entry))?.count ?? 0) ||
			entry.digest !== before.get(key(entry))?.digest
		) {
			errors.push(`The baseline cannot grow: ${entry.file} (${entry.rule}).`);
		}
		if ((counts.get(key(entry)) ?? 0) < entry.count) {
			errors.push(`Remove the stale baseline entry: ${entry.file} (${entry.rule}).`);
		}
	}
	for (const item of actual) {
		const allowance = allowed.get(key(item));
		if (allowance === undefined) {
			errors.push(`${item.file}:${item.line}: ${item.rule}: ${item.text}`);
			continue;
		}
		if (allowance.digest !== item.digest || (counts.get(key(item)) ?? 0) > allowance.count) {
			errors.push(`The source of a baseline entry changed: ${item.file}. Remove its old path.`);
		}
	}

	if (/^[1-9]\d*\./u.test(version) && !version.includes("-") && baseline.length > 0) {
		errors.push("The no-legacy baseline must be empty for 1.0 and later stable releases.");
	}
	return [...new Set(errors)];
}

function baseReference(): string {
	if (isSet(process.env.PR_BASE_SHA)) {
		return process.env.PR_BASE_SHA;
	}
	if (isSet(process.env.GITHUB_BASE_REF)) {
		return `origin/${process.env.GITHUB_BASE_REF}`;
	}
	return "origin/main";
}

export function check(root: string, requestedBase = baseReference()): string[] {
	const git = (...args: string[]) =>
		execFileSync("git", args, {
			cwd: root,
			env: environmentWithoutGitRepository(),
			encoding: "utf8",
			maxBuffer: 32 * 1024 * 1024,
		});
	const base = git("merge-base", "HEAD", requestedBase).trim();
	const baseline = parseBaseline(readFileSync(path.join(root, baselineFile), "utf8"));
	const baseFiles = git("ls-tree", "--name-only", base, "--", baselineFile).trim();
	// The first guard commit records the existing debt; every later baseline can only shrink.
	const previous =
		baseFiles === ""
			? baseline.map((entry) => {
					const exists = git("ls-tree", "--name-only", base, "--", entry.file).trim() !== "";
					return {
						...entry,
						digest: exists ? sourceDigest(git("show", `${base}:${entry.file}`)) : "",
					};
				})
			: parseBaseline(git("show", `${base}:${baselineFile}`));
	const files = git("ls-files", "-z", "--cached", "--others", "--exclude-standard")
		.split("\0")
		.filter(Boolean);
	const actual = files.flatMap((file) => {
		if (
			!code.test(file) &&
			!/\.(?:mdx?|html?|json|ya?ml|properties|env|sh|tpl|conf)$/u.test(file) &&
			!file.endsWith(".env.example")
		) {
			return [];
		}
		try {
			return findings(file, readFileSync(path.join(root, file), "utf8"), files);
		} catch (error) {
			if (error instanceof Error && "code" in error && error.code === "ENOENT") {
				return [];
			}
			throw error;
		}
	});
	const manifest: unknown = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8"));
	if (
		typeof manifest !== "object" ||
		manifest === null ||
		!("version" in manifest) ||
		typeof manifest.version !== "string"
	) {
		throw new Error("package.json needs a version.");
	}
	return violations(actual, baseline, previous, manifest.version);
}
if (import.meta.main) {
	const root = path.resolve(import.meta.dirname, "..");
	const errors = check(root);
	if (errors.length > 0) {
		console.error(errors.join("\n"));
		process.exitCode = 1;
	} else {
		console.log("No new legacy paths; the baseline can only shrink.");
	}
}
