import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { describe, test } from "node:test";
import { findDrift, readTokens } from "./check-docs-tokens.ts";

// Real stylesheets preserve cascade shapes that synthetic fixtures miss.
const repositoryRoot = path.resolve(import.meta.dirname, "..");
const [docsCss, appCss] = await Promise.all([
	readFile(path.resolve(repositoryRoot, "docs/src/css/custom.css"), "utf8"),
	readFile(path.resolve(repositoryRoot, "webapp/src/styles.css"), "utf8"),
]);

await describe("readTokens", async () => {
	await test("takes the last declaration, the way the cascade does", () => {
		const tokens = readTokens(":root { --a: 1rem; }\n:root {\n\t--a: 2rem;\n}");
		assert.equal(tokens.light.get("--a"), "2rem");
	});

	await test("files a declaration under the theme of the block it sits in", () => {
		const tokens = readTokens(
			':root {\n\t--a: 1rem;\n}\n[data-theme="dark"] {\n\t--a: 2rem;\n}\n:root {\n\t--b: 3rem;\n}',
		);
		assert.equal(tokens.light.get("--a"), "1rem");
		assert.equal(tokens.dark.get("--a"), "2rem");
		assert.equal(tokens.light.get("--b"), "3rem");
	});
});

await describe("findDrift", async () => {
	await test("reports a recoloured app token the docs did not follow", () => {
		const recoloured = appCss.replace(
			"--mentor: oklch(0.707 0.165 254.624)",
			"--mentor: oklch(0.6 0.2 30)",
		);
		assert.ok(
			findDrift(docsCss, recoloured).includes(
				"dark --ifm-color-primary is oklch(0.707 0.165 254.624), but the dark --mentor it copies is oklch(0.6 0.2 30).",
			),
		);
	});

	await test("catches a re-declaration appended below the block it overrides", () => {
		assert.deepEqual(
			findDrift(`${docsCss}\n:root {\n\t--ifm-global-radius: 0.5rem;\n}\n`, appCss),
			["light --ifm-global-radius is 0.5rem, but the light --radius it copies is 0.625rem."],
		);
	});

	await test("reports a copied token renamed out of the docs stylesheet", () => {
		assert.deepEqual(findDrift(docsCss.replaceAll("--ifm-global-radius", "--ifm-radius"), appCss), [
			"docs/src/css/custom.css no longer declares --ifm-global-radius for the light theme.",
		]);
	});

	await test("reports a source token renamed out of the web app stylesheet", () => {
		assert.deepEqual(findDrift(docsCss, appCss.replaceAll("--radius:", "--corner:")), [
			"webapp/src/styles.css no longer declares --radius for the light theme.",
		]);
	});
});
