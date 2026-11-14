/**
 * Read-path measures of the bench: B10, the practice profile overview's latency, and B11, the database statements under
 * load. Both compare a snapshot before the bench window with one after it.
 */
import { readFileSync } from "node:fs";

import { percentile, sleep } from "./qual-args.ts";
import { type Cluster, json, path2, str } from "./qual-cluster.ts";
import { type Evidence, RULES } from "./qual-evidence.ts";
import { type RowOf, scrapeAll, type Shape, sqlRows } from "./qual-state.ts";

const HTTP_BUCKETS = /^http_server_requests_seconds_bucket$/u;

/** A named statement to look for in `pg_stat_statements`: a regular expression over its normalized text. */
export interface Named {
	name: string;
	pattern: RegExp;
}

const STATEMENT = {
	queryid: "string",
	calls: "number",
	total_ms: "number",
	query: "string",
} as const satisfies Shape;
type Statement = RowOf<typeof STATEMENT>;

export interface ReadsBefore {
	buckets: Record<string, number>;
	statements: Statement[] | null;
}

export interface ProbeOptions {
	/** The full URL of the overview route of a test member's workspace, for example https://host/api/workspaces/w/practice-profile/overview. */
	url?: string;
	/** A file whose only content is that member's bearer token. Its content is never printed. */
	tokenFile?: string;
}

/** The overview route's histogram buckets summed over the server Pods, by upper bound. */
async function overviewBuckets(cluster: Cluster): Promise<Record<string, number>> {
	const scrape = await scrapeAll(cluster, HTTP_BUCKETS);
	const sums: Record<string, number> = {};
	for (const samples of Object.values(scrape)) {
		if (!Array.isArray(samples)) {
			continue;
		}
		for (const sample of samples) {
			if (!(sample.labels.uri ?? "").endsWith("/practice-profile/overview")) {
				continue;
			}
			const le = sample.labels.le ?? "+Inf";
			sums[le] = (sums[le] ?? 0) + sample.value;
		}
	}
	return sums;
}

async function statements(cluster: Cluster, errors: string[]): Promise<Statement[] | null> {
	// The extension may live outside the connection's search_path (the chart installs it in `monitoring`), so its
	// view is named by the schema PostgreSQL records for it.
	const found = await cluster.sql(
		"SELECT quote_ident(extnamespace::regnamespace::text) FROM pg_extension WHERE extname = 'pg_stat_statements';",
	);
	const [schema] = found[0] ?? [];
	if (schema === undefined || schema === "") {
		errors.push("pg_stat_statements is not installed in this database");
		return null;
	}
	const rows = await sqlRows(
		cluster,
		// The view has one row per user, query and top-level flag, so one query ID can repeat: the window compares sums.
		`SELECT queryid::text AS queryid, sum(calls)::float8 AS calls, sum(total_exec_time) AS total_ms, left(min(query), 200) AS query
		   FROM ${schema}.pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
		  GROUP BY queryid`,
		errors,
		"pg_stat_statements",
		STATEMENT,
	);
	return errors.length > 0 ? null : rows;
}

export async function readsBefore(cluster: Cluster): Promise<ReadsBefore> {
	const errors: string[] = [];
	return { buckets: await overviewBuckets(cluster), statements: await statements(cluster, errors) };
}

/** Requests the overview every 10 s until `until`, and returns each latency in ms; a failed request counts as missing. */
export async function probeOverview(
	options: ProbeOptions,
	until: number,
): Promise<{ latencies: number[]; failures: number }> {
	const latencies: number[] = [];
	let failures = 0;
	if (options.url === undefined || options.tokenFile === undefined) {
		return { latencies, failures };
	}
	const token = readFileSync(options.tokenFile, "utf8").trim();
	while (Date.now() < until) {
		const started = performance.now();
		try {
			const response = await fetch(options.url, { headers: { Authorization: `Bearer ${token}` } });
			await response.arrayBuffer();
			if (response.ok) {
				latencies.push(performance.now() - started);
			} else {
				failures += 1;
			}
		} catch {
			failures += 1;
		}
		await sleep(10_000);
	}
	return { latencies, failures };
}

/** The named statements of `--statements`: `[{ "name": "…", "pattern": "<regular expression>" }]`. */
export function readNamed(file: string): Named[] {
	const parsed = json(readFileSync(file, "utf8"));
	if (!Array.isArray(parsed)) {
		throw new Error(`${file}: expected [{ "name": "…", "pattern": "…" }]`);
	}
	return parsed.map((entry, index) => {
		const name = str(path2(entry, ["name"]));
		const pattern = str(path2(entry, ["pattern"]));
		if (name === "" || pattern === "") {
			throw new Error(`${file}: entry ${index} needs a name and a pattern`);
		}
		return { name, pattern: new RegExp(pattern, "iu") };
	});
}

/** Quantile of a cumulative histogram given as upper bound → count. */
function quantile(buckets: Record<string, number>, q: number): number | null {
	const bounds = Object.entries(buckets)
		.map(([le, count]) => [le === "+Inf" ? Number.POSITIVE_INFINITY : Number(le), count] as const)
		.toSorted((a, b) => a[0] - b[0]);
	const total = bounds.at(-1)?.[1] ?? 0;
	if (total <= 0) {
		return null;
	}
	const hit = bounds.find(([, count]) => count >= q * total);
	return hit !== undefined && Number.isFinite(hit[0]) ? hit[0] : null;
}

interface Probe {
	latencies: number[];
	failures: number;
}

export async function judgeReads(
	cluster: Cluster,
	evidence: Evidence,
	before: ReadsBefore,
	probe: Probe,
	named: readonly Named[],
): Promise<void> {
	judgeOverview(evidence, before, await overviewBuckets(cluster), probe);
	const errors: string[] = [];
	judgeStatements(evidence, before.statements, await statements(cluster, errors), errors, named);
}

/** B10: the server's own timer for the route, as the difference across the window, and the probe's client times. */
function judgeOverview(
	evidence: Evidence,
	before: ReadsBefore,
	after: Record<string, number>,
	probe: Probe,
): void {
	const delta: Record<string, number> = {};
	for (const [le, count] of Object.entries(after)) {
		delta[le] = count - (before.buckets[le] ?? 0);
	}
	const requests = delta["+Inf"] ?? 0;
	const client = {
		n: probe.latencies.length,
		failures: probe.failures,
		p50: percentile(probe.latencies, 50),
		p95: percentile(probe.latencies, 95),
	};
	evidence.raw("b10-overview.json", { server: { requests, buckets: delta }, client });
	const title = "practice profile overview latency under load";
	if (requests === 0) {
		evidence.notRun(
			"B10",
			title,
			client.n === 0
				? "traffic on the route: pass --overview-url and --token-file of a synthetic member, or real use in the window"
				: "server-side requests on the route in the window (the client probe alone is not the gate)",
		);
		return;
	}
	const p50 = quantile(delta, 0.5);
	const p95 = quantile(delta, 0.95);
	const serverP50 = p50 === null ? null : p50 * 1000;
	const serverP95 = p95 === null ? null : p95 * 1000;
	const meets = serverP50 !== null && serverP95 !== null && serverP50 <= 1000 && serverP95 <= 2000;
	evidence.check({
		id: "B10",
		title,
		status: meets ? "pass" : "fail",
		rule: `${RULES} § B10: server p50 ≤ 1 s and p95 ≤ 2 s under load`,
		detail: `server (bucket upper bounds): n=${requests} p50 ≤ ${serverP50 ?? "n/a"} ms, p95 ≤ ${serverP95 ?? "n/a"} ms; client probe: n=${client.n} failures=${client.failures} p50 ${client.p50?.toFixed(0) ?? "n/a"} ms p95 ${client.p95?.toFixed(0) ?? "n/a"} ms`,
	});
}

/** B11: the statements by total time across the window, and the named ones. */
function judgeStatements(
	evidence: Evidence,
	before: Statement[] | null,
	now: Statement[] | null,
	errors: string[],
	named: readonly Named[],
): void {
	const title = "database statements under load";
	if (before === null || now === null) {
		evidence.notRun(
			"B11",
			title,
			"pg_stat_statements on the bench database (the database metrics of observability.mdx)",
			errors.join("; ") || "pg_stat_statements was not readable before the window",
		);
		return;
	}
	const earlier = new Map(before.map((row) => [row.queryid, row]));
	const window = now
		.map((row) => {
			const prior = earlier.get(row.queryid);
			const calls = row.calls - (prior?.calls ?? 0);
			const total = row.total_ms - (prior?.total_ms ?? 0);
			return {
				queryid: row.queryid,
				calls,
				total_ms: total,
				mean_ms: calls > 0 ? total / calls : 0,
				query: row.query,
			};
		})
		.filter((row) => row.calls > 0)
		.toSorted((a, b) => b.total_ms - a.total_ms);
	const top = window.slice(0, 10);
	const found = named.map((entry) => {
		const rows = window.filter((row) => entry.pattern.test(row.query));
		let calls = 0;
		let total = 0;
		for (const row of rows) {
			calls += row.calls;
			total += row.total_ms;
		}
		return {
			name: entry.name,
			statements: rows.length,
			calls,
			mean_ms: calls > 0 ? total / calls : null,
			in_top10: rows.some((row) => top.includes(row)),
		};
	});
	evidence.raw("b11-statements.json", { top10: top, named: found });
	const ran = `${window.length} statements ran in the window; top 10 by total time in raw/b11-statements.json`;
	if (found.length === 0) {
		evidence.notRun("B11", title, "--statements <named.json>", `${ran}; no named statements given`);
		return;
	}
	const misses = found.filter(
		(entry) => entry.in_top10 || (entry.mean_ms !== null && entry.mean_ms > 50),
	);
	evidence.check({
		id: "B11",
		title,
		status: misses.length === 0 ? "pass" : "fail",
		rule: `${RULES} § B11: no named statement in the top 10 by total time, each mean ≤ 50 ms`,
		detail: `${ran}; named: ${found.map((entry) => `${entry.name} calls=${entry.calls} mean=${entry.mean_ms?.toFixed(1) ?? "n/a"} ms${entry.in_top10 ? " TOP10" : ""}`).join("; ")}`,
	});
}
