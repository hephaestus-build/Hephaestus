import { glob, mkdir, writeFile } from "node:fs/promises";

import { uiAlerts } from "./lib/ste-ui.ts";
import { contractions } from "./lib/ste-words.ts";
import { prepareVale, valeAlerts } from "./lib/vale.ts";

const counts = new Map<string, Map<string, number>>();
function uiRule(message: string): string {
	if (message.startsWith("Split")) {
		return "STE.SentenceLength";
	}
	if (message.includes("semicolon")) {
		return "STE.Semicolons";
	}
	if (contractions.some(({ from }) => message.includes(`instead of "${from}"`))) {
		return "STE.Contractions";
	}
	return "STE.Words";
}

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
			for (const alert of [
				...valeAlerts(vale.binary, files.slice(offset, offset + 50)).values(),
			].flat()) {
				count(tree, alert.Check);
			}
		}
	}
	for (const alert of await uiAlerts(["webapp/src"])) {
		const rule = uiRule(alert.message);
		count("webapp/src", rule);
	}
	for (const alert of await uiAlerts(["webapp/src"], true)) {
		if (alert.code.includes("ste-ui-text")) {
			count("webapp/src", "STE.Vocabulary");
		}
	}
	const rules = [...new Set([...counts.values()].flatMap((items) => [...items.keys()]))].toSorted();
	const trees = ["docs/user", "docs/admin", "docs/contributor", "webapp/src"];
	const unsupportedUi = new Set([
		"STE.ProcedureLength",
		"STE.ParagraphLength",
		"STE.PassiveVoice",
		"STE.IngForms",
	]);
	const table = [
		"| Rule | User docs | Admin docs | Contributor docs | UI source |",
		"| --- | ---: | ---: | ---: | ---: |",
		...rules.map(
			(rule) =>
				`| ${rule} | ${trees.map((tree) => (tree === "webapp/src" && unsupportedUi.has(rule) ? "—" : (counts.get(tree)?.get(rule) ?? 0))).join(" | ")} |`,
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
