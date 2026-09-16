/**
 * The GitHub App guide is the operator's registration template, so what it prescribes has to be what
 * the server actually consumes. A permission it omits is a sync that silently returns nothing, and a
 * subscription it adds ahead of a consumer is stored in the bounded webhook stream and then dropped —
 * paying retention that the deliveries Hephaestus does read would otherwise have.
 */
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { asRecord, asString, asStringArray, parseJson } from "./lib/json.ts";

const PAGE = "docs/admin/github-integration.mdx";
const EVENT_TYPES =
	"server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/github/common/GitHubEventType.java";

/**
 * GitHub delivers these to every App whether the registration asks for them or not, and offers them
 * in no subscription picker, so the server handles them while the manifest stays silent about them.
 */
const DELIVERED_UNSUBSCRIBED = new Set([
	"installation",
	"installation_repositories",
	"installation_target",
]);

/** Manifest access levels, keyed by what the page's Level column calls them. */
const LEVELS = new Map([
	["Read", "read"],
	["Read & write", "write"],
]);

const page = await readFile(PAGE, "utf8");

/** Every `##`…`####` section body, keyed by heading and ending where the next heading starts. */
const SECTIONS = new Map<string, string>();
const parts = page.split(/^#{2,4} (?<title>.+)$/mu);
for (let index = 1; index + 1 < parts.length; index += 2) {
	const [title, body] = [parts[index], parts[index + 1]];
	if (title !== undefined && body !== undefined) {
		SECTIONS.set(title.trim(), body);
	}
}

function section(title: string): string {
	const body = SECTIONS.get(title);
	if (body === undefined) {
		throw new Error(`${PAGE} has no "${title}" section`);
	}
	return body;
}

/** A table row's cells, and nothing for prose or for the `| --- |` separator. */
function cells(line: string): string[] {
	const row = line.trim();
	if (!row.startsWith("|") || !row.endsWith("|") || /^[|\s:-]+$/u.test(row)) {
		return [];
	}
	return row
		.slice(1, -1)
		.split("|")
		.map((cell) => cell.trim());
}

/** Manifest key → level, for every `Name (`key`) | Scope | Level | Why` row of a section. */
function grants(title: string): Map<string, string> {
	const granted = new Map<string, string>();
	for (const line of section(title).split("\n")) {
		const cell = cells(line);
		const key = cell.length === 4 ? /\(`(?<key>[a-z_]+)`\)$/u.exec(cell[0] ?? "") : null;
		if (key === null) {
			continue;
		}
		const level = LEVELS.get(cell[2] ?? "");
		assert.ok(level !== undefined, `${title} states an unknown level "${cell[2] ?? ""}"`);
		granted.set(asString(key.groups?.key, "permission key"), level);
	}
	assert.notEqual(granted.size, 0, `${title} lists no permission; the row shape changed`);
	return granted;
}

const block = /```json\n(?<template>[\s\S]*?)```/u.exec(page);
if (block === null) {
	throw new Error(`${PAGE} carries no manifest template`);
}
const manifest = asRecord(
	parseJson(asString(block.groups?.template, "manifest template")),
	`${PAGE} manifest template`,
);
const permissions = asRecord(manifest.default_permissions, "default_permissions");
const events = asStringArray(manifest.default_events, "default_events");

void test("the manifest grants exactly the permissions the tables explain", () => {
	const explained = new Map([
		...grants("Core contract — required"),
		...grants("Provisioned — read access ahead of features"),
	]);

	assert.deepEqual(
		new Map(Object.entries(permissions).map(([key, level]) => [key, asString(level, key)])),
		explained,
		"the permission tables and the manifest must name the same grants at the same levels",
	);
});

void test("the manifest subscribes to exactly the events the server consumes", async () => {
	const source = await readFile(EVENT_TYPES, "utf8");
	const handled = new Set(
		[...source.matchAll(/^\s+[A-Z_\d]+\("(?<value>[a-z_\d]+)"\)/gmu)].flatMap(({ groups }) =>
			groups?.value === undefined ? [] : [groups.value],
		),
	);
	assert.notEqual(handled.size, 0, `${EVENT_TYPES} parsed to no event; the pattern is stale`);

	assert.deepEqual(
		[...events].toSorted(),
		[...handled].filter((event) => !DELIVERED_UNSUBSCRIBED.has(event)).toSorted(),
		"a subscribed event with no handler pays webhook-stream retention to be dropped, and a handled event with no subscription only ever arrives through scheduled sync",
	);
});

void test("the event table lists exactly the manifest's subscriptions", () => {
	const listed = section("Event subscriptions")
		.split("\n")
		.flatMap((line) => {
			const cell = cells(line);
			const event = cell.length === 2 ? /^`(?<name>[a-z_\d]+)`$/u.exec(cell[0] ?? "") : null;
			return event === null ? [] : [asString(event.groups?.name, "event name")];
		});

	assert.deepEqual(new Set(listed), new Set(events), "the event table and the manifest disagree");
});

void test("no event is both subscribed and deliberately not subscribed", () => {
	const excluded = [
		...section("Deliberately not subscribed").matchAll(/`(?<name>[a-z_\d]+)`/gu),
	].flatMap(({ groups }) => (groups?.name === undefined ? [] : [groups.name]));
	assert.notEqual(excluded.length, 0, "the exclusion table names no event; the pattern is stale");

	assert.deepEqual(
		excluded.filter((name) => events.includes(name)),
		[],
		"an event cannot be prescribed and excluded on the same page",
	);
});
