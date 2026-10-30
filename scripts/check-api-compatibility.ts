/**
 * Fails when `server/openapi.yaml` breaks a client of the previous stable release.
 *
 * A browser tab keeps the SPA of one release while the server runs the next, and the extension can
 * lag further, so the spec must stay compatible with the latest published release. oasdiff decides
 * what breaks, applies the accepted breaks and reports; this script resolves the base and names the
 * entries that accept nothing. docs/contributor/ci-cd.mdx § API compatibility owns the rules.
 *
 * Usage: vp run gate:api-compatibility [--base vX.Y.Z]
 */
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";

import { readChangesets } from "@changesets/read";

import { asArray, asRecord, asString, parseJson, readJsonFile } from "./lib/json.ts";
import { installPinnedBinary, type PinnedAsset } from "./lib/pinned-binary.ts";
import { CAPTURE_LIMIT_BYTES, exitStatus, output, run, succeeds } from "./lib/process.ts";
import { compareVersions, parseVersion } from "./plan-release.ts";

const ROOT = path.resolve(import.meta.dirname, "..");
export const SPEC = "server/openapi.yaml";
export const ACCEPTED_BREAKS = "server/openapi-accepted-breaks.txt";

const OASDIFF_VERSION = "1.34.1";
// The upstream checksums.txt and the GitHub release asset digests both state these values.
const DARWIN: PinnedAsset = {
	name: "oasdiff_1.34.1_darwin_all.tar.gz",
	sha256: "64948ba30c5555d3d15b95a7f3eda22fa83c15cf37fd4dad1720c0deb0a07cb5",
};
const OASDIFF_ASSETS: Readonly<Record<string, PinnedAsset>> = {
	"linux-x64": {
		name: "oasdiff_1.34.1_linux_amd64.tar.gz",
		sha256: "75775f65b0adebd26a8e013af51a799342dfdc6262d89efa9e3b7bdf89657d90",
	},
	"linux-arm64": {
		name: "oasdiff_1.34.1_linux_arm64.tar.gz",
		sha256: "e8f99bbf102a4b7f235104da72d0b9d67702e78622d1d4d37169e698ee90a249",
	},
	// One universal archive serves both macOS architectures.
	"darwin-x64": DARWIN,
	"darwin-arm64": DARWIN,
	"win32-x64": {
		name: "oasdiff_1.34.1_windows_amd64.tar.gz",
		sha256: "15e6f32ece94244534a61ce4fb1d60912e51e67e363a05bb6561d89b28df73bb",
	},
	"win32-arm64": {
		name: "oasdiff_1.34.1_windows_arm64.tar.gz",
		sha256: "4794300521a2ff76d14205f5141884e6f6052212484caf1d3655c149b314492c",
	},
};

export async function installOasdiff(): Promise<string> {
	const asset = OASDIFF_ASSETS[`${process.platform}-${process.arch}`];
	if (asset === undefined) {
		throw new Error(
			`oasdiff ${OASDIFF_VERSION} has no pinned archive for ${process.platform}-${process.arch}.`,
		);
	}
	return installPinnedBinary({
		cache: path.join(ROOT, ".cache", "oasdiff"),
		version: OASDIFF_VERSION,
		asset,
		url: `https://github.com/oasdiff/oasdiff/releases/download/v${OASDIFF_VERSION}/`,
		executable: process.platform === "win32" ? "oasdiff.exe" : "oasdiff",
	});
}

/**
 * The ERR-level changes in `oasdiff breaking --format json` output, each as the line that accepts
 * it: oasdiff's GitHub Actions message, `in API <method> <path> <change text>`.
 */
export function breakLines(json: string): string[] {
	return asArray(parseJson(json), "oasdiff output").flatMap((value, index) => {
		const label = `oasdiff change ${index}`;
		const change = asRecord(value, label);
		const { level } = change;
		if (level !== 1 && level !== 2 && level !== 3) {
			throw new TypeError(`${label}.level must be 1, 2 or 3, but is ${String(level)}`);
		}
		const text = asString(change.text, `${label}.text`);
		const operation =
			change.operation === undefined ? "" : asString(change.operation, `${label}.operation`);
		const endpoint = change.path === undefined ? "" : asString(change.path, `${label}.path`);
		return level === 3 ? [`in API ${operation} ${endpoint} ${text}`] : [];
	});
}

/** The entries of the accepted-breaks file: every line that is not blank or a `#` comment. */
function acceptedEntries(file: string): string[] {
	return file
		.split(/\r?\n/u)
		.map((line) => line.trim())
		.filter((line) => line !== "" && !line.startsWith("#"));
}

/** The newest stable `vX.Y.Z` tag in `git ls-remote --tags --refs` output. */
export function latestStableTag(listing: string): string {
	let latest: { tag: string; version: NonNullable<ReturnType<typeof parseVersion>> } | undefined;
	for (const line of listing.split("\n")) {
		const tag = /\trefs\/tags\/(?<tag>v\S+)$/u.exec(line.trim())?.groups?.tag;
		const version = tag === undefined ? undefined : parseVersion(tag.slice(1));
		if (
			tag !== undefined &&
			version !== undefined &&
			version.rc === undefined &&
			(latest === undefined || compareVersions(version, latest.version) > 0)
		) {
			latest = { tag, version };
		}
	}
	if (latest === undefined) {
		throw new Error("origin has no stable vX.Y.Z release tag to compare with");
	}
	return latest.tag;
}

export interface Compatibility {
	/** Whether every ERR-level change is accepted. */
	readonly compatible: boolean;
	/** oasdiff's report in `format`. */
	readonly report: string;
	/** Entries that are not the line of a break of this comparison. */
	readonly obsolete: string[];
	/** Faults of the accepted-breaks file itself, each of which fails the gate. */
	readonly problems: string[];
}

/** The major version of a `vX.Y.Z` release tag. */
const majorOf = (tag: string): number => parseVersion(tag.slice(1))?.major ?? 0;

/**
 * Whether the checkout at `rootDir` heads for a major release after `baseTag`. Before the Version
 * PR, a pending changeset bumps `hephaestus` by `major`. From the Version PR on, it has consumed
 * that changeset and `package.json` carries the higher major.
 */
export async function headsForMajorRelease(rootDir: string, baseTag: string): Promise<boolean> {
	const changesets = await readChangesets(rootDir);
	if (
		changesets.some(({ releases }) =>
			releases.some(({ name, type }) => name === "hephaestus" && type === "major"),
		)
	) {
		return true;
	}
	const root = asRecord(await readJsonFile(path.join(rootDir, "package.json")), "package.json");
	const version = asString(root.version, "package.json version");
	const parsed = parseVersion(version);
	if (parsed === undefined) {
		throw new Error(`package.json version ${version} is not a stable X.Y.Z version`);
	}
	return parsed.major > majorOf(baseTag);
}

/**
 * From 1.0 a break needs a major release, so outside one no entry may accept a break. An obsolete
 * entry accepts nothing, so it does not count. A comment that contains a break's line accepts it
 * too, because oasdiff reads every line, so the file must not hide one there.
 */
function acceptedBreaksProblems(
	file: string,
	baseTag: string,
	breaks: ReadonlySet<string>,
	majorRelease: boolean,
) {
	const problems: string[] = [];
	const accepting = acceptedEntries(file).filter((entry) => breaks.has(entry));
	if (majorOf(baseTag) >= 1 && !majorRelease && accepting.length > 0) {
		problems.push(
			`From 1.0 a break needs a major release. ${accepting.length} entries in ${ACCEPTED_BREAKS} accept a break of ${baseTag}. Add a major changeset, or make the change compatible.`,
		);
	}
	const lines = [...breaks].map((line) => line.toLowerCase());
	for (const comment of file.split(/\r?\n/u).filter((line) => line.trim().startsWith("#"))) {
		if (lines.some((line) => comment.toLowerCase().includes(line))) {
			problems.push(
				`A comment accepts a break as an entry would. Reword or delete it: ${comment.trim()}`,
			);
		}
	}
	return problems;
}

function oasdiffBreaking(oasdiff: string, args: readonly string[], cwd: string) {
	const result = spawnSync(oasdiff, ["breaking", ...args], {
		cwd,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
	if (result.error !== undefined) {
		throw result.error;
	}
	return result;
}

/** `base` and `revision` are paths from `cwd`, which is also where oasdiff looks for `.oasdiff.*`. */
export async function compareSpecs(options: {
	oasdiff: string;
	base: string;
	/** The release tag that `base` was read from. */
	baseTag: string;
	/** Whether the change heads for a major release after `baseTag`, which may break it. */
	majorRelease: boolean;
	revision: string;
	acceptedBreaks: string;
	format: "githubactions" | "text";
	cwd: string;
}): Promise<Compatibility> {
	const { oasdiff, base, baseTag, majorRelease, revision, acceptedBreaks, format, cwd } = options;
	const all = oasdiffBreaking(oasdiff, [base, revision, "--format", "json"], cwd);
	if (all.status !== 0) {
		throw new Error(`oasdiff exited with ${exitStatus(all.status, all.signal)}:\n${all.stderr}`);
	}
	const breaks = new Set(breakLines(all.stdout));
	const file = await readFile(path.resolve(cwd, acceptedBreaks), "utf8");
	const gate = oasdiffBreaking(
		oasdiff,
		[base, revision, "--err-ignore", acceptedBreaks, "--fail-on", "ERR", "--format", format],
		cwd,
	);
	if (gate.status !== 0 && gate.status !== 1) {
		throw new Error(`oasdiff exited with ${exitStatus(gate.status, gate.signal)}:\n${gate.stderr}`);
	}
	return {
		compatible: gate.status === 0,
		report: `${gate.stdout}${gate.stderr}`,
		obsolete: acceptedEntries(file).filter((entry) => !breaks.has(entry)),
		problems: acceptedBreaksProblems(file, baseTag, breaks, majorRelease),
	};
}

/** The spec at `tag`. A CI checkout has one commit and no tags, so the tag is fetched on demand. */
async function specAt(tag: string): Promise<string> {
	const object = `${tag}:${SPEC}`;
	if (!(await succeeds("git", ["cat-file", "-e", object], { cwd: ROOT }))) {
		const shallow = await output("git", ["rev-parse", "--is-shallow-repository"], { cwd: ROOT });
		await run(
			"git",
			[
				"fetch",
				"--quiet",
				"--no-tags",
				...(shallow.trim() === "true" ? ["--depth=1"] : []),
				"origin",
				`refs/tags/${tag}:refs/tags/${tag}`,
			],
			{ cwd: ROOT },
		);
	}
	return output("git", ["show", object], { cwd: ROOT });
}

async function main(): Promise<void> {
	const { values } = parseArgs({ options: { base: { type: "string" } } });
	if (values.base !== undefined && !/^v\d+\.\d+\.\d+$/u.test(values.base)) {
		throw new Error("Usage: vp run gate:api-compatibility [--base vX.Y.Z]");
	}
	const base =
		values.base ??
		latestStableTag(
			await output("git", ["ls-remote", "--tags", "--refs", "origin", "v*"], { cwd: ROOT }),
		);
	const directory = await mkdtemp(path.join(tmpdir(), "api-compatibility-base-"));
	try {
		const baseSpec = path.join(directory, `openapi-${base}.yaml`);
		await writeFile(baseSpec, await specAt(base));
		console.log(`Comparing ${SPEC} with ${base} (oasdiff ${OASDIFF_VERSION}).`);
		const majorRelease = majorOf(base) >= 1 && (await headsForMajorRelease(ROOT, base));
		if (majorRelease) {
			console.log(`The change heads for a major release after ${base}, so it can break it.`);
		}
		const { compatible, report, obsolete, problems } = await compareSpecs({
			oasdiff: await installOasdiff(),
			base: baseSpec,
			baseTag: base,
			majorRelease,
			// Relative, so that each annotation names the file in the checkout.
			revision: SPEC,
			acceptedBreaks: ACCEPTED_BREAKS,
			format: process.env.GITHUB_ACTIONS === "true" ? "githubactions" : "text",
			cwd: ROOT,
		});
		console.log(report.trimEnd());
		if (obsolete.length > 0) {
			console.log(
				`::warning title=Obsolete accepted breaks::${obsolete.length} entries in ${ACCEPTED_BREAKS} accept no break against ${base}. Remove them:`,
			);
			console.log(obsolete.map((entry) => `  ${entry}`).join("\n"));
		}
		for (const problem of problems) {
			console.log(`::error title=Accepted breaks::${problem}`);
		}
		if (!compatible || problems.length > 0) {
			console.log(
				`::error title=Breaking API change::${SPEC} can break a client of ${base}. Make the change compatible, or accept a deliberate break in ${ACCEPTED_BREAKS}. See docs/contributor/ci-cd.mdx § API compatibility.`,
			);
			process.exitCode = 1;
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.log(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	}
}
