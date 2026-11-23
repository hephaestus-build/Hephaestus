import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { readCutoverSeeds } from "./lib/cutover-seeds.ts";

const ASSERTION =
	"DO $$ BEGIN IF EXISTS (SELECT FROM legacy) THEN RAISE EXCEPTION 'left'; END IF; END $$;";

function seedDirectory(files: Record<string, string>): string {
	const directory = mkdtempSync(path.join(tmpdir(), "cutover-seeds-"));
	for (const [name, content] of Object.entries(files)) {
		writeFileSync(path.join(directory, name), content);
	}
	return directory;
}

function withSeeds(files: Record<string, string>, check: (directory: string) => void): void {
	const directory = seedDirectory(files);
	try {
		check(directory);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

await test("a seed with an expected disposition reads", () => {
	withSeeds(
		{ "d10.seed.sql": "INSERT INTO legacy VALUES (1);", "d10.expect.sql": ASSERTION },
		(directory) => {
			assert.deepEqual(readCutoverSeeds(directory), [
				{ name: "d10", seed: "INSERT INTO legacy VALUES (1);", expect: ASSERTION },
			]);
		},
	);
});

await test("a seed that names no expected disposition fails the format check", () => {
	withSeeds({ "d10.seed.sql": "INSERT INTO legacy VALUES (1);" }, (directory) => {
		assert.throws(() => readCutoverSeeds(directory), /d10 names no expected disposition/u);
	});
});

await test("an expected disposition that cannot fail fails the format check", () => {
	withSeeds(
		{ "d10.seed.sql": "INSERT INTO legacy VALUES (1);", "d10.expect.sql": "SELECT 1;" },
		(directory) => {
			assert.throws(() => readCutoverSeeds(directory), /raises no exception/u);
		},
	);
});

await test("an assertion that only a comment names fails the format check", () => {
	withSeeds(
		{
			"d10.seed.sql": "INSERT INTO legacy VALUES (1);",
			"d10.expect.sql": "-- RAISE EXCEPTION when a row is left\n/* RAISE EXCEPTION */ SELECT 1;",
		},
		(directory) => {
			assert.throws(() => readCutoverSeeds(directory), /raises no exception/u);
		},
	);
});

await test("an assertion that only a string literal names fails the format check", () => {
	withSeeds(
		{
			"d10.seed.sql": "INSERT INTO legacy VALUES (1);",
			"d10.expect.sql": "SELECT 'RAISE EXCEPTION';",
		},
		(directory) => {
			assert.throws(() => readCutoverSeeds(directory), /raises no exception/u);
		},
	);
});

await test("an assertion that only a dollar-quoted string names fails the format check", () => {
	withSeeds(
		{
			"d10.seed.sql": "INSERT INTO legacy VALUES (1);",
			"d10.expect.sql": "SELECT $$DO RAISE EXCEPTION$$;",
		},
		(directory) => {
			assert.throws(() => readCutoverSeeds(directory), /raises no exception/u);
		},
	);
});

const hiddenAssertions: { name: string; expect: string }[] = [
	{ name: "a quoted semicolon", expect: "SELECT '; DO $$BEGIN RAISE EXCEPTION; END$$;';" },
	{
		name: "a raise inside a string of the DO body",
		expect: "DO $$BEGIN PERFORM $x$RAISE EXCEPTION$x$; END$$;",
	},
];
for (const { name, expect } of hiddenAssertions) {
	await test(`an assertion behind ${name} fails the format check`, () => {
		withSeeds(
			{ "d10.seed.sql": "INSERT INTO legacy VALUES (1);", "d10.expect.sql": expect },
			(directory) => {
				assert.throws(() => readCutoverSeeds(directory), /raises no exception/u);
			},
		);
	});
}

await test("an expected disposition without its seed fails the format check", () => {
	withSeeds({ "d10.expect.sql": ASSERTION, "notes.txt": "" }, (directory) => {
		assert.throws(
			() => readCutoverSeeds(directory),
			/d10\.expect\.sql has no seed[\s\S]*notes\.txt is neither/u,
		);
	});
});

await test("the committed seeds pass the format check", () => {
	assert.doesNotThrow(() => readCutoverSeeds());
});
