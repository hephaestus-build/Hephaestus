/** Host edit loops share k3d, but each port set owns its sandbox namespace and chart-derived quota. */
import { createHash, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { isIP } from "node:net";
import path from "node:path";

import { parseAllDocuments } from "yaml";

import {
	ensureDevCluster,
	requireDevCluster,
	mapImages,
	operatorValues,
	RELEASE,
} from "./kubernetes-cluster.ts";
import { claimDevNamespace, ownsDevNamespace } from "./lib/dev-namespace.ts";
import { positivePort, readEnvFile } from "./lib/env.ts";
import { asArray, asRecord, asString, at, isRecord, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";

const ROOT = path.join(import.meta.dirname, "..");
const CHART = path.join(ROOT, "deploy/chart/hephaestus");
const WORK = path.join(ROOT, ".context/kubernetes-host");

/** k3d writes this literal to each node's hosts file; the node image has no getent binary. */
export function hostAddress(hosts: string): string {
	for (const line of hosts.split("\n")) {
		const [address, ...names] = line.split("#", 1)[0]?.trim().split(/\s+/u) ?? [];
		if (address !== undefined && names.includes("host.k3d.internal") && isIP(address) === 4) {
			return address;
		}
	}
	throw new Error("k3d supplied no IPv4 address for host.k3d.internal");
}

/** Only the server's agent and sandbox literal defaults; the host retains its API, database and local profile. */
export function hostServerEnvironment(rendered: string): Record<string, string> {
	const deployment = parseAllDocuments(rendered)
		.map((document) => document.toJS() as unknown)
		.filter(isRecord)
		.find(
			(resource) =>
				resource.kind === "Deployment" &&
				asRecord(resource.metadata, "metadata").name === `${RELEASE}-server`,
		);
	if (deployment === undefined) {
		throw new Error("The chart returned no server Deployment");
	}
	const template = asRecord(asRecord(deployment.spec, "deployment").template, "template");
	const container = asRecord(
		asArray(asRecord(template.spec, "Pod").containers, "containers")[0],
		"server",
	);
	return Object.fromEntries(
		asArray(container.env, "server environment")
			.map((entry) => asRecord(entry, "variable"))
			.filter(
				(entry) =>
					typeof entry.name === "string" &&
					typeof entry.value === "string" &&
					/^(?:AGENT_|SANDBOX_|HEPHAESTUS_(?:WORKER_|AGENT_|SANDBOX_|MENTOR_TURN_CAPACITY$))/u.test(
						entry.name,
					),
			)
			.map((entry) => [
				asString(entry.name, "variable name"),
				asString(entry.value, "variable value"),
			]),
	);
}

export function hostPorts(settings: NodeJS.ProcessEnv): {
	namespace: string;
	server: number;
	gateway: number;
	management: number;
} {
	const server = positivePort(settings.SERVER_PORT ?? "8080", "SERVER_PORT");
	const gateway = positivePort(settings.SANDBOX_API_PORT ?? "8081", "SANDBOX_API_PORT");
	const management = positivePort(
		settings.MANAGEMENT_PORT ?? String(server + 1010),
		"MANAGEMENT_PORT",
	);
	if (new Set([server, gateway, management]).size !== 3) {
		throw new Error("SERVER_PORT, SANDBOX_API_PORT and MANAGEMENT_PORT must differ");
	}
	return { namespace: `heph-dev-${server}-${gateway}`, server, gateway, management };
}

/** The memory request of the review template, so that the host loop's reviews quota holds exactly one review. */
function oneReviewMemory(): string {
	const values: unknown = parseAllDocuments(
		readFileSync(path.join(CHART, "values.yaml"), "utf8"),
	)[0]?.toJS();
	return asString(
		at(
			values,
			["sandbox", "templates", "review", "resources", "requests", "memory"],
			"values.yaml",
		),
		"sandbox.templates.review.resources.requests.memory",
	);
}

export function hostValues(namespace: string, address: string): Record<string, unknown> {
	return {
		...operatorValues({
			images: mapImages(() => `ghcr.io/hephaestus-build/dev@sha256:${"0".repeat(64)}`),
			commit: "0".repeat(40),
			api: { address, port: 6443 },
		}),
		conversationLoop: { replicas: 1, turns: 1 },
		// One host process: the quota holds the isolation probe of one server, not of a rollout's two.
		server: { replicas: 1, rolloutStrategy: "Recreate", drainTimeoutSeconds: 0 },
		sandbox: { enabled: true, namespace, reviewMemory: oneReviewMemory() },
	};
}

/** The loop's credential for its turn pulls. The script makes it once for each port set, in this file. */
export async function conversationLoopSecret(file: string): Promise<string> {
	try {
		await writeFile(file, randomBytes(32).toString("hex"), { flag: "wx", mode: 0o600 });
	} catch (error) {
		if (!isRecord(error) || error.code !== "EEXIST") {
			throw error;
		}
	}
	await chmod(file, 0o600);
	const text = await readFile(file, "utf8");
	const secret = text.trim();
	if (!/^[0-9a-f]{64}$/u.test(secret)) {
		throw new Error(
			`${file} must hold 64 lowercase hex characters. Delete it to make a new secret.`,
		);
	}
	return secret;
}

/**
 * Select the chart's sandbox contract and its conversation loop, without its other roles, Services or
 * databases. A loop on the host cannot reach the tools Pods, so the loop runs in k3d, in the release
 * namespace that the chart renders for. Thus the chart's tools policy admits it as it is.
 */
export function hostResources(
	rendered: string,
	namespace: string,
	host: { address: string; port: number; image: string; loopSecret: string },
): unknown[] {
	const { address, port } = host;
	if (isIP(address) !== 4) {
		throw new Error("k3d must report an IPv4 address for host.k3d.internal");
	}
	const resources = parseAllDocuments(rendered)
		.map((document) => {
			if (document.errors.length > 0) {
				throw new Error("Helm returned invalid YAML");
			}
			return document.toJS() as unknown;
		})
		.filter(isRecord);
	const loopName = `${RELEASE}-conversation-loop`;
	const sandbox = resources.filter((resource) => {
		const metadata = asRecord(resource.metadata, "resource metadata");
		return (
			(metadata.namespace === namespace &&
				["ResourceQuota", "Role", "RoleBinding", "NetworkPolicy"].includes(
					String(resource.kind),
				)) ||
			(resource.kind === "PriorityClass" && String(metadata.name).startsWith(`${namespace}-`))
		);
	});
	const loop = resources.filter(
		(resource) =>
			["Deployment", "ServiceAccount", "NetworkPolicy"].includes(String(resource.kind)) &&
			asRecord(resource.metadata, "resource metadata").name === loopName,
	);
	if (
		sandbox.filter((resource) => resource.kind === "ResourceQuota").length !== 3 ||
		sandbox.filter((resource) => resource.kind === "NetworkPolicy").length !== 3 ||
		sandbox.filter((resource) => resource.kind === "RoleBinding").length !== 1 ||
		loop.length !== 3
	) {
		throw new Error(
			"The chart no longer supplies the expected sandbox quota, policies, server authority and conversation loop",
		);
	}
	// The gateway runs on the host here. The other rules keep the chart's selectors.
	const gateway = {
		to: [{ ipBlock: { cidr: `${address}/32` } }],
		ports: [{ protocol: "TCP", port }],
	};
	for (const resource of sandbox) {
		if (resource.kind === "RoleBinding") {
			resource.subjects = [{ kind: "ServiceAccount", name: "host-server", namespace }];
		}
		if (
			resource.kind === "NetworkPolicy" &&
			asRecord(resource.metadata, "metadata").name === `${RELEASE}-sandbox-gateway`
		) {
			asRecord(resource.spec, "network policy").egress = [gateway];
		}
	}
	let secretRef: Record<string, unknown> = {};
	for (const resource of loop) {
		asRecord(resource.metadata, "metadata").namespace = `${namespace}-host`;
		if (resource.kind === "NetworkPolicy") {
			const spec = asRecord(resource.spec, "loop policy");
			const egress = asArray(spec.egress, "loop egress");
			const servers = egress.filter((rule) =>
				asArray(asRecord(rule, "rule").to, "peers").some((peer) => {
					const selector = asRecord(peer, "peer").podSelector;
					return (
						isRecord(selector) &&
						isRecord(selector.matchLabels) &&
						selector.matchLabels["app.kubernetes.io/component"] === "server"
					);
				}),
			);
			if (servers.length !== 1) {
				throw new Error("The chart's loop policy no longer names the server's gateway once");
			}
			spec.egress = egress.map((rule) => (rule === servers[0] ? gateway : rule));
		}
		if (resource.kind === "Deployment") {
			const template = asRecord(asRecord(resource.spec, "loop").template, "loop template");
			const container = asRecord(
				asArray(asRecord(template.spec, "loop Pod").containers, "containers")[0],
				"loop container",
			);
			container.image = host.image;
			const env = asArray(container.env, "loop environment").map((entry) =>
				asRecord(entry, "variable"),
			);
			const proxy = env.find((entry) => entry.name === "LLM_PROXY_URL");
			const secret = env.find((entry) => entry.name === "HEPHAESTUS_CONVERSATION_LOOP_SECRET");
			if (proxy === undefined || secret === undefined) {
				throw new Error("The chart's loop no longer reads LLM_PROXY_URL and its secret");
			}
			proxy.value = `http://${address}:${port}/internal/llm`;
			secretRef = asRecord(
				asRecord(secret.valueFrom, "secret source").secretKeyRef,
				"secret reference",
			);
		}
	}
	return [
		{
			apiVersion: "v1",
			kind: "ServiceAccount",
			metadata: { name: "host-server", namespace },
			automountServiceAccountToken: false,
		},
		...sandbox,
		{
			apiVersion: "v1",
			kind: "Secret",
			metadata: { name: asString(secretRef.name, "secret name"), namespace: `${namespace}-host` },
			type: "Opaque",
			data: {
				[asString(secretRef.key, "secret key")]: Buffer.from(host.loopSecret).toString("base64"),
			},
		},
		...loop,
	];
}

/** The exec plugin refreshes a namespace-scoped token. No credential appears in argv or logs. */
export async function credentials(admin: string, namespace: string): Promise<void> {
	const request = asRecord(
		parseJson(
			await output("kubectl", [
				"--kubeconfig",
				admin,
				"--context",
				"k3d-hephaestus",
				"-n",
				namespace,
				"create",
				"token",
				"host-server",
				"--duration=1h",
				"-o",
				"json",
			]),
		),
		"token request",
	);
	const status = asRecord(request.status, "token status");
	process.stdout.write(
		JSON.stringify({
			apiVersion: "client.authentication.k8s.io/v1beta1",
			kind: "ExecCredential",
			status: {
				token: asString(status.token, "token"),
				expirationTimestamp: asString(status.expirationTimestamp, "expiration"),
			},
		}),
	);
}

async function environment(): Promise<NodeJS.ProcessEnv> {
	return { ...(await readEnvFile(path.join(ROOT, "server/.env"))), ...process.env };
}

export async function prepareHost(): Promise<NodeJS.ProcessEnv> {
	const env = await environment();
	const ports = hostPorts(env);
	const work = path.join(WORK, ports.namespace);
	const admin = await ensureDevCluster();
	// The sandbox namespace, and the release namespace where the conversation loop runs.
	for (const namespace of [ports.namespace, `${ports.namespace}-host`]) {
		await claimDevNamespace(admin, namespace, ROOT, {
			"pod-security.kubernetes.io/enforce": "restricted",
			"hephaestus.build/dev-host": "true",
		});
	}
	await mkdir(work, { recursive: true, mode: 0o700 });
	await chmod(work, 0o700);
	const loopSecret = await conversationLoopSecret(path.join(work, "conversation-loop-secret"));
	const address = hostAddress(
		await output("docker", ["exec", "k3d-hephaestus-server-0", "cat", "/etc/hosts"]),
	);
	const values = hostValues(ports.namespace, address);
	const file = path.join(work, "sandbox-values.json");
	await writeFile(file, JSON.stringify(values));
	const rendered = await output("helm", [
		"template",
		RELEASE,
		CHART,
		"--namespace",
		`${ports.namespace}-host`,
		"-f",
		path.join(CHART, "profiles/dev.yaml"),
		"-f",
		file,
	]);
	const tag = `dev-${createHash("sha256").update(ROOT).digest("hex").slice(0, 12)}`;
	const local = `localhost:5111/hephaestus-build/agent-pi:${tag}`;
	await run(
		"docker",
		[
			"build",
			"--quiet",
			"-t",
			local,
			"--build-context",
			"agent-runtime=server/application/src/main/resources/agent",
			"-f",
			"docker/agents/pi/Dockerfile",
			"docker/agents",
		],
		{ cwd: ROOT, stdout: "ignore" },
	);
	await run("docker", ["push", "--quiet", local], { stdout: "ignore" });
	const digests = asArray(
		parseJson(
			await output("docker", ["image", "inspect", "--format", "{{json .RepoDigests}}", local]),
		),
		"image digests",
	);
	const digest = digests
		.map((value) => asString(value, "image digest"))
		.find((value) => value.startsWith("localhost:5111/"));
	if (digest === undefined) {
		throw new Error("The local registry supplied no agent image digest");
	}
	const image = digest.replace("localhost:5111/", "ghcr.io/");
	const resources = hostResources(rendered, ports.namespace, {
		address,
		port: ports.gateway,
		image,
		loopSecret,
	});

	await output(
		"kubectl",
		["--kubeconfig", admin, "--context", "k3d-hephaestus", "apply", "-f", "-"],
		{
			input: JSON.stringify({ apiVersion: "v1", kind: "List", items: resources }),
		},
	);
	// The chart computes names from the release namespace. No Pod request or production value changes here.
	const kubeconfig = path.join(work, "kubeconfig.json");
	const source = asRecord(
		parseJson(
			await output("kubectl", [
				"--kubeconfig",
				admin,
				"--context",
				"k3d-hephaestus",
				"config",
				"view",
				"--raw",
				"--minify",
				"-o",
				"json",
			]),
		),
		"kubeconfig",
	);
	await writeFile(
		kubeconfig,
		JSON.stringify({
			apiVersion: "v1",
			kind: "Config",
			clusters: asArray(source.clusters, "clusters"),
			contexts: [
				{
					name: "host-server",
					context: { cluster: "k3d-hephaestus", user: "host-server", namespace: ports.namespace },
				},
			],
			"current-context": "host-server",
			users: [
				{
					name: "host-server",
					user: {
						exec: {
							apiVersion: "client.authentication.k8s.io/v1beta1",
							command: process.execPath,
							args: [
								path.join(ROOT, "scripts/dev-kubernetes.ts"),
								"credentials",
								admin,
								ports.namespace,
							],
							interactiveMode: "Never",
						},
					},
				},
			],
		}),
		{ mode: 0o600 },
	);
	await chmod(kubeconfig, 0o600);
	return {
		...env,
		...hostServerEnvironment(rendered),
		KUBECONFIG: kubeconfig,
		SERVER_PORT: String(ports.server),
		SANDBOX_API_PORT: String(ports.gateway),
		MANAGEMENT_PORT: String(ports.management),
		HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE: ports.namespace,
		HEPHAESTUS_SANDBOX_KUBERNETES_POD_NAME: ports.namespace,
		HEPHAESTUS_SANDBOX_KUBERNETES_POD_IP: address,
		HEPHAESTUS_SANDBOX_KUBERNETES_CONVERSATION_PRIORITY_CLASS: `${ports.namespace}-host-${RELEASE}-conversations`,
		HEPHAESTUS_SANDBOX_KUBERNETES_REVIEW_PRIORITY_CLASS: `${ports.namespace}-host-${RELEASE}-reviews`,
		HEPHAESTUS_AGENT_IMAGE_REFERENCE: image,
		HEPHAESTUS_CONVERSATION_LOOP_SECRET: loopSecret,
		SANDBOX_MAX_CONCURRENT: "1",
	};
}

if (import.meta.main) {
	const [command = "", ...args] = process.argv.slice(2);
	switch (command) {
		case "credentials": {
			const [admin, namespace] = args;
			if (admin === undefined || namespace === undefined) {
				throw new Error("The credential plugin requires a kubeconfig and namespace");
			}
			await credentials(admin, namespace).catch(() => {
				throw new Error("The local sandbox credential could not be refreshed; restart dev:server");
			});
			break;
		}
		case "run": {
			const env = await prepareHost();
			await run(
				path.join(ROOT, "server", process.platform === "win32" ? "gradlew.bat" : "gradlew"),
				[":application:bootRun", `-Pprofiles=${args[0] ?? "local"}`],
				{ cwd: path.join(ROOT, "server"), env },
			);
			break;
		}
		case "reset": {
			const { namespace } = hostPorts(await environment());
			const admin = await requireDevCluster();
			await ownsDevNamespace(admin, namespace, ROOT);
			await ownsDevNamespace(admin, `${namespace}-host`, ROOT);
			await run("kubectl", [
				"--kubeconfig",
				admin,
				"--context",
				"k3d-hephaestus",
				"delete",
				"namespace",
				namespace,
				`${namespace}-host`,
				"--ignore-not-found",
				"--wait=true",
			]);
			await run("kubectl", [
				"--kubeconfig",
				admin,
				"--context",
				"k3d-hephaestus",
				"delete",
				"priorityclass",
				`${namespace}-host-${RELEASE}-reviews`,
				`${namespace}-host-${RELEASE}-conversations`,
				`${namespace}-host-${RELEASE}-roles`,
				"--ignore-not-found",
			]);
			break;
		}
		default: {
			throw new Error("Use dev-kubernetes.ts run [profile] or reset");
		}
	}
}
