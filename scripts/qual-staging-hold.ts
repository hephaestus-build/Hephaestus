/**
 * Holds staging still for a measurement (lib/qual-hold.ts): freezes automatic promotion on the applied commit, runs the
 * command after `--`, and lifts the freeze afterwards. bench.ts, drills.ts and smoke.ts refuse `--target staging`
 * unless this wrapper started them.
 *
 *   QUAL_STAGING_EXEC="ssh <host>" node scripts/qual-staging-hold.ts -- node scripts/qual-bench.ts --target staging …
 */
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

import { output } from "./lib/process.ts";
import { json, onStaging, path2, str } from "./lib/qual-cluster.ts";
import { hold } from "./lib/qual-hold.ts";

const REPOSITORY = "hephaestus-build/Hephaestus";
const POLL_MS = 10_000;
const PROMOTION_DEADLINE_MS = 15 * 60_000;

const separator = process.argv.indexOf("--");
const [program, ...args] = separator === -1 ? [] : process.argv.slice(separator + 1);
if (program === undefined) {
	throw new Error("usage: node scripts/qual-staging-hold.ts -- <command> [args…]");
}

const gh = async (ghArgs: string[]): Promise<string> => output("gh", [...ghArgs, "-R", REPOSITORY]);

async function applied(): Promise<string> {
	const result = await onStaging("sudo cat /var/lib/hephaestus/applied.json", {
		timeoutMs: 60_000,
	});
	const commit = result.code === 0 ? str(path2(json(result.stdout), ["commit"])) : "";
	if (!/^[0-9a-f]{40}$/u.test(commit)) {
		throw new Error(`staging's applied.json names no full commit (exit ${result.code})`);
	}
	return commit;
}

/** The promotion run of `commit`: the one with `id`, or the first created after `since`. */
function findRun(runs: unknown, commit: string, idOrSince: string | number): unknown {
	// The run title is `Promote <commit> to Staging` (promote.yml's run-name).
	return (Array.isArray(runs) ? runs : []).find(
		(candidate: unknown) =>
			(typeof idOrSince === "string"
				? String(path2(candidate, ["databaseId"])) === idOrSince
				: Date.parse(str(path2(candidate, ["createdAt"]))) >= idOrSince) &&
			str(path2(candidate, ["displayTitle"])) === `Promote ${commit} to Staging`,
	);
}

async function promote(commit: string, freeze: boolean): Promise<string> {
	const since = Date.now() - 5000;
	await gh([
		"workflow",
		"run",
		"promote.yml",
		"--ref",
		"main",
		"-f",
		"environment=Staging",
		"-f",
		`commit=${commit}`,
		"-f",
		`freeze=${freeze}`,
	]);
	const deadline = Date.now() + PROMOTION_DEADLINE_MS;
	let runId: string | undefined;
	while (Date.now() < deadline) {
		await sleep(POLL_MS);
		const runs = json(
			await gh([
				"run",
				"list",
				"--workflow",
				"promote.yml",
				"--event",
				"workflow_dispatch",
				"--limit",
				"10",
				"--json",
				"databaseId,createdAt,displayTitle,status,conclusion",
			]),
		);
		const run = findRun(runs, commit, runId ?? since);
		if (run === undefined) {
			continue;
		}
		runId = String(path2(run, ["databaseId"]));
		if (str(path2(run, ["status"])) === "completed") {
			const conclusion = str(path2(run, ["conclusion"]));
			if (conclusion !== "success") {
				throw new Error(`promotion run ${runId} (freeze=${freeze}) ended ${conclusion}`);
			}
			return runId;
		}
	}
	throw new Error(
		`promotion (freeze=${freeze}) did not finish within ${PROMOTION_DEADLINE_MS / 60_000} min${runId === undefined ? "" : ` (run ${runId})`}`,
	);
}

process.exitCode = await hold({
	applied,
	promote,
	start(frozen) {
		const child = spawn(program, args, {
			stdio: "inherit",
			env: { ...process.env, QUAL_STAGING_HOLD: frozen },
		});
		const exited = Promise.withResolvers<number>();
		child.once("exit", (exit, signal) => {
			exited.resolve(exit ?? (signal === null ? 1 : 128));
		});
		child.once("error", (error) => {
			console.error(`staging-hold: could not start the measurement: ${error.message}`);
			exited.resolve(1);
		});
		return {
			exited: exited.promise,
			kill: (signal) => {
				child.kill(signal);
			},
		};
	},
	onSignal(handler) {
		for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
			process.on(signal, () => handler(signal));
		}
	},
	log: (line) => console.error(line),
});
