import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { stripTypeScriptTypes } from "node:module";
import { test } from "node:test";

// The Pi image runs host-check.ts as `.js` with Node's TypeScript loader off (docker/agents/pi/Dockerfile).
await test("the image's host check holds no type syntax", () => {
	const source = readFileSync(new URL("host-check.ts", import.meta.url), "utf8");
	assert.equal(stripTypeScriptTypes(source), source);
});
