import type { Plugin } from "vite";

import { budgetViolations, type EntryBudget, entryClosure } from "./entry-closure.ts";

/**
 * Every page's cold load fetches the entry's static JavaScript before it renders. With the router
 * plugin's default groupings, only a route's `component`, `errorComponent` and `notFoundComponent`
 * are split: an import that `validateSearch`, `beforeLoad`, `loader` or `pendingComponent` uses
 * stays in the entry, with everything it imports.
 */
// Room for the shell to grow by a small feature; a larger step needs a deliberate new ceiling.
const budget: EntryBudget = {
	ceiling: 1_250_000,
	slack: 50_000,
	routeOnlyPackages: [
		"@dnd-kit/core",
		"@monaco-editor/react",
		"@tanstack/react-table",
		"@tanstack/table-core",
		"mermaid",
		"monaco-editor",
		"react-day-picker",
		"recharts",
		"shiki",
		"streamdown",
	],
};

export function entryChunkBudget(): Plugin {
	return {
		name: "entry-chunk-budget",
		apply: "build",
		// Vite expands its preload map after generateBundle; budget the final bytes written.
		writeBundle(_options, bundle) {
			const closure = entryClosure(
				Object.values(bundle).flatMap((output) =>
					output.type === "chunk"
						? [
								{
									fileName: output.fileName,
									isEntry: output.isEntry,
									imports: output.imports,
									moduleIds: output.moduleIds,
									bytes: Buffer.byteLength(output.code),
								},
							]
						: [],
				),
			);
			const violations = budgetViolations(closure, budget);
			if (violations.length > 0) {
				throw new Error(violations.join("\n"));
			}
			this.info(
				`entry: ${closure.bytes}/${budget.ceiling} bytes of static JavaScript in ${closure.chunks} chunks`,
			);
		},
	};
}
