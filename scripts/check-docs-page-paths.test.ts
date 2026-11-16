import assert from "node:assert/strict";
import { test } from "node:test";

import { stalePagePaths } from "./check-docs-page-paths.ts";

const pages = new Set([
	"docs/admin/renamed-page.mdx",
	"docs/admin/dsms/README.md",
	"docs/user/overview.mdx",
]);
const exists = (file: string) => pages.has(file);

void test("a .md path to a page that is .mdx fails and names the .mdx path", () => {
	assert.deepEqual(
		stalePagePaths(
			["deploy/chart/hephaestus/values.yaml:192:    # docs/admin/renamed-page.md § Storage."],
			exists,
		),
		[
			"deploy/chart/hephaestus/values.yaml:192 names docs/admin/renamed-page.md, which is now docs/admin/renamed-page.mdx.",
		],
	);
});

void test("an existing .md file, an .mdx path and a fixture path to no page pass", () => {
	assert.deepEqual(
		stalePagePaths(
			[
				"server/A.java:3: // docs/user/gone.md",
				"README.md:1:[x](docs/admin/dsms/README.md) and docs/user/overview.mdx",
			],
			exists,
		),
		[],
	);
});

void test("released entries keep the links they shipped with", () => {
	assert.deepEqual(
		stalePagePaths(
			["CHANGELOG.md:9:see docs/admin/renamed-page.md", "MIGRATION.md:2:docs/user/gone.md"],
			exists,
		),
		[],
	);
});
