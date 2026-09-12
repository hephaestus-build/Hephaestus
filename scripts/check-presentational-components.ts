/**
 * Components under `webapp/src/components/**` are presentational: they take data as props and never
 * fetch. Fetching lives in the route file (or a `src/hooks/use-*.ts` it calls), which passes plain
 * props down. Their stories carry the second half of the rule: no MSW.
 *
 * A script rather than a lint rule because the allowlist below is repo-wide, and a linter reporting
 * per file cannot fail the build when an entry scans clean. Vitest cannot carry it either — scanning
 * the tree inside a worker starves the route tests sharing it.
 */
import { readdir, readFile } from "node:fs/promises";
import { join, relative, resolve, sep } from "node:path";

/** Resolved from this file, so the script runs identically from the repo root and from `webapp/`. */
const REPO_ROOT = resolve(import.meta.dirname, "..");
const COMPONENTS = "webapp/src/components";
const WEBAPP_SRC = "webapp/src";

/** Modules that reach the network. `@/api/types.gen` is pure types and stays allowed everywhere. */
const FETCHING_MODULES = [
	"@/api/@tanstack/react-query.gen",
	"@/api/sdk.gen",
	"@/api/client",
	"@/api/client.gen",
];
const QUERY_HOOKS = [
	"useQuery",
	"useQueries",
	"useMutation",
	"useInfiniteQuery",
	"useSuspenseQuery",
	"useSuspenseInfiniteQuery",
	"useQueryClient",
];
const MOCK_MODULES = ["msw", "msw-storybook-addon", "story-mock-server", "@/mocks/handlers"];

/** Shrink only: refactor a file, delete its line. An entry that scans clean fails the build. */
const ALLOWLIST = {
	fetching: [
		"webapp/src/components/admin/AdminDangerZoneSettings.tsx",
		"webapp/src/components/admin/ai/WorkspaceLlmProviderPanel.tsx",
		"webapp/src/components/admin/audit/AuthAuditPanel.tsx",
		"webapp/src/components/admin/config-audit/ConfigAuditPanel.tsx",
		"webapp/src/components/admin/integrations/AdminSlackNotificationSettings.tsx",
		"webapp/src/components/admin/integrations/outline/AddCollectionDialog.tsx",
		"webapp/src/components/admin/integrations/slack-channels/ChannelHistorySheet.tsx",
		"webapp/src/components/settings/DangerZoneSection.tsx",
		"webapp/src/components/settings/SessionsSection.tsx",
		"webapp/src/components/workspace/create-workspace/ConnectGitLabStep.tsx",
	],
	storyMocks: [
		"webapp/src/components/admin/AdminSettingsPage.stories.tsx",
		"webapp/src/components/admin/audit/AuthAuditPanel.stories.tsx",
		"webapp/src/components/admin/config-audit/ConfigAuditPanel.stories.tsx",
		"webapp/src/components/admin/integrations/outline/AddCollectionDialog.stories.tsx",
		"webapp/src/components/admin/integrations/slack-channels/ChannelHistorySheet.stories.tsx",
		"webapp/src/components/settings/SessionsSection.stories.tsx",
	],
};

/**
 * Import statements start a line; a commented-out or quoted one does not. Multi-line specifier lists
 * are the repo's formatting for long imports, hence the newline-tolerant clause.
 */
const IMPORT = /^import\s+(type\s+)?([\w*{},\s$]*?)\s*from\s*["']([^"']+)["']/gm;
const BARE_IMPORT = /^import\s+["']([^"']+)["']/gm;
/** Inside a story's own JSX a hook name in prose is documentation, not a call. */
const COMMENT_LINE = /^\s*(\/\/|\/\*|\*)/;

const listFiles = async (directory: string, suffixes: readonly string[]): Promise<string[]> => {
	const entries = await readdir(join(REPO_ROOT, directory), {
		recursive: true,
		withFileTypes: true,
	});
	return (
		entries
			.filter((entry) => entry.isFile() && suffixes.some((suffix) => entry.name.endsWith(suffix)))
			// Repository-relative with `/`, whatever the platform separator is.
			.map((entry) => relative(REPO_ROOT, join(entry.parentPath, entry.name)).split(sep).join("/"))
			.toSorted()
	);
};

const readSource = async (path: string): Promise<string> => readFile(join(REPO_ROOT, path), "utf8");

/** A statement is type-only when it says so, or when every specifier it names does. */
const isTypeOnly = (typeKeyword: string | undefined, clause: string): boolean => {
	if (typeKeyword) return true;
	const named = /\{([\s\S]*)\}/.exec(clause);
	if (!named?.[1] || clause.replace(/\{[\s\S]*\}/, "").trim().length > 0) return false;
	return named[1]
		.split(",")
		.map((specifier) => specifier.trim())
		.filter(Boolean)
		.every((specifier) => specifier.startsWith("type "));
};

const runtimeImports = (source: string): string[] => {
	const modules: string[] = [];
	for (const [, typeKeyword, clause, module] of source.matchAll(IMPORT)) {
		if (clause !== undefined && module !== undefined && !isTypeOnly(typeKeyword, clause)) {
			modules.push(module);
		}
	}
	for (const [, module] of source.matchAll(BARE_IMPORT)) {
		if (module !== undefined) modules.push(module);
	}
	return modules;
};

const importsAny = (source: string, modules: readonly string[]): string[] =>
	runtimeImports(source).filter((module) =>
		modules.some((candidate) => module === candidate || module.startsWith(`${candidate}/`)),
	);

const calledQueryHooks = (source: string): string[] => {
	const called = new Set<string>();
	for (const line of source.split("\n")) {
		if (COMMENT_LINE.test(line)) continue;
		for (const hook of QUERY_HOOKS) {
			if (new RegExp(`\\b${hook}\\s*\\(`).test(line)) called.add(hook);
		}
	}
	return [...called];
};

const fetchingFailures: string[] = [];
const docsFailures: string[] = [];
const stale: string[] = [];

/** R1 — components take data as props. */
const componentFiles = (await listFiles(COMPONENTS, [".ts", ".tsx"])).filter(
	(path) => !path.endsWith(".stories.tsx") && !/\.test\.tsx?$/.test(path),
);
if (componentFiles.length === 0) {
	console.error(`No component files found under ${COMPONENTS} — this check would pass unchecked.`);
	process.exit(1);
}
for (const path of componentFiles) {
	const source = await readSource(path);
	const reasons = [
		...importsAny(source, FETCHING_MODULES).map((module) => `imports ${module}`),
		...calledQueryHooks(source).map((hook) => `calls ${hook}()`),
	];
	const allowed = ALLOWLIST.fetching.includes(path);
	if (reasons.length > 0 && !allowed) fetchingFailures.push(`${path} — ${reasons.join(", ")}`);
	if (reasons.length === 0 && allowed) stale.push(`${path} (fetching)`);
}

/** R2 — a story of a presentational component needs no network, so it needs no mock. */
const componentStories =
	componentFiles.length > 0 ? await listFiles(COMPONENTS, [".stories.tsx"]) : [];
if (componentStories.length === 0) {
	console.error(`No story files found under ${COMPONENTS} — this check would pass unchecked.`);
	process.exit(1);
}
for (const path of componentStories) {
	const source = await readSource(path);
	const mocked =
		importsAny(source, MOCK_MODULES).length > 0 || source.includes("story-mock-server");
	const allowed = ALLOWLIST.storyMocks.includes(path);
	if (mocked && !allowed) fetchingFailures.push(`${path} — mocks the network in a story`);
	if (!mocked && allowed) stale.push(`${path} (storyMocks)`);
}

/**
 * R3 — one global MSW worker serves a Docs page, so inlined stories answer each other's requests.
 * An iframe per story restores the isolation; opting out of autodocs avoids the page entirely.
 */
const allStories = await listFiles(WEBAPP_SRC, [".stories.tsx"]);
if (allStories.length === 0) {
	console.error(`No story files found under ${WEBAPP_SRC} — this check would pass unchecked.`);
	process.exit(1);
}
for (const path of allStories) {
	const source = await readSource(path);
	if (!source.includes("msw")) continue;
	if (!source.includes(`tags: ["autodocs"]`)) continue;
	if (source.includes("inline: false")) continue;
	docsFailures.push(path);
}

const reports: readonly (readonly [string, readonly string[]])[] = [
	[
		"Components take their data as props; fetching belongs in the route file or a src/hooks module.",
		fetchingFailures,
	],
	[
		'A story file that mocks the network must set docs: { story: { inline: false } } on its meta, or drop tags: ["autodocs"].',
		docsFailures,
	],
	[
		"These are no longer violations. Delete them from ALLOWLIST in this script — it only shrinks.",
		stale,
	],
];
for (const [message, entries] of reports) {
	if (entries.length === 0) continue;
	console.error(message);
	for (const entry of entries) console.error(`  ${entry}`);
	console.error("");
}
if (fetchingFailures.length > 0 || docsFailures.length > 0 || stale.length > 0) process.exit(1);

console.log(
	`check-presentational-components: ${componentFiles.length} components, ${allStories.length} story files, ` +
		`${ALLOWLIST.fetching.length + ALLOWLIST.storyMocks.length} allowlisted.`,
);
