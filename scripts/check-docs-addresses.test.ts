import assert from "node:assert/strict";
import { test } from "node:test";

import { addressProblem, linkedAddresses, releasedAddresses } from "./check-docs-addresses.ts";

const site: Record<string, string> = {
	"index.html": "<h1>Home</h1>",
	"admin/upgrade.html": '<h2 id="stopped-schema-upgrades">Stopped</h2>',
	"admin/practice-review.html":
		'<meta http-equiv="refresh" content="0;url=/user/practice-reviews">',
	"user/practice-reviews.html": '<h2 id="feedback-mode">Feedback mode</h2>',
	"admin/dsms/index.html": "<h1>DSMS</h1>",
	"admin/upgrade.md": "# Upgrade",
};
const read = (file: string) => site[file];

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
		linkedAddresses(["scripts/a.test.ts:3:https://docs.hephaestus.build/admin/gone"]),
		[],
	);
});
