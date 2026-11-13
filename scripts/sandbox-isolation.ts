/**
 * The sandbox isolation gate: proves on a live cluster that a sandbox from a SandboxTemplate reaches the
 * gateway port and nothing else, holds no Kubernetes token, runs as a non-root user and, on the default
 * runtime, in a user namespace of its own. The CI cluster legs run it against the chart's templates
 * (docs/admin/kubernetes.mdx § Sandbox isolation).
 *
 * It clones the template, with its Pod spec, labels and policies, and changes only the command, so
 * that the container waits instead of running a loop. It claims two sandboxes from the clone, so each
 * Pod is what the controller creates for a subject. A probe then runs in sandbox A's container through
 * `kubectl exec`, with the container's network, user and security context. Sandbox B listens; A must
 * not reach it.
 *
 * The Kubernetes API counts twice: by its Service address, and by each address that the Service sends to,
 * because a network plugin can match a policy before or after it translates the Service address.
 *
 *   node scripts/sandbox-isolation.ts --namespace <sandboxes> --template <name> \
 *     --gateway <host>:<port> [--reach <name>=<host>:<port> ...] [--forbid <name>=<host>:<port> ...] \
 *     [--dns <namespace>/<service>] [--context <kubectl context>]
 */
import { parseArgs } from "node:util";

import { asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

/** A TCP address that a sandbox must reach, or must not. */
export interface Target {
	name: string;
	host: string;
	port: number;
	reachable: boolean;
}

/** What the probe reports from inside one sandbox container. */
export interface Inside {
	uid: number;
	gid: number;
	token: boolean;
	/** Whether the service account directory exists, with or without a token in it. */
	serviceAccount: boolean;
	/** The `*_SERVICE_HOST` variables of the container's environment. */
	serviceHosts: string[];
	uidMap: string | null;
	noNewPrivs: boolean;
	capEff: string | null;
	kernel: string;
	reached: Record<string, boolean>;
	dns: boolean;
	publicDns: boolean;
	/** When the Kubernetes API stopped answering the container after its start: `closed after <n> ms`, or `open`. */
	firstApi: string | null;
}

/** The listener port that sandbox B opens for sandbox A to try. */
export const PEER_PORT = 9099;

/** The identity map: a container without a user namespace of its own. */
const HOST_USERS_MAP = "0 0 4294967295";

/**
 * Runs inside the sandbox with the image's Node. A target counts as reached once a TCP connection opens.
 * A target that must be reached gets ten seconds, because a new Pod's policy can take a moment to apply;
 * one that must not gets three. DNS is one UDP query to the cluster resolver.
 */
export const PROBE = String.raw`
const fs = require("node:fs");
const net = require("node:net");
const dgram = require("node:dgram");
const os = require("node:os");
const spec = JSON.parse(process.argv[1]);
const reach = (host, port) => new Promise((done) => {
  const socket = net.connect({ host, port, timeout: 3000 });
  socket.on("connect", () => { socket.destroy(); done(true); });
  socket.on("timeout", () => { socket.destroy(); done(false); });
  socket.on("error", () => done(false));
});
const resolve = (server) => new Promise((done) => {
  if (!server) return done(false);
  const socket = dgram.createSocket("udp4");
  const query = Buffer.from("abcd01000001000000000000076578616d706c6503636f6d0000010001", "hex");
  const timer = setTimeout(() => { socket.close(); done(false); }, 3000);
  socket.on("message", () => { clearTimeout(timer); socket.close(); done(true); });
  socket.on("error", () => { clearTimeout(timer); socket.close(); done(false); });
  socket.send(query, 53, server);
});
const status = (key) => {
  const line = fs.readFileSync("/proc/self/status", "utf8").split("\n").find((l) => l.startsWith(key + ":"));
  return line ? line.slice(key.length + 1).trim() : null;
};
(async () => {
  const out = { uid: process.getuid(), gid: process.getgid(), reached: {} };
  out.token = fs.existsSync("/var/run/secrets/kubernetes.io/serviceaccount/token");
  out.serviceAccount = fs.existsSync("/var/run/secrets/kubernetes.io/serviceaccount");
  out.serviceHosts = Object.keys(process.env).filter((name) => name.endsWith("_SERVICE_HOST")).sort();
  try { out.uidMap = fs.readFileSync("/proc/self/uid_map", "utf8").trim().split(/\s+/).join(" "); } catch { out.uidMap = null; }
  out.noNewPrivs = status("NoNewPrivs") === "1";
  out.capEff = status("CapEff");
  out.kernel = os.release();
  try { out.firstApi = fs.readFileSync(spec.firstApi, "utf8"); } catch { out.firstApi = null; }
  await Promise.all(spec.targets.map(async (target) => {
    const until = Date.now() + (target.reachable ? 10000 : 0);
    let reached = await reach(target.host, target.port);
    while (!reached && Date.now() < until) {
      await new Promise((wait) => setTimeout(wait, 250));
      reached = await reach(target.host, target.port);
    }
    out.reached[target.name] = reached;
  }));
  [out.dns, out.publicDns] = await Promise.all([resolve(spec.dns), resolve("1.1.1.1")]);
  console.log(JSON.stringify(out));
})();
`;

/** Exits 0 once the peer's listener accepts on its own loopback, within five seconds. */
const LISTENING = `const net = require("node:net"); const until = Date.now() + 5000; const try_ = () => { const s = net.connect(${PEER_PORT}, "127.0.0.1"); s.on("connect", () => process.exit(0)); s.on("error", () => (Date.now() < until ? setTimeout(try_, 100) : process.exit(1))); }; try_();`;

/** Starts a listener that outlives the exec, so the peer has something to reach. */
const LISTEN = `require("node:net").createServer((s) => s.end()).listen(${PEER_PORT});setTimeout(() => process.exit(0), 120000);`;

/** The command of a gate sandbox: it waits, so that the probe can run beside it. */
export const FIRST_API = "/tmp/isolation-first-api";
export const HOLD = [
	"node",
	"-e",
	String.raw`
const fs = require("node:fs");
const net = require("node:net");
const start = performance.timeOrigin;
const reach = () => new Promise((done) => {
  const socket = net.connect({ host: process.env.KUBERNETES_SERVICE_HOST, port: Number(process.env.KUBERNETES_SERVICE_PORT), timeout: 300 });
  socket.on("connect", () => { socket.destroy(); done(true); });
  socket.on("timeout", () => { socket.destroy(); done(false); });
  socket.on("error", () => done(false));
});
(async () => {
  let result = "open";
  while (Date.now() - start < 10000) {
    if (!(await reach())) { result = "closed after " + Math.round(Date.now() - start) + " ms"; break; }
    await new Promise((wait) => setTimeout(wait, 50));
  }
  try { fs.writeFileSync("${FIRST_API}", result); } catch {}
})();
process.on("SIGTERM", () => process.exit(0));
setInterval(() => {}, 1 << 30);
`,
];

/**
 * A clone of a SandboxTemplate under another name: the same Pod spec, labels and policies, with the
 * one container's command replaced by {@link HOLD}.
 */
export function gateTemplate(template: unknown, name: string): Record<string, unknown> {
	const source = asRecord(template, "SandboxTemplate");
	const metadata = asRecord(source.metadata, "metadata");
	const spec = structuredClone(asRecord(source.spec, "spec"));
	const pod = asRecord(asRecord(spec.podTemplate, "podTemplate").spec, "pod spec");
	const containers = Array.isArray(pod.containers) ? pod.containers : [];
	pod.containers = containers.map((container) => ({
		...asRecord(container, "container"),
		command: HOLD,
		args: [],
	}));
	return {
		apiVersion: source.apiVersion,
		kind: "SandboxTemplate",
		metadata: {
			name,
			namespace: metadata.namespace,
			labels: { "hephaestus.build/isolation-gate": "true" },
		},
		spec,
	};
}

const text = (field: unknown) => (typeof field === "string" ? field : null);
const statusOf = (pod: unknown) => asRecord(asRecord(pod, "pod").status, "status");

/** The probe's report, validated: it crosses from the sandbox, which runs model-chosen code. */
export function parseInside(value: unknown): Inside {
	const report = asRecord(value, "probe report");
	return {
		uid: Number(report.uid),
		gid: Number(report.gid),
		token: report.token === true,
		serviceAccount: report.serviceAccount === true,
		serviceHosts: Array.isArray(report.serviceHosts)
			? report.serviceHosts.filter((name): name is string => typeof name === "string")
			: [],
		uidMap: text(report.uidMap),
		noNewPrivs: report.noNewPrivs === true,
		capEff: text(report.capEff),
		kernel: asString(report.kernel, "kernel"),
		reached: Object.fromEntries(
			Object.entries(asRecord(report.reached, "reached")).map(([name, reached]) => [
				name,
				reached === true,
			]),
		),
		dns: report.dns === true,
		publicDns: report.publicDns === true,
		firstApi: text(report.firstApi),
	};
}

/**
 * G3: no API credential reaches the sandbox. Service links are off, so the only Service variables are
 * the kubelet's for the API; no volume projects a token or a Secret; no variable comes from a Secret.
 */
function credentialFailures(spec: Record<string, unknown>): string[] {
	const failures: string[] = [];
	if (spec.enableServiceLinks !== false) {
		failures.push("enableServiceLinks is not false");
	}
	// No credential of any kind reaches the sandbox: no projected token, and no Secret as a volume or a variable.
	for (const volume of Array.isArray(spec.volumes) ? spec.volumes : []) {
		const listed = asRecord(volume, "volume");
		if (listed.projected !== undefined || listed.secret !== undefined) {
			failures.push(`volume ${String(listed.name)} projects a token or a Secret`);
		}
	}
	for (const container of Array.isArray(spec.containers) ? spec.containers : []) {
		const { env, envFrom } = asRecord(container, "container");
		const variables: unknown[] = Array.isArray(env) ? env : [];
		const sources: unknown[] = Array.isArray(envFrom) ? envFrom : [];
		const fromSecret =
			variables.some(
				(variable) =>
					asRecord(asRecord(variable, "env").valueFrom ?? {}, "valueFrom").secretKeyRef !==
					undefined,
			) || sources.some((source) => asRecord(source, "envFrom").secretRef !== undefined);
		if (fromSecret) {
			failures.push("a variable comes from a Secret");
		}
	}
	return failures;
}

/** G3 from inside: no service account directory, and no Service variable but the API's. */
function credentialFailuresInside(inside: Inside): string[] {
	const failures: string[] = [];
	if (inside.serviceAccount) {
		failures.push("the service account directory exists");
	}
	// The kubelet always gives a container the API Service's KUBERNETES_* variables, whatever the Pod
	// spec says. The address is no secret, and the API endpoint checks prove that the sandbox cannot
	// reach it. Any other Service variable means that service links are on.
	const others = inside.serviceHosts.filter((name) => name !== "KUBERNETES_SERVICE_HOST");
	if (others.length > 0) {
		failures.push(`Service variables beside the API's: ${others.join(", ")}`);
	}
	return failures;
}

/** The failures of one sandbox's Pod spec: what the template must state. */
export function specFailures(pod: unknown): string[] {
	const failures: string[] = [];
	const spec = asRecord(asRecord(pod, "pod").spec, "pod spec");
	const gvisor = spec.runtimeClassName === "gvisor";
	// gVisor does not support user namespaces (google/gvisor#13303); its own kernel stands in for one.
	if (!gvisor && spec.hostUsers !== false) {
		failures.push("hostUsers is not false");
	}
	if (spec.automountServiceAccountToken !== false) {
		failures.push("the service account token is mounted");
	}
	failures.push(...credentialFailures(spec));
	const security = asRecord(spec.securityContext ?? {}, "pod securityContext");
	if (security.runAsNonRoot !== true) {
		failures.push("runAsNonRoot is not set");
	}
	const seccomp = asRecord(security.seccompProfile ?? {}, "seccompProfile");
	if (seccomp.type !== "RuntimeDefault") {
		failures.push("seccomp is not RuntimeDefault");
	}
	const containers = Array.isArray(spec.containers) ? spec.containers : [];
	if (containers.length !== 1) {
		failures.push(`${containers.length} containers, not one`);
	}
	if (Array.isArray(spec.initContainers) && spec.initContainers.length > 0) {
		failures.push("init containers");
	}
	for (const container of containers) {
		const context = asRecord(
			asRecord(container, "container").securityContext ?? {},
			"securityContext",
		);
		if (context.readOnlyRootFilesystem !== true) {
			failures.push("the root filesystem is writable");
		}
		if (context.allowPrivilegeEscalation !== false) {
			failures.push("privilege escalation allowed");
		}
		if (context.privileged === true) {
			failures.push("privileged");
		}
		const { drop } = asRecord(context.capabilities ?? {}, "capabilities");
		if (!Array.isArray(drop) || !drop.includes("ALL")) {
			failures.push("capabilities not dropped");
		}
	}
	return failures;
}

/** The failures of what one sandbox saw from inside. */
export function insideFailures(inside: Inside, targets: Target[], gvisor: boolean): string[] {
	const failures: string[] = [];
	if (inside.uid === 0 || inside.gid === 0) {
		failures.push(`runs as ${inside.uid}:${inside.gid}`);
	}
	if (inside.token) {
		failures.push("a Kubernetes token is mounted");
	}
	failures.push(...credentialFailuresInside(inside));
	if (!inside.noNewPrivs) {
		failures.push("no_new_privs is not set");
	}
	if (inside.capEff !== null && /[1-9a-f]/iu.test(inside.capEff)) {
		failures.push(`effective capabilities ${inside.capEff}`);
	}
	if (gvisor) {
		if (!inside.kernel.includes("gvisor")) {
			failures.push(`kernel ${inside.kernel} is not gVisor`);
		}
	} else if (inside.uidMap === null || inside.uidMap === HOST_USERS_MAP) {
		failures.push("no user namespace of its own");
	}
	for (const target of targets) {
		const reached = inside.reached[target.name] === true;
		if (reached !== target.reachable) {
			failures.push(
				`${target.reachable ? "cannot reach" : "reaches"} ${target.name} (${target.host}:${target.port})`,
			);
		}
	}
	if (inside.dns) {
		failures.push("the cluster DNS resolver answers");
	}
	if (inside.publicDns) {
		failures.push("a public DNS resolver answers");
	}
	// A network plugin may start a Pod before its policy holds, and the probe runs seconds later. The
	// container itself records when the Kubernetes API stopped answering it after its start.
	if (inside.firstApi === null || !inside.firstApi.startsWith("closed after ")) {
		failures.push(
			inside.firstApi === "open"
				? "still reached the Kubernetes API 10 seconds after its start"
				: "recorded no check of the Kubernetes API at its start",
		);
	}
	return failures;
}

/**
 * The addresses behind the `kubernetes` Service, from its EndpointSlices: where the API server
 * listens, such as port 6443 on a node. None is a failure, because a gate that tries none proves nothing.
 */
export function apiEndpoints(slices: unknown): { host: string; port: number }[] {
	const { items } = asRecord(slices, "EndpointSlice list");
	const endpoints = (Array.isArray(items) ? items : []).flatMap((item) => {
		const slice = asRecord(item, "EndpointSlice");
		const ports = (Array.isArray(slice.ports) ? slice.ports : [])
			.map((port) => asRecord(port, "port").port)
			.filter((port): port is number => typeof port === "number");
		const addresses = (Array.isArray(slice.endpoints) ? slice.endpoints : []).flatMap(
			(endpoint) => {
				const listed = asRecord(endpoint, "endpoint").addresses;
				return (Array.isArray(listed) ? listed : []).filter(
					(address): address is string => typeof address === "string",
				);
			},
		);
		return addresses.flatMap((host) => ports.map((port) => ({ host, port })));
	});
	if (endpoints.length === 0) {
		throw new Error("the kubernetes Service has no endpoint address, so the gate cannot try one");
	}
	return endpoints;
}

export function parseAddress(value: string): { host: string; port: number } {
	const at = value.lastIndexOf(":");
	const port = Number(value.slice(at + 1));
	if (at <= 0 || !Number.isInteger(port) || port <= 0 || port > 65_535) {
		throw new Error(`not a host:port: ${value}`);
	}
	return { host: value.slice(0, at), port };
}

export interface GateOptions {
	namespace: string;
	template: string;
	gateway: { host: string; port: number };
	/** Further addresses that a sandbox must reach. */
	reach?: Target[];
	/** Further addresses that a sandbox must not reach. */
	forbid?: Target[];
	/** The cluster DNS Service, `kube-system/kube-dns` unless the distribution names it otherwise (RKE2). */
	dns?: { namespace: string; name: string };
	context?: string;
}

/** Runs the gate for one template; throws with every failure, or returns what the sandbox saw. */
export async function isolationGate(options: GateOptions): Promise<Inside> {
	const { namespace, template } = options;
	const base = options.context === undefined ? [] : ["--context", options.context];
	const kubectl = async (args: string[], input?: string) =>
		output("kubectl", [...base, ...args], input === undefined ? {} : { input });
	const json = async (args: string[]) => parseJson(await kubectl([...args, "-o", "json"]));
	const api = asRecord(await json(["-n", "default", "get", "service", "kubernetes"]), "service");
	const apiIp = asString(asRecord(api.spec, "spec").clusterIP, "API address");
	const apiBackends = apiEndpoints(
		await json([
			"-n",
			"default",
			"get",
			"endpointslices",
			"-l",
			"kubernetes.io/service-name=kubernetes",
		]),
	);
	const dnsRef = options.dns ?? { namespace: "kube-system", name: "kube-dns" };
	const dnsService = asRecord(
		await json(["-n", dnsRef.namespace, "get", "service", dnsRef.name]),
		"service",
	);
	const dns = asString(asRecord(dnsService.spec, "spec").clusterIP, "DNS address");
	const pin = String(Date.now() % 1_000_000);
	const pool = `isolation-gate-${pin}`;
	const probe = `isolation-a-${pin}`;
	const peer = `isolation-b-${pin}`;
	const names = [probe, peer];
	const source = await json(["-n", namespace, "get", "sandboxtemplate", template]);
	const claim = (name: string) =>
		JSON.stringify({
			apiVersion: "extensions.agents.x-k8s.io/v1beta1",
			kind: "SandboxClaim",
			metadata: { name, namespace, labels: { "hephaestus.build/isolation-gate": "true" } },
			spec: { warmPoolRef: { name: pool } },
		});
	const failures: string[] = [];
	try {
		await kubectl(["apply", "-f", "-"], JSON.stringify(gateTemplate(source, pool)));
		await kubectl(
			["apply", "-f", "-"],
			JSON.stringify({
				apiVersion: "extensions.agents.x-k8s.io/v1beta1",
				kind: "SandboxWarmPool",
				metadata: { name: pool, namespace, labels: { "hephaestus.build/isolation-gate": "true" } },
				spec: { replicas: 0, sandboxTemplateRef: { name: pool } },
			}),
		);
		for (const name of names) {
			await kubectl(["apply", "-f", "-"], claim(name));
		}
		for (const name of names) {
			await kubectl([
				"-n",
				namespace,
				"wait",
				"--for=condition=Ready",
				`sandboxclaim/${name}`,
				"--timeout=5m",
			]);
		}
		const pods = await Promise.all(
			names.map(async (name) => json(["-n", namespace, "get", "pod", name])),
		);
		for (const [index, pod] of pods.entries()) {
			for (const failure of specFailures(pod)) {
				failures.push(`${names[index] ?? ""}: ${failure}`);
			}
		}
		const peerIp = asString(statusOf(pods[1]).podIP, "peer address");
		const nodeIp = asString(statusOf(pods[0]).hostIP, "node address");
		// Exec starts the peer's listener and returns; the listener stays for two minutes.
		await kubectl([
			"-n",
			namespace,
			"exec",
			peer,
			"--",
			"sh",
			"-c",
			`node -e '${LISTEN}' >/dev/null 2>&1 &`,
		]);
		// A denied peer counts only if it listens.
		await kubectl(["-n", namespace, "exec", peer, "--", "node", "-e", LISTENING]);
		const targets: Target[] = [
			{ name: "the gateway", ...options.gateway, reachable: true },
			{ name: "the Kubernetes API", host: apiIp, port: 443, reachable: false },
			...apiBackends.map((endpoint) => ({
				name: `the Kubernetes API endpoint ${endpoint.host}:${String(endpoint.port)}`,
				...endpoint,
				reachable: false,
			})),
			{ name: "the cluster DNS over TCP", host: dns, port: 53, reachable: false },
			{ name: "the node's kubelet", host: nodeIp, port: 10_250, reachable: false },
			{ name: "another sandbox", host: peerIp, port: PEER_PORT, reachable: false },
			{ name: "the cloud metadata address", host: "169.254.169.254", port: 80, reachable: false },
			{ name: "the internet", host: "1.1.1.1", port: 443, reachable: false },
			...(options.reach ?? []),
			...(options.forbid ?? []),
		];
		const gvisor = asRecord(asRecord(pods[0], "pod").spec, "spec").runtimeClassName === "gvisor";
		const raw = await kubectl([
			"-n",
			namespace,
			"exec",
			probe,
			"--",
			"node",
			"-e",
			PROBE,
			JSON.stringify({ targets, dns, firstApi: FIRST_API }),
		]);
		const inside = parseInside(parseJson(raw.trim().split("\n").at(-1) ?? ""));
		for (const failure of insideFailures(inside, targets, gvisor)) {
			failures.push(`${probe}: ${failure}`);
		}
		if (failures.length > 0) {
			throw new Error(
				`the sandbox isolation gate failed for ${namespace}/${template}:\n  ${failures.join("\n  ")}`,
			);
		}
		return inside;
	} finally {
		await kubectl([
			"-n",
			namespace,
			"delete",
			"sandboxclaim",
			...names,
			"--ignore-not-found",
			"--wait=false",
		]);
		await kubectl([
			"-n",
			namespace,
			"delete",
			"sandboxwarmpool,sandboxtemplate",
			pool,
			"--ignore-not-found",
			"--wait=false",
		]);
	}
}

/** A `<name>=<host>:<port>` argument as a target. */
export function namedTarget(entry: string, reachable: boolean): Target {
	const at = entry.indexOf("=");
	if (at <= 0) {
		throw new Error(`not a <name>=<host>:<port>: ${entry}`);
	}
	return { name: entry.slice(0, at), ...parseAddress(entry.slice(at + 1)), reachable };
}

/** `<namespace>/<name>`, as `--dns kube-system/rke2-coredns-rke2-coredns`. */
export function namespacedName(entry: string): { namespace: string; name: string } {
	const [namespace, name, ...rest] = entry.split("/");
	if (
		namespace === undefined ||
		name === undefined ||
		namespace === "" ||
		name === "" ||
		rest.length > 0
	) {
		throw new Error(`not a <namespace>/<name>: ${entry}`);
	}
	return { namespace, name };
}

async function main() {
	const { values } = parseArgs({
		options: {
			namespace: { type: "string" },
			template: { type: "string" },
			gateway: { type: "string" },
			reach: { type: "string", multiple: true, default: [] },
			forbid: { type: "string", multiple: true, default: [] },
			dns: { type: "string" },
			context: { type: "string" },
		},
	});
	if (
		values.namespace === undefined ||
		values.template === undefined ||
		values.gateway === undefined
	) {
		throw new Error("--namespace, --template and --gateway are required");
	}
	const inside = await isolationGate({
		namespace: values.namespace,
		template: values.template,
		gateway: parseAddress(values.gateway),
		reach: values.reach.map((entry) => namedTarget(entry, true)),
		forbid: values.forbid.map((entry) => namedTarget(entry, false)),
		...(values.dns === undefined ? {} : { dns: namespacedName(values.dns) }),
		...(values.context === undefined ? {} : { context: values.context }),
	});
	process.stdout.write(
		`${JSON.stringify(inside)}\nThe sandbox isolation gate passed for ${values.namespace}/${values.template}\n`,
	);
}

if (import.meta.main) {
	await main();
}
