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
import path from "node:path";
import { isSet } from "./lib/env.ts";

/** Resolved from this file, so the script runs identically from the repo root and from `webapp/`. */
const REPO_ROOT = path.resolve(import.meta.dirname, "..");
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
		"webapp/src/components/admin/settings/WorkspaceDangerZoneSettings.tsx",
		"webapp/src/components/admin/workspace-llm/WorkspaceLlmProviderPanel.tsx",
		"webapp/src/components/admin/audit/AuthAuditPanel.tsx",
		"webapp/src/components/admin/audit/ConfigAuditPanel.tsx",
		"webapp/src/components/admin/integrations/WorkspaceSlackNotificationSettings.tsx",
		"webapp/src/components/admin/integrations/outline/AddCollectionDialog.tsx",
		"webapp/src/components/admin/integrations/slack-channels/ChannelHistorySheet.tsx",
		"webapp/src/components/settings/DangerZoneSection.tsx",
		"webapp/src/components/settings/SessionsSection.tsx",
		"webapp/src/components/create-workspace/ConnectGitLabStep.tsx",
	],
	storyMocks: [
		"webapp/src/components/admin/settings/WorkspaceSettingsPage.stories.tsx",
		"webapp/src/components/admin/audit/AuthAuditPanel.stories.tsx",
		"webapp/src/components/admin/audit/ConfigAuditPanel.stories.tsx",
		"webapp/src/components/admin/integrations/outline/AddCollectionDialog.stories.tsx",
		"webapp/src/components/admin/integrations/slack-channels/ChannelHistorySheet.stories.tsx",
		"webapp/src/components/settings/SessionsSection.stories.tsx",
	],
};

/**
 * Import statements start a line; a commented-out or quoted one does not. Multi-line specifier lists
 * are the repo's formatting for long imports, hence the newline-tolerant clause.
 */
const IMPORT =
	/^import\s+(?<typeKeyword>type\s+)?(?<clause>[\w*{},\s$]*?)\s*from\s*["'](?<module>[^"']+)["']/gmu;
const BARE_IMPORT = /^import\s+["'](?<module>[^"']+)["']/gmu;
/** Inside a story's own JSX a hook name in prose is documentation, not a call. */
const COMMENT_LINE = /^\s*(?:\/\/|\/\*|\*)/u;

const listFiles = async (directory: string, suffixes: readonly string[]): Promise<string[]> => {
	const entries = await readdir(path.join(REPO_ROOT, directory), {
		recursive: true,
		withFileTypes: true,
	});
	return (
		entries
			.filter((entry) => entry.isFile() && suffixes.some((suffix) => entry.name.endsWith(suffix)))
			// Repository-relative with `/`, whatever the platform separator is.
			.map((entry) =>
				path.relative(REPO_ROOT, path.join(entry.parentPath, entry.name)).split(path.sep).join("/"),
			)
			.toSorted()
	);
};

const readSource = async (file: string): Promise<string> =>
	readFile(path.join(REPO_ROOT, file), "utf8");

/** A statement is type-only when it says so, or when every specifier it names does. */
const isTypeOnly = (typeKeyword: string | undefined, clause: string): boolean => {
	if (typeKeyword !== undefined) {
		return true;
	}
	const specifiers = /\{(?<specifiers>[\s\S]*)\}/u.exec(clause)?.groups?.specifiers;
	if (!isSet(specifiers) || clause.replace(/\{[\s\S]*\}/u, "").trim().length > 0) {
		return false;
	}
	return specifiers
		.split(",")
		.map((specifier) => specifier.trim())
		.filter(Boolean)
		.every((specifier) => specifier.startsWith("type "));
};

const runtimeImports = (source: string): string[] => {
	const modules: string[] = [];
	for (const { groups } of source.matchAll(IMPORT)) {
		if (
			groups?.clause !== undefined &&
			groups.module !== undefined &&
			!isTypeOnly(groups.typeKeyword, groups.clause)
		) {
			modules.push(groups.module);
		}
	}
	for (const { groups } of source.matchAll(BARE_IMPORT)) {
		if (groups?.module !== undefined) {
			modules.push(groups.module);
		}
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
		if (COMMENT_LINE.test(line)) {
			continue;
		}
		for (const hook of QUERY_HOOKS) {
			if (new RegExp(`\\b${hook}\\s*\\(`, "u").test(line)) {
				called.add(hook);
			}
		}
	}
	return [...called];
};

const fetchingFailures: string[] = [];
const docsFailures: string[] = [];
const stale: string[] = [];

/** R1 — components take data as props. */
const componentTree = await listFiles(COMPONENTS, [".ts", ".tsx"]);
const componentFiles = componentTree.filter(
	(file) => !file.endsWith(".stories.tsx") && !/\.test\.tsx?$/u.test(file),
);
if (componentFiles.length === 0) {
	console.error(`No component files found under ${COMPONENTS} — this check would pass unchecked.`);
	process.exit(1);
}
for (const file of componentFiles) {
	const source = await readSource(file);
	const reasons = [
		...importsAny(source, FETCHING_MODULES).map((module) => `imports ${module}`),
		...calledQueryHooks(source).map((hook) => `calls ${hook}()`),
	];
	const allowed = ALLOWLIST.fetching.includes(file);
	if (reasons.length > 0 && !allowed) {
		fetchingFailures.push(`${file} — ${reasons.join(", ")}`);
	}
	if (reasons.length === 0 && allowed) {
		stale.push(`${file} (fetching)`);
	}
}

/** R2 — a story of a presentational component needs no network, so it needs no mock. */
const componentStories =
	componentFiles.length > 0 ? await listFiles(COMPONENTS, [".stories.tsx"]) : [];
if (componentStories.length === 0) {
	console.error(`No story files found under ${COMPONENTS} — this check would pass unchecked.`);
	process.exit(1);
}
for (const file of componentStories) {
	const source = await readSource(file);
	const mocked =
		importsAny(source, MOCK_MODULES).length > 0 || source.includes("story-mock-server");
	const allowed = ALLOWLIST.storyMocks.includes(file);
	if (mocked && !allowed) {
		fetchingFailures.push(`${file} — mocks the network in a story`);
	}
	if (!mocked && allowed) {
		stale.push(`${file} (storyMocks)`);
	}
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
for (const file of allStories) {
	const source = await readSource(file);
	if (!source.includes("msw")) {
		continue;
	}
	if (!source.includes(`tags: ["autodocs"]`)) {
		continue;
	}
	if (source.includes("inline: false")) {
		continue;
	}
	docsFailures.push(file);
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
	if (entries.length === 0) {
		continue;
	}
	console.error(message);
	for (const entry of entries) {
		console.error(`  ${entry}`);
	}
	console.error("");
}
if (fetchingFailures.length > 0 || docsFailures.length > 0 || stale.length > 0) {
	process.exit(1);
}

console.log(
	`check-presentational-components: ${componentFiles.length} components, ${allStories.length} story files, ` +
		`${ALLOWLIST.fetching.length + ALLOWLIST.storyMocks.length} allowlisted.`,
);
