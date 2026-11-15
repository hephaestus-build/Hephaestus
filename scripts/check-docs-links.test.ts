import assert from "node:assert/strict";
import { test } from "node:test";

import { brokenLinks, linksOf, targetFile } from "./check-docs-links.ts";

const site: Record<string, string> = {
	"admin/upgrade.html": '<h2 id="install">Install</h2>',
	"admin/upgrade.md": "# Upgrade",
	"index.html": "<h1>Home</h1>",
	"img/user/a.light.webp": "",
};
const read = (file: string): string | undefined => site[file];

void test("reads quoted and bare link targets", () => {
	assert.deepEqual(
		linksOf(
			`<a href="/a">x</a><a class=x href='/b'>y</a><a href=/c>z</a><a href="/d?e=1&amp;f=2">w</a>`,
		),
		["/a", "/b", "/c", "/d?e=1&f=2"],
	);
});

void test("maps a page path to its HTML file and keeps a static file", () => {
	assert.equal(targetFile("/admin/upgrade"), "admin/upgrade.html");
	assert.equal(targetFile("/"), "index.html");
	assert.equal(targetFile("/admin/upgrade.md"), "admin/upgrade.md");
});

void test("accepts links to pages, anchors, twins and static files", () => {
	const html =
		'<a href="/admin/upgrade#install">a</a><a href="./upgrade.md">b</a><a href="/img/user/a.light.webp">c</a><a href="https://example.org">d</a><a href="#install">e</a>';
	assert.deepEqual(brokenLinks("admin/upgrade", html, "/", read), []);
});

void test("reports a missing page, a missing anchor and a link outside the base path", () => {
	const html =
		'<a href="/admin/gone">a</a><a href="/admin/upgrade#gone">b</a><a href="/other">c</a>';
	assert.deepEqual(brokenLinks("admin/upgrade", html, "/", read), [
		"admin/upgrade: /admin/gone reaches no file (admin/gone.html)",
		"admin/upgrade: /admin/upgrade#gone reaches no element #gone",
		"admin/upgrade: /other reaches no file (other.html)",
	]);
	assert.deepEqual(brokenLinks("admin/upgrade", '<a href="/admin/upgrade">a</a>', "/next/", read), [
		"admin/upgrade: /admin/upgrade leaves the base path /next/",
	]);
});
