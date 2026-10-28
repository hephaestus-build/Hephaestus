// A practice is shown, may read through the loop's tools, and may cite only the sources it declares.
// Pure rules, so a test can call them; pi-runner.ts applies them to each session.
import path from "node:path";

import { CHANGE_ROOT } from "./pi-change.ts";
import { DIFF_SOURCE, TREE_SOURCE } from "./work/practice-scripts/kits/scm/sources.ts";
import { isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";

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

/** The kind that owns a workspace-relative path, or undefined for a path outside captured sources. */
function kindOf(relative: string, sources: DeclaredSources): string | undefined {
	const known = sources.fileKinds.get(relative);
	if (known !== undefined) {
		return known;
	}
	if (relative === `${CHANGE_ROOT}/description.authored.md`) {
		return sources.fileKinds.get(`${sources.contextRoot}/description.md`);
	}
	if (relative === CHANGE_ROOT || relative.startsWith(`${CHANGE_ROOT}/`)) {
		return DIFF_SOURCE;
	}
	if (relative.startsWith(`${sources.contextRoot}/linked_work_items/`)) {
		return sources.fileKinds.get(`${sources.contextRoot}/linked_work_items.json`);
	}
	return undefined;
}

function inside(relative: string, root: string): boolean {
	return relative === root || relative.startsWith(`${root}/`);
}

/**
 * Why a read or search of `target` is refused for this practice, or undefined when it may run. `target`
 * is the path the tool received, relative to `cwd`.
 * - A listing shows names only, so it always runs.
 * - A read of a captured file needs its kind declared.
 * - A search of a declared captured file runs. A search of any directory that holds captured files is
 *   refused, because it would read the undeclared ones too.
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
	const relative = path.relative(cwd, path.resolve(cwd, target ?? "."));
	const roots = [sources.contextRoot, CHANGE_ROOT];
	const kind = kindOf(relative, sources);
	const declaredFile = kind !== undefined && sources.readable.has(kind) && relative !== CHANGE_ROOT;
	if (tool === "read") {
		if (!roots.some((root) => inside(relative, root)) || declaredFile) {
			return undefined;
		}
		return `${target} is ${kind === undefined ? "not a captured source" : `\`${kind}\``}, which this practice does not declare. Read only its declared sources: ${[...sources.readable].toSorted().join(", ")}.`;
	}
	const holdsCaptured = roots.some(
		(root) => relative === "" || inside(root, relative) || inside(relative, root),
	);
	if (!holdsCaptured || declaredFile) {
		return undefined;
	}
	return `${tool} over ${target ?? "."} would also search captured sources this practice does not declare. Search one of: ${declaredRoots(
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
		const evidence = candidate.evidence;
		if (
			isRecord(evidence) &&
			isRecord(evidence.search) &&
			Array.isArray(evidence.search.consulted)
		) {
			for (const kind of evidence.search.consulted) {
				if (typeof kind === "string") {
					kinds.add(kind);
				}
			}
		}
	}
	return [...kinds].filter((kind) => !readable.has(kind)).toSorted();
}
