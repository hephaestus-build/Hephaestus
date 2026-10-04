import { glob, mkdir, writeFile } from "node:fs/promises";

import { uiAlerts, uiRuleName } from "./lib/ui-text.ts";
import { prepareVale, valeAlerts } from "./lib/vale.ts";

const counts = new Map<string, Map<string, number>>();
function count(tree: string, rule: string): void {
	const rules = counts.get(tree) ?? new Map<string, number>();
	rules.set(rule, (rules.get(rule) ?? 0) + 1);
	counts.set(tree, rules);
}

const vale = await prepareVale();
try {
	for (const tree of ["docs/user", "docs/admin", "docs/contributor"]) {
		const files = await Array.fromAsync(glob(`${tree}/**/*.{md,mdx}`));
		for (let offset = 0; offset < files.length; offset += 50) {
			for (const alert of [...valeAlerts(vale, files.slice(offset, offset + 50)).values()].flat()) {
				count(tree, alert.Check);
			}
		}
	}
	for (const alert of await uiAlerts(["webapp/src", "extension/src"])) {
		count("UI source", uiRuleName(alert.message));
	}
	const rules = [...new Set([...counts.values()].flatMap((items) => [...items.keys()]))].toSorted();
	const trees = ["docs/user", "docs/admin", "docs/contributor", "UI source"];
	const unsupportedUi = new Set([
		"STE.ProcedureLength",
		"STE.ParagraphLength",
		"STE.PassiveVoice",
		"STE.IngForms",
		"STE.Vocabulary",
	]);
	const table = [
		"| Rule | User docs | Admin docs | Contributor docs | UI source |",
		"| --- | ---: | ---: | ---: | ---: |",
		...rules.map(
			(rule) =>
				`| ${rule} | ${trees.map((tree) => (tree === "UI source" && unsupportedUi.has(rule) ? "—" : (counts.get(tree)?.get(rule) ?? 0))).join(" | ")} |`,
		),
	].join("\n");
	console.log(table);
	await mkdir(".cache/ste", { recursive: true });
	await writeFile(
		".cache/ste/report.json",
		`${JSON.stringify(Object.fromEntries([...counts].map(([tree, results]) => [tree, Object.fromEntries(results)])), null, 2)}\n`,
	);
} finally {
	await vale.dispose();
}
