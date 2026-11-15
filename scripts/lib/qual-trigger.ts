/**
 * Starts reviews on staging with no signed-in session: enable the workspace's practice-review auto trigger, push one
 * comment-line commit to each fixture pull request, watch the item events until each review STARTED, then restore the
 * flag. A systemd timer on the host restores the flag if this process or its transport dies.
 *
 * The fixture names a synthetic workspace by id and slug, and the open pull requests of its test repositories:
 *   { "workspaceId": 1, "workspaceSlug": "qualification", "reviews": [{ "repo": "owner/name", "pr": 1, "path": "src/File.java" }] }
 * The fixture is the caller's: it stays outside the repository, and a run records only its path.
 */
import { readFileSync } from "node:fs";

import { sleep } from "./qual-args.ts";
import { type Cluster, exec, json, onStaging, path2, quote, str } from "./qual-cluster.ts";
import type { Evidence } from "./qual-evidence.ts";

export interface Fixture {
	workspaceId: number;
	workspaceSlug: string;
	reviews: { repo: string; pr: number; path: string }[];
}

export function readFixture(file: string): Fixture {
	const parsed = json(readFileSync(file, "utf8"));
	const workspaceId = path2(parsed, ["workspaceId"]);
	const workspaceSlug = str(path2(parsed, ["workspaceSlug"]));
	const reviews = path2(parsed, ["reviews"]);
	if (
		typeof workspaceId !== "number" ||
		!Number.isSafeInteger(workspaceId) ||
		!/^[a-z0-9-]+$/u.test(workspaceSlug) ||
		!Array.isArray(reviews)
	) {
		throw new Error(
			`${file}: expected { workspaceId: number, workspaceSlug: string, reviews: [{ repo, pr, path }] }`,
		);
	}
	return {
		workspaceId,
		workspaceSlug,
		reviews: reviews.map((entry, index) => {
			const repo = str(path2(entry, ["repo"]));
			const pr = path2(entry, ["pr"]);
			const file2 = str(path2(entry, ["path"]));
			if (!/^[\w.-]+\/[\w.-]+$/u.test(repo) || typeof pr !== "number" || file2 === "") {
				throw new Error(`${file}: reviews[${index}] needs repo "owner/name", pr number and path`);
			}
			return { repo, pr, path: file2 };
		}),
	};
}

async function gh(args: string[], input?: string): Promise<unknown> {
	const result = await exec("gh", args, {
		...(input === undefined ? {} : { input }),
		timeoutMs: 60_000,
	});
	if (result.code !== 0) {
		throw new Error(`gh ${args.slice(0, 3).join(" ")} failed (${result.code})`);
	}
	return json(result.stdout);
}

const COMMENT: Record<string, [string, string]> = {
	java: ["// ", ""],
	kt: ["// ", ""],
	ts: ["// ", ""],
	js: ["// ", ""],
	go: ["// ", ""],
	py: ["# ", ""],
	rb: ["# ", ""],
	sh: ["# ", ""],
	md: ["<!-- ", " -->"],
};

/** Starts up to `count` reviews from the fixture; returns the commits it pushed and which reviews started. */
export async function startReviews(
	cluster: Cluster,
	evidence: Evidence,
	fixture: Fixture,
	count: number,
	label: string,
): Promise<{ commit: string; repo: string; pr: number; started: boolean }[]> {
	if (cluster.target !== "staging") {
		throw new Error("reviews start only on staging: a k3d cluster has no SCM connection");
	}
	const chosen = fixture.reviews.slice(0, count);
	const workspace = fixture.workspaceId;
	// The slug must match the id, so that a mistyped id never switches on another workspace's reviews.
	const [row] = await cluster.sql(
		`SELECT slug, practice_review_auto_trigger_enabled FROM workspace WHERE id = ${workspace};`,
	);
	if (row?.[0] !== fixture.workspaceSlug) {
		throw new Error(`workspace ${workspace} is not "${fixture.workspaceSlug}": check the fixture`);
	}
	const wasOn = row[1] === "t";
	const unit = `qual-bench-reset-${label}`;
	const { pod, container } = await cluster.postgres();
	const restoreSql = `UPDATE workspace SET practice_review_auto_trigger_enabled = false WHERE id = ${workspace};`;
	const pushed: { commit: string; repo: string; pr: number; started: boolean }[] = [];
	if (!wasOn) {
		// The host's own timer restores the flag even if this process or its transport disappears.
		const arm = await onStaging(
			[
				"sudo",
				"-n",
				"systemd-run",
				`--unit=${unit}`,
				"--on-active=15m",
				"--timer-property=AccuracySec=1s",
				"--collect",
				"k3s",
				"kubectl",
				"-n",
				cluster.namespace,
				"exec",
				pod,
				"-c",
				container,
				"--",
				"sh",
				"-c",
				`psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c '${restoreSql}'`,
			]
				.map(quote)
				.join(" "),
		);
		if (arm.code !== 0) {
			throw new Error("could not arm the independent reset timer; no flag was changed");
		}
		evidence.line("trigger.jsonl", { operation: "reset-armed", unit, minutes: 15 });
	}
	try {
		if (!wasOn) {
			const changed = await cluster.sqlWrite(
				`UPDATE workspace SET practice_review_auto_trigger_enabled = true WHERE id = ${workspace} AND practice_review_auto_trigger_enabled = false RETURNING id;`,
			);
			evidence.line("trigger.jsonl", { operation: "enable", workspace, rows: changed.length });
		}
		for (const [index, review] of chosen.entries()) {
			const view = await gh([
				"pr",
				"view",
				String(review.pr),
				"--repo",
				review.repo,
				"--json",
				"state,headRefName",
			]);
			if (str(path2(view, ["state"])) !== "OPEN") {
				throw new Error(`${review.repo}#${review.pr} is not open`);
			}
			const branch = str(path2(view, ["headRefName"]));
			const file = await gh([
				"api",
				`repos/${review.repo}/contents/${review.path}?ref=${encodeURIComponent(branch)}`,
			]);
			const before = Buffer.from(str(path2(file, ["content"])), "base64").toString("utf8");
			const [open, close] = COMMENT[review.path.split(".").at(-1) ?? ""] ?? ["// ", ""];
			const content = `${before.replace(/\n?$/u, "\n")}${open}qual-bench ${label}-${index}${close}\n`;
			const commit = await gh(
				["api", "--method", "PUT", `repos/${review.repo}/contents/${review.path}`, "--input", "-"],
				JSON.stringify({
					message: `chore: qual bench ${label}-${index}`,
					branch,
					sha: str(path2(file, ["sha"])),
					content: Buffer.from(content).toString("base64"),
				}),
			);
			const sha = str(path2(commit, ["commit", "sha"]));
			evidence.line("trigger.jsonl", {
				operation: "commit",
				repo: review.repo,
				pr: review.pr,
				commit: sha,
			});
			pushed.push({ commit: sha, repo: review.repo, pr: review.pr, started: false });
		}
		// The quiet window reads the flag when it evaluates the event: keep it on until each review STARTED.
		const deadline = Date.now() + 10 * 60_000;
		while (Date.now() < deadline && pushed.some((entry) => !entry.started)) {
			for (const entry of pushed.filter((candidate) => !candidate.started)) {
				const events = await cluster.sql(
					`SELECT state FROM item_event WHERE workspace_id = ${workspace} AND item_version LIKE '%:${entry.commit}:%';`,
				);
				entry.started = events.some((event) => event[0] === "STARTED");
			}
			await sleep(10_000);
		}
		evidence.line("trigger.jsonl", { operation: "started", reviews: pushed });
	} finally {
		if (!wasOn) {
			const restored = await cluster.sqlWrite(`${restoreSql.replace(";", "")} RETURNING id;`);
			evidence.line("trigger.jsonl", { operation: "restore", rows: restored.length });
			await onStaging(["sudo", "-n", "systemctl", "stop", `${unit}.timer`].map(quote).join(" "));
			evidence.line("trigger.jsonl", { operation: "reset-disarmed", unit });
		}
	}
	return pushed;
}
