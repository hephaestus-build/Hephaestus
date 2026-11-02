import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import {
	breakLines,
	compareSpecs,
	headsForMajorRelease,
	installOasdiff,
	latestStableTag,
	removedFromContract,
} from "./check-api-compatibility.ts";

const oasdiff = await installOasdiff();
const directory = await mkdtemp(path.join(tmpdir(), "api-compatibility-test-"));
after(async () => {
	await rm(directory, { recursive: true, force: true });
});

const json = (schema: object) => ({ content: { "application/json": { schema } } });
const item = (properties: Record<string, object> = {}) => ({
	type: "object",
	required: ["id"],
	properties: { id: { type: "string" }, ...properties },
});
const draft = (properties: Record<string, object> = {}) => ({
	type: "object",
	required: ["title"],
	properties: { title: { type: "string" }, ...properties },
});

/** One spec with a list, a create and a delete operation, as the base release publishes it. */
function spec(
	options: {
		optional?: Record<string, object>;
		withDelete?: boolean;
		deleteDeprecated?: boolean;
		responseOnly?: Record<string, object>;
	} = {},
) {
	const { optional = {}, withDelete = true, deleteDeprecated = false, responseOnly = {} } = options;
	return {
		openapi: "3.1.0",
		info: { title: "Fixture", version: "1.0.0" },
		paths: {
			"/items": {
				get: {
					operationId: "listItems",
					responses: {
						"200": {
							description: "Items",
							...json({ type: "array", items: item({ ...optional, ...responseOnly }) }),
						},
					},
				},
				post: {
					operationId: "createItem",
					requestBody: { required: true, ...json(draft(optional)) },
					responses: { "201": { description: "Created", ...json(item(optional)) } },
				},
			},
			...(withDelete && {
				"/items/{id}": {
					delete: {
						operationId: "deleteItem",
						deprecated: deleteDeprecated,
						parameters: [{ name: "id", in: "path", required: true, schema: { type: "string" } }],
						responses: { "204": { description: "Deleted" } },
					},
				},
			}),
		},
	};
}

let fixtures = 0;
async function compare(
	revision: object,
	acceptedBreaks = "",
	baseTag = "v0.9.0",
	majorRelease = false,
	base: object = spec(),
) {
	fixtures += 1;
	const name = (role: string) => path.join(directory, `${role}-${fixtures}`);
	await writeFile(name("base"), JSON.stringify(base));
	await writeFile(name("revision"), JSON.stringify(revision));
	await writeFile(name("accepted"), acceptedBreaks);
	return compareSpecs({
		oasdiff,
		base: name("base"),
		baseTag,
		majorRelease,
		revision: name("revision"),
		acceptedBreaks: name("accepted"),
		format: "text",
		cwd: directory,
	});
}

const removedDelete = "in API DELETE /items/{id} api path removed without deprecation";

void test("a removed endpoint breaks a client of the base", async () => {
	const { compatible, report } = await compare(spec({ withDelete: false }));
	assert.equal(compatible, false);
	assert.match(report, /api-path-removed-without-deprecation/u);
});

void test("an added optional field in a request and a response is compatible", async () => {
	const { compatible } = await compare(spec({ optional: { note: { type: "string" } } }));
	assert.equal(compatible, true);
});

const withStatus = (values: string[]) =>
	spec({ responseOnly: { status: { type: "string", enum: values } } });

for (const baseTag of ["v0.9.0", "v1.2.0"]) {
	void test(`a new value in a response enum is compatible against ${baseTag}`, async () => {
		const { compatible, problems } = await compare(
			withStatus(["OPEN", "CLOSED", "PAUSED"]),
			"",
			baseTag,
			false,
			withStatus(["OPEN", "CLOSED"]),
		);
		assert.equal(compatible, true);
		assert.deepEqual(problems, []);
	});
}

const requestWithStatus = (values: string[]) =>
	spec({ optional: { status: { type: "string", enum: values } } });

void test("a value removed from a request enum still breaks a client", async () => {
	const { compatible } = await compare(
		requestWithStatus(["OPEN"]),
		"",
		"v0.9.0",
		false,
		requestWithStatus(["OPEN", "CLOSED"]),
	);
	assert.equal(compatible, false);
});

const withNote = spec({ responseOnly: { note: { type: "string" } } });

void test("an optional response property can go before 1.0", async () => {
	const { compatible } = await compare(spec(), "", "v0.9.0", false, withNote);
	assert.equal(compatible, true);
});

void test("from a 1.0 base, a removed optional response property breaks a client", async () => {
	const { compatible, report } = await compare(spec(), "", "v1.2.0", false, withNote);
	assert.equal(compatible, false);
	assert.match(report, /response-optional-property-removed/u);
});

const deprecatedDelete = spec({ deleteDeprecated: true });

void test("a deprecated operation can go before 1.0", async () => {
	const { compatible, problems } = await compare(
		spec({ withDelete: false }),
		"",
		"v0.9.0",
		false,
		deprecatedDelete,
	);
	assert.equal(compatible, true);
	assert.deepEqual(problems, []);
});

void test("from a 1.0 base, a deprecated operation stays until a major release", async () => {
	const minor = await compare(spec({ withDelete: false }), "", "v1.2.0", false, deprecatedDelete);
	assert.equal(minor.compatible, true, "oasdiff accepts the removal of a deprecated operation");
	assert.match(minor.problems.join("\n"), /in API DELETE \/items\/\{\}/u);
	const major = await compare(spec({ withDelete: false }), "", "v1.2.0", true, deprecatedDelete);
	assert.deepEqual(major.problems, []);
});

const withParameters = (parameters: string) => `paths:
  /items:
    parameters: [{ name: shared, in: query }]
    get:
      parameters: ${parameters}
`;

void test("a removed parameter counts as removed, wherever the spec declares it", () => {
	assert.deepEqual(
		removedFromContract(withParameters("[{ name: page, in: query }]"), withParameters("[]")),
		["in API GET /items parameter query page"],
	);
	assert.deepEqual(
		removedFromContract(withParameters("[]"), withParameters("[{ name: page, in: query }]")),
		[],
	);
});

const withVariable = (variable: string) => `paths:
  /items/{${variable}}:
    get:
      parameters: [{ name: ${variable}, in: path }]
`;

void test("renaming a path variable removes nothing", () => {
	assert.deepEqual(removedFromContract(withVariable("id"), withVariable("itemId")), []);
});

void test("an accepted break passes, and an entry that accepts nothing is obsolete", async () => {
	const stale = "in API GET /items/{id} api path removed without deprecation";
	const { compatible, obsolete, problems } = await compare(
		spec({ withDelete: false }),
		`# Why the endpoint goes.\n${removedDelete}\n${stale}\n`,
	);
	assert.equal(compatible, true);
	assert.deepEqual(obsolete, [stale]);
	assert.deepEqual(problems, []);
});

/** A checkout at `version` whose pending changeset, if any, bumps `hephaestus` by `bump`. */
async function checkout(version: string, bump?: "major" | "minor") {
	fixtures += 1;
	const root = path.join(directory, `checkout-${fixtures}`);
	await mkdir(path.join(root, ".changeset"), { recursive: true });
	await writeFile(path.join(root, "package.json"), JSON.stringify({ version }));
	if (bump !== undefined) {
		await writeFile(
			path.join(root, ".changeset", "api-break.md"),
			`---\n"hephaestus": ${bump}\n---\n\nThe delete endpoint is gone.\n`,
		);
	}
	return root;
}

async function compareWith1x(root: string, acceptedBreaks = `${removedDelete}\n`) {
	return compare(
		spec({ withDelete: false }),
		acceptedBreaks,
		"v1.2.0",
		await headsForMajorRelease(root, "v1.2.0"),
	);
}

void test("from a 1.0 base, an accepted break fails the gate outside a major release", async () => {
	const { compatible, problems } = await compareWith1x(await checkout("1.2.0", "minor"));
	assert.equal(compatible, true, "oasdiff accepts the listed break");
	assert.match(problems.join("\n"), /From 1\.0 a break needs a major release/u);
});

void test("from a 1.0 base, a pending major changeset lets an accepted break pass", async () => {
	const { compatible, problems } = await compareWith1x(await checkout("1.2.0", "major"));
	assert.equal(compatible, true);
	assert.deepEqual(problems, []);
});

void test("from a 1.0 base, a higher package.json major lets an accepted break pass", async () => {
	const { compatible, problems } = await compareWith1x(await checkout("2.0.0"));
	assert.equal(compatible, true);
	assert.deepEqual(problems, []);
});

void test("a major changeset that pre mode consumed heads for no release after that one", async () => {
	const root = await checkout("1.0.0");
	await mkdir(path.join(root, ".changeset", "pre"), { recursive: true });
	await writeFile(
		path.join(root, ".changeset", "pre", "release-1-0-0.md"),
		'---\n"hephaestus": major\n---\n\nHephaestus 1.0.\n',
	);
	assert.equal(await headsForMajorRelease(root, "v1.0.0"), false);
});

void test("from a 1.0 base, an obsolete entry does not fail the gate", async () => {
	const stale = "in API GET /items/{id} api path removed without deprecation";
	const { compatible, obsolete, problems } = await compare(spec(), `${stale}\n`, "v1.2.0");
	assert.equal(compatible, true);
	assert.deepEqual(obsolete, [stale]);
	assert.deepEqual(problems, []);
});

void test("a comment that holds a break's line fails the gate", async () => {
	const { compatible, problems } = await compare(
		spec({ withDelete: false }),
		`# Disabled: ${removedDelete}\n`,
	);
	assert.equal(compatible, true, "oasdiff accepts the break through the comment");
	assert.match(problems.join("\n"), /A comment accepts a break/u);
});

void test("the base is the newest stable release tag", () => {
	const listing = [
		"a\trefs/tags/v0.9.9",
		"b\trefs/tags/v0.10.0",
		"c\trefs/tags/v0.11.0-rc.1",
		"d\trefs/tags/v0.10.0-rc.3",
		"e\trefs/tags/release-1",
	].join("\n");
	assert.equal(latestStableTag(listing), "v0.10.0");
	assert.throws(() => latestStableTag("a\trefs/tags/v1.0.0-rc.1\n"), /no stable/u);
});

void test("oasdiff output that changed shape is refused", () => {
	const change = { text: "t", level: 3, operation: "GET", path: "/a" };
	assert.deepEqual(breakLines(JSON.stringify([change, { ...change, level: 2 }])), [
		"in API GET /a t",
	]);
	for (const malformed of [
		{ ...change, level: 4 },
		{ ...change, path: 1 },
		{ ...change, text: 1 },
	]) {
		assert.throws(() => breakLines(JSON.stringify([malformed])), TypeError);
	}
	assert.throws(() => breakLines("{}"), TypeError);
});
