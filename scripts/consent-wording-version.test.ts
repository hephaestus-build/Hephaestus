import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

function wordingVersion(path: string): string {
	const source = readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
	const version = /WORDING_VERSION = "(?<version>[^"]+)"/u.exec(source)?.groups?.version;
	return version ?? assert.fail(`${path} declares no WORDING_VERSION`);
}

await test("server and webapp name the same consent wording version", () => {
	assert.equal(
		wordingVersion("webapp/src/components/auth/consent-wording.tsx"),
		wordingVersion(
			"server/application/src/main/java/de/tum/cit/aet/hephaestus/core/auth/consent/ConsentService.java",
		),
		"a mismatch locks every account out of setup",
	);
});
