/**
 * A self-hoster's release change on a cluster: install the latest published release with its own chart, images and
 * scripts, then upgrade to the candidate with the candidate's chart. A release whose migration guide says
 * "Stop every role" takes the three phases of docs/admin/upgrade.mdx § Stopped schema upgrades; any other takes one
 * rolling upgrade. Both end with the candidate's readiness and authenticated-read checks. Entry-specific steps of a
 * migration note, such as the conversation-sandbox cutover of an older release, are not part of the drill.
 */
import { mkdir, mkdtemp, readFile, readdir, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";

import { stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { latestReleaseOf } from "./lib/previous-release.ts";
import { output, run } from "./lib/process.ts";

const ROOT = path.resolve(import.meta.dirname, "..");
const NAMESPACE = "hephaestus";
const SANDBOXES = "hephaestus-sandboxes";

const { values } = parseArgs({
	options: {
		work: { type: "string" },
		images: { type: "string" },
		commit: { type: "string" },
		profile: { type: "string", default: "dev" },
	},
});
const { commit, profile } = values;
if (values.work === undefined || values.images === undefined || commit === undefined) {
	throw new Error(
		"Usage: node scripts/chart-upgrade-drill.ts --work <dir> --images <candidate images file> --commit <sha> [--profile dev]",
	);
}

// The cluster script runs in two checkouts, so both paths are absolute.
const work = path.resolve(values.work);
const images = path.resolve(values.images);
await mkdir(work, { recursive: true, mode: 0o700 });

const previous = latestReleaseOf(commit);
if (previous === undefined) {
	throw new Error(`No published release is merged into ${commit}`);
}
const resolved = await output("git", ["rev-parse", `${previous}^{commit}`], { cwd: ROOT });
const previousCommit = resolved.trim();
const fragmentFiles = await readdir(path.join(ROOT, ".migration"));
const fragments = await Promise.all(
	fragmentFiles
		.filter((file) => file.endsWith(".md") && file !== "README.md")
		.map(async (file) => readFile(path.join(ROOT, ".migration", file), "utf8")),
);
const stops = stopEveryRoleEntries(
	await readFile(path.join(ROOT, "MIGRATION.md"), "utf8"),
	fragments,
	previous.slice(1),
);
console.log(
	`Upgrading ${previous} (${previousCommit}) to ${commit}: ${stops.length > 0 ? `stopped, because ${stops.join("; ")}` : "rolling"}.`,
);

const cluster = async (cwd: string, ...args: string[]) =>
	run(process.execPath, [path.join(cwd, "scripts", "kubernetes-cluster.ts"), ...args], { cwd });
const settings = (...pairs: string[]) => pairs.flatMap((pair) => ["--set", pair]);

const checkout = await mkdtemp(path.join(tmpdir(), "previous-release-"));
await run("git", ["worktree", "add", "--detach", checkout, previousCommit], { cwd: ROOT });
try {
	// The previous release's scripts need packages; the candidate's install serves them for a few releases' drift.
	await symlink(path.join(ROOT, "node_modules"), path.join(checkout, "node_modules"), "dir");
	const previousImages = path.join(work, "previous-images.tsv");
	await run(
		process.execPath,
		["scripts/resolve-release-images.ts", previousCommit, previousImages],
		{
			cwd: checkout,
		},
	);

	console.log(`Installing ${previous} with its own chart and images.`);
	// The previous release, installed as that release installs.
	await cluster(
		checkout,
		"prepare",
		"--work",
		work,
		"--images",
		previousImages,
		"--commit",
		previousCommit,
		"--gateway",
	);
	await cluster(checkout, "gateway", "--work", work);
	await cluster(checkout, "install", "--work", work, "--profile", profile);
	await cluster(checkout, "verify");

	if (stops.length > 0) {
		console.log("Phase 1: the current chart stops every role.");
		// Phase 1: the current chart stops every role, and no old Pod remains.
		await cluster(
			checkout,
			"upgrade",
			"--work",
			work,
			"--profile",
			profile,
			...settings("server.replicas=0", "migration.enabled=false"),
		);
		await run("kubectl", [
			"--namespace",
			NAMESPACE,
			"wait",
			"--for=delete",
			"pod",
			"--selector",
			"app.kubernetes.io/component=server",
			"--timeout=20m",
		]);
		// The phase also ends every sandbox Pod and Job of the old servers.
		await run("kubectl", [
			"--namespace",
			SANDBOXES,
			"wait",
			"--for=delete",
			"pods,jobs",
			"--all",
			"--timeout=20m",
		]);
	}
	// The candidate keeps the Secrets that the database started with.
	await cluster(
		ROOT,
		"prepare",
		"--work",
		work,
		"--images",
		images,
		"--commit",
		commit,
		"--gateway",
	);
	if (stops.length > 0) {
		console.log("Phase 2: the new chart migrates with every role stopped.");
		// Phase 2: the new chart migrates with every role stopped; Helm waits for the migration Job.
		await cluster(
			ROOT,
			"upgrade",
			"--work",
			work,
			"--profile",
			profile,
			...settings("server.replicas=0"),
		);
	}
	console.log(
		stops.length > 0 ? "Phase 3: the new roles start." : "Rolling upgrade to the new chart.",
	);
	// Phase 3, or the rolling upgrade: the new roles start.
	await cluster(ROOT, "upgrade", "--work", work, "--profile", profile);
	await cluster(ROOT, "verify");
	console.log(`The chart of ${previous} upgraded to ${commit}.`);
} finally {
	await run("git", ["worktree", "remove", "--force", checkout], { cwd: ROOT });
	await rm(checkout, { recursive: true, force: true });
}
