/**
 * The bench collectors. Each runs on its own interval until the window ends and appends JSON lines under `raw/`: the
 * Prometheus series of every server and worker Pod, the Pod parent cgroups (the one-host platform runs no
 * metrics-server), the memory split and OOM counter of the release Pods, node PSI, swap and available memory, and a SQL
 * snapshot of run, action and permit states, lock waits and connections.
 */
import { sleep } from "./qual-args.ts";
import {
	arr,
	type Cluster,
	fieldsOf,
	items,
	num,
	path2,
	podResource,
	str,
} from "./qual-cluster.ts";
import type { Evidence } from "./qual-evidence.ts";
import { AGENT_METRICS, scrapeAll, sqlRows } from "./qual-state.ts";

// The node scripts run as root through `sh -s` on a node, which has no Node.js. Each reads cgroup v2 files only, and no
// environment besides its inputs. A Pod's directory is `kubepods[/<qos>]/pod<uid>` with the cgroupfs driver (the k3s
// default) or `kubepods.slice/kubepods-<qos>.slice/kubepods-<qos>-pod<uid with _>.slice` with the systemd driver.

/** One line per Pod of NAMESPACES: `<namespace>/<pod> mem_bytes=… peak_bytes=… cpu_usec=…`, or `cgroup=missing`, never a zero. */
const POD_CGROUPS = String.raw`set -u
ROOT=${"$"}{CGROUP_ROOT:-/sys/fs/cgroup}
KUBECTL=${"$"}{KUBECTL:-k3s kubectl}
for ns in $NAMESPACES; do
  $KUBECTL -n "$ns" get pods -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.uid}{"\n"}{end}' 2>/dev/null |
    while read -r name uid; do
      [ -n "$uid" ] || continue
      under=$(printf '%s' "$uid" | tr - _)
      dir=$(find "$ROOT"/kubepods* -maxdepth 2 -type d \( -name "pod$uid" -o -name "*-pod$under.slice" \) 2>/dev/null | head -n 1)
      if [ -z "$dir" ]; then
        echo "$ns/$name cgroup=missing"
        continue
      fi
      mem=$(cat "$dir/memory.current" 2>/dev/null || echo missing)
      peak=$(cat "$dir/memory.peak" 2>/dev/null || echo missing)
      cpu=$(awk '$1 == "usage_usec" { print $2 }' "$dir/cpu.stat" 2>/dev/null)
      echo "$ns/$name mem_bytes=$mem peak_bytes=$peak cpu_usec=${"$"}{cpu:-missing}"
    done
done`;

/** One line per Pod of POD_UIDS found on the node: limits, throttling, pids, OOM kills and the memory.stat split. */
const POD_LIMITS = String.raw`set -u
ROOT=${"$"}{CGROUP_ROOT:-/sys/fs/cgroup}
for uid in $POD_UIDS; do
  under=$(printf '%s' "$uid" | tr - _)
  dir=$(find "$ROOT"/kubepods* -maxdepth 2 -type d \( -name "pod$uid" -o -name "*-pod$under.slice" \) 2>/dev/null | head -n 1)
  [ -n "$dir" ] || continue
  read_one() { cat "$dir/$1" 2>/dev/null | head -n 1 | tr ' ' '_' || echo missing; }
  stat_of() { awk -v key="$2" '$1 == key { print $2 }' "$dir/$1" 2>/dev/null; }
  printf 'uid=%s cpu_max=%s usage_usec=%s nr_throttled=%s throttled_usec=%s pids_max=%s pids_current=%s memory_max=%s memory_current=%s memory_peak=%s oom_kill=%s anon=%s file=%s kernel=%s\n' \
    "$uid" "$(read_one cpu.max)" "$(stat_of cpu.stat usage_usec)" "$(stat_of cpu.stat nr_throttled)" \
    "$(stat_of cpu.stat throttled_usec)" "$(read_one pids.max)" "$(read_one pids.current)" \
    "$(read_one memory.max)" "$(read_one memory.current)" "$(read_one memory.peak)" \
    "$(stat_of memory.events oom_kill)" "$(stat_of memory.stat anon)" "$(stat_of memory.stat file)" \
    "$(stat_of memory.stat kernel)"
done`;

/** Node counters: PSI, swap traffic and available memory. */
const NODE_SAMPLE = String.raw`
psi() { awk -v k="$2" '$1 == "some" { split($2, a, "="); print k "_some10=" a[2] }' "/proc/pressure/$1" 2>/dev/null; }
printf '%s %s ' "$(psi memory mem)" "$(psi cpu cpu)"
awk '$1 == "pswpin" || $1 == "pswpout" { printf "%s=%s ", $1, $2 }' /proc/vmstat
awk '$1 == "MemAvailable:" || $1 == "SwapTotal:" || $1 == "SwapFree:" || $1 == "MemTotal:" { sub(":", "", $1); printf "%s=%s ", $1, $2 * 1024 }' /proc/meminfo
echo
`;

export interface Intervals {
	cgroupsMs: number;
	nodeMs: number;
	prometheusMs: number;
	sqlMs: number;
	limitsMs: number;
	podsMs: number;
}

export interface CollectOptions {
	namespaces: string[];
	intervals: Intervals;
	/** Pods whose memory split and OOM counter to read: the stateful roles and the JVMs. */
	limitComponents: string[];
}

/** Runs every collector until `until`; returns when all have stopped. */
export async function collect(
	cluster: Cluster,
	evidence: Evidence,
	until: number,
	options: CollectOptions,
): Promise<void> {
	const loop = async (name: string, everyMs: number, sample: () => Promise<unknown>) => {
		while (Date.now() < until) {
			const started = Date.now();
			try {
				evidence.line(`${name}.jsonl`, { sample: await sample() });
			} catch (error) {
				evidence.line(`${name}.jsonl`, {
					error: error instanceof Error ? error.message.split("\n")[0] : String(error),
				});
			}
			await sleep(Math.max(0, Math.min(everyMs - (Date.now() - started), until - Date.now())));
		}
	};
	await Promise.all([
		loop("cgroups", options.intervals.cgroupsMs, async () => cgroups(cluster, options.namespaces)),
		loop("node", options.intervals.nodeMs, async () => nodes(cluster)),
		loop("prometheus", options.intervals.prometheusMs, async () =>
			scrapeAll(cluster, AGENT_METRICS),
		),
		loop("sql", options.intervals.sqlMs, async () => sqlSample(cluster)),
		loop("limits", options.intervals.limitsMs, async () =>
			limits(cluster, options.limitComponents),
		),
		loop("pods", options.intervals.podsMs, async () => pods(cluster, options.namespaces)),
	]);
}

export interface CgroupSample {
	memBytes: number;
	peakBytes: number;
	cpuUsec: number;
}

/** `<namespace>/<pod>` → the Pod parent's memory.current, memory.peak and cumulative CPU. */
async function cgroups(
	cluster: Cluster,
	namespaces: string[],
): Promise<Record<string, CgroupSample>> {
	const results = await cluster.nodeShell(POD_CGROUPS, { NAMESPACES: namespaces.join(" ") });
	const samples: Record<string, CgroupSample> = {};
	for (const { result } of results) {
		for (const line of result.stdout.split("\n")) {
			const [pod, ...rest] = line.trim().split(" ");
			if (pod === undefined || pod === "" || rest.join(" ").includes("cgroup=missing")) {
				continue;
			}
			const fields = fieldsOf(rest.join(" "));
			samples[pod] = {
				memBytes: Number(fields.mem_bytes),
				peakBytes: Number(fields.peak_bytes),
				cpuUsec: Number(fields.cpu_usec),
			};
		}
	}
	return samples;
}

async function nodes(cluster: Cluster): Promise<Record<string, Record<string, number>>> {
	const results = await cluster.nodeShell(NODE_SAMPLE);
	return Object.fromEntries(
		results.map(({ node, result }) => [
			node,
			Object.fromEntries(
				Object.entries(fieldsOf(result.stdout)).map(([key, value]) => [key, Number(value)]),
			),
		]),
	);
}

/** The memory split and OOM counter of the Pods of the given components, by Pod name. */
async function limits(
	cluster: Cluster,
	components: string[],
): Promise<Record<string, Record<string, string>>> {
	const list = await cluster.json(["-n", cluster.namespace, "get", "pods"]);
	const wanted = items(list)
		.filter((pod) =>
			components.includes(str(path2(pod, ["metadata", "labels", "app.kubernetes.io/component"]))),
		)
		.map((pod) => ({
			name: str(path2(pod, ["metadata", "name"])),
			uid: str(path2(pod, ["metadata", "uid"])),
		}));
	const results = await cluster.nodeShell(POD_LIMITS, {
		POD_UIDS: wanted.map((pod) => pod.uid).join(" "),
	});
	const byUid = new Map<string, Record<string, string>>();
	for (const { result } of results) {
		for (const line of result.stdout.split("\n")) {
			if (line.startsWith("uid=")) {
				const fields = fieldsOf(line);
				byUid.set(fields.uid ?? "", fields);
			}
		}
	}
	return Object.fromEntries(
		wanted.flatMap((pod) => (byUid.has(pod.uid) ? [[pod.name, byUid.get(pod.uid) ?? {}]] : [])),
	);
}

async function sqlSample(cluster: Cluster): Promise<Record<string, unknown>> {
	const errors: string[] = [];
	const [runs, actions, permits, database] = await Promise.all([
		sqlRows(
			cluster,
			`SELECT kind, band, state, count(*)::int AS n,
			        max(extract(epoch FROM now() - available_at)) FILTER (WHERE state = 'QUEUED' AND available_at <= now()) AS oldest_queued_s
			   FROM agent_run WHERE state <> 'ENDED' GROUP BY kind, band, state`,
			errors,
			"agent_run",
			{ kind: "string", band: "string", state: "string", n: "number", oldest_queued_s: "number?" },
		),
		sqlRows(
			cluster,
			"SELECT kind, state, count(*)::int AS n FROM agent_action WHERE ended_at IS NULL GROUP BY kind, state",
			errors,
			"agent_action",
			{ kind: "string", state: "string", n: "number" },
		),
		sqlRows(
			cluster,
			"SELECT band, state, count(*)::int AS n FROM llm_call_permit WHERE closed_at IS NULL GROUP BY band, state",
			errors,
			"llm_call_permit",
			{ band: "string", state: "string", n: "number" },
		),
		sqlRows(
			cluster,
			`SELECT (SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
			          WHERE NOT l.granted AND c.relname IN ('llm_call_permit', 'agent_run', 'review_job'))::int AS lock_waits,
			        (SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend' AND pid <> pg_backend_pid())::int AS connections,
			        current_setting('max_connections')::int AS max_connections,
			        pg_current_wal_lsn()::text AS wal_lsn`,
			errors,
			"database",
			{ lock_waits: "number", connections: "number", max_connections: "number", wal_lsn: "string" },
		),
	]);
	return { runs, actions, permits, database: database[0] ?? null, errors };
}

export interface PodSeen {
	component: string;
	sandboxKind: string;
	uid: string;
	node: string;
	phase: string;
	reason: string;
	restarts: number;
	lastTerminated: string;
	requestMemory: number;
	limitMemory: number;
	requestCpu: number;
	limitCpu: number;
	env: Record<string, string>;
}

/** Variables read from a Pod spec: only plain values of these names, never a Secret reference. */
const SPEC_ENV = new Set([
	"GOMEMLIMIT",
	"JAVA_TOOL_OPTIONS",
	"BPL_JVM_HEAD_ROOM",
	"BPL_JVM_THREAD_COUNT",
]);

/** `<namespace>/<pod>` → what the bench judges by: role, requests, limits, restarts and the last termination. */
async function pods(cluster: Cluster, namespaces: string[]): Promise<Record<string, PodSeen>> {
	const seen: Record<string, PodSeen> = {};
	for (const namespace of namespaces) {
		const listed = await cluster.kubectlRaw(["-n", namespace, "get", "pods", "-o", "json"]);
		if (listed.code !== 0) {
			continue;
		}
		const list: unknown = JSON.parse(listed.stdout);
		for (const pod of items(list)) {
			const statuses = arr(path2(pod, ["status", "containerStatuses"]));
			seen[`${namespace}/${str(path2(pod, ["metadata", "name"]))}`] = {
				component: str(path2(pod, ["metadata", "labels", "app.kubernetes.io/component"])),
				sandboxKind: str(path2(pod, ["metadata", "labels", "hephaestus.build/sandbox-kind"])),
				uid: str(path2(pod, ["metadata", "uid"])),
				node: str(path2(pod, ["spec", "nodeName"])),
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
				requestMemory: podResource(pod, "requests", "memory"),
				limitMemory: podResource(pod, "limits", "memory"),
				requestCpu: podResource(pod, "requests", "cpu"),
				limitCpu: podResource(pod, "limits", "cpu"),
				env: Object.fromEntries(
					arr(path2(pod, ["spec", "containers"]))
						.flatMap((container) => arr(path2(container, ["env"])))
						.filter(
							(entry) =>
								SPEC_ENV.has(str(path2(entry, ["name"]))) &&
								typeof path2(entry, ["value"]) === "string",
						)
						.map((entry) => [str(path2(entry, ["name"])), str(path2(entry, ["value"]))]),
				),
			};
		}
	}
	return seen;
}
