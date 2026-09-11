// Precompute HINTS for documents-public-api-and-behaviour-changes. Surfaces FACTS ONLY — the LLM judges.
//
// The observation hinges on a STRUCTURAL fact a diff hunk alone cannot reveal: does the repo even SHIP a public
// product (library/framework), and are the symbols touched in the diff actually EXPORTED (part of that public
// surface) or merely internal/private? An app-only repo with no library product has no public API to document,
// and a modifier-less Swift declaration is INTERNAL — not public. We surface {hasPublicProduct,
// changedPublicSymbols, changedInternalSymbols} and let the model decide whether documentation was owed.
// Treating modifier-less Swift as public on an app-only repo yields false negative observations, so the
// export-status table classifies it as internal. The table is keyed off file extension: adding a language =
// one row, no engine change.
import { findFiles, readFileLines } from "../lib/grep.ts";
import type { DiffFile, Hint, PullRequestMetadata } from "../lib/types.ts";

// ── (1) Public-product manifest scan ──────────────────────────────────────────────────────────────────────
// Each manifest filename maps to a predicate over its raw text that answers "does this declare a consumable
// library/framework product?". One row per ecosystem; the predicate stays neutral (a structural test, no observation).
const PRODUCT_MANIFESTS: Array<[RegExp, (text: string) => boolean]> = [
	// Swift Package Manager: a `.library(...)` product (executables/apps don't expose a public API surface).
	[/(^|\/)Package\.swift$/, (t) => /\.library\s*\(/.test(t)],
	// npm: a non-private package that declares an entry/types surface for consumers.
	[
		/(^|\/)package\.json$/,
		(t) => !/"private"\s*:\s*true/.test(t) && /"(main|module|exports|types|typings)"\s*:/.test(t),
	],
	// Maven: packaged as a real artifact (jar/etc.), not an aggregator pom.
	[/(^|\/)pom\.xml$/, (t) => !/<packaging>\s*pom\s*<\/packaging>/.test(t)],
	// Gradle: applies the java-library plugin or a publishing plugin.
	[
		/(^|\/)build\.gradle(\.kts)?$/,
		(t) => /(java-library|maven-publish|`maven-publish`|com\.vanniktech\.maven\.publish)/.test(t),
	],
	// Python: declares packages to distribute.
	[/(^|\/)setup\.py$/, (t) => /(packages\s*=|find_packages\s*\()/.test(t)],
	[/(^|\/)setup\.cfg$/, (t) => /\[options\][^[]*packages\s*=/.test(t)],
	[/(^|\/)pyproject\.toml$/, (t) => /(\[project\]|\[tool\.poetry\]|packages\s*=)/.test(t)],
	// Go: a module declaration makes the package importable by others.
	[/(^|\/)go\.mod$/, (t) => /^module\s+\S+/m.test(t)],
];

const MANIFEST_NAMES = ["swift", "json", "xml", "gradle", "kts", "py", "cfg", "toml", "mod"];

// ── (2) Per-language export status of a CHANGED declaration ────────────────────────────────────────────────
// For each language: detect that a line declares a symbol, and whether that symbol is PUBLIC (exported) or not.
// `isPublic` is only consulted when `decl` matches. Modifier-less Swift is INTERNAL, not public.
interface ExportRule {
	decl: RegExp; // line introduces a named declaration (func/class/type/var/const)
	isPublic: (line: string) => boolean; // is that declaration part of the public surface?
}

const EXPORT_RULES: Record<string, ExportRule> = {
	// Swift: public/open are exported; modifier-less / internal / private / fileprivate are NOT public.
	swift: {
		decl: /\b(func|class|struct|enum|protocol|extension|var|let|typealias|actor|init)\b/,
		isPublic: (l) => /\b(public|open)\b/.test(l),
	},
	// TypeScript: an `export` keyword (or `export default`) marks the public surface.
	ts: {
		decl: /\b(function|class|interface|type|enum|const|let|var|namespace)\b/,
		isPublic: (l) => /\bexport\b/.test(l),
	},
	// Java: only `public` declarations are part of the API; package-private/protected/private are not.
	java: {
		decl: /\b(class|interface|enum|record|void|[A-Z][A-Za-z0-9_<>[\]]*)\s+[A-Za-z_]\w*\s*[({]/,
		isPublic: (l) => /\bpublic\b/.test(l),
	},
	// Kotlin: declarations are public by default; private/internal/protected demote them.
	kotlin: {
		decl: /\b(fun|class|interface|object|val|var)\b/,
		isPublic: (l) => !/\b(private|internal|protected)\b/.test(l),
	},
	// Python: a leading-underscore name is private by convention; anything else is public.
	python: {
		decl: /^\s*(def|class)\s+[A-Za-z_]/,
		isPublic: (l) => !/^\s*(def|class)\s+_/.test(l),
	},
	// Go: an exported identifier starts with an upper-case letter.
	go: {
		decl: /^\s*(func|type|var|const)\s+[A-Za-z_]/,
		isPublic: (l) => /^\s*(func|type|var|const)\s+(\([^)]*\)\s*)?[A-Z]/.test(l),
	},
};

// extension -> language key (one source of truth).
const EXT_LANG: Record<string, string> = {
	swift: "swift",
	ts: "ts",
	tsx: "ts",
	mts: "ts",
	cts: "ts",
	js: "ts",
	jsx: "ts",
	mjs: "ts",
	cjs: "ts",
	java: "java",
	kt: "kotlin",
	kts: "kotlin",
	py: "python",
	go: "go",
};

function langOf(path: string): string | null {
	const ext = path.split(".").pop()?.toLowerCase() ?? "";
	return EXT_LANG[ext] ?? null;
}

function isComment(trimmed: string): boolean {
	return (
		trimmed.startsWith("//") ||
		trimmed.startsWith("#") ||
		trimmed.startsWith("*") ||
		trimmed.startsWith("/*")
	);
}

export default async function documentsPublicApiAndBehaviourChanges(
	repoPath: string,
	diffFiles: Map<string, DiffFile>,
	_m: PullRequestMetadata,
) {
	// (1) Does the repo declare a public library/framework product?
	let hasPublicProduct = false;
	for (const ext of MANIFEST_NAMES) {
		if (hasPublicProduct) break;
		for (const manifestPath of findFiles(repoPath, ext)) {
			const matcher = PRODUCT_MANIFESTS.find(([re]) => re.test(manifestPath));
			if (!matcher) continue;
			const lines = await readFileLines(manifestPath);
			const text = [...lines.values()].join("\n");
			if (matcher[1](text)) {
				hasPublicProduct = true;
				break;
			}
		}
	}

	// (2) Classify each declaration ADDED in the diff as public (exported) or internal.
	const hints: Hint[] = [];
	let changedPublicSymbols = 0;
	let changedInternalSymbols = 0;
	for (const [path, df] of diffFiles) {
		const lang = langOf(path);
		if (!lang) continue;
		const rule = EXPORT_RULES[lang];
		if (!rule) continue;
		for (const [line, content] of df.addedLines) {
			const trimmed = content.trimStart();
			if (isComment(trimmed) || !rule.decl.test(content)) continue;
			const isPublic = rule.isPublic(content);
			if (isPublic) changedPublicSymbols++;
			else changedInternalSymbols++;
			if (hints.length < 40) {
				hints.push({
					file: path,
					line,
					pattern: `${lang}:${isPublic ? "public-decl" : "internal-decl"}`,
					context: content.trim().slice(0, 160),
					inDiff: true,
					flags: { exported: isPublic },
				});
			}
		}
	}

	const directions: string[] = [];
	if (!hasPublicProduct) {
		directions.push(
			"No public library/framework product was detected in the repo manifests (no SwiftPM .library, non-private package.json entry, publishable Maven/Gradle artifact, distributable Python package, or Go module) — investigate whether this repo exposes any public API surface a consumer depends on before deciding documentation was owed.",
		);
	}
	if (changedPublicSymbols > 0) {
		directions.push(
			`The diff adds ${changedPublicSymbols} exported/public declaration(s)${hasPublicProduct ? " in a repo that ships a public product" : ""} — investigate whether their documented behaviour or signature changed and whether that change is reflected in docs/comments.`,
		);
	}
	if (changedInternalSymbols > 0 && changedPublicSymbols === 0) {
		directions.push(
			`The diff adds ${changedInternalSymbols} declaration(s) but none are exported/public (e.g. modifier-less Swift is internal, non-exported TS, package-private Java, leading-underscore Python) — investigate whether any public-facing behaviour actually changed before expecting public-API documentation.`,
		);
	}

	return {
		hints,
		metrics: {
			hasPublicProduct: hasPublicProduct ? 1 : 0,
			changedPublicSymbols,
			changedInternalSymbols,
		},
		directions,
	};
}
