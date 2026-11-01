import { copyFile, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { assertSbomFormatsCover, SYFT_CONFIG } from "./check-release-sbom.ts";
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { releaseRepository } from "./lib/release-identities.ts";

export const SOURCE_SBOM_FORMATS = [
	["syft-json", "source.syft.json"],
	["spdx-json", "source.spdx.json"],
	["cyclonedx-json", "source.cdx.json"],
] as const;
export const SOURCE_CATALOGERS = [
	"javascript-lock-cataloger",
	"java-gradle-lockfile-cataloger",
	"github-actions-usage-cataloger",
	"github-action-workflow-usage-cataloger",
];

export function validateReleaseSourceSbom(
	input: unknown,
	commit: unknown,
	repository: string,
): void {
	if (typeof commit !== "string" || !/^[a-f0-9]{40}$/u.test(commit)) {
		throw new Error("source SBOM requires a full release commit");
	}
	const sbom = asRecord(input, "source SBOM");
	const source = asRecord(sbom.source, "source SBOM source");
	if (source.type !== "file" || source.name !== repository || source.version !== commit) {
		throw new Error("source SBOM is not bound to the release repository and commit");
	}
	const configuration = asRecord(
		asRecord(sbom.descriptor, "descriptor").configuration,
		"configuration",
	);
	const used = asArray(asRecord(configuration.catalogers, "catalogers").used, "catalogers used");
	for (const cataloger of SOURCE_CATALOGERS) {
		if (!used.includes(cataloger)) {
			throw new Error(`source SBOM did not run ${cataloger}`);
		}
	}
	const artifacts = asArray(sbom.artifacts, "source artifacts");
	if (artifacts.length === 0) {
		throw new Error("source SBOM contains no packages");
	}
	for (const item of artifacts) {
		const artifact = asRecord(item, "source artifact");
		const name = asString(artifact.name, "artifact name");
		const version = asString(artifact.version, "artifact version");
		const type = asString(artifact.type, "artifact type");
		if (name.length === 0 || version.length === 0 || type.length === 0) {
			throw new Error("source SBOM package identity is incomplete");
		}
		if (type === "github-action" || type === "github-action-workflow") {
			// Syft uses a version comment for display; metadata.value retains the actual uses reference.
			const reference = asString(
				asRecord(artifact.metadata, "action metadata").value,
				"action reference",
			);
			if (!reference.startsWith("./") && !/@[a-f0-9]{40}$/u.test(reference)) {
				throw new Error(`source SBOM action ${name} is not pinned to a full commit`);
			}
		}
	}
	const types = new Set(artifacts.map((item) => asRecord(item, "artifact").type));
	for (const type of ["npm", "java-archive", "github-action", "github-action-workflow"]) {
		if (!types.has(type)) {
			throw new Error(`source SBOM omitted the ${type} ecosystem`);
		}
	}
	if (
		!artifacts.some((item) =>
			asArray(asRecord(item, "artifact").locations, "artifact locations").some(
				(location) =>
					asRecord(location, "location").path === "gradle-inputs/buildscript/gradle.lockfile",
			),
		)
	) {
		throw new Error("source SBOM omitted the buildscript lock");
	}
}

export function validateReleaseSourceBundle(
	syft: unknown,
	spdx: unknown,
	cycloneDx: unknown,
	commit: unknown,
	repository: string,
): void {
	validateReleaseSourceSbom(syft, commit, repository);
	assertSbomFormatsCover(
		syft,
		spdx,
		cycloneDx,
		repository,
		asString(commit, "source commit"),
		"file",
	);
}

/** Preserve the exact native plugin lock bytes under names that Syft recognizes. */
export async function archiveReleaseSource(
	commit: string,
	archive: string,
	directory = process.cwd(),
): Promise<void> {
	// Syft recognizes only gradle.lockfile. Add the two native plugin locks under that basename.
	const aliases: string[] = [];
	for (const name of ["buildscript", "settings"]) {
		const lock = await output("git", ["show", `${commit}:server/${name}-gradle.lockfile`], {
			cwd: directory,
		});
		aliases.push(`--add-virtual-file=gradle-inputs/${name}/gradle.lockfile:${lock}`);
	}
	await run("git", ["archive", "--format=tar", `--output=${archive}`, ...aliases, commit], {
		cwd: directory,
	});
}

/** Scan committed source, including development inputs, without reading an installed or dirty checkout. */
export async function captureReleaseSourceSbom(
	directory: string,
	commit: string,
	release: string,
): Promise<void> {
	if (!/^[a-f0-9]{40}$/u.test(commit)) {
		throw new Error("source SBOM requires a full release commit");
	}
	const repository = releaseRepository(release, process.env);
	const temporary = await mkdtemp(path.join(tmpdir(), "release-source-"));
	try {
		await archiveReleaseSource(commit, path.join(temporary, "source.tar"));
		await run(
			"syft",
			[
				"file:source.tar",
				"--config",
				SYFT_CONFIG,
				"--source-name",
				repository,
				"--source-version",
				commit,
				"--override-default-catalogers",
				SOURCE_CATALOGERS.join(","),
				"--select-catalogers=-file",
				...SOURCE_SBOM_FORMATS.flatMap(([format, file]) => ["-o", `${format}=${file}`]),
			],
			{ cwd: temporary },
		);
		const [syft, spdx, cycloneDx] = await Promise.all(
			SOURCE_SBOM_FORMATS.map(async ([, file]) => readJsonFile(path.join(temporary, file))),
		);
		validateReleaseSourceBundle(syft, spdx, cycloneDx, commit, repository);
		for (const [, file] of SOURCE_SBOM_FORMATS) {
			await copyFile(path.join(temporary, file), path.join(directory, file));
		}
	} finally {
		await rm(temporary, { recursive: true, force: true });
	}
}
