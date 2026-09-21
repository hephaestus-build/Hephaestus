import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";

import { answerBlankSettings } from "./prepare-host-smoke-env.ts";

// `setup.sh` copies `.env.example` and fills in the secrets it generates; every setting the smoke
// has to answer is still blank in it, so the shipped example is the honest input for this.
const example = await readFile(
	path.join(import.meta.dirname, "..", "docker", "self-host", ".env.example"),
	"utf8",
);

await test("answers the settings a boot refuses to start without, and touches nothing else", () => {
	const answered = answerBlankSettings(example);
	const before = example.split("\n");
	const after = answered.split("\n");
	assert.equal(after.length, before.length);

	const changed = new Map<string, string>();
	for (const [index, line] of before.entries()) {
		if (after[index] === line) {
			continue;
		}
		const key = /^(?<key>\w+)=$/u.exec(line)?.groups?.key;
		assert.ok(
			key !== undefined,
			`only a blank setting may be answered, but line ${index + 1} was "${line}"`,
		);
		assert.match(String(after[index]), new RegExp(`^${key}=.+$`, "u"));
		changed.set(key, String(after[index]).slice(key.length + 1));
	}

	// Without these five the installation stops at boot: no hostname, no certificate contact, no
	// login provider, and nobody who can reach instance administration.
	assert.deepEqual([...changed.keys()].toSorted(), [
		"ACME_EMAIL",
		"APP_HOSTNAME",
		"GH_OAUTH_CLIENT_ID",
		"GH_OAUTH_CLIENT_SECRET",
		"HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS",
	]);
	// Placeholders, not credentials: a smoke boot authenticates against no provider, and every
	// hostname it names has to stay unresolvable.
	assert.match(String(changed.get("APP_HOSTNAME")), /\.invalid$/u);
	assert.match(String(changed.get("ACME_EMAIL")), /@[\w.-]+\.invalid$/u);
	assert.match(String(changed.get("HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS")), /^github:\d+$/u);
});

await test("refuses a setting the installer did not leave blank", () => {
	const supplied = example.replace(/^APP_HOSTNAME=$/mu, "APP_HOSTNAME=hephaestus.example");
	assert.throws(() => answerBlankSettings(supplied), /APP_HOSTNAME/u);
});
