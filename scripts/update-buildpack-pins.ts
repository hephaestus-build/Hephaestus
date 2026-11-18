/**
 * Opens one pull request that moves the server image to a newer JRE as soon as a Paketo builder ships it.
 *
 * The build supplies every buildpack dependency from `.github/ci-tools/buildpack-*.json`, and libpak
 * accepts a file only when its SHA-256 is the one that the buildpack's `buildpack.toml` declares. So a
 * newer JRE needs a newer builder and the pins of that builder's buildpacks, together. Renovate proposes
 * routine builder updates on its weekly schedule, after its minimum release age, and cannot write the
 * pins. This runs every hour. When the newest builder carries a JRE of the `.java-version` line that is
 * newer than the pin, it writes the builder digest and every changed pin, derived from the
 * `buildpack.toml` of the buildpack versions that the builder's metadata names.
 *
 * It never removes a mitigation: gate:toolchain decides whether the new JRE still needs one.
 */
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";

import { commitInput, createCommit } from "./commit-via-api.ts";
import { isSet, requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { pointBranch } from "./maintain-version-pr.ts";

export const BRANCH = "automation/jre-update";
const DESCRIPTOR = "server/application/project.toml";
const TOML_LIMIT_BYTES = 4 * 1024 * 1024;

export interface BuildpackDependency {
	readonly id: string;
	readonly version: string;
	readonly uri: string;
	readonly sha256: string;
	/** `amd64` or `arm64` from the purl, or `undefined` for a dependency of every architecture. */
	readonly arch?: string;
}

/** The `[[metadata.dependencies]]` tables of a `buildpack.toml`, each written as `key = "value"` lines. */
export function buildpackDependencies(toml: string): BuildpackDependency[] {
	return toml
		.split(/^\s*\[\[metadata\.dependencies\]\]\s*$/mu)
		.slice(1)
		.map((table) => {
			// Nested tables such as `[[metadata.dependencies.licenses]]` follow the dependency's own keys.
			const own = table.split(/^\s*\[/mu)[0] ?? "";
			const value = (key: string) =>
				new RegExp(`^\\s*${key}\\s*=\\s*"(?<value>[^"]*)"\\s*$`, "mu").exec(own)?.groups?.value;
			const id = value("id");
			const version = value("version");
			const uri = value("uri");
			const sha256 = value("sha256");
			if (id === undefined || version === undefined || uri === undefined || sha256 === undefined) {
				throw new Error(`a buildpack dependency lacks its id, version, uri or sha256:${own}`);
			}
			const arch = /[?&]arch=(?<arch>[a-z0-9]+)/u.exec(value("purl") ?? "")?.groups?.arch;
			return arch === undefined ? { id, version, uri, sha256 } : { id, version, uri, sha256, arch };
		});
}

const ASSET_KEYS: Readonly<Record<string, string>> = { amd64: "linux-x64", arm64: "linux-arm64" };

const versionParts = (version: string): number[] => version.split(/[._+-]/u).map(Number);

function newer(left: string, right: string): number {
	const a = versionParts(left);
	const b = versionParts(right);
	for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
		const difference = (a[index] ?? 0) - (b[index] ?? 0);
		if (difference !== 0) {
			return difference;
		}
	}
	return 0;
}

export interface Pin {
	readonly version: string;
	readonly url: string;
	readonly assets: Readonly<Record<string, { readonly name: string; readonly sha256: string }>>;
}

/**
 * The pin of the newest `dependency` of a `line` (`25` for the JRE, `2` for a library major). Its version
 * is the release directory of the URI, so a Liberica build keeps its build number (`25.0.5.0.3+5`), written
 * with `_` because an OCI tag cannot carry `+`.
 */
export function derivePin(
	dependencies: readonly BuildpackDependency[],
	dependency: string,
	line: string,
): Pin {
	const candidates = dependencies.filter(
		(entry) =>
			entry.id === dependency &&
			entry.version.startsWith(`${line}.`) &&
			(entry.arch === undefined || Object.hasOwn(ASSET_KEYS, entry.arch)),
	);
	const newest = candidates
		.map((entry) => entry.version)
		.toSorted(newer)
		.at(-1);
	if (newest === undefined) {
		throw new Error(`the buildpack declares no ${dependency} of line ${line}`);
	}
	const chosen = candidates.filter((entry) => entry.version === newest);
	const directories = new Set(
		chosen.map((entry) => entry.uri.slice(0, entry.uri.lastIndexOf("/") + 1)),
	);
	const [url] = directories;
	if (directories.size !== 1 || url === undefined) {
		throw new Error(`the ${dependency} ${newest} files are not in one release directory`);
	}
	const release = url.slice(0, -1).split("/").at(-1) ?? "";
	const assets: Record<string, { name: string; sha256: string }> = {};
	for (const entry of chosen) {
		const key = entry.arch === undefined ? "all" : (ASSET_KEYS[entry.arch] ?? "all");
		if (Object.hasOwn(assets, key)) {
			throw new Error(`the buildpack declares ${dependency} ${newest} twice for ${key}`);
		}
		assets[key] = { name: entry.uri.slice(url.length), sha256: entry.sha256 };
	}
	return { version: release.replace(/^v/u, "").replaceAll("+", "_"), url, assets };
}

/** The pins this workflow keeps in step with the builder, and the buildpack that declares each. */
export const PINS = [
	{
		pin: "buildpack-liberica-jre",
		buildpack: "paketo-buildpacks/bellsoft-liberica",
		dependency: "jre",
	},
	{
		pin: "buildpack-spring-cloud-bindings",
		buildpack: "paketo-buildpacks/spring-boot",
		dependency: "spring-cloud-bindings",
	},
	{ pin: "buildpack-syft", buildpack: "paketo-buildpacks/syft", dependency: "syft" },
] as const;

/** The buildpack versions that a builder image's `io.buildpacks.builder.metadata` label names. */
export function builderBuildpacks(metadata: unknown): Map<string, string> {
	return new Map(
		asArray(asRecord(metadata, "builder metadata").buildpacks, "builder buildpacks").map(
			(value, index) => {
				const buildpack = asRecord(value, `builder buildpacks[${index}]`);
				return [
					asString(buildpack.id, "buildpack id"),
					asString(buildpack.version, "buildpack version"),
				] as const;
			},
		),
	);
}

export function pullRequestTitle(jre: string): string {
	return `fix(deps): the server image runs the Liberica JRE ${jre}`;
}

async function fetchText(url: string): Promise<string> {
	const response = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(30_000) });
	if (response.status !== 200 || response.body === null) {
		throw new Error(`${url} answered ${response.status}`);
	}
	const chunks: Uint8Array[] = [];
	let size = 0;
	for await (const chunk of response.body) {
		size += chunk.byteLength;
		if (size > TOML_LIMIT_BYTES) {
			throw new Error(`${url} exceeds ${TOML_LIMIT_BYTES} bytes`);
		}
		chunks.push(chunk);
	}
	return Buffer.concat(chunks).toString("utf8");
}

const pinFile = (id: string): string => `.github/ci-tools/${id}.json`;

async function readPin(id: string): Promise<Pin> {
	const pin = asRecord(parseJson(await readFile(pinFile(id), "utf8")), id);
	return {
		version: asString(pin.version, `${id} version`),
		url: asString(pin.url, `${id} url`),
		assets: Object.fromEntries(
			Object.entries(asRecord(pin.assets, `${id} assets`)).map(([key, value]) => {
				const asset = asRecord(value, `${id} ${key}`);
				return [
					key,
					{ name: asString(asset.name, "name"), sha256: asString(asset.sha256, "sha256") },
				];
			}),
		),
	};
}

/** Each downloaded file must match the SHA-256 that the buildpack declares; libpak checks it again. */
async function verifyDownloads(pin: Pin): Promise<void> {
	for (const asset of Object.values(pin.assets)) {
		const response = await fetch(`${pin.url}${asset.name}`, {
			signal: AbortSignal.timeout(300_000),
		});
		if (!response.ok) {
			throw new Error(`${pin.url}${asset.name} answered ${response.status}`);
		}
		const digest = createHash("sha256")
			.update(Buffer.from(await response.arrayBuffer()))
			.digest("hex");
		if (digest !== asset.sha256) {
			throw new Error(`${asset.name} has SHA-256 ${digest}, not the declared ${asset.sha256}`);
		}
	}
}

async function main(): Promise<void> {
	const descriptor = await readFile(DESCRIPTOR, "utf8");
	const pinned = /^builder = "(?<repository>[^"@]+)@(?<digest>sha256:[a-f0-9]{64})"$/mu.exec(
		descriptor,
	)?.groups;
	if (pinned?.repository === undefined || pinned.digest === undefined) {
		throw new Error(`${DESCRIPTOR} pins no builder by digest`);
	}
	const inspected = await output("docker", [
		"buildx",
		"imagetools",
		"inspect",
		`${pinned.repository}:latest`,
		"--format",
		"{{.Manifest.Digest}}",
	]);
	const latest = inspected.trim();
	if (!/^sha256:[a-f0-9]{64}$/u.test(latest)) {
		throw new Error(`no digest for ${pinned.repository}:latest (got ${latest})`);
	}
	const image = asRecord(
		parseJson(
			await output("docker", [
				"buildx",
				"imagetools",
				"inspect",
				`${pinned.repository}@${latest}`,
				"--format",
				"{{json .Image}}",
			]),
		),
		"builder image",
	);
	const labels = asRecord(
		asRecord(asRecord(image["linux/amd64"], "linux/amd64 image").config, "image config").Labels,
		"image labels",
	);
	const buildpacks = builderBuildpacks(
		parseJson(asString(labels["io.buildpacks.builder.metadata"], "builder metadata label")),
	);
	const javaVersion = await readFile(".java-version", "utf8");
	const javaLine = javaVersion.trim();
	const changes: { id: string; from: Pin; to: Pin }[] = [];
	for (const { pin, buildpack, dependency } of PINS) {
		const version = buildpacks.get(buildpack);
		if (version === undefined) {
			throw new Error(`the builder ${latest} carries no ${buildpack}`);
		}
		const current = await readPin(pin);
		const line =
			pin === "buildpack-liberica-jre" ? javaLine : (current.version.split(".")[0] ?? "");
		const toml = await fetchText(
			`https://raw.githubusercontent.com/${buildpack}/v${version}/buildpack.toml`,
		);
		const next = derivePin(buildpackDependencies(toml), dependency, line);
		if (JSON.stringify(next) !== JSON.stringify(current)) {
			changes.push({ id: pin, from: current, to: next });
		}
	}
	const jre = changes.find((change) => change.id === "buildpack-liberica-jre");
	if (jre === undefined || newer(jre.to.version, jre.from.version) <= 0) {
		process.stdout.write(
			`The newest builder carries no JRE newer than the pin; Renovate proposes routine builder updates.\n`,
		);
		return;
	}
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const title = pullRequestTitle(jre.to.version);
	const open = asArray(
		parseJson(
			await output("gh", [
				"pr",
				"list",
				"--repo",
				repository,
				"--head",
				BRANCH,
				"--state",
				"open",
				"--json",
				"number,title",
			]),
		),
		"gh pr list",
	).map((value) => asRecord(value, "pull request"));
	if (open.some((pull) => pull.title === title)) {
		process.stdout.write(`The pull request for JRE ${jre.to.version} is already open.\n`);
		return;
	}
	for (const change of changes) {
		await verifyDownloads(change.to);
		await writeFile(pinFile(change.id), `${JSON.stringify(change.to, null, "\t")}\n`);
	}
	await writeFile(
		DESCRIPTOR,
		descriptor.replace(`${pinned.repository}@${pinned.digest}`, `${pinned.repository}@${latest}`),
	);
	const files = [DESCRIPTOR, ...changes.map((change) => pinFile(change.id))];
	const head = await output("git", ["rev-parse", "HEAD"]);
	const base = head.trim();
	await pointBranch(repository, BRANCH, base);
	const body = [
		`The newest \`${pinned.repository}\` (\`${latest}\`) carries the Liberica JRE ${jre.to.version}; the image pins ${jre.from.version}.`,
		"",
		"| Pin | From | To |",
		"| --- | --- | --- |",
		...changes.map(
			(change) => `| \`${change.id}\` | ${change.from.version} | ${change.to.version} |`,
		),
		"",
		"Each SHA-256 is the one that the buildpack's `buildpack.toml` declares, and each file was downloaded and checked against it.",
		"gate:toolchain decides whether `server/application/project.toml` must keep a JVM mitigation for this JRE.",
		"The changeset is yours to add: name the fixed CVEs in the operator's voice.",
		"",
		"Opened by `.github/workflows/jvm-watch.yml` (`scripts/update-buildpack-pins.ts`), which also dispatched CI/CD on this branch. The pull_request runs wait for approval and are not required.",
	].join("\n");
	const commit = await createCommit(
		requiredEnv(process.env, "GH_TOKEN"),
		commitInput(
			{ repository, branch: BRANCH, expectedHeadOid: base, headline: title },
			new Map(await Promise.all(files.map(async (file) => [file, await readFile(file)] as const))),
			[],
		),
	);
	const existing = open[0] === undefined ? undefined : String(open[0].number);
	await (existing === undefined
		? run("gh", [
				"pr",
				"create",
				"--repo",
				repository,
				"--head",
				BRANCH,
				"--base",
				"main",
				"--title",
				title,
				"--body",
				body,
			])
		: run("gh", ["pr", "edit", existing, "--repo", repository, "--title", title, "--body", body]));
	// GitHub holds the pull_request runs of a GITHUB_TOKEN push for approval, but it starts a workflow_dispatch
	// run at once. That run reports `CI Status Gate`, the one required context, on the head commit.
	await run("gh", ["workflow", "run", "cicd.yml", "--repo", repository, "--ref", BRANCH]);
	process.stdout.write(`Proposed JRE ${jre.to.version} in ${commit.description}.\n`);
	const summaryPath = process.env.GITHUB_STEP_SUMMARY;
	if (isSet(summaryPath)) {
		await writeFile(summaryPath, `### JRE update\n\nProposed JRE ${jre.to.version}.\n`, {
			flag: "a",
		});
	}
}

if (import.meta.main) {
	await main();
}
