import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { test } from "node:test";

import { browserImage } from "./ci-browser.ts";
import { asRecord, asString, parseJson } from "./lib/json.ts";

const pin = {
	repository: "mcr.microsoft.com/playwright",
	tag: "v1.64.1-noble",
	digest: `sha256:${"a".repeat(64)}`,
};

void test("the browser image must pin the official image and match the installed package", async () => {
	assert.equal(browserImage(pin, "1.64.1"), `${pin.repository}:${pin.tag}@${pin.digest}`);
	for (const invalid of [
		null,
		{},
		{ ...pin, repository: "other/playwright" },
		{ ...pin, digest: "latest" },
		{ ...pin, tag: "v1.63.0-noble" },
	]) {
		assert.throws(() => browserImage(invalid, "1.64.1"));
	}
	const pkg = asRecord(parseJson(await readFile("webapp/package.json", "utf8")), "webapp package");
	const version = asString(asRecord(pkg.devDependencies, "dependencies").playwright, "Playwright");
	assert.match(
		browserImage(parseJson(await readFile(".github/playwright-image.json", "utf8")), version),
		/@sha256:[a-f0-9]{64}$/u,
	);
});

async function inspect(directory: string): Promise<void> {
	for (const entry of await readdir(directory, { withFileTypes: true })) {
		const file = `${directory}/${entry.name}`;
		if (entry.isDirectory()) {
			await inspect(file);
		} else if (/\.ya?ml$/u.test(file)) {
			assert.doesNotMatch(
				await readFile(file, "utf8"),
				/\b(?:apt(?:-get)?|apk|yum|dnf)\s+(?:update|upgrade|install|add)\b|\binstall-deps\b|--with-deps/u,
				file,
			);
		}
	}
}

void test("workflows and local actions cannot install system packages at runtime", async () => {
	await inspect(".github/workflows");
	await inspect(".github/actions");
});
