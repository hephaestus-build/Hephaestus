import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

await test("the session-rotation rollback warns operators about the remaining token lifetime", () => {
	const migration = readFileSync(
		"server/application/src/main/resources/db/changelog/1793168406889_changelog.xml",
		"utf8",
	);
	assert.match(migration, /rollback/iu);
	assert.match(migration, /24\s*(?:h|hours)/iu);
	assert.match(migration, /up to/iu);
});
