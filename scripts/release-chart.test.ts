import assert from "node:assert/strict";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { chartImages, type ChartValues, releaseValuesLines } from "./kubernetes-chart.ts";
import { releaseCertificateIdentity } from "./lib/release-identities.ts";
import {
	chartRepository,
	type Execute,
	publishReleaseChart,
	verifyReleaseChart,
} from "./release-chart.ts";
import { parseReleaseImageLock, type ReleaseImageLockV2 } from "./release-image-lock.ts";

const release = "v0.93.0";
const version = "0.93.0";
const digest = (character: string) => `sha256:${character.repeat(64)}`;
const repository = chartRepository(release);
const metadata = (chartVersion = version) =>
	`apiVersion: v2\nname: hephaestus\nversion: ${chartVersion}\nappVersion: ${chartVersion}\n`;
/** The real chart source: a release packages a copy of it and never changes it. */
const source = fileURLToPath(new URL("../deploy/chart/hephaestus", import.meta.url));
const image = (name: string, character: string) =>
	`ghcr.io/hephaestus-build/${name}@${digest(character)}`;
const values: ChartValues = {
	release: { version: release, commit: "c".repeat(40) },
	images: {
		"agent-pi": image("agent-pi", "1"),
		"application-server": image("application-server", "2"),
		nats: image("nats", "3"),
		postgres: image("postgres", "4"),
		webapp: image("webapp", "5"),
	},
};
/** The release image inventory of these tests: the five images the chart runs, all first-party. */
const inventory = { images: [...chartImages], upstream: [] };
/** A pushed package: one line of package bytes, then the values.yaml it was packaged with. */
const chartBytes = (pinned = values) => `chart bytes\n${releaseValuesLines(pinned)}`;

const option = (args: string[], flag: string) => args[args.indexOf(flag) + 1] ?? "";
/** What `helm show values` prints for a fake package: the values.yaml it was packaged with. */
const shown = async (file: string) => {
	const bytes = await readFile(file, "utf8");
	return `${bytes.replace(/^chart bytes\n/u, "")}\n`;
};

/**
 * A fake registry, Helm and Cosign. `registry` maps an OCI reference to the bytes a pull returns.
 * A package holds the values.yaml that it was packaged with, and `helm show values` prints it.
 */
function tools({
	registry = new Map<string, string>(),
	pushed = digest("b"),
	chartYaml = metadata(),
	shownValues,
	signed = () => true,
	pulledName = `hephaestus@${digest("b").replace(":", "-")}.tgz`,
}: {
	registry?: Map<string, string>;
	/** null: a push that reports no digest. */
	pushed?: string | null;
	chartYaml?: string;
	/** What `helm show values` prints instead of the packaged values.yaml. */
	shownValues?: string;
	/** Whether the release identity signed an OCI reference. */
	signed?: (reference: string) => boolean;
	pulledName?: string;
} = {}) {
	const calls: string[][] = [];
	const packaged: string[] = [];
	const helm: Record<string, (args: string[]) => Promise<string>> = {
		package: async (args) => {
			const valuesYaml = await readFile(path.join(args[1] ?? "", "values.yaml"), "utf8");
			packaged.push(valuesYaml);
			await writeFile(
				path.join(option(args, "--destination"), `hephaestus-${option(args, "--version")}.tgz`),
				`chart bytes\n${valuesYaml}`,
			);
			return "";
		},
		show: async (args) =>
			args[1] === "chart" ? chartYaml : (shownValues ?? (await shown(args[2] ?? ""))),
		push: async (args) => {
			registry.set(`${repository}@${pushed ?? ""}`, await readFile(args[1] ?? "", "utf8"));
			return `Pushed: ${repository}:${version}\n${pushed === null ? "" : `Digest: ${pushed}\n`}`;
		},
		pull: async (args) => {
			const bytes = registry.get((args[1] ?? "").replace(/^oci:\/\//u, ""));
			if (bytes === undefined) {
				throw new Error(`${args[1] ?? ""}: not found`);
			}
			await writeFile(path.join(option(args, "--destination"), pulledName), bytes);
			return `Digest: ${digest("b")}\n`;
		},
	};
	const execute: Execute = async (command, args) => {
		calls.push([command, ...args]);
		const handler = command === "helm" ? helm[args[0] ?? ""] : undefined;
		if (handler !== undefined) {
			return handler(args);
		}
		if (command === "cosign" && args[0] === "verify") {
			if (!signed(args[1] ?? "")) {
				throw new Error("cosign: no signatures found");
			}
			return "";
		}
		throw new Error(`unexpected ${command} ${args.join(" ")}`);
	};
	return { calls, execute, registry, packaged };
}

async function scratch(t: { after: (callback: () => Promise<void>) => void }) {
	const directory = await mkdtemp(path.join(tmpdir(), "release-chart-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	return directory;
}

const lockFor = (chart: {
	repository: string;
	version: string;
	digest: string;
}): ReleaseImageLockV2 => {
	const lock = parseReleaseImageLock(
		{
			schemaVersion: 2,
			release,
			commit: "c".repeat(40),
			// The lock names the images that `values` pins.
			images: chartImages.map((name) => {
				const [imageRepository, indexDigest] = values.images[name].split("@");
				return {
					image: name,
					repository: imageRepository,
					provenance: "first-party",
					indexDigest,
					platforms: { "linux/amd64": digest("e"), "linux/arm64": digest("f") },
				};
			}),
			chart,
		},
		release,
	);
	assert.ok(lock.schemaVersion === 2);
	return lock;
};

await test("publishing packages a copy that pins the release images, pushes it and accepts only a matching round trip", async (t) => {
	const directory = await scratch(t);
	const sourceValues = await readFile(path.join(source, "values.yaml"), "utf8");
	const { calls, execute, packaged: packagedValues } = tools();
	const { chart, package: packaged } = await publishReleaseChart(
		release,
		source,
		values,
		directory,
		execute,
	);
	assert.deepEqual(chart, {
		repository: "ghcr.io/hephaestus-build/charts/hephaestus",
		version,
		digest: digest("b"),
	});
	assert.equal(path.basename(packaged), "hephaestus-0.93.0.tgz");
	const [helm = "", mode = "", staged = "", ...options] = calls[0] ?? [];
	assert.deepEqual([helm, mode], ["helm", "package"]);
	assert.notEqual(staged, source, "the package is made from a copy");
	assert.equal(path.basename(staged), "hephaestus");
	assert.deepEqual(options, [
		"--version",
		version,
		"--app-version",
		version,
		"--destination",
		directory,
	]);
	// The copy names the release and its images; everything else is the source, byte for byte.
	const [released = ""] = packagedValues;
	assert.ok(released.includes(releaseValuesLines(values)));
	assert.equal(
		released.replace(
			releaseValuesLines(values),
			'release:\n  version: ""\n  commit: ""\nimages: {}\n',
		),
		sourceValues,
	);
	assert.equal(await readFile(path.join(source, "values.yaml"), "utf8"), sourceValues);
	assert.deepEqual(
		calls.slice(1, 3).map((call) => call.slice(0, 3)),
		[
			["helm", "show", "chart"],
			["helm", "show", "values"],
		],
	);
	assert.deepEqual(calls[3], ["helm", "push", packaged, "oci://ghcr.io/hephaestus-build/charts"]);
	assert.equal(calls[4]?.[1], "pull");
	assert.equal(calls[4][2], `oci://${repository}@${digest("b")}`);
	// The staged copy and the round trip directory are gone; only the package stays.
	assert.deepEqual(await readdir(directory), ["hephaestus-0.93.0.tgz"]);
});

await test("publishing refuses a push without a digest, a different pull, foreign metadata and unpinned values", async (t) => {
	await assert.rejects(
		publishReleaseChart(release, source, values, await scratch(t), tools({ pushed: null }).execute),
		/helm push reported no chart digest/u,
	);
	const registry = new Map<string, string>();
	const swapped = tools({ registry });
	const execute: Execute = async (command, args) => {
		const result = await swapped.execute(command, args);
		if (args[0] === "push") {
			registry.set(`${repository}@${digest("b")}`, "chart bytes from elsewhere");
		}
		return result;
	};
	await assert.rejects(
		publishReleaseChart(release, source, values, await scratch(t), execute),
		/the chart pulled by sha256:b{64} differs from the package that was pushed/u,
	);
	await assert.rejects(
		publishReleaseChart(
			release,
			source,
			values,
			await scratch(t),
			tools({ chartYaml: metadata("0.93.1") }).execute,
		),
		/is not the hephaestus chart 0\.93\.0/u,
	);
	await assert.rejects(
		publishReleaseChart(
			release,
			source,
			values,
			await scratch(t),
			tools({ chartYaml: "apiVersion: v2\nname: hephaestus\nversion: 0.93.0\nappVersion: 9.9.9\n" })
				.execute,
		),
		/is not the hephaestus chart 0\.93\.0/u,
	);
	for (const [label, shownValues] of [
		["the source values", await readFile(path.join(source, "values.yaml"), "utf8")],
		["the release values twice", `${releaseValuesLines(values)}${releaseValuesLines(values)}`],
		["indented release values", `nested:\n  ${releaseValuesLines(values)}`],
		["a second images key", `${releaseValuesLines(values)}images: {}\n`],
	] as const) {
		const tool = tools({ shownValues });
		await assert.rejects(
			publishReleaseChart(release, source, values, await scratch(t), tool.execute),
			/hephaestus-0\.93\.0\.tgz does not pin the v0\.93\.0 images/u,
			label,
		);
		assert.ok(!tool.calls.some((call) => call[1] === "push"), `${label} was pushed`);
	}
	await assert.rejects(
		publishReleaseChart("v0.93.1", source, values, await scratch(t), tools().execute),
		/the chart values are for v0\.93\.0, not v0\.93\.1/u,
	);
});

await test("chart metadata is read as four exact top-level lines, never through a YAML parser", async (t) => {
	// Helm re-marshals Chart.yaml with sorted keys; other keys and nested lines are not read.
	const normalized = [
		"apiVersion: v2",
		"appVersion: 0.93.0",
		"description: Hephaestus server, webhook receiver and webapp on Kubernetes.",
		"home: https://github.com/hephaestus-build/Hephaestus",
		"name: hephaestus",
		"sources:",
		"- https://github.com/hephaestus-build/Hephaestus",
		"type: application",
		"version: 0.93.0",
		"",
	].join("\n");
	await publishReleaseChart(
		release,
		source,
		values,
		await scratch(t),
		tools({ chartYaml: normalized }).execute,
	);
	for (const [label, chartYaml] of [
		["a repeated version", `${normalized}version: 0.93.0\n`],
		["a quoted version", normalized.replace("version: 0.93.0", 'version: "0.93.0"')],
		["a trailing comment", normalized.replace("name: hephaestus", "name: hephaestus # fork")],
		["a missing appVersion", normalized.replace("appVersion: 0.93.0\n", "")],
		["another API version", normalized.replace("apiVersion: v2", "apiVersion: v1")],
		["another name", normalized.replace("name: hephaestus", "name: hephaestus-fork")],
		["a version prefix", normalized.replace("version: 0.93.0", "version: 0.93.0-rc.1")],
		["nothing at all", ""],
	] as const) {
		await assert.rejects(
			publishReleaseChart(release, source, values, await scratch(t), tools({ chartYaml }).execute),
			/is not the hephaestus chart 0\.93\.0/u,
			label,
		);
	}
});

await test("verification pulls the locked digest, checks its registry signature and moves the package last", async (t) => {
	const directory = await scratch(t);
	const registry = new Map([[`${repository}@${digest("b")}`, chartBytes()]]);
	const { calls, execute } = tools({ registry });
	const verified = await verifyReleaseChart(
		lockFor({ repository, version, digest: digest("b") }),
		inventory,
		directory,
		{},
		execute,
	);
	assert.equal(verified, path.join(directory, "hephaestus-0.93.0.tgz"));
	assert.deepEqual(await readdir(directory), ["hephaestus-0.93.0.tgz"]);
	assert.deepEqual(
		calls.map((call) => call.slice(0, 2)),
		[
			["helm", "pull"],
			["cosign", "verify"],
			["helm", "show"],
			["helm", "show"],
		],
	);
	assert.deepEqual(
		calls.slice(2).map((call) => call[2]),
		["chart", "values"],
	);
	assert.equal(calls[0]?.[2], `oci://${repository}@${digest("b")}`);
	const verify = calls[1] ?? [];
	// The signature is checked on the locked digest in the registry, never on a tag or a file.
	assert.equal(verify[2], `${repository}@${digest("b")}`);
	assert.equal(
		verify[verify.indexOf("--certificate-identity") + 1],
		releaseCertificateIdentity(release, {}),
	);
	assert.equal(
		verify[verify.indexOf("--certificate-oidc-issuer") + 1],
		"https://token.actions.githubusercontent.com",
	);
	assert.equal(verify.length, 7);
});

/** A fresh registry that holds the locked chart's bytes at its digest. */
const lockedRegistry = (bytes = chartBytes()) => new Map([[`${repository}@${digest("b")}`, bytes]]);
const otherImages: ChartValues = {
	...values,
	images: { ...values.images, webapp: image("webapp", "9") },
};

await test("a stable promotion verifies the same candidate chart bytes and keeps the artifact filename", async (t) => {
	const artifact = `${version}-rc.2`;
	const candidateValues = { ...values, release: { ...values.release, version: `v${artifact}` } };
	const bytes = chartBytes(candidateValues);
	const registry = lockedRegistry(bytes);
	const tool = tools({ registry, chartYaml: metadata(artifact) });
	const directory = await scratch(t);
	const verified = await verifyReleaseChart(
		lockFor({ repository, version: artifact, digest: digest("b") }),
		inventory,
		directory,
		{},
		tool.execute,
	);
	assert.equal(path.basename(verified), `hephaestus-${artifact}.tgz`);
	assert.equal(await readFile(verified, "utf8"), bytes);
	assert.ok(tool.calls.every((call) => call[1] !== "package" && call[1] !== "push"));
	await assert.rejects(
		verifyReleaseChart(
			lockFor({ repository, version: artifact, digest: digest("b") }),
			inventory,
			await scratch(t),
			{},
			tools({ registry, chartYaml: metadata() }).execute,
		),
		/is not the hephaestus chart/u,
	);
});

await test("verification leaves nothing behind for a foreign, unlocked, unsigned, renamed or unpinned chart", async (t) => {
	for (const [label, chart, options, message] of [
		[
			"another namespace",
			{ repository: "ghcr.io/someone/charts/hephaestus", version, digest: digest("b") },
			{},
			/the lock names chart ghcr\.io\/someone/u,
		],
		["an unlocked digest", { repository, version, digest: digest("c") }, {}, /not found/u],
		[
			"an unsigned digest",
			{ repository, version, digest: digest("b") },
			{ signed: () => false },
			/no signatures found/u,
		],
		[
			"another package name",
			{ repository, version, digest: digest("b") },
			{ pulledName: "hephaestus-0.93.1.tgz" },
			/not hephaestus@sha256-b+/u,
		],
		[
			"foreign metadata",
			{ repository, version, digest: digest("b") },
			{ chartYaml: metadata("0.93.1") },
			/is not the hephaestus chart/u,
		],
		[
			"values that pin other images than the lock",
			{ repository, version, digest: digest("b") },
			{ registry: lockedRegistry(chartBytes(otherImages)) },
			/hephaestus@sha256-b+\.tgz does not pin the v0\.93\.0 images/u,
		],
	] as const) {
		const directory = await scratch(t);
		await assert.rejects(
			verifyReleaseChart(
				lockFor(chart),
				inventory,
				directory,
				{},
				tools({ registry: lockedRegistry(), ...options }).execute,
			),
			message,
			label,
		);
		assert.deepEqual(await readdir(directory), [], `${label} left output`);
	}
});
