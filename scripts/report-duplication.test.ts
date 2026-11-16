import assert from "node:assert/strict";
import { test } from "node:test";

import { countMoved, isLogic, markdown, summarize } from "./report-duplication.ts";

const clone = (fragment: string, lines: number, isNew = false) => ({ fragment, lines, isNew });

await test("a clone counts only with five lines besides imports", () => {
	assert.ok(!isLogic(clone("import java.util.List;\nimport java.util.Map;\n", 2)));
	assert.ok(!isLogic(clone('import {\n\tuseState,\n\tuseEffect,\n} from "react";', 4)));
	assert.ok(!isLogic(clone("package de.tum.cit.hephaestus;\n\nimport java.util.Set;", 3)));
	// An import block that runs into the class header is still import noise.
	const header =
		"import java.util.List;\nimport java.util.Map;\n\n@WorkspaceScopedController\npublic class A {";
	assert.ok(!isLogic(clone(header, 5)));
	const logic = "if (review == null) {\n\treturn;\n}\nreview.close();\nreturn review;";
	assert.ok(isLogic(clone(logic, 5)));
	assert.ok(isLogic(clone(`import java.util.List;\n${logic}`, 6)));
});

await test("an area counts the clones and new clones that hold logic", () => {
	const report = {
		statistics: { total: { lines: 1000 } },
		duplicates: [
			clone(
				"int total = a + b;\nlog(total);\ncheck(total);\nsave(total);\nreturn total;",
				12,
				true,
			),
			clone("String name = user.name();\nlog(name);\ncheck(name);\nsave(name);\nreturn name;", 8),
			clone("import java.util.List;\nimport java.util.Map;", 9, true),
		],
	};
	assert.deepEqual(summarize("server", report), {
		area: "server",
		lines: 1000,
		clones: 2,
		cloneLines: 20,
		newClones: 1,
		newCloneLines: 12,
	});
	assert.throws(() => summarize("server", { statistics: { total: {} }, duplicates: [] }), /count/u);
});

await test("moved lines are the added lines that git colors as moved", () => {
	const diff = [
		"\u001B[1mdiff --git a/x.ts b/x.ts\u001B[m",
		"\u001B[1m+++ b/x.ts\u001B[m",
		"\u001B[32m+const a = 1;\u001B[m",
		"\u001B[36m+export function moved() {\u001B[m",
		"\u001B[36m+\treturn 42;\u001B[m",
		"\u001B[31m-const b = 2;\u001B[m",
		" unchanged",
	].join("\n");
	assert.deepEqual(countMoved(diff), { added: 3, moved: 2 });
});

await test("the summary gives each area, the total and the moved share", () => {
	const area = { lines: 100, clones: 1, cloneLines: 10, newClones: 1, newCloneLines: 10 };
	const text = markdown(
		"a".repeat(40),
		"b".repeat(40),
		[
			{ area: "server", ...area },
			{ area: "webapp", ...area },
		],
		{ added: 50, moved: 5 },
	);
	assert.match(text, /\| server \| 100 \| 1 \| 10 \| 10\.0% \| 1 \| 10 \|/u);
	assert.match(text, /\| \*\*Total\*\* \| 200 \| 2 \| 20 \| 10\.0% \| 2 \| 20 \|/u);
	assert.match(text, /Of 50 lines added .* git found 5 \(10\.0%\) moved/u);
});
