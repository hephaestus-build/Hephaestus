import assert from "node:assert/strict";
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { rehearsalRelease, rehearseChart } from "./rehearse-chart-signing.ts";
import type { Execute } from "./release-chart.ts";

const source = fileURLToPath(new URL("../deploy/chart/hephaestus", import.meta.url));
const commit = "c".repeat(40);
const scratchRepository = "ghcr.io/hephaestus-build/charts-rehearsal/hephaestus";
const pushed = `sha256:${"b".repeat(64)}`;

const option = (args: string[], flag: string) => args[args.indexOf(flag) + 1] ?? "";

/** A fake Helm and registry: the package holds its values.yaml, and a pull returns the pushed bytes. */
function helm() {
	const calls: string[][] = [];
	const registry = new Map<string, string>();
	const execute: Execute = async (command, args) => {
		calls.push([command, ...args]);
		const [mode = "", target = ""] = args;
		if (mode === "package") {
			const name = `hephaestus-${option(args, "--version")}.tgz`;
			const values = await readFile(path.join(target, "values.yaml"), "utf8");
			await writeFile(path.join(option(args, "--destination"), name), values);
			return "";
		}
		if (mode === "show" && target === "chart") {
			const version = path.basename(args[2] ?? "").replaceAll(/^hephaestus-|\.tgz$/gu, "");
			return `apiVersion: v2\nname: hephaestus\nversion: ${version}\nappVersion: ${version}\n`;
		}
		if (mode === "show" && target === "values") {
			return `${await readFile(args[2] ?? "", "utf8")}\n`;
		}
		if (mode === "push") {
			registry.set(`${scratchRepository}@${pushed}`, await readFile(target, "utf8"));
			return `Digest: ${pushed}\n`;
		}
		if (mode === "pull") {
			const bytes = registry.get(target.replace(/^oci:\/\//u, "")) ?? "";
			const name = `hephaestus-${rehearsalRelease("42").slice(1)}.tgz`;
			await writeFile(path.join(option(args, "--destination"), name), bytes);
			return "";
		}
		throw new Error(`unexpected ${command} ${args.join(" ")}`);
	};
	return { calls, execute };
}

async function scratch(t: { after: (callback: () => Promise<void>) => void }) {
	const directory = await mkdtemp(path.join(tmpdir(), "chart-rehearsal-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	return directory;
}

await test("the rehearsal publishes through the release's code to its scratch repository only", async (t) => {
	const directory = await scratch(t);
	const { calls, execute } = helm();
	const chart = await rehearseChart("42", commit, scratchRepository, source, directory, execute);
	assert.deepEqual(chart, {
		repository: scratchRepository,
		version: "0.0.0-rehearsal.42",
		digest: pushed,
	});
	assert.deepEqual(calls.find((call) => call[1] === "push")?.slice(3), [
		"oci://ghcr.io/hephaestus-build/charts-rehearsal",
	]);
	assert.equal(
		calls.find((call) => call[1] === "pull")?.[2],
		`oci://${scratchRepository}@${pushed}`,
	);
	assert.ok(!calls.flat().some((argument) => argument.includes("/charts/hephaestus")));
	assert.deepEqual(await readdir(directory), ["hephaestus-0.0.0-rehearsal.42.tgz"]);
});

await test("the rehearsal refuses a release repository, a release version and a malformed commit", async (t) => {
	for (const [label, args, message] of [
		[
			"the release chart repository",
			["42", commit, "ghcr.io/hephaestus-build/charts/hephaestus"],
			/is not a chart rehearsal repository/u,
		],
		[
			"another chart name",
			["42", commit, "ghcr.io/hephaestus-build/charts-rehearsal/other"],
			/is not a chart rehearsal repository/u,
		],
		["a non-numeric run", ["1.2.3", commit, scratchRepository], /positive number/u],
		["a malformed commit", ["42", "main", scratchRepository], /malformed source commit/u],
	] as const) {
		const { calls, execute } = helm();
		const [runId, sourceCommit, repository] = args;
		await assert.rejects(
			rehearseChart(runId, sourceCommit, repository, source, await scratch(t), execute),
			message,
			label,
		);
		assert.deepEqual(calls, [], `${label} ran a tool`);
	}
});
