import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, rm, writeFile, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, parseJson } from "./lib/json.ts";
import { releaseRepository } from "./lib/release-identities.ts";
import {
	addOwnedComponents,
	assertOwnedCoverage,
	ownedComponent,
	scanOwnedCode,
} from "./owned-code-sbom.ts";
import { scanOwnedSource } from "./owned-source-sbom.ts";
import { SOURCE_CATALOGERS } from "./release-source-sbom.ts";

const file = "webapp/src/components/gitlab-icons.tsx";
const header = `/**
 * Owned from: https://gitlab.com/gitlab-org/gitlab-svgs/-/blob/v3.164.0/sprite_icons.svg
 * Package: pkg:npm/%40gitlab/svgs@3.164.0
 * License: MIT
 * Changes: Keep two icon paths.
 *
 * Copyright upstream. License notice stays here.
 */
export const path = "example";
`;

await test("owned source retains its exact content, upstream identity, license and changes", () => {
	const component = ownedComponent(file, header);
	assert.ok(component);
	assert.equal(component.name, file);
	const hash = createHash("sha256").update(header).digest("hex");
	assert.deepEqual(component.hashes, [{ alg: "SHA-256", content: hash }]);
	assert.equal(component.pedigree.ancestors[0]?.purl, "pkg:npm/%40gitlab/svgs@3.164.0");
	assert.equal(component.pedigree.ancestors[0].name, "@gitlab/svgs");
	assert.deepEqual(component.licenses, [{ expression: "MIT" }]);
	assert.equal(component.pedigree.notes, "Keep two icon paths.");
	assert.equal(
		component.pedigree.ancestors[0].externalReferences[0]?.url,
		"https://gitlab.com/gitlab-org/gitlab-svgs/-/blob/v3.164.0/sprite_icons.svg",
	);
	const cdx = addOwnedComponents({ bomFormat: "CycloneDX", components: [] }, [component]);
	assert.doesNotThrow(() => assertOwnedCoverage(cdx, [component]));
	assert.throws(
		() => assertOwnedCoverage({ ...cdx, components: [] }, [component]),
		/owned-code entry/u,
	);
	assert.throws(
		() =>
			assertOwnedCoverage({ ...cdx, components: [{ ...component, pedigree: {} }] }, [component]),
		/owned-code entry/u,
	);
	assert.throws(
		() => assertOwnedCoverage({ ...cdx, components: [component, component] }, [component]),
		/owned-code entry/u,
	);
});

await test("unexpected stale owned entries and invalid UTF-8 fail closed", () => {
	const component = ownedComponent(file, header);
	assert.ok(component);
	assert.throws(
		() => assertOwnedCoverage({ components: [component] }, []),
		/unexpected owned-code entry/u,
	);
	assert.throws(
		() => ownedComponent(file, Buffer.concat([Buffer.from(header), Buffer.from([255])])),
		/UTF-8/u,
	);
});

await test("a detected header fails closed when malformed or not at the start", () => {
	for (const changed of [
		header.replace(" * License: MIT\n", ""),
		header.replace(" * License: MIT", " * License: MIT\n * License:"),
		header.replace(" * License: MIT", " * License:"),
		header.replace(" * Changes: Keep two icon paths.", " * Changes: "),
		header.replace(" * Package:", " * Changes:"),
		header.replace("Owned from:", "Owned From:"),
		header.replace("Owned from:", "owned from:"),
		header.replace("Owned from:", "Ownedfrom:"),
		header.replace("https:", "http:"),
		header.replace("pkg:npm/%40gitlab/svgs@3.164.0", "pkg:npm/@gitlab/svgs@3.164.0"),
		header.replace("@3.164.0", "@latest"),
		header.replace("@3.164.0", ""),
		`export const before = true;\n${header}`,
		header.replace("/**", "/*"),
		`${header}\0`,
	]) {
		assert.throws(
			() => ownedComponent(file, changed),
			/webapp\/src\/components\/gitlab-icons\.tsx/u,
		);
	}
});

await test("CSS comments and SPDX expressions survive; test and story files are excluded", () => {
	const css = ownedComponent(
		"webapp/src/styles/owned.css",
		header.replace("/**", "/*").replace("License: MIT", "License: MIT OR Apache-2.0"),
	);
	assert.deepEqual(css?.licenses, [{ expression: "MIT OR Apache-2.0" }]);
	for (const excluded of [
		"scripts/lib/owned.ts",
		"webapp/src/foo.test.ts",
		"extension/src/foo.spec.ts",
		"docker/foo.stories.tsx",
		"server/application/src/main/test/foo.ts",
		"webapp/src/__tests__/foo.ts",
	]) {
		assert.equal(ownedComponent(excluded, "Owned from: broken"), undefined);
	}
	assert.equal(ownedComponent(file, `${"\n".repeat(40)}Owned from: ignored`), undefined);
	assert.throws(() => ownedComponent(file, `${"\n".repeat(39)}Owned from: detected`));
});

await test("release capture reads the exact commit, while admission reads new and changed files", async (context) => {
	const directory = await mkdtemp(path.join(tmpdir(), "owned-code-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const env = environmentForGitFixture();
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd: directory, env, encoding: "utf8" }).trim();
	await mkdir(path.join(directory, "webapp/src/components"), { recursive: true });
	await writeFile(path.join(directory, file), header);
	git("init", "-q");
	git("add", ".");
	git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "fixture");
	const commit = git("rev-parse", "HEAD");
	const [captured] = scanOwnedCode(commit, directory);
	assert.equal(captured?.hashes[0]?.content, ownedComponent(file, header)?.hashes[0]?.content);
	await writeFile(path.join(directory, file), `${header}// changed\n`);
	assert.notEqual(
		scanOwnedCode(undefined, directory)[0]?.hashes[0]?.content,
		captured?.hashes[0]?.content,
	);
	assert.deepEqual(scanOwnedCode(commit, directory), [captured]);
	await writeFile(path.join(directory, "webapp/src/new.ts"), "Owned from: incomplete");
	assert.throws(() => scanOwnedCode(undefined, directory), /new.ts/u);
});

await test(
	"capture writes owned pedigree and both capture-verifier modes reject a removed entry",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "owned-capture-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		const env = environmentForGitFixture();
		const git = (...args: string[]) =>
			execFileSync("git", args, { cwd: directory, env, encoding: "utf8" }).trim();
		await mkdir(path.join(directory, "webapp/src/components"), { recursive: true });
		await mkdir(path.join(directory, "server"));
		await writeFile(path.join(directory, file), header);
		for (const name of ["buildscript", "settings"]) {
			await writeFile(path.join(directory, `server/${name}-gradle.lockfile`), "empty=classpath\n");
		}
		const asset = "webapp/public/brand/mark.svg";
		await mkdir(path.join(directory, "webapp/public/brand"), { recursive: true });
		await writeFile(path.join(directory, asset), "<svg />\r\n");
		await writeFile(
			path.join(directory, "webapp/public/brand/NOTICE.md"),
			`Owned from: https://example.com/icons/v1.0.0
Package: pkg:npm/example@1.0.0
License: MIT
Changes: Keep one unmodified mark.
Files: ["mark.svg"]
`,
		);
		const patch = "docker/agents/pi/patches/@earendil-works__pi-ai@1.0.0.patch";
		await mkdir(path.join(directory, "docker/agents/pi/patches"), { recursive: true });
		await writeFile(path.join(directory, patch), "diff --git a/api.js b/api.js\n+diagnostics();\n");
		await writeFile(
			path.join(directory, "docker/agents/pi/patches/NOTICE.md"),
			`Owned from: https://github.com/earendil-works/pi/tree/a13d35a742c6ef8462812a28fbe1d8c8b7431c32/packages/ai
Package: pkg:npm/%40earendil-works/pi-ai@1.0.0
License: MIT
Changes: Keep the provider diagnostics patch.
Files: ["@earendil-works__pi-ai@1.0.0.patch"]
`,
		);

		git("init", "-q");
		git("add", ".");
		git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "fixture");
		const revision = git("rev-parse", "HEAD");
		const repository = releaseRepository("v0.95.2", env);
		const artifacts = [
			{ name: "react", version: "19", type: "npm", locations: [] },
			{
				name: "spring",
				version: "4",
				type: "java-archive",
				locations: [{ path: "gradle-inputs/buildscript/gradle.lockfile" }],
			},
			...["github-action", "github-action-workflow"].map((type) => ({
				name: type,
				version: "1",
				type,
				locations: [],
				metadata: { value: `owner/repo@${"a".repeat(40)}` },
			})),
		];
		const identity = { type: "file", name: repository, version: revision };
		const native = {
			source: identity,
			descriptor: { configuration: { catalogers: { used: SOURCE_CATALOGERS } } },
			artifacts,
		};
		const spdx = {
			spdxVersion: "SPDX-2.3",
			dataLicense: "CC0-1.0",
			documentNamespace: "https://example.com/source",
			packages: [
				{ name: repository, versionInfo: revision, primaryPackagePurpose: "FILE" },
				...artifacts.map((item) => ({ name: item.name, versionInfo: item.version })),
			],
		};
		const cdx = {
			bomFormat: "CycloneDX",
			specVersion: "1.7",
			metadata: { component: identity },
			components: artifacts.map((item) => ({ name: item.name, version: item.version })),
		};
		const bin = path.join(directory, "bin");
		const evidence = path.join(directory, "evidence");
		await mkdir(bin);
		await mkdir(evidence);
		const files = { "source.syft.json": native, "source.spdx.json": spdx, "source.cdx.json": cdx };
		await writeFile(
			path.join(bin, "syft"),
			`#!/usr/bin/env node\nconst fs = require("node:fs"); for (const [file, content] of Object.entries(${JSON.stringify(files)})) fs.writeFileSync(file, JSON.stringify(content));\n`,
			{ mode: 0o755 },
		);
		const module = pathToFileURL(path.join(import.meta.dirname, "release-source-sbom.ts")).href;
		const childEnv = { ...env, PATH: `${bin}${path.delimiter}${env.PATH ?? ""}` };
		execFileSync(
			process.execPath,
			[
				"--input-type=module",
				"-e",
				`import {captureReleaseSourceSbom} from ${JSON.stringify(module)}; await captureReleaseSourceSbom(${JSON.stringify(evidence)}, ${JSON.stringify(revision)}, "v0.95.2");`,
			],
			{ cwd: directory, env: childEnv },
		);
		const written = parseJson(await readFile(path.join(evidence, "source.cdx.json"), "utf8"));
		const expected = scanOwnedSource(revision, directory);
		assert.equal(expected.length, 3);
		assert.doesNotThrow(() => assertOwnedCoverage(written, expected));
		await writeFile(
			path.join(evidence, "manifest.json"),
			JSON.stringify({
				schemaVersion: 1,
				release: "v0.95.2",
				commit: revision,
				bundledNpmSbom: 1,
				sourceSbom: 1,
			}),
		);
		const verifier = pathToFileURL(
			path.join(import.meta.dirname, "verify-release-evidence.ts"),
		).href;
		for (const omitted of [asset, patch]) {
			await writeFile(
				path.join(evidence, "source.cdx.json"),
				JSON.stringify({
					...asRecord(written, "written BOM"),
					components: asArray(asRecord(written, "written BOM").components, "components").filter(
						(entry) => asRecord(entry, "component")["bom-ref"] !== `owned:${omitted}`,
					),
				}),
			);
			for (const mode of ["write-validation", "verify-capture"]) {
				assert.throws(
					() =>
						execFileSync(
							process.execPath,
							[
								"--input-type=module",
								"-e",
								`import {verifyReleaseEvidence} from ${JSON.stringify(verifier)}; verifyReleaseEvidence(${JSON.stringify(evidence)}, ${JSON.stringify(mode)});`,
							],
							{ cwd: directory, env: childEnv, stdio: "pipe" },
						),
					(error: unknown) =>
						error instanceof Error && error.message.includes(`owned-code entry for ${omitted}`),
				);
			}
		}
	},
);
