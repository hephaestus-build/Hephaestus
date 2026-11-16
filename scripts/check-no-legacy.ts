import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";

export interface Finding {
	rule: string;
	file: string;
	line: number;
	text: string;
}
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

/**
 * A renamed workspace's old slug answers 308 with its current address for members. It is a current flow, not an old
 * path: the slug history keeps the old slug from every other workspace while the redirect exists.
 */
function renamedSlugRedirect(file: string, source: string, statement: string): boolean {
	const redirect = "response.setStatus(HttpStatus.PERMANENT_REDIRECT.value());";
	return (
		file ===
			"server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/context/WorkspaceContextFilter.java" &&
		statement === redirect &&
		source.split(redirect).length === 2 &&
		source.includes("workspaceSlugHistoryRepository.findFirstByOldSlugOrderByChangedAtDesc(")
	);
}

/** Permanent redirects and forwards in server code. */
function javaRedirects(
	file: string,
	source: string,
	add: (rule: string, offset: number) => void,
): void {
	const matches = (rule: string, pattern: RegExp) => {
		for (const match of source.matchAll(pattern)) {
			add(rule, match.index);
		}
	};
	for (const match of source.matchAll(
		/\b(?:SC_MOVED_PERMANENTLY|MOVED_PERMANENTLY|PERMANENT_REDIRECT)\b/gu,
	)) {
		const start = source.lastIndexOf("\n", match.index - 1) + 1;
		const end = source.indexOf("\n", match.index);
		if (
			!renamedSlugRedirect(file, source, source.slice(start, end === -1 ? undefined : end).trim())
		) {
			add("path-alias", match.index);
		}
	}
	matches("path-alias", /["'](?:redirect|forward):(?:\/|https?:\/\/)/gu);
	matches("path-alias", /\b(?:setStatus|status|valueOf)\(\s*30[178]\s*\)/gu);
	if (!/\/(?:core\/auth|integration\/core\/oauth)\//u.test(file)) {
		matches("path-alias", /(?:\.sendRedirect|new\s+RedirectView)\(\s*["']\//gu);
	}
}

/** Find obsolete paths, not authentication, current landing pages, the renamed-slug redirect or provider schema directives. */
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
			javaRedirects(file, source, add);
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
	// The one redirect map keeps the addresses of released pages working (docs/src/lib/released-redirects.ts).
	// The site configuration may hand that map to Astro, and nothing else.
	const usesReleasedRedirects = (index: number) =>
		file === "docs/astro.config.ts" &&
		/^\s*["']?redirects["']?\s*:\s*(?:Object\.fromEntries\(\s*Object\.entries\(\s*)?RELEASED_REDIRECTS\b/u.test(
			source.slice(index),
		);
	const docsMatches = (pattern: RegExp) => {
		for (const match of source.matchAll(pattern)) {
			if (!usesReleasedRedirects(match.index)) {
				add("docs-redirect", match.index);
			}
		}
	};
	if (
		file.startsWith("docs/") &&
		!file.startsWith("docs/decisions/") &&
		file !== "docs/src/lib/released-redirects.ts"
	) {
		docsMatches(/^\s*(?:redirect_to|redirect_from|redirects|aliases)\s*:/gmu);
		docsMatches(/["'](?:redirect_to|redirect_from|redirects|aliases)["']\s*:/gu);
		matches(
			"docs-redirect",
			/<\s*(?:Redirect|Navigate)\b|http-equiv\s*=\s*["']?refresh\b|(?:window\.)?location\.(?:(?:replace|assign)\s*\(|href\s*=)/giu,
		);
		docsMatches(/\b(?:redirects|createRedirects)\s*(?::|\()/gu);
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

/** Every finding in the files that Git tracks or would add. A finding has no baseline: remove the old path. */
export function check(root: string): string[] {
	const files = execFileSync(
		"git",
		["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
		{
			cwd: root,
			env: environmentWithoutGitRepository(),
			encoding: "utf8",
			maxBuffer: 32 * 1024 * 1024,
		},
	)
		.split("\0")
		.filter(Boolean);
	return files.flatMap((file) => {
		if (
			!code.test(file) &&
			!/\.(?:mdx?|html?|json|ya?ml|properties|env|sh|tpl|conf)$/u.test(file) &&
			!file.endsWith(".env.example")
		) {
			return [];
		}
		let source: string;
		try {
			source = readFileSync(path.join(root, file), "utf8");
		} catch (error) {
			if (error instanceof Error && "code" in error && error.code === "ENOENT") {
				return [];
			}
			throw error;
		}
		return findings(file, source, files).map(
			(finding) => `${finding.file}:${finding.line}: ${finding.rule}: ${finding.text}`,
		);
	});
}
if (import.meta.main) {
	const root = path.resolve(import.meta.dirname, "..");
	const errors = check(root);
	if (errors.length > 0) {
		console.error(errors.join("\n"));
		process.exitCode = 1;
	} else {
		console.log("No legacy paths.");
	}
}
