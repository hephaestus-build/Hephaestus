/** The options that the entry points share, and the guard that keeps a staging measurement under a promotion freeze. */
import { existsSync } from "node:fs";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import type { ParseArgsOptionsConfig } from "node:util";

import { Cluster, exec, type TargetName } from "./qual-cluster.ts";

export const REPO = path.resolve(import.meta.dirname, "..", "..");
/** Evidence folders go to the gitignored `.context/`: they hold run data, never a source file. */
export const OUT = path.join(REPO, ".context", "qual");

export const COMMON = {
	target: { type: "string" },
	cluster: { type: "string", default: "hephaestus-qual" },
	kubeconfig: { type: "string" },
	namespace: { type: "string", default: "hephaestus" },
	sandboxes: { type: "string", default: "hephaestus-sandboxes" },
	out: { type: "string", default: OUT },
	help: { type: "boolean", default: false },
} as const satisfies ParseArgsOptionsConfig;

const COMMON_USAGE = `
--target k3d       --kubeconfig <file> [--cluster <k3d cluster name>]
--target staging   only under staging-hold.ts; QUAL_STAGING_EXEC runs a shell command on the host (such as \`ssh <host>\`)
[--namespace hephaestus] [--sandboxes hephaestus-sandboxes] [--out <dir>]`;

export interface Common {
	target: TargetName;
	out: string;
	cluster: Cluster;
	command: string;
}

interface CommonValues {
	target?: string | undefined;
	cluster?: string | undefined;
	kubeconfig?: string | undefined;
	namespace?: string | undefined;
	sandboxes?: string | undefined;
	out?: string | undefined;
	help?: boolean | undefined;
}

/**
 * Reads the shared options. `staging` refuses to run unless staging-hold.ts started this process: a lock that people
 * agree on does not stop the promoter, and only a frozen channel keeps staging on one commit for the whole window.
 */
export function commonFrom(
	values: CommonValues,
	usage: string,
	env: NodeJS.ProcessEnv = process.env,
): Common {
	if (values.help === true) {
		process.stdout.write(`${usage}\n${COMMON_USAGE}\n`);
		process.exit(0);
	}
	const { target } = values;
	if (target !== "k3d" && target !== "staging") {
		throw new Error(`--target k3d|staging is required\n${usage}\n${COMMON_USAGE}`);
	}
	if (target === "staging" && (env.QUAL_STAGING_HOLD ?? "") === "") {
		throw new Error(
			"refusing to measure staging without a promotion freeze: run it as `node scripts/qual-staging-hold.ts -- node scripts/qual-<entry>.ts …`, which freezes staging on its applied commit and lifts the freeze afterwards",
		);
	}
	if (target === "staging" && (env.QUAL_STAGING_EXEC ?? "").trim() === "") {
		throw new Error(
			"--target staging needs QUAL_STAGING_EXEC: the command that runs a shell command on the staging host",
		);
	}
	const kubeconfig = values.kubeconfig === undefined ? undefined : path.resolve(values.kubeconfig);
	if (target === "k3d" && (kubeconfig === undefined || !existsSync(kubeconfig))) {
		throw new Error(
			`--target k3d needs --kubeconfig <file>: write it with \`k3d kubeconfig get <cluster> > <file>\` (mode 600). The current context is never used.`,
		);
	}
	return {
		target,
		out: values.out ?? OUT,
		cluster: new Cluster({
			target,
			k3dCluster: values.cluster ?? "hephaestus-qual",
			kubeconfig,
			namespace: values.namespace ?? "hephaestus",
			sandboxes: values.sandboxes ?? "hephaestus-sandboxes",
		}),
		command: ["node", path.relative(REPO, process.argv[1] ?? ""), ...process.argv.slice(2)]
			.map((word) => (/\s/u.test(word) ? `'${word}'` : word))
			.join(" "),
	};
}

/** This checkout's commit, with `-dirty` when tracked files have local changes. */
export async function commitOf(): Promise<string> {
	const head = await exec("git", ["-C", REPO, "rev-parse", "HEAD"]);
	const status = await exec("git", ["-C", REPO, "status", "--porcelain", "--untracked-files=no"]);
	if (head.code !== 0) {
		throw new Error(`${REPO} is not a Git checkout`);
	}
	return `${head.stdout.trim()}${status.stdout.trim() === "" ? "" : "-dirty"}`;
}

export function percentile(values: readonly number[], p: number): number | null {
	const sorted = values.filter((value) => Number.isFinite(value)).toSorted((a, b) => a - b);
	if (sorted.length === 0) {
		return null;
	}
	const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
	return sorted[index] ?? null;
}

export async function sleep(ms: number): Promise<void> {
	await delay(ms);
}

export function mib(bytes: number | null): string {
	return bytes === null || !Number.isFinite(bytes)
		? "n/a"
		: `${(bytes / 1024 / 1024).toFixed(0)} MiB`;
}
