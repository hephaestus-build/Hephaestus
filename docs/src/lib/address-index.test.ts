import assert from "node:assert/strict";
import path from "node:path";
import { test } from "node:test";

import { sourcePages, type SitePage } from "./address-index.ts";

const docs = path.resolve(import.meta.dirname, "..", "..");
const pages = await sourcePages(docs);
const idsOf = (route: string): ReadonlySet<string> => {
	const page: SitePage | undefined = pages.get(route);
	assert.ok(page !== undefined && "ids" in page, `${route} is a page`);
	return page.ids;
};

void test("a guide page has the ids of its headings, a custom id and an element id", () => {
	assert.ok(idsOf("admin/upgrade").has("stopped-schema-upgrades"));
	assert.ok(!idsOf("admin/upgrade").has("no-such-heading"));
	assert.ok(idsOf("admin/compatibility-policy").has("upgrade-paths-to-10"));
	assert.ok(
		pages.has("admin/dsms") && !pages.has("admin/dsms/index"),
		"a folder index is its folder's page",
	);
});

void test("a page has the headings of the partials that it includes", () => {
	assert.ok(idsOf("admin/configuration-reference").has("config"));
	assert.ok(idsOf("contributor/database-schema").has("core"));
	assert.equal(pages.get("admin/_configuration-reference/helm"), undefined);
});

void test("the site's other paths and the released redirects are there", () => {
	for (const route of ["", "search", "sitemap.xml", "admin/upgrade.md", "robots.txt"]) {
		assert.ok(pages.has(route), route);
	}
	assert.deepEqual(pages.get("admin/practice-review"), { redirect: "user/practice-reviews" });
});
