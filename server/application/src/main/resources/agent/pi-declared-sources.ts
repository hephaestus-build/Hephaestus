// A practice is shown, may read through the loop's tools, and may cite only the sources it declares.
// Pure rules, so a test can call them; pi-runner.ts applies them to each session.
import path from "node:path";

import type { ToolCallEvent, ToolCallEventResult } from "@earendil-works/pi-coding-agent";

import { CHANGE_ROOT } from "./pi-change.ts";
import { DIFF_SOURCE, TREE_SOURCE } from "./work/practice-scripts/kits/scm/sources.ts";
import { isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";

/**
 * Whether captured context sits on a volume that only the loop mounts. While it is false, `bash` and code
 * in the tools container can read every captured file, so only the loop's own tools hold the boundary.
 * ADR 0068's tools layer sets it with the Pod layout that makes it true (`SandboxLayout`), and L7 of ADR 0069
 * reads it to decide which sources a session could read.
 */
export const CONTEXT_IS_LOOP_ONLY = false;

/** What a session of a practice that declares `declared` may read. */
export function readableKinds(declared: Iterable<string>): Set<string> {
	return new Set([...declared, DIFF_SOURCE, TREE_SOURCE]);
}

export interface DeclaredSources {
	/**
	 * The source kinds the practice's session may read: the kinds it declares, and the change and the
	 * checkout, which every session reaches through Git.
	 */
	readable: ReadonlySet<string>;
	/** The kind of each captured file, by workspace-relative path, as the manifest records it. */
	fileKinds: ReadonlyMap<string, string>;
	contextRoot: string;
	repositoryRoot: string;
}

/** The kind that owns a workspace-relative path, or undefined for a path that no captured source owns. */
function kindOf(relative: string, sources: DeclaredSources): string | undefined {
	const known = sources.fileKinds.get(relative);
	if (known !== undefined) {
		return known;
	}
	if (relative === `${CHANGE_ROOT}/description.authored.md`) {
		return sources.fileKinds.get(`${sources.contextRoot}/description.md`);
	}
	if (relative.startsWith(`${CHANGE_ROOT}/`)) {
		return DIFF_SOURCE;
	}
	if (relative.startsWith(`${sources.contextRoot}/linked_work_items/`)) {
		return sources.fileKinds.get(`${sources.contextRoot}/linked_work_items.json`);
	}
	return undefined;
}

function inside(relative: string, root: string): boolean {
	return root === "" || relative === root || relative.startsWith(`${root}/`);
}

/** The captured paths, files or directories, whose kind the practice's session may not read. */
function unreadable(sources: DeclaredSources): string[] {
	const derived = [
		[
			`${CHANGE_ROOT}/description.authored.md`,
			kindOf(`${CHANGE_ROOT}/description.authored.md`, sources),
		],
		[
			`${sources.contextRoot}/linked_work_items`,
			sources.fileKinds.get(`${sources.contextRoot}/linked_work_items.json`),
		],
	] as const;
	return [...sources.fileKinds, ...derived]
		.filter(([, kind]) => kind !== undefined && !sources.readable.has(kind))
		.map(([owned]) => owned);
}

/**
 * Why a read or search of `target` is refused for this practice, or undefined when it may run. `target`
 * is the path the tool received, relative to `cwd`.
 * - A listing shows names, not content, so it always runs.
 * - A read of a captured file needs its kind readable. A path that no captured source owns is read.
 * - A search is refused when its target holds a captured file that the practice may not read.
 */
export function refusedContextRead(
	tool: "read" | "grep" | "find" | "ls",
	target: string | undefined,
	cwd: string,
	sources: DeclaredSources,
): string | undefined {
	if (tool === "ls") {
		return undefined;
	}
	// Pi's tools strip a leading @ from a path; the guard reads the path the tool will open.
	const given = (target ?? ".").replace(/^@/u, "");
	const relative = path.relative(cwd, path.resolve(cwd, given));
	const kind = kindOf(relative, sources);
	if (kind !== undefined) {
		if (sources.readable.has(kind)) {
			return undefined;
		}
		return `${given} is \`${kind}\`, which this practice does not declare. Read only: ${[...sources.readable].toSorted().join(", ")}.`;
	}
	if (tool === "read" || !unreadable(sources).some((owned) => inside(owned, relative))) {
		return undefined;
	}
	return `${tool} over ${given} would also search captured sources this practice does not declare. Search one of: ${declaredRoots(
		sources,
	)
		.map((root) => `\`${root}\``)
		.join(", ")}.`;
}

/** The paths a search for this practice may cover: its declared captured files and the checkout. */
export function declaredRoots(sources: DeclaredSources): string[] {
	const files = [...sources.fileKinds]
		.filter(([, kind]) => sources.readable.has(kind))
		.map(([file]) => file);
	const change = sources.readable.has(DIFF_SOURCE) ? [CHANGE_ROOT] : [];
	return [...new Set([...change, ...files, sources.repositoryRoot])].toSorted();
}

/** The source kinds a submitted result cites or says it consulted that its session may not read. */
export function undeclaredCitedKinds(candidate: unknown, readable: ReadonlySet<string>): string[] {
	const kinds = new Set<string>();
	const citations = (value: unknown) => {
		if (!Array.isArray(value)) {
			return;
		}
		for (const citation of value) {
			if (isRecord(citation) && typeof citation.sourceKind === "string") {
				kinds.add(citation.sourceKind);
			}
		}
	};
	if (isRecord(candidate)) {
		citations(candidate.citations);
		if (Array.isArray(candidate.problems)) {
			for (const problem of candidate.problems) {
				if (isRecord(problem)) {
					citations(problem.citations);
				}
			}
		}
		const { evidence } = candidate;
		// Every warrant that names what it consulted: a search and an inapplicability.
		for (const warrant of isRecord(evidence) ? [evidence.search, evidence.inapplicability] : []) {
			const consulted =
				isRecord(warrant) && Array.isArray(warrant.consulted) ? warrant.consulted : [];
			for (const kind of consulted.filter((value): value is string => typeof value === "string")) {
				kinds.add(kind);
			}
		}
	}
	return [...kinds].filter((kind) => !readable.has(kind)).toSorted();
}

/** The one hook the guard needs, so a test can drive it without a whole session. */
export interface ToolCallHook {
	on: (
		event: "tool_call",
		handler: (event: ToolCallEvent) => ToolCallEventResult | undefined,
	) => unknown;
}

/**
 * The guard on the loop's read, grep, find and ls. Pi fires `tool_call` for a call that codemode makes too,
 * with `parentToolCallId` set, so a script's calls pass here as well.
 */
export function declaredSourcesExtension(
	sources: DeclaredSources,
	cwd: string,
): (pi: ToolCallHook) => void {
	return (pi) => {
		pi.on("tool_call", (event) => {
			if (
				event.toolName !== "read" &&
				event.toolName !== "grep" &&
				event.toolName !== "find" &&
				event.toolName !== "ls"
			) {
				return;
			}
			const target: unknown = isRecord(event.input) ? event.input.path : undefined;
			const reason = refusedContextRead(
				event.toolName,
				typeof target === "string" ? target : undefined,
				cwd,
				sources,
			);
			return reason === undefined ? undefined : { block: true, reason };
		});
	};
}
