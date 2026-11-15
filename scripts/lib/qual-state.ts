/**
 * Read-only views of the agent system's state, shared by drills.ts and bench.ts: the rows of
 * `agent_run`, `agent_sandbox`, `agent_action`, `llm_call_permit` and `llm_usage_event` as the
 * Liquibase changelogs define them, and the reaper and outbox counters from each Pod's Prometheus text.
 * No query selects a payload, a recipient, a lane key or an idempotency key: they can hold personal data.
 */
import { arr, type Cluster, json, num, path2, str } from "./qual-cluster.ts";

/** A column's type in a `json_agg` row; a `?` type admits null. */
export type Column = "string" | "number" | "boolean" | "string?" | "number?";
export type Shape = Readonly<Record<string, Column>>;
type ColumnType<C extends Column> = C extends "string"
	? string
	: C extends "number"
		? number
		: C extends "boolean"
			? boolean
			: C extends "string?"
				? string | null
				: number | null;
/** The row that a shape describes. */
export type RowOf<S extends Shape> = { -readonly [K in keyof S]: ColumnType<S[K]> };

function matches(value: unknown, column: Column): boolean {
	if (value === null) {
		return column.endsWith("?");
	}
	return typeof value === column.replace("?", "");
}

/** Whether a parsed row has every column of the shape with its type: the check at the database's trust boundary. */
export function isRow<S extends Shape>(value: unknown, shape: S): value is RowOf<S> {
	return (
		value !== null &&
		typeof value === "object" &&
		Object.entries(shape).every(([key, column]) => matches(Reflect.get(value, key), column))
	);
}

const RUN = {
	id: "string",
	workspace_id: "number",
	kind: "string",
	band: "string",
	concurrency_key: "string",
	state: "string",
	attempt: "number",
	lease_epoch: "number",
	leased_until: "string?",
	lease_expired: "boolean",
	stop_reason: "string?",
	created_at: "string",
	available_at: "string",
	started_at: "string?",
	ended_at: "string?",
} as const satisfies Shape;
export type RunRow = RowOf<typeof RUN>;

const SANDBOX = {
	id: "string",
	workspace_id: "number",
	subject_key: "string",
	kind: "string",
	claim_name: "string",
	state: "string",
	created_at: "string",
	last_used_at: "string",
	shutdown_at: "string",
} as const satisfies Shape;
export type SandboxRow = RowOf<typeof SANDBOX>;

const ACTION = {
	id: "string",
	workspace_id: "number?",
	kind: "string",
	state: "string",
	attempts: "number",
	stop_reason: "string?",
	write_started: "boolean",
	leased_until: "string?",
	has_readback: "boolean",
	created_at: "string",
	ended_at: "string?",
} as const satisfies Shape;
export type ActionRow = RowOf<typeof ACTION>;

/** `lease_lapsed`: the owner stopped renewing for longer than the permit lease, so admission prunes the row lazily. */
const PERMIT = {
	id: "string",
	worker_id: "string",
	band: "string",
	workspace_id: "number?",
	state: "string",
	acquired_at: "string",
	granted_at: "string?",
	closed_at: "string?",
	lease_lapsed: "boolean",
} as const satisfies Shape;
export type PermitRow = RowOf<typeof PERMIT>;

export interface Snapshot {
	at: string;
	runs: RunRow[];
	sandboxes: SandboxRow[];
	actions: ActionRow[];
	permits: PermitRow[];
	errors: string[];
}

/** Rows of one query as objects: `json_agg` keeps the column names and types across the transport. */
async function rows<S extends Shape>(
	cluster: Cluster,
	query: string,
	errors: string[],
	name: string,
	shape: S,
): Promise<RowOf<S>[]> {
	try {
		// json_agg separates elements with a newline, and psql -At prints that as a second line. Newlines inside a JSON
		// value are escaped, so removing the literal ones keeps the array on the one line this reads.
		const [line] = await cluster.sql(
			`SELECT replace(coalesce(json_agg(t), '[]'::json)::text, E'\n', '') FROM (${query}) t;`,
		);
		const parsed = arr(json(line?.[0] ?? "[]"));
		const valid = parsed.filter((row) => isRow(row, shape));
		if (valid.length !== parsed.length) {
			errors.push(`${name}: ${parsed.length - valid.length} rows do not have the expected columns`);
			return [];
		}
		return valid;
	} catch (error) {
		errors.push(
			`${name}: ${error instanceof Error ? (error.message.split("\n")[0] ?? "") : String(error)}`,
		);
		return [];
	}
}

/** Live rows and those that ended within the look-back window. */
export async function snapshot(cluster: Cluster, lookBackMinutes = 180): Promise<Snapshot> {
	const errors: string[] = [];
	const since = `now() - interval '${Math.max(1, Math.round(lookBackMinutes))} minutes'`;
	const [runs, sandboxes, actions, permits, now] = await Promise.all([
		rows(
			cluster,
			`SELECT id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch, leased_until,
			        coalesce(leased_until < now(), false) AS lease_expired, stop_reason, created_at, available_at,
			        started_at, ended_at
			   FROM agent_run WHERE state <> 'ENDED' OR ended_at > ${since} ORDER BY created_at`,
			errors,
			"agent_run",
			RUN,
		),
		rows(
			cluster,
			`SELECT id, workspace_id, subject_key, kind, claim_name, state, created_at, last_used_at, shutdown_at
			   FROM agent_sandbox ORDER BY created_at`,
			errors,
			"agent_sandbox",
			SANDBOX,
		),
		rows(
			cluster,
			`SELECT id, workspace_id, kind, state, attempts, stop_reason, write_started_at IS NOT NULL AS write_started,
			        leased_until, readback_ref IS NOT NULL AS has_readback, created_at, ended_at
			   FROM agent_action WHERE ended_at IS NULL OR ended_at > ${since} ORDER BY created_at`,
			errors,
			"agent_action",
			ACTION,
		),
		rows(
			cluster,
			// The lease is `hephaestus.llm.call-pools.permit-lease` (PT1M by default); a lapsed row is no longer capacity.
			`SELECT id, worker_id, band, workspace_id, state, acquired_at, granted_at, closed_at,
			        coalesce(renewed_at, acquired_at) < now() - interval '1 minute' AS lease_lapsed
			   FROM llm_call_permit WHERE closed_at IS NULL OR closed_at > ${since} ORDER BY acquired_at`,
			errors,
			"llm_call_permit",
			PERMIT,
		),
		rows(cluster, "SELECT now() AS now", errors, "now", { now: "string" }),
	]);
	return { at: now[0]?.now ?? new Date().toISOString(), runs, sandboxes, actions, permits, errors };
}

export interface UsageSummary {
	rows: number;
	calls: number;
	unverifiable: number;
	zeroTokenVerified: number;
	byJobType: Record<string, number>;
}

/** `llm_usage_event` rows since a time, counted by job type. */
export async function usageSince(cluster: Cluster, since: string): Promise<UsageSummary> {
	const errors: string[] = [];
	const list = await rows(
		cluster,
		`SELECT job_type, count(*)::int AS rows, coalesce(sum(total_calls), 0)::int AS calls,
		        count(*) FILTER (WHERE measurement_verification = 'UNVERIFIABLE')::int AS unverifiable,
		        count(*) FILTER (WHERE measurement_verification = 'VERIFIED' AND input_tokens = 0 AND output_tokens = 0)::int AS zero_verified
		   FROM llm_usage_event WHERE occurred_at >= '${since.replaceAll("'", "")}'::timestamptz GROUP BY job_type`,
		errors,
		"llm_usage_event",
		{
			job_type: "string",
			rows: "number",
			calls: "number",
			unverifiable: "number",
			zero_verified: "number",
		},
	);
	if (errors.length > 0) {
		throw new Error(errors.join("; "));
	}
	return {
		rows: list.reduce((sum, row) => sum + row.rows, 0),
		calls: list.reduce((sum, row) => sum + row.calls, 0),
		unverifiable: list.reduce((sum, row) => sum + row.unverifiable, 0),
		zeroTokenVerified: list.reduce((sum, row) => sum + row.zero_verified, 0),
		byJobType: Object.fromEntries(list.map((row) => [row.job_type, row.calls])),
	};
}

export { rows as sqlRows };

// ---------------------------------------------------------------------------------------------------
// Prometheus text.

export interface Sample {
	name: string;
	labels: Record<string, string>;
	value: number;
}

export function parsePrometheus(text: string, keep?: RegExp): Sample[] {
	const samples: Sample[] = [];
	for (const line of text.split("\n")) {
		if (line === "" || line.startsWith("#")) {
			continue;
		}
		const match = /^(?<name>[a-zA-Z_:][\w:]*)(?:\{(?<labels>.*)\})?\s+(?<value>\S+)/u.exec(line);
		if (match === null) {
			continue;
		}
		const name = match.groups?.name ?? "";
		if (keep !== undefined && !keep.test(name)) {
			continue;
		}
		const labels: Record<string, string> = {};
		for (const pair of (match.groups?.labels ?? "").matchAll(
			/(?<key>\w+)="(?<label>(?:[^"\\]|\\.)*)"/gu,
		)) {
			labels[pair.groups?.key ?? ""] = pair.groups?.label ?? "";
		}
		samples.push({ name, labels, value: Number(match.groups?.value) });
	}
	return samples;
}

/** The counters and gauges the drills and the bench compare, by the metric names the server registers. */
export const AGENT_METRICS =
	/^(?:agent_run_|agent_sandbox_|action_|llm_pool_|llm_proxy_|llm_usage_|agent_job_|agent_queue_|conversation_turn_|gen_ai_|jvm_memory_used_bytes|jvm_gc_pause_seconds|jvm_gc_memory|process_cpu_usage|system_cpu_usage|hikaricp_connections|http_server_requests_seconds_count|executor_)/u;

/** Each server and worker Pod's samples, by Pod name. A Pod whose scrape fails maps to its error. */
export async function scrapeAll(
	cluster: Cluster,
	keep: RegExp = AGENT_METRICS,
): Promise<Record<string, Sample[] | { error: string }>> {
	const pods = [
		...(await cluster.componentPods("server")),
		...(await cluster.componentPods("worker")),
	];
	const scraped: Record<string, Sample[] | { error: string }> = {};
	await Promise.all(
		pods.map(async (pod) => {
			const port = pod.ports.find((entry) => entry.name === "management")?.port ?? 9090;
			try {
				scraped[pod.name] = parsePrometheus(await cluster.prometheus(pod.name, port), keep);
			} catch (error) {
				scraped[pod.name] = {
					error: error instanceof Error ? (error.message.split("\n")[0] ?? "") : String(error),
				};
			}
		}),
	);
	return scraped;
}

/** The sum of one metric over Pods, grouped by the given labels. */
export function sumBy(
	scrape: Record<string, Sample[] | { error: string }>,
	name: string,
	labels: readonly string[] = [],
): Record<string, number> {
	const sums: Record<string, number> = {};
	for (const samples of Object.values(scrape)) {
		if (!Array.isArray(samples)) {
			continue;
		}
		for (const sample of samples) {
			if (sample.name !== name) {
				continue;
			}
			const key =
				labels.map((label) => `${label}=${sample.labels[label] ?? ""}`).join(",") || "all";
			sums[key] = (sums[key] ?? 0) + sample.value;
		}
	}
	return sums;
}

export function delta(
	before: Record<string, number>,
	after: Record<string, number>,
): Record<string, number> {
	const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
	return Object.fromEntries([...keys].map((key) => [key, (after[key] ?? 0) - (before[key] ?? 0)]));
}

/** The reaper and outbox counters that the drills read before and after an injection. */
export const REAPER_COUNTERS = [
	["agent_run_reaped_total", ["kind", "outcome"]],
	["agent_run_reaper_failure_total", []],
	["agent_sandbox_reaped_total", ["kind", "reason"]],
	["agent_sandbox_reaper_failure_total", []],
	["action_ended_total", ["kind", "state"]],
	["llm_pool_permits_expired_total", []],
	["llm_pool_permit_abandoned_total", []],
] as const;

export function counters(
	scrape: Record<string, Sample[] | { error: string }>,
): Record<string, Record<string, number>> {
	return Object.fromEntries(
		REAPER_COUNTERS.map(([name, labels]) => [name, sumBy(scrape, name, labels)]),
	);
}

/** Pod restarts, OOM kills and evictions in a namespace: the B8 and drill side-effect check. */
export async function podHealth(
	cluster: Cluster,
	namespace: string,
): Promise<
	{
		name: string;
		component: string;
		uid: string;
		phase: string;
		reason: string;
		restarts: number;
		lastTerminated: string;
	}[]
> {
	const list = await cluster.json(["-n", namespace, "get", "pods"]);
	return arr(path2(list, ["items"])).map((pod) => {
		const statuses = arr(path2(pod, ["status", "containerStatuses"]));
		return {
			name: str(path2(pod, ["metadata", "name"])),
			component: str(path2(pod, ["metadata", "labels", "app.kubernetes.io/component"])),
			uid: str(path2(pod, ["metadata", "uid"])),
			phase: str(path2(pod, ["status", "phase"])),
			reason: str(path2(pod, ["status", "reason"])),
			restarts: statuses.reduce<number>(
				(sum, entry) => sum + (num(path2(entry, ["restartCount"])) || 0),
				0,
			),
			lastTerminated: statuses
				.map(
					(entry) =>
						str(path2(entry, ["lastState", "terminated", "reason"])) ||
						str(path2(entry, ["state", "terminated", "reason"])),
				)
				.filter(Boolean)
				.join(","),
		};
	});
}
