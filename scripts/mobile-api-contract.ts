import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { copyFile, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { arch, platform, tmpdir } from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";

import { stringify } from "yaml";

import { asRecord, asString, asStringArray, parseJson } from "./lib/json.ts";
import { breaksForRelease } from "./lib/mobile-api-contract.ts";
import { output, repositoryCli, run } from "./lib/process.ts";

/**
 * The server API an installed app depends on. `mobile/api-contract/operations.json` lists every
 * operation the app calls; the mobile client is generated from those alone, so the list cannot fall
 * behind the code. `record` keeps today's spec and list under `released/<app version>/` when that
 * version ships. `check` runs oasdiff from each release's spec to today's, and fails on a breaking
 * change to an operation that release calls. A recorded release is never edited: it is deleted once
 * the server's minimum app version passes it. docs/contributor/mobile.mdx § API compatibility.
 */

const SPEC = path.join("server", "openapi.yaml");
const CONTRACT = path.join("mobile", "api-contract");
const OPERATIONS = path.join(CONTRACT, "operations.json");
const RELEASED = path.join(CONTRACT, "released");

const OASDIFF_VERSION = "1.32.1";
const DARWIN = {
	name: "darwin_all",
	sha256: "e4d74b7e2dfb9d4819e7fc720c905ec86547e4637ac270a2b0187c0f1fb7187e",
};
const OASDIFF_ARCHIVES: Record<string, { name: string; sha256: string }> = {
	"darwin-arm64": DARWIN,
	"darwin-x64": DARWIN,
	"linux-x64": {
		name: "linux_amd64",
		sha256: "7c8939fc49b75ee11fec66a5b83b37a2fca6aee109fed85013b1ba2ac2a1ee7f",
	},
	"linux-arm64": {
		name: "linux_arm64",
		sha256: "32fff58a120f75a723d6c2422444691c37fa6813fed61d23f53dbcb604b30f6d",
	},
	"win32-x64": {
		name: "windows_amd64",
		sha256: "4d0758b32d454e6011e59db93884af1ca27ae2b212d990f36738ea5efb5d7f28",
	},
	"win32-arm64": {
		name: "windows_arm64",
		sha256: "7937f4506954676c495188b5c0a221019079801857fb7ba29dd2bc2a767cacab",
	},
};

async function operationsIn(file: string): Promise<readonly string[]> {
	return asStringArray(parseJson(await readFile(file, "utf8")), file);
}

async function appVersion(): Promise<string> {
	const config = asRecord(
		parseJson(
			await output(process.execPath, [
				repositoryCli(),
				"-C",
				"mobile",
				"exec",
				"expo",
				"config",
				"--json",
				"--type",
				"public",
			]),
		),
		"expo config",
	);
	return asString(config.version, "expo config version");
}

async function record(): Promise<void> {
	const version = await appVersion();
	const directory = path.join(RELEASED, version);
	if (existsSync(directory)) {
		throw new Error(
			`${directory} is already recorded, and a recorded release never changes. Raise the app version.`,
		);
	}
	await operationsIn(OPERATIONS);
	await mkdir(directory, { recursive: true });
	await copyFile(SPEC, path.join(directory, "openapi.yaml"));
	await copyFile(OPERATIONS, path.join(directory, "operations.json"));
	console.log(`Recorded the API app version ${version} calls in ${directory}`);
}

async function oasdiff(): Promise<string> {
	const archive = OASDIFF_ARCHIVES[`${platform()}-${arch()}`];
	if (archive === undefined) {
		throw new Error(`oasdiff ${OASDIFF_VERSION} has no archive for ${platform()}-${arch()}`);
	}
	const directory = path.join("node_modules", ".cache", `oasdiff-${OASDIFF_VERSION}`);
	const binary = path.join(directory, platform() === "win32" ? "oasdiff.exe" : "oasdiff");
	if (existsSync(binary)) {
		return binary;
	}
	const response = await fetch(
		`https://github.com/oasdiff/oasdiff/releases/download/v${OASDIFF_VERSION}/oasdiff_${OASDIFF_VERSION}_${archive.name}.tar.gz`,
	);
	if (!response.ok) {
		throw new Error(`Downloading oasdiff failed: ${response.status}`);
	}
	const bytes = Buffer.from(await response.arrayBuffer());
	if (createHash("sha256").update(bytes).digest("hex") !== archive.sha256) {
		throw new Error(`The oasdiff ${OASDIFF_VERSION} archive does not match its pinned digest`);
	}
	await rm(directory, { recursive: true, force: true });
	await mkdir(directory, { recursive: true });
	const tarball = path.join(directory, "oasdiff.tar.gz");
	await writeFile(tarball, bytes);
	await run("tar", ["-xzf", tarball, "-C", directory]);
	return binary;
}

async function breaks(
	binary: string,
	base: string,
	revision: string,
	operations: readonly string[],
) {
	const report = await output(binary, ["breaking", base, revision, "--format", "json"]);
	return breaksForRelease(parseJson(report.trim() === "" ? "[]" : report), operations);
}

/** A 200 answer whose JSON object requires exactly `properties`. */
function answering(properties: Record<string, unknown>) {
	return {
		"200": {
			description: "ok",
			content: {
				"application/json": {
					schema: { type: "object", required: Object.keys(properties), properties },
				},
			},
		},
	};
}

function proofApi(paths: Record<string, unknown>): string {
	return stringify({ openapi: "3.1.0", info: { title: "proof", version: "1" }, paths });
}

/**
 * The gate proves itself before it judges: on a small API, a changed response and a new required
 * parameter on a called operation are reported, and removing an operation nobody calls is not. A
 * change to oasdiff's rules or report that silently let breaks through fails here instead.
 */
async function proveGate(binary: string): Promise<void> {
	const used = { get: { operationId: "used", responses: answering({ name: { type: "string" } }) } };
	const unrelated = {
		get: { operationId: "unrelated", responses: answering({ id: { type: "string" } }) },
	};
	const cases = [
		{
			name: "a called operation's response loses a field",
			paths: {
				"/used": { get: { operationId: "used", responses: answering({}) } },
				"/unrelated": unrelated,
			},
			breaks: true,
		},
		{
			name: "a called operation gains a required parameter",
			paths: {
				"/used": {
					get: {
						...used.get,
						parameters: [
							{ name: "since", in: "query", required: true, schema: { type: "string" } },
						],
					},
				},
				"/unrelated": unrelated,
			},
			breaks: true,
		},
		{ name: "an operation nobody calls is removed", paths: { "/used": used }, breaks: false },
	];

	const directory = await mkdtemp(path.join(tmpdir(), "mobile-api-proof-"));
	try {
		const base = path.join(directory, "base.yaml");
		await writeFile(base, proofApi({ "/used": used, "/unrelated": unrelated }));
		for (const [index, proof] of cases.entries()) {
			const revision = path.join(directory, `revision-${index}.yaml`);
			await writeFile(revision, proofApi(proof.paths));
			const found = await breaks(binary, base, revision, ["GET /used"]);
			if (found.length > 0 !== proof.breaks) {
				throw new Error(
					`The API gate misjudged a known case (${proof.name}): ${found.join("; ") || "nothing reported"}`,
				);
			}
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

async function check(): Promise<void> {
	const recorded = existsSync(RELEASED) ? await readdir(RELEASED) : [];
	const releases = recorded.toSorted();
	if (releases.length === 0) {
		throw new Error(
			`${RELEASED} records no released app version, so there is nothing to hold the API to`,
		);
	}
	const binary = await oasdiff();
	await proveGate(binary);
	const broken: string[] = [];
	for (const version of releases) {
		const found = await breaks(
			binary,
			path.join(RELEASED, version, "openapi.yaml"),
			SPEC,
			await operationsIn(path.join(RELEASED, version, "operations.json")),
		);
		console.log(
			found.length === 0
				? `${version}: no breaking change`
				: `${version}:\n  ${found.join("\n  ")}`,
		);
		if (found.length > 0) {
			broken.push(version);
		}
	}
	if (broken.length > 0) {
		throw new Error(`The spec breaks installed app versions ${broken.join(", ")}`);
	}
}

const { positionals } = parseArgs({ allowPositionals: true, strict: true });
const command = positionals[0];
if (command === "record") {
	await record();
} else if (command === "check") {
	await check();
} else {
	throw new Error("Usage: node scripts/mobile-api-contract.ts record|check");
}
