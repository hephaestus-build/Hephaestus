import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { environmentForGitFixture } from "./lib/git-environment.ts";

import {
	SOURCE_CATALOGERS,
	archiveReleaseSource,
	validateReleaseSourceBundle,
	validateReleaseSourceSbom,
} from "./release-source-sbom.ts";

const commit = "a".repeat(40);
const repository = "hephaestus-build/Hephaestus";
const source = { type: "file", name: repository, version: commit };
const artifact = {
	name: "actions/checkout",
	version: "v7.0.1",
	type: "github-action",
	locations: [],
	metadata: { value: `actions/checkout@${"b".repeat(40)}` },
};
const sbom = {
	source,
	descriptor: { configuration: { catalogers: { used: SOURCE_CATALOGERS } } },
	artifacts: [
		artifact,
		{ ...artifact, name: "owner/repo/.github/workflows/build.yml", type: "github-action-workflow" },
		{ name: "react", version: "19", type: "npm", locations: [] },
		{
			name: "spring",
			version: "4",
			type: "java-archive",
			locations: [{ path: "gradle-inputs/buildscript/gradle.lockfile" }],
		},
	],
};

await test("the source inventory is bound to the release repository and exact commit", () => {
	assert.doesNotThrow(() => validateReleaseSourceSbom(sbom, commit, repository));
	for (const changed of [
		{ ...source, name: "another/project" },
		{ ...source, version: "c".repeat(40) },
		{ ...source, type: "directory" },
	]) {
		assert.throws(
			() => validateReleaseSourceSbom({ ...sbom, source: changed }, commit, repository),
			/not bound/u,
		);
	}
	assert.throws(() => validateReleaseSourceSbom(sbom, "main", repository), /full release commit/u);
});

await test("omitted ecosystems, empty inventories, and unpinned remote actions fail verification", () => {
	assert.throws(
		() =>
			validateReleaseSourceSbom(
				{ ...sbom, descriptor: { configuration: { catalogers: { used: [] } } } },
				commit,
				repository,
			),
		/did not run/u,
	);
	assert.throws(
		() => validateReleaseSourceSbom({ ...sbom, artifacts: [] }, commit, repository),
		/no packages/u,
	);
	assert.throws(
		() =>
			validateReleaseSourceSbom(
				{ ...sbom, artifacts: [{ ...artifact, metadata: { value: "actions/checkout@v7" } }] },
				commit,
				repository,
			),
		/not pinned/u,
	);
	assert.throws(
		() =>
			validateReleaseSourceSbom(
				{ ...sbom, artifacts: [{ ...artifact, name: "" }] },
				commit,
				repository,
			),
		/incomplete/u,
	);
	assert.doesNotThrow(() =>
		validateReleaseSourceSbom(
			{
				...sbom,
				artifacts: [
					...sbom.artifacts.slice(1),
					{
						...artifact,
						name: "./.github/actions/setup-toolchain",
						version: "UNKNOWN",
						metadata: { value: "./.github/actions/setup-toolchain" },
					},
				],
			},
			commit,
			repository,
		),
	);
});

await test("missing ecosystem and buildscript evidence fail even if their catalogers ran", () => {
	for (const type of ["npm", "java-archive", "github-action", "github-action-workflow"]) {
		assert.throws(
			() =>
				validateReleaseSourceSbom(
					{ ...sbom, artifacts: sbom.artifacts.filter((item) => item.type !== type) },
					commit,
					repository,
				),
			/ecosystem/u,
		);
	}
	assert.throws(
		() =>
			validateReleaseSourceSbom(
				{ ...sbom, artifacts: sbom.artifacts.map((item) => ({ ...item, locations: [] })) },
				commit,
				repository,
			),
		/buildscript lock/u,
	);
});

await test("source standard formats preserve the source subject and every captured package", () => {
	const spdx = {
		spdxVersion: "SPDX-2.3",
		dataLicense: "CC0-1.0",
		documentNamespace: "https://example.com/source",
		packages: [
			{ name: repository, versionInfo: commit, primaryPackagePurpose: "FILE" },
			{ name: "react", versionInfo: "19" },
			{ name: "spring", versionInfo: "4" },
			{ name: "actions/checkout", versionInfo: "v7.0.1" },
			{ name: "owner/repo/.github/workflows/build.yml", versionInfo: "v7.0.1" },
		],
	};
	const cdx = {
		bomFormat: "CycloneDX",
		specVersion: "1.6",
		metadata: { component: { type: "file", name: repository, version: commit } },
		components: [
			{ name: "react", version: "19" },
			{ name: "spring", version: "4" },
			{ name: "actions/checkout", version: "v7.0.1" },
			{ name: "owner/repo/.github/workflows/build.yml", version: "v7.0.1" },
		],
	};
	assert.doesNotThrow(() => validateReleaseSourceBundle(sbom, spdx, cdx, commit, repository));
	assert.throws(
		() =>
			validateReleaseSourceBundle(
				sbom,
				{ ...spdx, packages: spdx.packages.slice(0, -1) },
				cdx,
				commit,
				repository,
			),
		/SPDX output omitted/u,
	);
	assert.throws(
		() =>
			validateReleaseSourceBundle(
				sbom,
				spdx,
				{ ...cdx, components: cdx.components.slice(0, -1) },
				commit,
				repository,
			),
		/CycloneDX output omitted/u,
	);
	assert.throws(
		() =>
			validateReleaseSourceBundle(
				sbom,
				spdx,
				{
					...cdx,
					metadata: { component: { type: "file", name: repository, version: "d".repeat(40) } },
				},
				commit,
				repository,
			),
		/not bound/u,
	);
});

await test("an unpinned reusable workflow fails even when all ecosystems are present", () => {
	const artifacts = [
		...sbom.artifacts,
		{
			...artifact,
			type: "github-action-workflow",
			metadata: { value: "owner/repo/.github/workflows/build.yml@main" },
		},
	];
	assert.throws(
		() => validateReleaseSourceSbom({ ...sbom, artifacts }, commit, repository),
		/not pinned/u,
	);
});

await test("native archive aliases preserve both committed plugin locks byte for byte", async (context) => {
	const directory = await mkdtemp(path.join(tmpdir(), "source-archive-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const env = environmentForGitFixture();
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd: directory, env, encoding: "utf8" }).trim();
	await mkdir(path.join(directory, "server"));
	const locks = {
		buildscript: "example.group:plugin:1.0.0=classpath\nempty=\n",
		settings: "empty=injectedPluginClasspath\n",
	};
	for (const [name, content] of Object.entries(locks)) {
		await writeFile(path.join(directory, `server/${name}-gradle.lockfile`), content);
	}
	git("init", "-q");
	git("add", ".");
	git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "fixture");
	const revision = git("rev-parse", "HEAD");
	await writeFile(path.join(directory, "server/buildscript-gradle.lockfile"), "dirty checkout");
	const archive = path.join(directory, "source.tar");
	await archiveReleaseSource(revision, archive, directory);
	for (const [name, content] of Object.entries(locks)) {
		assert.equal(
			execFileSync("tar", ["-xOf", "source.tar", `gradle-inputs/${name}/gradle.lockfile`], {
				cwd: directory,
				encoding: "utf8",
			}),
			content,
		);
	}
});
