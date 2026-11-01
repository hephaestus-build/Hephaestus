/** Host edit loops share k3d, but each port set owns its sandbox namespace and chart-derived quota. */
import { createHash } from "node:crypto";
import { chmod, mkdir, writeFile } from "node:fs/promises";
import { isIP } from "node:net";
import path from "node:path";

import { parseAllDocuments } from "yaml";

import {
	AGENT_IMAGE_BUILD_CONTEXTS,
	ensureDevCluster,
	requireDevCluster,
	mapImages,
	operatorValues,
	RELEASE,
} from "./kubernetes-cluster.ts";
import { claimDevNamespace, ownsDevNamespace } from "./lib/dev-namespace.ts";
import { positivePort, readEnvFile } from "./lib/env.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
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

/** Only worker/sandbox literal defaults; the host retains its API, database and local profile. */
export function hostWorkerEnvironment(rendered: string): Record<string, string> {
	const deployment = parseAllDocuments(rendered)
		.map((document) => document.toJS() as unknown)
		.filter(isRecord)
		.find(
			(resource) =>
				resource.kind === "Deployment" &&
				asRecord(resource.metadata, "metadata").name === `${RELEASE}-worker`,
		);
	if (deployment === undefined) {
		throw new Error("The chart returned no worker Deployment");
	}
	const template = asRecord(asRecord(deployment.spec, "deployment").template, "template");
	const container = asRecord(
		asArray(asRecord(template.spec, "Pod").containers, "containers")[0],
		"worker",
	);
	return Object.fromEntries(
		asArray(container.env, "worker environment")
			.map((entry) => asRecord(entry, "variable"))
			.filter(
				(entry) =>
					typeof entry.name === "string" &&
					typeof entry.value === "string" &&
					/^(?:AGENT_|SANDBOX_|HEPHAESTUS_(?:WORKER_|AGENT_|SANDBOX_))/u.test(entry.name),
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

export function hostValues(namespace: string, address: string): Record<string, unknown> {
	return {
		...operatorValues({
			images: mapImages(() => `ghcr.io/hephaestus-build/dev@sha256:${"0".repeat(64)}`),
			commit: "0".repeat(40),
			api: { address, port: 6443 },
		}),
		worker: {
			enabled: true,
			replicas: 1,
			reviews: 1,
			conversationTurns: 1,
			rolloutStrategy: "Recreate",
			sandbox: { namespace },
		},
	};
}

/** Select the chart's sandbox contract, without installing its roles, Services or databases. */
export function hostResources(
	rendered: string,
	namespace: string,
	address: string,
	port: number,
): unknown[] {
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
	const selected = resources.filter((resource) => {
		const metadata = asRecord(resource.metadata, "resource metadata");
		return (
			(metadata.namespace === namespace &&
				["ResourceQuota", "Role", "RoleBinding", "NetworkPolicy"].includes(
					String(resource.kind),
				)) ||
			(resource.kind === "PriorityClass" &&
				String(metadata.name).startsWith(`${namespace}-`) &&
				!String(metadata.name).endsWith("-roles"))
		);
	});
	for (const resource of selected) {
		if (resource.kind === "RoleBinding") {
			resource.subjects = [{ kind: "ServiceAccount", name: "host-worker", namespace }];
		}
		if (resource.kind === "NetworkPolicy") {
			const spec = asRecord(resource.spec, "network policy");
			// Only the gateway policy has egress. Turn/tools Pods retain the chart's complete network denial.
			if (spec.egress !== undefined) {
				spec.egress = [
					{ to: [{ ipBlock: { cidr: `${address}/32` } }], ports: [{ protocol: "TCP", port }] },
				];
			}
		}
	}
	if (
		selected.filter((resource) => resource.kind === "ResourceQuota").length !== 3 ||
		// The sandbox deny-all, the gateway policy, and the tools Jobs' ingress from the conversation loop.
		selected.filter((resource) => resource.kind === "NetworkPolicy").length !== 3 ||
		selected.filter((resource) => resource.kind === "RoleBinding").length !== 1
	) {
		throw new Error(
			"The chart no longer supplies the expected sandbox quota, policies and worker authority",
		);
	}
	return [
		{
			apiVersion: "v1",
			kind: "ServiceAccount",
			metadata: { name: "host-worker", namespace },
			automountServiceAccountToken: false,
		},
		...selected,
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
				"host-worker",
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
	await claimDevNamespace(admin, ports.namespace, ROOT, {
		"pod-security.kubernetes.io/enforce": "restricted",
		"hephaestus.build/dev-host": "true",
	});
	await mkdir(work, { recursive: true, mode: 0o700 });
	await chmod(work, 0o700);
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
	const resources = hostResources(rendered, ports.namespace, address, ports.gateway);

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
					name: "host-worker",
					context: { cluster: "k3d-hephaestus", user: "host-worker", namespace: ports.namespace },
				},
			],
			"current-context": "host-worker",
			users: [
				{
					name: "host-worker",
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
	const tag = `dev-${createHash("sha256").update(ROOT).digest("hex").slice(0, 12)}`;
	const local = `localhost:5111/hephaestus-build/agent-pi:${tag}`;
	await run(
		"docker",
		[
			"build",
			"--quiet",
			"-t",
			local,
			"-f",
			"docker/agents/pi/Dockerfile",
			...AGENT_IMAGE_BUILD_CONTEXTS,
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
	return {
		...env,
		...hostWorkerEnvironment(rendered),
		KUBECONFIG: kubeconfig,
		SERVER_PORT: String(ports.server),
		SANDBOX_API_PORT: String(ports.gateway),
		MANAGEMENT_PORT: String(ports.management),
		HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE: ports.namespace,
		HEPHAESTUS_SANDBOX_KUBERNETES_POD_NAME: ports.namespace,
		HEPHAESTUS_SANDBOX_KUBERNETES_POD_IP: address,
		HEPHAESTUS_SANDBOX_KUBERNETES_CONVERSATION_PRIORITY_CLASS: `${ports.namespace}-host-${RELEASE}-conversations`,
		HEPHAESTUS_SANDBOX_KUBERNETES_REVIEW_PRIORITY_CLASS: `${ports.namespace}-host-${RELEASE}-reviews`,
		HEPHAESTUS_AGENT_IMAGE_REFERENCE: digest.replace("localhost:5111/", "ghcr.io/"),
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
			await run("kubectl", [
				"--kubeconfig",
				admin,
				"--context",
				"k3d-hephaestus",
				"delete",
				"namespace",
				namespace,
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
