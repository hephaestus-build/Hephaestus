import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";

import { anchorsOf, missingTargets, readBuiltPage } from "./check-doc-targets.ts";

const PAGES: Record<string, string> = {
	"admin/upgrade": '<h2 class="anchor" id="update-checks">Update checks</h2>',
	// The HTML minifier writes a value without quotes when it can.
	"admin/practice-review-operations": "<h4 class=anchor id=the-trace-line>The trace line</h4>",
};
const readPage = (page: string) => PAGES[page];

await describe("missingTargets", async () => {
	await test("accepts pages and headings that the build has", () => {
		assert.deepEqual(
			missingTargets(
				{
					upgrade: { path: "admin/upgrade" },
					updateChecks: { path: "admin/upgrade", anchor: "update-checks" },
					traceLine: { path: "admin/practice-review-operations", anchor: "the-trace-line" },
				},
				readPage,
			),
			[],
		);
	});

	await test("names a page that the build does not have", () => {
		assert.deepEqual(missingTargets({ slack: { path: "admin/slack" } }, readPage), [
			"slack: the docs site has no page /admin/slack",
		]);
	});

	await test("names a heading that the page does not have", () => {
		assert.deepEqual(
			missingTargets({ rollback: { path: "admin/upgrade", anchor: "rollback" } }, readPage),
			["rollback: /admin/upgrade has no heading #rollback"],
		);
	});
});

await describe("anchorsOf", async () => {
	await test("reads quoted, single-quoted and bare ids, and no other attribute", () => {
		assert.deepEqual(
			[...anchorsOf(`<h2 id="a">A</h2><h3 id='b'>B</h3><p id=c data-id="d">C</p>`)].toSorted(),
			["a", "b", "c"],
		);
	});
});

await describe("readBuiltPage", async () => {
	await test("reads a page from the build output", () => {
		const build = mkdtempSync(path.join(tmpdir(), "doc-targets-"));
		try {
			mkdirSync(path.join(build, "admin"));
			writeFileSync(path.join(build, "admin", "upgrade.html"), "upgrade");
			assert.equal(readBuiltPage(build, "admin/upgrade"), "upgrade");
			assert.equal(readBuiltPage(build, "admin/missing"), undefined);
		} finally {
			rmSync(build, { recursive: true, force: true });
		}
	});
});
