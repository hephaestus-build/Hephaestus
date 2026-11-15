/**
 * The cluster that a qualification run measures, behind one interface. `k3d` runs kubectl with an explicit kubeconfig
 * file and node commands through `docker exec` on the k3d node containers. `staging` is the one-host k3s platform of
 * docs/admin/pull-based-deployment.mdx: every command runs as `sudo k3s kubectl …` or `sudo sh -s` on the host,
 * through the command in QUAL_STAGING_EXEC.
 */
import { spawn } from "node:child_process";

export type TargetName = "k3d" | "staging";

export interface Result {
	code: number;
	stdout: string;
	stderr: string;
}

export interface ClusterOptions {
	target: TargetName;
	/** k3d: the cluster name, which selects its node containers. */
	k3dCluster: string;
	/** k3d: the kubeconfig file. Every kubectl call names it, so the current context is never used. */
	kubeconfig: string | undefined;
	namespace: string;
	sandboxes: string;
}

/** Quotes one word for a POSIX shell. */
export function quote(word: string): string {
	return /^[\w@%+=:,./-]+$/u.test(word) ? word : `'${word.replaceAll("'", `'\\''`)}'`;
}

export async function exec(
	program: string,
	args: readonly string[],
	options: { input?: string; timeoutMs?: number } = {},
): Promise<Result> {
	const child = spawn(program, args, {
		stdio: ["pipe", "pipe", "pipe"],
		timeout: options.timeoutMs ?? 0,
	});
	const out: Buffer[] = [];
	const err: Buffer[] = [];
	child.stdout.on("data", (chunk: Buffer) => {
		out.push(chunk);
	});
	child.stderr.on("data", (chunk: Buffer) => {
		err.push(chunk);
	});
	const closed = Promise.withResolvers<Result>();
	child.once("error", closed.reject);
	child.once("close", (code) => {
		closed.resolve({
			code: code ?? 1,
			stdout: Buffer.concat(out).toString("utf8"),
			stderr: Buffer.concat(err).toString("utf8"),
		});
	});
	// A program that reads no input closes its stdin early; that is not an error of the run.
	child.stdin.on("error", () => undefined);
	child.stdin.end(options.input ?? "");
	return closed.promise;
}

/**
 * The staging host's transport: QUAL_STAGING_EXEC is a command, split on whitespace, that runs its last argument as a
 * shell command on the host (`ssh <host>` has this shape). The host name stays in the caller's environment.
 */
export function stagingExec(): [string, ...string[]] {
	const [program, ...args] = (process.env.QUAL_STAGING_EXEC ?? "")
		.trim()
		.split(/\s+/u)
		.filter(Boolean);
	if (program === undefined) {
		throw new Error(
			"QUAL_STAGING_EXEC is not set: give the command that runs a shell command on the staging host, such as `ssh <host>`",
		);
	}
	return [program, ...args];
}

/** Runs one shell command on the staging host. */
export async function onStaging(
	command: string,
	options: { input?: string; timeoutMs?: number } = {},
): Promise<Result> {
	const [program, ...args] = stagingExec();
	return exec(program, [...args, command], options);
}

/** The label selector of a release component. */
export function selector(component: string): string {
	return `app.kubernetes.io/component=${component}`;
}

export class Cluster {
	readonly options: ClusterOptions;

	constructor(options: ClusterOptions) {
		this.options = options;
	}

	get target(): TargetName {
		return this.options.target;
	}

	get namespace(): string {
		return this.options.namespace;
	}

	get sandboxes(): string {
		return this.options.sandboxes;
	}

	async kubectlRaw(args: readonly string[], input?: string, timeoutMs = 600_000): Promise<Result> {
		const options = { ...(input === undefined ? {} : { input }), timeoutMs };
		if (this.target === "staging") {
			return onStaging(["sudo", "k3s", "kubectl", ...args].map(quote).join(" "), options);
		}
		return exec("kubectl", ["--kubeconfig", this.options.kubeconfig ?? "", ...args], options);
	}

	/** kubectl that throws on a non-zero exit, with stderr cut to its first lines (callers read no Secret). */
	async kubectl(args: readonly string[], input?: string, timeoutMs?: number): Promise<string> {
		const result = await this.kubectlRaw(args, input, timeoutMs);
		if (result.code !== 0) {
			throw new Error(
				`kubectl ${args.slice(0, 6).join(" ")} failed (${result.code}): ${result.stderr.split("\n").slice(0, 4).join(" ")}`,
			);
		}
		return result.stdout;
	}

	async json(args: readonly string[]): Promise<unknown> {
		return json(await this.kubectl([...args, "-o", "json"]));
	}

	/** Runs a POSIX shell script as root on every node; one result per node. */
	async nodeShell(
		script: string,
		env: Record<string, string> = {},
	): Promise<{ node: string; result: Result }[]> {
		const prelude = Object.entries(env)
			.map(([key, value]) => `export ${key}=${quote(value)}`)
			.join("\n");
		const input = `${prelude}\n${script}`;
		if (this.target === "staging") {
			return [
				{ node: "staging", result: await onStaging("sudo sh -s", { input, timeoutMs: 120_000 }) },
			];
		}
		const listed = await exec("docker", [
			"ps",
			"--filter",
			`label=k3d.cluster=${this.options.k3dCluster}`,
			"--format",
			'{{.Names}}\t{{.Label "k3d.role"}}',
		]);
		const nodes = listed.stdout
			.split("\n")
			.map((line) => line.split("\t"))
			.filter(([, role]) => role === "server" || role === "agent")
			.map(([name]) => name ?? "")
			.filter((name) => name !== "");
		// A k3d agent node has no API credential, so a node script that lists Pods reads the host's listing through
		// this function instead of `k3s kubectl`.
		const listing = await this.kubectl([
			"get",
			"pods",
			"-A",
			"-o",
			String.raw`jsonpath={range .items[*]}{.metadata.namespace} {.metadata.name} {.metadata.uid}{"\n"}{end}`,
		]);
		const kubectlFunction = `qual_kubectl() { awk -v ns="$2" '$1 == ns { print $2 " " $3 }' <<'QUAL_PODS'\n${listing}\nQUAL_PODS\n}\nexport KUBECTL=qual_kubectl`;
		return Promise.all(
			nodes.map(async (node) => ({
				node,
				result: await exec("docker", ["exec", "-i", node, "sh", "-s"], {
					input: `${kubectlFunction}\n${input}`,
					timeoutMs: 120_000,
				}),
			})),
		);
	}

	/** The bundled PostgreSQL Pod and its container. */
	async postgres(): Promise<{ pod: string; container: string }> {
		const list = await this.json([
			"-n",
			this.namespace,
			"get",
			"pods",
			"-l",
			selector("postgresql"),
		]);
		const pod = items(list).find((item) => str(path2(item, ["status", "phase"])) === "Running");
		if (pod === undefined) {
			throw new Error(`no running PostgreSQL Pod in ${this.namespace}`);
		}
		const containers = arr(path2(pod, ["spec", "containers"])).map((container) =>
			str(path2(container, ["name"])),
		);
		const container =
			containers.find((name) => name === "postgresql" || name === "postgres") ?? containers[0];
		return { pod: str(path2(pod, ["metadata", "name"])), container: container ?? "" };
	}

	/**
	 * Read-only SQL in a read-only transaction with short timeouts, as the bundled PostgreSQL's admin user over the local
	 * socket. The SQL goes on stdin, so no quoting crosses the transport. Rows come back tab-separated.
	 */
	async sql(query: string): Promise<string[][]> {
		return this.psql(
			`SET default_transaction_read_only = on; SET statement_timeout = '15s'; SET lock_timeout = '2s';\n${query}`,
		);
	}

	/** SQL that writes: only the review trigger uses it, with a bounded transaction. */
	async sqlWrite(query: string): Promise<string[][]> {
		return this.psql(`SET statement_timeout = '5s'; SET lock_timeout = '2s';\n${query}`);
	}

	private async psql(query: string): Promise<string[][]> {
		const { pod, container } = await this.postgres();
		const output = await this.kubectl(
			[
				"-n",
				this.namespace,
				"exec",
				"-i",
				pod,
				"-c",
				container,
				"--",
				"sh",
				"-c",
				'exec psql -X -q -At -F "\t" -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
			],
			query,
			60_000,
		);
		return output
			.split("\n")
			.filter((line) => line !== "" && line !== "SET")
			.map((line) => line.split("\t"));
	}

	/**
	 * The Prometheus text of one Pod's management port. Staging (one node) reads it through the API server's Pod proxy.
	 * The management server listens on the Pod IP only, and the chart's policy admits it from the Pod's own node, so on
	 * a multi-node k3d cluster the API proxy reaches only the server node's Pods: k3d reads it from the node container
	 * that hosts the Pod.
	 */
	async prometheus(pod: string, port = 9090): Promise<string> {
		if (this.target === "k3d") {
			const podJson = await this.json(["-n", this.namespace, "get", "pod", pod]);
			const node = str(path2(podJson, ["spec", "nodeName"]));
			const ip = str(path2(podJson, ["status", "podIP"]));
			const result = await exec(
				"docker",
				["exec", node, "wget", "-q", "-O-", "-T", "20", `http://${ip}:${port}/actuator/prometheus`],
				{ timeoutMs: 30_000 },
			);
			if (result.code !== 0) {
				throw new Error(`${pod}:${port} metrics unreadable from ${node} (${result.code})`);
			}
			return result.stdout;
		}
		return this.kubectl(
			[
				"get",
				"--raw",
				`/api/v1/namespaces/${this.namespace}/pods/${pod}:${port}/proxy/actuator/prometheus`,
			],
			undefined,
			30_000,
		);
	}

	/** The target, its nodes and the release images: the commit that runs on the cluster. */
	async describe(): Promise<Record<string, unknown>> {
		const nodes = items(await this.json(["get", "nodes"])).map((node) => ({
			name: str(path2(node, ["metadata", "name"])),
			kubelet: str(path2(node, ["status", "nodeInfo", "kubeletVersion"])),
			arch: str(path2(node, ["status", "nodeInfo", "architecture"])),
			kernel: str(path2(node, ["status", "nodeInfo", "kernelVersion"])),
			allocatableMemory: str(path2(node, ["status", "allocatable", "memory"])),
		}));
		const workloads = await this.json(["-n", this.namespace, "get", "deployments,statefulsets"]);
		return {
			target: this.target,
			...(this.target === "k3d" ? { name: `k3d-${this.options.k3dCluster}` } : {}),
			namespace: this.namespace,
			sandboxes: this.sandboxes,
			nodes,
			images: Object.fromEntries(
				items(workloads).map((item) => [
					str(path2(item, ["metadata", "name"])),
					arr(path2(item, ["spec", "template", "spec", "containers"]))
						.map((container) => str(path2(container, ["image"])).replace(/^.*\//u, ""))
						.join(","),
				]),
			),
		};
	}

	/** Running, not terminating Pods of a release component. */
	async componentPods(component: string): Promise<Pod[]> {
		const list = await this.json(["-n", this.namespace, "get", "pods", "-l", selector(component)]);
		return items(list)
			.filter(
				(item) =>
					path2(item, ["metadata", "deletionTimestamp"]) === undefined &&
					str(path2(item, ["status", "phase"])) === "Running",
			)
			.map(podOf);
	}
}

export interface Pod {
	name: string;
	uid: string;
	node: string;
	ports: { name: string; port: number }[];
}

function podOf(item: unknown): Pod {
	return {
		name: str(path2(item, ["metadata", "name"])),
		uid: str(path2(item, ["metadata", "uid"])),
		node: str(path2(item, ["spec", "nodeName"])),
		ports: arr(path2(item, ["spec", "containers"])).flatMap((container) =>
			arr(path2(container, ["ports"])).map((port) => ({
				name: str(path2(port, ["name"])),
				port: num(path2(port, ["containerPort"])),
			})),
		),
	};
}

// Narrow readers for JSON from kubectl and the probes: each returns a safe default for a missing field.

export function path2(value: unknown, keys: readonly string[]): unknown {
	let current: unknown = value;
	for (const key of keys) {
		if (current === null || typeof current !== "object" || Array.isArray(current)) {
			return undefined;
		}
		const next: unknown = Reflect.get(current, key);
		current = next;
	}
	return current;
}

export function rec(value: unknown): Record<string, unknown> {
	return value !== null && typeof value === "object" && !Array.isArray(value)
		? Object.fromEntries(Object.entries(value))
		: {};
}

export function arr(value: unknown): unknown[] {
	return Array.isArray(value) ? value : [];
}

export function str(value: unknown): string {
	return typeof value === "string" ? value : "";
}

export function num(value: unknown): number {
	return typeof value === "number" ? value : Number.NaN;
}

/** JSON from a command or a probe, as unknown: callers read it with the narrow readers. */
export function json(text: string): unknown {
	const parsed: unknown = JSON.parse(text);
	return parsed;
}

/** `key=value key=value` lines of the node scripts. */
export function fieldsOf(line: string): Record<string, string> {
	return Object.fromEntries(
		line
			.trim()
			.split(/\s+/u)
			.map((pair) => {
				const at = pair.indexOf("=");
				return at === -1 ? [pair, ""] : [pair.slice(0, at), pair.slice(at + 1)];
			}),
	);
}

export function items(list: unknown): unknown[] {
	return arr(path2(list, ["items"]));
}

/** A Kubernetes quantity (`512Mi`, `2Gi`, `500m`, `1.5`) as a number of bytes or cores. */
export function quantity(text: string): number {
	const match = /^(?<value>[0-9.]+)(?<unit>[a-zA-Z]*)$/u.exec(text.trim());
	if (match === null) {
		return Number.NaN;
	}
	const units: Record<string, number> = {
		"": 1,
		m: 1e-3,
		k: 1e3,
		K: 1e3,
		M: 1e6,
		G: 1e9,
		T: 1e12,
		Ki: 1024,
		Mi: 1024 ** 2,
		Gi: 1024 ** 3,
		Ti: 1024 ** 4,
	};
	const unit = units[match.groups?.unit ?? ""];
	return unit === undefined ? Number.NaN : Number(match.groups?.value) * unit;
}

/** The sum of one resource over a Pod's containers, for `requests` or `limits`. */
export function podResource(pod: unknown, kind: "requests" | "limits", resource: string): number {
	return arr(path2(pod, ["spec", "containers"]))
		.map((container) => quantity(str(path2(container, ["resources", kind, resource]))))
		.reduce((sum, value) => sum + (Number.isNaN(value) ? 0 : value), 0);
}
