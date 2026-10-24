import assert from "node:assert/strict";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { releaseCertificateIdentity } from "./lib/release-identities.ts";
import {
	chartPackageName,
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

/** A fake registry, Helm and Cosign. `registry` maps an OCI reference to the bytes a pull returns. */
function tools({
	registry = new Map<string, string>(),
	pushed = digest("b"),
	chartYaml = metadata(),
	signed = (bytes: string) => bytes.startsWith("chart"),
	pulledName = chartPackageName(release),
}: {
	registry?: Map<string, string>;
	/** null: a push that reports no digest. */
	pushed?: string | null;
	chartYaml?: string;
	signed?: (bytes: string) => boolean;
	pulledName?: string;
} = {}) {
	const calls: string[][] = [];
	const execute: Execute = async (command, args) => {
		calls.push([command, ...args]);
		const option = (flag: string) => args[args.indexOf(flag) + 1] ?? "";
		if (command === "helm" && args[0] === "package") {
			await writeFile(
				path.join(option("--destination"), `hephaestus-${option("--version")}.tgz`),
				"chart bytes",
			);
			return "";
		}
		if (command === "helm" && args[0] === "show") {
			return chartYaml;
		}
		if (command === "helm" && args[0] === "push") {
			registry.set(`${repository}@${pushed ?? ""}`, await readFile(args[1] ?? "", "utf8"));
			return `Pushed: ${repository}:${version}\n${pushed === null ? "" : `Digest: ${pushed}\n`}`;
		}
		if (command === "helm" && args[0] === "pull") {
			const bytes = registry.get((args[1] ?? "").replace(/^oci:\/\//u, ""));
			if (bytes === undefined) {
				throw new Error(`${args[1] ?? ""}: not found`);
			}
			await writeFile(path.join(option("--destination"), pulledName), bytes);
			return "";
		}
		if (command === "cosign" && args[0] === "verify-blob") {
			if (!signed(await readFile(args.at(-1) ?? "", "utf8"))) {
				throw new Error("cosign: invalid signature");
			}
			return "";
		}
		throw new Error(`unexpected ${command} ${args.join(" ")}`);
	};
	return { calls, execute, registry };
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
			images: [
				{
					image: "webapp",
					repository: "ghcr.io/hephaestus-build/webapp",
					provenance: "first-party",
					indexDigest: digest("a"),
					platforms: { "linux/amd64": digest("1"), "linux/arm64": digest("2") },
				},
			],
			chart,
		},
		release,
	);
	assert.ok(lock.schemaVersion === 2);
	return lock;
};

await test("publishing packages the release version, pushes it and accepts only a matching round trip", async (t) => {
	const directory = await scratch(t);
	const { calls, execute } = tools();
	const { chart, package: packaged } = await publishReleaseChart(
		release,
		"deploy/chart/hephaestus",
		directory,
		execute,
	);
	assert.deepEqual(chart, {
		repository: "ghcr.io/hephaestus-build/charts/hephaestus",
		version,
		digest: digest("b"),
	});
	assert.equal(path.basename(packaged), "hephaestus-0.93.0.tgz");
	assert.deepEqual(calls[0], [
		"helm",
		"package",
		"deploy/chart/hephaestus",
		"--version",
		version,
		"--app-version",
		version,
		"--destination",
		directory,
	]);
	assert.deepEqual(calls[2], ["helm", "push", packaged, "oci://ghcr.io/hephaestus-build/charts"]);
	assert.equal(calls[3]?.[1], "pull");
	assert.equal(calls[3][2], `oci://${repository}@${digest("b")}`);
	// The round trip directory is gone; only the package stays.
	assert.deepEqual(await readdir(directory), ["hephaestus-0.93.0.tgz"]);
});

await test("publishing refuses a push without a digest, a different pull and foreign metadata", async (t) => {
	await assert.rejects(
		publishReleaseChart(release, "chart", await scratch(t), tools({ pushed: null }).execute),
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
		publishReleaseChart(release, "chart", await scratch(t), execute),
		/the chart pulled by sha256:b{64} differs from the package that was pushed/u,
	);
	await assert.rejects(
		publishReleaseChart(
			release,
			"chart",
			await scratch(t),
			tools({ chartYaml: metadata("0.93.1") }).execute,
		),
		/is not the hephaestus chart 0\.93\.0/u,
	);
	await assert.rejects(
		publishReleaseChart(
			release,
			"chart",
			await scratch(t),
			tools({ chartYaml: "apiVersion: v2\nname: hephaestus\nversion: 0.93.0\nappVersion: 9.9.9\n" })
				.execute,
		),
		/is not the hephaestus chart 0\.93\.0/u,
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
		"chart",
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
			publishReleaseChart(release, "chart", await scratch(t), tools({ chartYaml }).execute),
			/is not the hephaestus chart 0\.93\.0/u,
			label,
		);
	}
});

await test("verification pulls the locked digest, checks the release signature and moves the package last", async (t) => {
	const directory = await scratch(t);
	const registry = new Map([[`${repository}@${digest("b")}`, "chart bytes"]]);
	const { calls, execute } = tools({ registry });
	const verified = await verifyReleaseChart(
		lockFor({ repository, version, digest: digest("b") }),
		"/signatures/hephaestus-0.93.0.tgz.sigstore.json",
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
			["cosign", "verify-blob"],
			["helm", "show"],
		],
	);
	assert.equal(calls[0]?.[2], `oci://${repository}@${digest("b")}`);
	const verify = calls[1] ?? [];
	assert.equal(
		verify[verify.indexOf("--certificate-identity") + 1],
		releaseCertificateIdentity(release, {}),
	);
	assert.equal(
		verify[verify.indexOf("--certificate-oidc-issuer") + 1],
		"https://token.actions.githubusercontent.com",
	);
	assert.equal(
		verify[verify.indexOf("--bundle") + 1],
		"/signatures/hephaestus-0.93.0.tgz.sigstore.json",
	);
});

/** A fresh registry that holds the locked chart's bytes at its digest. */
const lockedRegistry = () => new Map([[`${repository}@${digest("b")}`, "chart bytes"]]);

await test("verification leaves nothing behind for a foreign, unlocked, unsigned or renamed chart", async (t) => {
	for (const [label, chart, options, message] of [
		[
			"another namespace",
			{ repository: "ghcr.io/someone/charts/hephaestus", version, digest: digest("b") },
			{},
			/the lock names chart ghcr\.io\/someone/u,
		],
		["an unlocked digest", { repository, version, digest: digest("c") }, {}, /not found/u],
		[
			"an unsigned package",
			{ repository, version, digest: digest("b") },
			{ signed: () => false },
			/invalid signature/u,
		],
		[
			"another package name",
			{ repository, version, digest: digest("b") },
			{ pulledName: "hephaestus-0.93.1.tgz" },
			/not hephaestus-0\.93\.0\.tgz/u,
		],
		[
			"foreign metadata",
			{ repository, version, digest: digest("b") },
			{ chartYaml: metadata("0.93.1") },
			/is not the hephaestus chart/u,
		],
	] as const) {
		const directory = await scratch(t);
		await assert.rejects(
			verifyReleaseChart(
				lockFor(chart),
				"bundle",
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
