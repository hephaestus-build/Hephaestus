import assert from "node:assert/strict";
import { test } from "node:test";

import {
	addressProblem,
	builtPages,
	indexDrift,
	linkedAddresses,
	releasedAddresses,
	type SiteLookup,
} from "./check-docs-addresses.ts";

const site: Record<string, string> = {
	"index.html": "<h1>Home</h1>",
	"admin/upgrade.html": '<h2 id="stopped-schema-upgrades">Stopped</h2>',
	"admin/practice-review.html":
		'<meta http-equiv="refresh" content="0;url=/user/practice-reviews">',
	"user/practice-reviews.html": '<h2 id="feedback-mode">Feedback mode</h2>',
	"admin/dsms/index.html": '<h1>DSMS</h1><h2 data-id="gone">Not an id</h2>',
	"admin/upgrade.md": "# Upgrade",
};
const read = builtPages((file) => site[file]);

void test("a page, a folder index, a Markdown twin and a /next/ path resolve", () => {
	for (const address of [
		"",
		"admin/upgrade",
		"admin/dsms",
		"admin/upgrade.md",
		"next/admin/upgrade",
	]) {
		assert.equal(addressProblem(address, read), undefined, address);
	}
});

void test("an anchor must exist on the page that the address finally reaches, through a redirect", () => {
	assert.equal(addressProblem("admin/upgrade#stopped-schema-upgrades", read), undefined);
	assert.equal(addressProblem("admin/practice-review#feedback-mode", read), undefined);
	assert.equal(addressProblem("admin/upgrade#gone", read), 'no element with id "gone"');
	assert.equal(addressProblem("admin/kubernetes#install", read), "no page and no redirect");
});

void test("an anchor matches an element id, as written or percent-decoded", () => {
	assert.equal(addressProblem("admin/upgrade#stopped%2Dschema%2Dupgrades", read), undefined);
	assert.equal(addressProblem("admin/dsms#gone", read), 'no element with id "gone"');
});

void test("a site read from the source resolves the same way: a redirect leads to a page and its ids", () => {
	const pages = new Map<string, ReturnType<SiteLookup>>([
		["admin/practice-review", { redirect: "/user/practice-reviews" }],
		["user/practice-reviews", { ids: new Set(["feedback-mode"]) }],
	]);
	const source: SiteLookup = (route) => pages.get(route);
	assert.equal(addressProblem("admin/practice-review#feedback-mode", source), undefined);
	assert.equal(addressProblem("next/user/practice-reviews", source), undefined);
	assert.equal(addressProblem("user/practice-reviews#gone", source), 'no element with id "gone"');
});

void test("the docs build refuses a source index that claims a path or an id that the build lacks", () => {
	const index = new Map([
		["admin/upgrade", { ids: new Set(["stopped-schema-upgrades"]) }],
		["admin/practice-review", { redirect: "user/practice-reviews" }],
	]);
	assert.deepEqual(indexDrift(index, read), []);
	index.set("admin/upgrade", { ids: new Set(["stopped-schema-upgrades", "renamed"]) });
	index.set("admin/kubernetes", { ids: new Set() });
	assert.deepEqual(indexDrift(index, read), [
		"admin/upgrade: the source index has ids that the build lacks: renamed",
		"admin/kubernetes: the source index has this path, the build does not",
	]);
});

void test("released addresses skip comments and blank lines; linked addresses keep their source line", () => {
	assert.deepEqual(
		releasedAddresses("# v1\n\n/admin/upgrade\n/admin/upgrade#stopped-schema-upgrades\n"),
		["/admin/upgrade", "/admin/upgrade#stopped-schema-upgrades"],
	);
	assert.deepEqual(
		linkedAddresses([
			"MIGRATION.md:12:See https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades.",
		]),
		[{ where: "MIGRATION.md:12", address: "admin/upgrade#stopped-schema-upgrades" }],
	);
	assert.deepEqual(
		linkedAddresses([
			"README.md:4:https://docs.hephaestus.build/admin/upgrade?source=release#gone",
		]),
		[{ where: "README.md:4", address: "admin/upgrade#gone" }],
	);
	assert.deepEqual(
		linkedAddresses(["scripts/a.test.ts:3:https://docs.hephaestus.build/admin/gone"]),
		[],
	);
});
