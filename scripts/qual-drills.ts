/**
 * The failure drills D1–D5 of docs/contributor/qualify-a-release.mdx. Each drill injects its failure, watches, and then
 * judges the expected outcome from read-only SQL on `agent_run`, `agent_sandbox`, `agent_action`, `llm_call_permit`,
 * `llm_usage_event`, `review` and the reaper counters.
 *
 *   node scripts/qual-drills.ts --target k3d|staging --drill D1|D2|D3|D4|D5|all [--minutes <watch>] [--allow-idle]
 *     [--victim <pod>] [--sandbox-pod <pod>] [--claim <name>] [--injection restart|external] [--item <id>]
 *
 * A drill needs work in flight to prove that no run is lost. Without a LEASED run it injects nothing and records
 * `not-run`, unless --allow-idle asks it to prove the injection and the recovery alone. D3 has no safe automated
 * injection: the script prints the manual step and, with --item, runs its read-only checks.
 */
import { parseArgs } from "node:util";

import { COMMON, commitOf, commonFrom, sleep } from "./lib/qual-args.ts";
import { items, num, path2, str } from "./lib/qual-cluster.ts";
import { decide, Evidence, RULES } from "./lib/qual-evidence.ts";
import {
	counters,
	delta,
	podHealth,
	type RunRow,
	scrapeAll,
	type Snapshot,
	snapshot,
	sqlRows,
	usageSince,
} from "./lib/qual-state.ts";
import { gateTemplate } from "./sandbox-isolation.ts";

const USAGE =
	"node scripts/qual-drills.ts --target k3d|staging --drill D1|D2|D3|D4|D5|all [--minutes <n>] [--allow-idle] [--victim <pod>] [--sandbox-pod <pod>] [--claim <name>] [--injection restart|external] [--item <id>] [--controller-namespace agent-sandbox-system]";

const { values } = parseArgs({
	options: {
		...COMMON,
		drill: { type: "string" },
		minutes: { type: "string" },
		"allow-idle": { type: "boolean", default: false },
		victim: { type: "string" },
		"sandbox-pod": { type: "string" },
		claim: { type: "string" },
		injection: { type: "string", default: "restart" },
		item: { type: "string" },
		"controller-namespace": { type: "string", default: "agent-sandbox-system" },
	},
});
const common = commonFrom(values, USAGE);
const { cluster } = common;
const ALL = ["D1", "D2", "D3", "D4", "D5"] as const;
type Drill = (typeof ALL)[number];
const chosen: Drill[] =
	values.drill === "all" ? [...ALL] : ALL.filter((drill) => drill === values.drill);
if (chosen.length === 0) {
	throw new Error(`--drill D1|D2|D3|D4|D5|all\n${USAGE}`);
}
const allowIdle = values["allow-idle"];
const LABEL = "hephaestus.build/isolation-gate";

// ---------------------------------------------------------------------------------------------------

const DRILLS: Record<Drill, (evidence: Evidence, notes: string[]) => Promise<void>> = {
	D1: d1,
	D2: d2,
	D3: d3,
	D4: d4,
	D5: d5,
};

function minutes(fallback: number): number {
	return values.minutes === undefined ? fallback : Math.max(1, Number(values.minutes));
}

/** The component whose Pods serve the sandbox gateway (port 8081). */
async function gatewayComponent(): Promise<string> {
	for (const component of ["worker", "server"]) {
		const pods = await cluster.componentPods(component);
		if (pods.some((pod) => pod.ports.some((entry) => entry.port === 8081))) {
			return component;
		}
	}
	return "server";
}

async function deploymentOf(
	component: string,
): Promise<{ name: string; desired: number; ready: number; available: number } | null> {
	const list = await cluster.json([
		"-n",
		cluster.namespace,
		"get",
		"deployments",
		"-l",
		`app.kubernetes.io/component=${component}`,
	]);
	const deployment = items(list)[0];
	if (deployment === undefined) {
		return null;
	}
	return {
		name: str(path2(deployment, ["metadata", "name"])),
		desired: num(path2(deployment, ["spec", "replicas"])),
		ready: num(path2(deployment, ["status", "readyReplicas"])) || 0,
		available: num(path2(deployment, ["status", "availableReplicas"])) || 0,
	};
}

async function record(
	evidence: Evidence,
	name: string,
): Promise<{ state: Snapshot; metrics: Record<string, Record<string, number>> }> {
	const [state, scrape] = await Promise.all([snapshot(cluster), scrapeAll(cluster)]);
	evidence.raw(`${name}-state.json`, state);
	evidence.raw(`${name}-metrics.json`, scrape);
	readable(state, name);
	return { state, metrics: counters(scrape) };
}

/**
 * A view that failed to read comes back as an empty list, and an empty list reads as "nothing was in flight", so a
 * check would go not-run or pass on a renamed column. A drill therefore stops on any unreadable view instead of judging.
 */
function readable(state: Snapshot, name: string): void {
	if (state.errors.length > 0) {
		throw new Error(
			`the ${name} state is unreadable, so no check can judge it: ${state.errors.join("; ")}`,
		);
	}
}

/** Watches the given runs until each has ended or moved on, or the time is up; writes every tick. */
async function watch(
	evidence: Evidence,
	stopAfterMinutes: number,
	done: (state: Snapshot) => boolean,
	tick?: () => Promise<Record<string, unknown>>,
): Promise<Snapshot> {
	const deadline = Date.now() + stopAfterMinutes * 60_000;
	let last = await snapshot(cluster);
	while (Date.now() < deadline) {
		await sleep(10_000);
		last = await snapshot(cluster);
		evidence.line("watch.jsonl", {
			runs: last.runs
				.filter((run) => run.state !== "ENDED")
				.map((run) => ({
					id: run.id,
					state: run.state,
					attempt: run.attempt,
					epoch: run.lease_epoch,
					expired: run.lease_expired,
				})),
			sandboxes: last.sandboxes.map((row) => ({ subject: row.subject_key, state: row.state })),
			actions: last.actions
				.filter((action) => action.ended_at === null)
				.map((action) => ({ id: action.id, state: action.state })),
			...(last.errors.length === 0 ? {} : { errors: last.errors }),
			...(tick === undefined ? {} : await tick()),
		});
		// A tick that could not read a view proves nothing either way; the next tick reads again.
		if (last.errors.length === 0 && done(last)) {
			break;
		}
	}
	readable(last, "final");
	return last;
}

/** The outcome of each run that was LEASED at the injection: lost, cut, moved on or completed. */
function runOutcomes(
	before: RunRow[],
	after: Snapshot,
): { id: string; outcome: string; lost: boolean; cut: boolean }[] {
	return before.map((run) => {
		const now = after.runs.find((row) => row.id === run.id);
		if (now === undefined) {
			// A review's REVIEW run row goes when its compose run takes the same subject; follow the subject, and let
			// <drill>-reviews judge the outcome. A subject with no run left is lost.
			const successor = after.runs.find((row) => row.concurrency_key === run.concurrency_key);
			return successor === undefined
				? { id: run.id, outcome: "row gone, and no run holds its subject", lost: true, cut: true }
				: {
						id: run.id,
						outcome: `moved on to ${successor.kind} ${successor.id.slice(0, 8)} (${successor.state}, attempt ${successor.attempt})`,
						lost: false,
						cut: successor.kind === run.kind,
					};
		}
		if (now.state === "ENDED") {
			const completed = now.stop_reason === "COMPLETED" || now.stop_reason === "CANCELLED";
			return {
				id: run.id,
				outcome: `ENDED ${now.stop_reason ?? ""} (attempt ${run.attempt}→${now.attempt}, epoch ${run.lease_epoch}→${now.lease_epoch})`,
				lost: !completed,
				cut: now.attempt > run.attempt,
			};
		}
		if (now.state === "LEASED" && now.lease_expired) {
			return {
				id: run.id,
				outcome: `still LEASED with an expired lease (epoch ${now.lease_epoch})`,
				lost: true,
				cut: true,
			};
		}
		return {
			id: run.id,
			outcome: `${now.state} (attempt ${run.attempt}→${now.attempt}, epoch ${run.lease_epoch}→${now.lease_epoch})`,
			lost: false,
			cut: now.attempt > run.attempt,
		};
	});
}

function idle(evidence: Evidence, drill: string, leased: RunRow[]): boolean {
	if (leased.length > 0 || allowIdle) {
		if (leased.length === 0) {
			evidence.log(
				`${drill}: no run in flight; --allow-idle proves the injection and the recovery only`,
			);
		}
		return false;
	}
	evidence.notRun(
		`${drill}-precondition`,
		"a run is in flight at the injection",
		"work in flight: start a review first (smoke.ts or bench.ts starts them on staging), or pass --allow-idle for the injection alone",
		"no agent_run is LEASED, so the drill injected nothing",
	);
	return true;
}

// D1 -------------------------------------------------------------------------------------------------

async function d1(evidence: Evidence, notes: string[]): Promise<void> {
	const component = values.victim === undefined ? await gatewayComponent() : "";
	const pods = component === "" ? [] : await cluster.componentPods(component);
	const victim = values.victim ?? pods[0]?.name;
	if (victim === undefined) {
		throw new Error(`no running ${component} Pod to kill`);
	}
	const victimComponent =
		component ||
		str(
			path2(await cluster.json(["-n", cluster.namespace, "get", "pod", victim]), [
				"metadata",
				"labels",
				"app.kubernetes.io/component",
			]),
		);
	notes.push(
		`Victim: ${victim} (${victimComponent}), the component that serves the sandbox gateway on this commit.`,
	);
	const before = await record(evidence, "before");
	const leased = before.state.runs.filter((run) => run.state === "LEASED");
	if (idle(evidence, "D1", leased)) {
		return;
	}
	const openPermits = before.state.permits.filter((permit) => permit.closed_at === null);
	const executing = before.state.actions.filter((action) => action.state === "EXECUTING");
	const killedAt = new Date().toISOString();
	evidence.log(`D1: kill ${victim} with --grace-period=0`);
	await cluster.kubectl([
		"-n",
		cluster.namespace,
		"delete",
		"pod",
		victim,
		"--grace-period=0",
		"--force",
		"--wait=false",
	]);
	const deployment = await deploymentOf(victimComponent);
	const ready: { at: number | null } = { at: null };
	const killed = Date.now();
	const after = await watch(
		evidence,
		minutes(10),
		(state) =>
			ready.at !== null &&
			leased.every((run) => state.runs.find((row) => row.id === run.id)?.state === "ENDED") &&
			openPermits.every(
				(permit) => state.permits.find((row) => row.id === permit.id)?.closed_at !== null,
			),
		async () => {
			const now = await deploymentOf(victimComponent);
			if (ready.at === null && now !== null && now.ready >= now.desired) {
				ready.at = Date.now();
			}
			return { deployment: now };
		},
	);
	const final = await record(evidence, "after");
	evidence.check({
		id: "D1-replica",
		title: "the killed replica is replaced and Ready",
		status: ready.at === null ? "fail" : "pass",
		detail:
			ready.at === null
				? `${deployment?.name ?? victimComponent} not back to ${deployment?.desired ?? "?"} Ready replicas`
				: `Ready again after ${Math.round((ready.at - killed) / 1000)} s`,
	});
	await judgeRuns(
		evidence,
		"D1",
		leased,
		after,
		`${RULES} § D1: the review completes; a cut attempt repeats, no run is lost`,
	);
	const permits = openPermits.map((permit) => {
		const now = after.permits.find((row) => row.id === permit.id);
		// Admission prunes expired permits lazily (before each grant round), so a row past its lease is returned capacity
		// even while no new call has pruned it yet.
		return {
			id: permit.id,
			holder: permit.worker_id,
			closed: now === undefined || now.closed_at !== null || now.lease_lapsed,
			closedAt: now?.closed_at ?? null,
			leaseLapsed: now?.lease_lapsed ?? null,
		};
	});
	evidence.check(
		openPermits.length === 0
			? {
					id: "D1-permits",
					title: "the dead replica's permits return",
					status: "not-run",
					needs: "a held permit at the kill (a review that calls the model)",
					detail: "no permit was open at the kill",
				}
			: {
					id: "D1-permits",
					title: "the dead replica's permits return",
					status: permits.every((permit) => permit.closed) ? "pass" : "fail",
					rule: `${RULES} § D1: every permit open at the kill closes or lapses`,
					detail: `${permits.filter((permit) => permit.closed).length}/${permits.length} closed`,
					data: permits,
				},
	);
	const usage = await usageSince(cluster, killedAt);
	evidence.check({
		id: "D1-usage",
		title: "usage rows after the kill: cut calls UNVERIFIABLE, never zero",
		status: usage.zeroTokenVerified === 0 ? "recorded" : "fail",
		detail: `${usage.rows} rows, ${usage.calls} calls, ${usage.unverifiable} UNVERIFIABLE, ${usage.zeroTokenVerified} VERIFIED with zero tokens`,
		data: usage,
	});
	outboxCheck(evidence, "D1", executing, after);
	evidence.check({
		id: "D1-reaper",
		title: "reaper and outbox counters",
		status: "recorded",
		detail: JSON.stringify(nonZero(final.metrics, before.metrics)),
	});
}

async function judgeRuns(
	evidence: Evidence,
	drill: string,
	leased: RunRow[],
	after: Snapshot,
	rule: string,
): Promise<void> {
	if (leased.length === 0) {
		evidence.check({
			id: `${drill}-runs`,
			title: "no run is lost",
			status: "not-run",
			needs: "a LEASED run at the injection",
			detail: "--allow-idle: no run was in flight",
		});
		return;
	}
	const outcomes = runOutcomes(leased, after);
	evidence.check({
		id: `${drill}-runs`,
		title: "no run is lost",
		status: outcomes.some((outcome) => outcome.lost) ? "fail" : "pass",
		rule,
		detail: outcomes.map((outcome) => `${outcome.id.slice(0, 8)}: ${outcome.outcome}`).join("; "),
		data: outcomes,
	});
	evidence.check({
		id: `${drill}-cut`,
		title: "attempts spent by the injection",
		status: "recorded",
		detail: `${outcomes.filter((outcome) => outcome.cut).length} of ${outcomes.length} runs repeated an attempt`,
	});
	await judgeReviews(evidence, drill, leased, rule);
}

/**
 * A run can end COMPLETED while its review failed (preparation failed after a server kill, and the run still read
 * COMPLETED), so "no run is lost" alone is not "the review completes". This reads the review behind each REVIEW run
 * and fails on a review that ended without completing.
 */
async function judgeReviews(
	evidence: Evidence,
	drill: string,
	leased: RunRow[],
	rule: string,
): Promise<void> {
	const ids = leased
		.filter((run) => run.kind === "REVIEW" && run.concurrency_key.startsWith("review:"))
		.map((run) => run.concurrency_key.slice("review:".length))
		.filter((id) => /^[0-9a-f-]{36}$/u.test(id));
	if (ids.length === 0) {
		evidence.notRun(
			`${drill}-reviews`,
			"the review behind each cut run completes",
			"a REVIEW run in flight at the injection",
			"no REVIEW run was leased",
		);
		return;
	}
	const read = async () => {
		const rows = await cluster.sql(
			`SELECT id, status, coalesce(stop_reason, '') FROM review WHERE id IN (${ids.map((id) => `'${id}'`).join(",")});`,
		);
		return rows.map(([id = "", status = "", stop = ""]) => ({ id, status, stop }));
	};
	// The compose phase runs after the run that was cut has gone, so the review can still be running when the runs settle.
	const deadline = Date.now() + 20 * 60_000;
	let reviews = await read();
	while (
		reviews.some((review) => review.status === "QUEUED" || review.status === "RUNNING") &&
		Date.now() < deadline
	) {
		await sleep(15_000);
		reviews = await read();
	}
	// PARTIAL with a withhold (`EXECUTION_INCOMPLETE`, `READINESS_REFUSED`) is an honest completion: the trust rule kept a
	// claim back. Only a review that lost its work is a failure here.
	const LOST = new Set(["EXECUTION_FAILED", "PREPARATION_FAILED"]);
	const failed = reviews.filter(
		(review) =>
			!(
				review.status === "COMPLETED" ||
				review.status === "COMPLETE" ||
				(review.status === "PARTIAL" && !LOST.has(review.stop))
			),
	);
	evidence.check({
		id: `${drill}-reviews`,
		title: "the review behind each cut run completes",
		status: failed.length === 0 && reviews.length === ids.length ? "pass" : "fail",
		rule,
		detail:
			reviews
				.map(
					(review) =>
						`${review.id.slice(0, 8)}: ${review.status}${review.stop === "" ? "" : ` ${review.stop}`}`,
				)
				.join("; ") || "no review row",
		data: reviews,
	});
}

function outboxCheck(
	evidence: Evidence,
	drill: string,
	executing: Snapshot["actions"],
	after: Snapshot,
): void {
	if (executing.length === 0) {
		evidence.notRun(
			`${drill}-outbox`,
			"an email past its write fence ends UNCERTAIN, never sent twice",
			"an action EXECUTING at the injection (send an email notice during the drill)",
			"no agent_action was EXECUTING at the injection",
		);
		return;
	}
	const outcomes = executing.map((action) => {
		const now = after.actions.find((row) => row.id === action.id);
		return {
			id: action.id,
			kind: action.kind,
			wrote: action.write_started,
			state: now?.state ?? "gone",
			attempts: now?.attempts ?? null,
		};
	});
	const resent = outcomes.filter(
		(outcome) => outcome.wrote && outcome.state === "DONE" && outcome.kind === "EMAIL",
	);
	evidence.check({
		id: `${drill}-outbox`,
		title: "an email past its write fence ends UNCERTAIN, never sent twice",
		status: resent.length === 0 ? "pass" : "fail",
		rule: `${RULES} § D1: an EMAIL with write_started at the injection must not end DONE through a second attempt`,
		detail: outcomes
			.map((outcome) => `${outcome.kind} ${outcome.id.slice(0, 8)} → ${outcome.state}`)
			.join("; "),
		data: outcomes,
	});
}

function nonZero(
	after: Record<string, Record<string, number>>,
	before: Record<string, Record<string, number>>,
): Record<string, Record<string, number>> {
	return Object.fromEntries(
		Object.entries(after).map(([name, series]) => [
			name,
			Object.fromEntries(
				Object.entries(delta(before[name] ?? {}, series)).filter(([, value]) => value !== 0),
			),
		]),
	);
}

// D2 -------------------------------------------------------------------------------------------------

async function d2(evidence: Evidence, notes: string[]): Promise<void> {
	const before = await record(evidence, "before");
	const leased = before.state.runs.filter((run) => run.state === "LEASED");
	const pods = items(await cluster.json(["-n", cluster.sandboxes, "get", "pods"])).filter(
		(pod) =>
			str(path2(pod, ["status", "phase"])) === "Running" &&
			str(path2(pod, ["metadata", "labels", LABEL])) === "",
	);
	const claimNames = new Set(before.state.sandboxes.map((row) => row.claim_name));
	const owned = pods.find((pod) => claimNames.has(str(path2(pod, ["metadata", "name"]))));
	const target = values["sandbox-pod"] ?? str(path2(owned, ["metadata", "name"]));
	if (target === "") {
		evidence.notRun(
			"D2-pod",
			"delete a sandbox Pod mid-run: the lease expires and the reaper requeues",
			"a review running in a claimed sandbox (start one first)",
			"no claimed sandbox Pod runs",
		);
	} else if (!idle(evidence, "D2", leased)) {
		notes.push(`Pod variant: deleted ${target}.`);
		evidence.log(`D2: delete sandbox Pod ${target}`);
		await cluster.kubectl(["-n", cluster.sandboxes, "delete", "pod", target, "--wait=false"]);
		const after = await watch(evidence, minutes(10), (state) =>
			leased.every(
				(run) =>
					state.runs.find((row) => row.id === run.id)?.state !== "LEASED" ||
					(state.runs.find((row) => row.id === run.id)?.lease_epoch ?? 0) > run.lease_epoch,
			),
		);
		await judgeRuns(
			evidence,
			"D2",
			leased,
			after,
			`${RULES} § D2: the run moves on to a new holder (epoch + 1) and is not lost`,
		);
		// A lost sandbox is an infrastructure loss: the run continues under a new epoch, with or without spending an
		// attempt. What must hold is that it moved on to a new holder and was not lost.
		const outcomes = runOutcomes(leased, after);
		const movedOn = leased.filter((run) => {
			const now = after.runs.find((row) => row.id === run.id);
			return now !== undefined && (now.lease_epoch > run.lease_epoch || now.state !== "LEASED");
		});
		const lost = outcomes.filter((outcome) => outcome.lost);
		evidence.check({
			id: "D2-requeue",
			title: "the run moves on to a new holder (epoch + 1) and is not lost",
			status: decide(
				[
					[leased.length === 0, "not-run"],
					[lost.length === 0 && movedOn.length === leased.length, "pass"],
				],
				"fail",
			),
			...(leased.length === 0 ? { needs: "a LEASED run" } : {}),
			detail: `${movedOn.length} of ${leased.length} runs moved on; ${outcomes.filter((outcome) => outcome.cut).length} spent an attempt; ${lost.length} lost`,
		});
		const final = await record(evidence, "after");
		evidence.check({
			id: "D2-reaper",
			title: "reaper counters",
			status: "recorded",
			detail: JSON.stringify(nonZero(final.metrics, before.metrics)),
		});
	}
	// The claim variant needs an agent_sandbox row: the claim belongs to the owner row.
	const row =
		values.claim === undefined
			? before.state.sandboxes.find((entry) => entry.state === "READY")
			: before.state.sandboxes.find((entry) => entry.claim_name === values.claim);
	if (row === undefined) {
		evidence.notRun(
			"D2-claim",
			"delete the SandboxClaim: agent_sandbox goes, the start limit recreates it",
			"a READY agent_sandbox row (a review or a conversation in a sandbox)",
			"no agent_sandbox row is READY",
		);
		return;
	}
	evidence.log(`D2: delete SandboxClaim ${row.claim_name} (${row.subject_key})`);
	await cluster.kubectl([
		"-n",
		cluster.sandboxes,
		"delete",
		"sandboxclaim",
		row.claim_name,
		"--wait=false",
	]);
	const after = await watch(
		evidence,
		minutes(10),
		(state) => !state.sandboxes.some((entry) => entry.id === row.id),
	);
	const gone = !after.sandboxes.some((entry) => entry.id === row.id);
	const again = after.sandboxes.find(
		(entry) => entry.subject_key === row.subject_key && entry.id !== row.id,
	);
	evidence.check({
		id: "D2-claim",
		title: "the owner row leaves after its claim is deleted",
		status: gone ? "pass" : "fail",
		detail: gone
			? "agent_sandbox row gone"
			: `row still ${after.sandboxes.find((entry) => entry.id === row.id)?.state ?? "?"}`,
	});
	// A sandbox is recreated only for a subject that still has work: a claim deleted after its review ended stays gone.
	const subjectBusy = after.runs.some(
		(run) => run.concurrency_key === row.subject_key && run.state !== "ENDED",
	);
	evidence.check(
		subjectBusy || again !== undefined
			? {
					id: "D2-recreate",
					title: "the start limit recreates the subject's sandbox",
					status: again === undefined ? "fail" : "pass",
					detail:
						again === undefined
							? "no new row for the subject, which still has a run"
							: `new row ${again.state}`,
				}
			: {
					id: "D2-recreate",
					title: "the start limit recreates the subject's sandbox",
					status: "not-run",
					needs: "a subject that still has an open run when its claim is deleted",
					detail: "the subject's runs had all ended, so no sandbox was due",
				},
	);
}

// D3 -------------------------------------------------------------------------------------------------

async function d3(evidence: Evidence, notes: string[]): Promise<void> {
	notes.push(
		"D3 has no safe automated injection: dropping a provider event needs a webhook delivery that the receiver never gets.",
		"Manual step: pick an item that is mid-review. Disable the webhook at the provider, push a second commit to its pull request, and enable the webhook again. Wait for the scheduled sync.",
		"Then run: node scripts/qual-staging-hold.ts -- node scripts/qual-drills.ts --target staging --drill D3 --item <item id> --minutes 30",
	);
	if (values.item === undefined) {
		evidence.notRun(
			"D3",
			"a lost event: the old head's review completes or is superseded; the sync finds the change",
			"the manual step above, then --item <id>",
			"manual drill: no --item given",
		);
		return;
	}
	const item = Number(values.item);
	if (!Number.isSafeInteger(item)) {
		throw new Error("--item must be a numeric item id");
	}
	const errors: string[] = [];
	const window = `now() - interval '${minutes(30) + 60} minutes'`;
	const reviews = await sqlRows(
		cluster,
		`SELECT id, item_version, status, stop_reason, created_at, completed_at FROM review
		  WHERE item_id = ${item} AND created_at > ${window} ORDER BY created_at`,
		errors,
		"review",
		{
			id: "string",
			item_version: "string",
			status: "string",
			stop_reason: "string?",
			created_at: "string",
			completed_at: "string?",
		},
	);
	const [latest] = await sqlRows(
		cluster,
		`SELECT item_version, occurred_at FROM item_event WHERE item_id = ${item} ORDER BY occurred_at DESC LIMIT 1`,
		errors,
		"item_event",
		{ item_version: "string", occurred_at: "string" },
	);
	evidence.raw("d3-reviews.json", { reviews, latest, errors });
	if (errors.length > 0) {
		evidence.check({
			id: "D3-harness",
			title: "the D3 reads completed",
			status: "fail",
			detail: errors.join("; "),
		});
		return;
	}
	const current = reviews.filter((review) => review.item_version === latest?.item_version);
	evidence.check({
		id: "D3-sync",
		title: "the scheduled sync finds the missed change: the item's latest version has a review",
		status: decide(
			[
				[latest === undefined, "not-run"],
				[current.length > 0, "pass"],
			],
			"fail",
		),
		...(latest === undefined ? { needs: "an item_event for the item" } : {}),
		detail: `latest version ${latest?.item_version ?? "?"}: ${current.length} reviews`,
	});
	const older = reviews.filter((review) => review.item_version !== latest?.item_version);
	evidence.check({
		id: "D3-old-head",
		title: "the old head's review completed or was superseded",
		status: decide(
			[
				[older.length === 0, "not-run"],
				[older.every((review) => review.completed_at !== null), "pass"],
			],
			"fail",
		),
		...(older.length === 0 ? { needs: "a review of the old head in the window" } : {}),
		detail:
			older
				.map((review) => `${review.id.slice(0, 8)} ${review.status} ${review.stop_reason ?? ""}`)
				.join("; ") || "none",
	});
	evidence.notRun(
		"D3-head",
		"no delivered feedback names a head the item no longer has",
		"a reviewer's read of the delivered feedback against the latest version above",
	);
}

// D4 -------------------------------------------------------------------------------------------------

async function d4(evidence: Evidence, notes: string[]): Promise<void> {
	const namespace = values["controller-namespace"];
	const controller = items(await cluster.json(["-n", namespace, "get", "deployments"])).find(
		(deployment) =>
			str(path2(deployment, ["metadata", "name"])).includes("agent-sandbox-controller"),
	);
	if (controller === undefined) {
		throw new Error(`no agent-sandbox-controller Deployment in ${namespace}`);
	}
	const name = str(path2(controller, ["metadata", "name"]));
	const replicas = num(path2(controller, ["spec", "replicas"])) || 1;
	const before = await record(evidence, "before");
	const leased = before.state.runs.filter((run) => run.state === "LEASED");
	const queued = before.state.runs.filter((run) => run.state === "QUEUED");
	if (queued.length === 0 && leased.length === 0 && !allowIdle) {
		evidence.notRun(
			"D4-precondition",
			"runs are queued while the controller is down",
			"queued work (start reviews first), or --allow-idle for the injection alone",
			"no run queued or leased",
		);
		return;
	}
	const sandboxHealth = await podHealth(cluster, cluster.sandboxes);
	const sandboxPods = sandboxHealth.filter((pod) => pod.phase === "Running");
	// A probe claim, made while the controller is down, must get no Pod; it must start once it is back.
	const review = items(
		await cluster.json(["-n", cluster.sandboxes, "get", "sandboxtemplates"]),
	).find((template) => str(path2(template, ["metadata", "name"])).endsWith("-sandbox-review"));
	const probe = `qual-d4-${String(Date.now() % 1_000_000)}`;
	const down = minutes(10);
	try {
		evidence.log(`D4: scale ${namespace}/${name} from ${replicas} to 0 for ${down} min`);
		await cluster.kubectl(["-n", namespace, "scale", `deployment/${name}`, "--replicas=0"]);
		await cluster
			.kubectl([
				"-n",
				namespace,
				"wait",
				"--for=delete",
				"pod",
				"-l",
				selectorOf(controller),
				"--timeout=120s",
			])
			.catch(() => "");
		if (review !== undefined) {
			await apply(gateTemplate(review, probe));
			await apply({
				apiVersion: "extensions.agents.x-k8s.io/v1beta1",
				kind: "SandboxWarmPool",
				metadata: { name: probe, namespace: cluster.sandboxes, labels: { [LABEL]: "true" } },
				spec: { replicas: 0, sandboxTemplateRef: { name: probe } },
			});
			await apply({
				apiVersion: "extensions.agents.x-k8s.io/v1beta1",
				kind: "SandboxClaim",
				metadata: { name: probe, namespace: cluster.sandboxes, labels: { [LABEL]: "true" } },
				spec: { warmPoolRef: { name: probe } },
			});
		}
		const during = await watch(
			evidence,
			down,
			() => false,
			async () => {
				const listed = await cluster.kubectlRaw([
					"-n",
					cluster.sandboxes,
					"get",
					"pod",
					probe,
					"-o",
					"name",
				]);
				return { probePod: listed.stdout.trim() };
			},
		);
		await judgeDowntime(evidence, {
			review: review !== undefined,
			probe,
			down,
			sandboxPods,
			during,
			sandboxesBefore: before.state.sandboxes,
			startedAt: before.state.at,
			queued: queued.length,
		});
	} finally {
		await cluster.kubectl([
			"-n",
			namespace,
			"scale",
			`deployment/${name}`,
			`--replicas=${replicas}`,
		]);
		await cluster.kubectl([
			"-n",
			namespace,
			"rollout",
			"status",
			`deployment/${name}`,
			"--timeout=5m",
		]);
		evidence.log(`D4: ${name} back to ${replicas}`);
	}
	if (review !== undefined) {
		const ready = await cluster.kubectlRaw([
			"-n",
			cluster.sandboxes,
			"wait",
			"--for=condition=Ready",
			`sandboxclaim/${probe}`,
			"--timeout=5m",
		]);
		evidence.check({
			id: "D4-recovery",
			title: "after the controller returns, the waiting claim starts",
			status: ready.code === 0 ? "pass" : "fail",
			detail:
				ready.code === 0 ? "the probe claim became Ready" : (ready.stderr.split("\n")[0] ?? ""),
		});
		for (const kind of ["sandboxclaim", "sandboxwarmpool", "sandboxtemplate"]) {
			await cluster.kubectlRaw([
				"-n",
				cluster.sandboxes,
				"delete",
				kind,
				probe,
				"--ignore-not-found",
				"--wait=false",
			]);
		}
	}
	const after = await watch(evidence, minutes(10) / 2, (state) =>
		state.runs.every((run) => run.state !== "QUEUED" || Date.parse(run.available_at) > Date.now()),
	);
	evidence.check({
		id: "D4-queued-start",
		title: "after the controller returns, the queued runs start",
		status: decide(
			[
				[queued.length === 0, "not-run"],
				[
					after.runs.some(
						(run) => queued.some((entry) => entry.id === run.id) && run.state === "QUEUED",
					),
					"fail",
				],
			],
			"pass",
		),
		...(queued.length === 0 ? { needs: "a QUEUED run at the injection" } : {}),
		detail: `${after.runs.filter((run) => run.state === "QUEUED").length} still QUEUED`,
	});
	if (before.state.sandboxes.length === 0) {
		evidence.notRun(
			"D4-start-limit",
			"REQUESTED rows hit the start limit, are deleted and requested again",
			"an agent_sandbox row at the injection",
			"no agent_sandbox row existed",
		);
	} else {
		evidence.check({
			id: "D4-start-limit",
			title: "agent_sandbox states through the downtime",
			status: "recorded",
			detail: `see raw/watch.jsonl (${before.state.sandboxes.length} rows at the start)`,
		});
	}
	notes.push(
		"The probe claim is a held clone of the review template (scripts/sandbox-isolation.ts), labelled hephaestus.build/isolation-gate=true; it is deleted at the end.",
	);
}

/** Run ends that are not a failure. A REVIEW run ends HANDED_OFF when its review moves on to composition. */
const NORMAL_ENDS = new Set(["COMPLETED", "CANCELLED", "HANDED_OFF"]);

interface Downtime {
	review: boolean;
	probe: string;
	down: number;
	sandboxPods: Awaited<ReturnType<typeof podHealth>>;
	during: Snapshot;
	sandboxesBefore: Snapshot["sandboxes"];
	startedAt: string;
	queued: number;
}

/** D4's checks at the end of the controller's downtime. */
async function judgeDowntime(
	evidence: Evidence,
	{ review, probe, down, sandboxPods, during, sandboxesBefore, startedAt, queued }: Downtime,
): Promise<void> {
	const listed = await cluster.kubectlRaw([
		"-n",
		cluster.sandboxes,
		"get",
		"pod",
		probe,
		"-o",
		"name",
	]);
	const probePod = listed.stdout.trim();
	evidence.check({
		id: "D4-no-new-sandbox",
		title: "no new sandbox starts while the controller is down",
		status: review && probePod === "" ? "pass" : "fail",
		detail: probeDetail(review, probePod, down),
	});
	const health = await podHealth(cluster, cluster.sandboxes);
	const stillRunning = health.filter((pod) => pod.phase === "Running");
	// The server deletes a sandbox after a run of its subject ended normally: that Pod was released, not moved by the
	// downtime. A reaped or revoked sandbox also loses its row, so the row alone does not prove a release.
	const released = (pod: { name: string }) => {
		const sandbox = sandboxesBefore.find((row) => row.claim_name === pod.name);
		return (
			during.errors.length === 0 &&
			sandbox !== undefined &&
			!during.sandboxes.some((row) => row.claim_name === pod.name) &&
			during.runs.some(
				(run) =>
					run.concurrency_key === sandbox.subject_key &&
					run.state === "ENDED" &&
					NORMAL_ENDS.has(run.stop_reason ?? "") &&
					Date.parse(run.ended_at ?? "") > Date.parse(startedAt),
			)
		);
	};
	const moved = sandboxPods.filter(
		(pod) =>
			!stillRunning.some((now) => now.uid === pod.uid && now.restarts === pod.restarts) &&
			!(released(pod) && !stillRunning.some((now) => now.uid === pod.uid)),
	);
	evidence.check({
		id: "D4-existing-keep",
		title: "existing sandboxes keep running",
		status: decide(
			[
				[sandboxPods.length === 0, "not-run"],
				[moved.length === 0, "pass"],
			],
			"fail",
		),
		...(sandboxPods.length === 0 ? { needs: "a running sandbox at the injection" } : {}),
		detail:
			sandboxPods.length === 0
				? "no sandbox Pod ran"
				: `${sandboxPods.length - moved.length}/${sandboxPods.length} unchanged${moved.length > 0 ? `; moved: ${moved.map((pod) => pod.name).join(", ")}` : ""}`,
	});
	const badEnds = during.runs.filter(
		(run) =>
			run.state === "ENDED" &&
			!NORMAL_ENDS.has(run.stop_reason ?? "") &&
			Date.parse(run.ended_at ?? "") > Date.parse(startedAt),
	);
	evidence.check({
		id: "D4-no-claim-about-work",
		title: "no run ends as a claim about work",
		status: badEnds.length === 0 ? "pass" : "fail",
		detail:
			badEnds.length === 0
				? "no run ended with a failure stop reason during the downtime"
				: badEnds.map((run) => `${run.id.slice(0, 8)} ${run.stop_reason ?? ""}`).join("; "),
	});
	evidence.check({
		id: "D4-queued-wait",
		title: "queued runs wait",
		status: "recorded",
		detail: `${during.runs.filter((run) => run.state === "QUEUED").length} QUEUED at the end of the downtime (${queued} before)`,
	});
}

function probeDetail(review: boolean, probePod: string, down: number): string {
	if (!review) {
		return "no review template for the probe claim";
	}
	return probePod === ""
		? `a claim made during the downtime got no Pod in ${down} min`
		: `the probe claim got ${probePod}`;
}

interface Rolled {
	component: string;
	deployment: { name: string; desired: number };
}

/** The lowest available replica count of each component while the update rolls, sampled every 2 s. */
async function watchAvailability(
	evidence: Evidence,
	components: Rolled[],
	restart: boolean,
): Promise<Record<string, number>> {
	const lowest: Record<string, number> = {};
	const until = Date.now() + minutes(10) * 60_000;
	let rolled = false;
	while (Date.now() < until && !rolled) {
		const states = await Promise.all(
			components.map(async (entry) => ({
				component: entry.component,
				now: await deploymentOf(entry.component),
				rollout: await cluster.kubectlRaw([
					"-n",
					cluster.namespace,
					"rollout",
					"status",
					`deployment/${entry.deployment.name}`,
					"--watch=false",
				]),
			})),
		);
		for (const state of states) {
			lowest[state.component] = Math.min(
				lowest[state.component] ?? Number.POSITIVE_INFINITY,
				state.now?.available ?? 0,
			);
		}
		evidence.line("availability.jsonl", {
			states: states.map((state) => ({
				component: state.component,
				available: state.now?.available,
				ready: state.now?.ready,
				rollout: state.rollout.stdout.trim(),
			})),
		});
		rolled =
			restart && states.every((state) => /successfully rolled out/u.test(state.rollout.stdout));
		await sleep(2000);
	}
	return lowest;
}

/** D5's precondition: a server that two replicas and a PodDisruptionBudget keep available through an update. */
async function checkPdb(
	evidence: Evidence,
	server: { desired: number } | undefined,
): Promise<void> {
	const pdbs = items(await cluster.json(["-n", cluster.namespace, "get", "poddisruptionbudgets"]));
	evidence.raw(
		"pdbs.json",
		pdbs.map((pdb) => ({
			name: str(path2(pdb, ["metadata", "name"])),
			spec: path2(pdb, ["spec"]),
		})),
	);
	const serverPdb = pdbs.find(
		(pdb) =>
			str(path2(pdb, ["spec", "selector", "matchLabels", "app.kubernetes.io/component"])) ===
			"server",
	);
	if (serverPdb === undefined || (server?.desired ?? 0) < 2) {
		evidence.notRun(
			"D5-pdb",
			"server ×2 with a PDB that keeps one replica",
			"server replicas ≥ 2 and a server PodDisruptionBudget",
			`server replicas ${server?.desired ?? 0}, PDB ${serverPdb === undefined ? "absent" : "present"}`,
		);
	} else {
		evidence.check({
			id: "D5-pdb",
			title: "server ×2 with a PDB",
			status: "pass",
			detail: `replicas ${server?.desired ?? 0}, PDB ${str(path2(serverPdb, ["metadata", "name"]))}`,
		});
	}
}

function selectorOf(deployment: unknown): string {
	const labels = path2(deployment, ["spec", "selector", "matchLabels"]);
	return Object.entries(labels !== null && typeof labels === "object" ? labels : {})
		.map(([key, value]) => `${key}=${String(value)}`)
		.join(",");
}

async function apply(manifest: unknown): Promise<void> {
	await cluster.kubectl(["apply", "-f", "-"], JSON.stringify(manifest));
}

// D5 -------------------------------------------------------------------------------------------------

async function d5(evidence: Evidence, notes: string[]): Promise<void> {
	const found = await Promise.all(
		["server", "worker"].map(async (component) => ({
			component,
			deployment: await deploymentOf(component),
		})),
	);
	const components = found.filter(
		(entry): entry is { component: string; deployment: NonNullable<typeof entry.deployment> } =>
			entry.deployment !== null,
	);
	const before = await record(evidence, "before");
	const leased = before.state.runs.filter((run) => run.state === "LEASED");
	if (idle(evidence, "D5", leased)) {
		return;
	}
	await checkPdb(evidence, components.find((entry) => entry.component === "server")?.deployment);
	const executing = before.state.actions.filter((action) => action.state === "EXECUTING");
	const { injection } = values;
	if (injection === "restart") {
		for (const entry of components) {
			evidence.log(`D5: rollout restart deployment/${entry.deployment.name}`);
			await cluster.kubectl([
				"-n",
				cluster.namespace,
				"rollout",
				"restart",
				`deployment/${entry.deployment.name}`,
			]);
		}
		notes.push(
			"Injection: `kubectl rollout restart` of the server and the worker (the same rolling replacement as `helm upgrade` with a new image, without changing the image).",
		);
	} else {
		notes.push(
			"Injection: external. Someone ran `helm upgrade` with a new image while this script watched.",
		);
	}
	const lowest = await watchAvailability(evidence, components, injection === "restart");
	for (const entry of components) {
		const kept = (lowest[entry.component] ?? 0) >= 1;
		const detail = `lowest available replicas ${lowest[entry.component] ?? 0} (desired ${entry.deployment.desired})`;
		const title = `the ${entry.component} keeps an available replica through the update`;
		// One server replica cannot stay available through its own replacement; the rule assumes server ×2.
		const single = entry.component === "server" && entry.deployment.desired < 2 && !kept;
		evidence.check(
			single
				? {
						id: `D5-available-${entry.component}`,
						title,
						status: "not-run",
						needs: "server replicas ≥ 2",
						detail: `${detail}: one replica on this release`,
					}
				: { id: `D5-available-${entry.component}`, title, status: kept ? "pass" : "fail", detail },
		);
	}
	const after = await watch(evidence, Math.max(1, minutes(10) / 2), (state) =>
		leased.every((run) => state.runs.find((row) => row.id === run.id)?.state === "ENDED"),
	);
	const outcomes = runOutcomes(leased, after);
	evidence.check(
		leased.length === 0
			? {
					id: "D5-no-run-cut",
					title: "no run is cut by the update",
					status: "not-run",
					needs: "a LEASED run at the injection",
					detail: "--allow-idle: no run was in flight",
				}
			: {
					id: "D5-no-run-cut",
					title: "no run is cut by the update",
					status: outcomes.some((outcome) => outcome.cut || outcome.lost) ? "fail" : "pass",
					rule: `${RULES} § D5: no run is cut`,
					detail: outcomes
						.map((outcome) => `${outcome.id.slice(0, 8)}: ${outcome.outcome}`)
						.join("; "),
				},
	);
	if (leased.length > 0) {
		await judgeReviews(
			evidence,
			"D5",
			leased,
			`${RULES} § D5: the review completes through the update`,
		);
	}
	outboxCheck(evidence, "D5", executing, after);
	const final = await record(evidence, "after");
	evidence.check({
		id: "D5-reaper",
		title: "reaper and outbox counters",
		status: "recorded",
		detail: JSON.stringify(nonZero(final.metrics, before.metrics)),
	});
	evidence.raw("pods-after.json", await podHealth(cluster, cluster.namespace));
}

// Last, so that every constant above is initialized before the drills run.
const description = await cluster.describe();
const commit = await commitOf();
let failed = false;
for (const drill of chosen) {
	const evidence = new Evidence(common.out, `drill-${drill}-${common.target}`);
	evidence.raw("cluster.json", description);
	const notes: string[] = [];
	try {
		await DRILLS[drill](evidence, notes);
	} catch (error) {
		evidence.check({
			id: `${drill}-harness`,
			title: "the drill harness completed",
			status: "fail",
			detail: error instanceof Error ? error.message : String(error),
		});
	}
	const verdict = evidence.finish({ command: common.command, commit, cluster: description }, notes);
	failed ||= verdict === "FAIL";
}
process.exitCode = failed ? 1 : 0;
