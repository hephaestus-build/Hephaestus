/** Read workload containers from a rendered chart without a cluster or secret values. */
import { spawnSync } from "node:child_process";
import path from "node:path";

import { parseAllDocuments, stringify } from "yaml";

import { asRecord, readJsonFileSync, isRecord } from "./json.ts";

export const REPO_ROOT = path.resolve(import.meta.dirname, "../..");
export const CHART = "deploy/chart/hephaestus";
export const topologyImage = (name: string): string =>
	`ghcr.io/hephaestus-build/${name}@sha256:${"a".repeat(64)}`;
export const applicationImage = topologyImage("application-server");

/** Synthetic names and pins. These values never contact a registry, API server or Secret. */
export function renderDeployTopology(root = REPO_ROOT, workerEnabled = true): string {
	const schema = asRecord(
		readJsonFileSync(path.join(root, CHART, "values.schema.json")),
		"chart schema",
	);
	const properties = asRecord(schema.properties, "chart properties");
	const images = asRecord(properties.images, "chart images");
	const chartImages = Object.keys(asRecord(images.properties, "chart image properties"));
	const values = {
		release: { version: "v1.2.3", commit: "c".repeat(40) },
		images: Object.fromEntries(
			chartImages.map((name) => [
				name,
				`ghcr.io/hephaestus-build/${name}@sha256:${"a".repeat(64)}`,
			]),
		),
		host: "hephaestus.example.com",
		trustedProxies: String.raw`10\.42\..*`,
		secret: { name: "hephaestus-secrets" },
		route: { parentRefs: [{ name: "edge", namespace: "gateway-system", sectionName: "https" }] },
		networkPolicy: {
			gateway: {
				namespace: "gateway-system",
				podLabels: { "gateway.networking.k8s.io/gateway-name": "edge" },
			},
			kubernetesApi: { cidr: "192.168.5.15/32", port: 6443 },
		},
		postgresql: {
			tls: { existingSecret: "postgresql-tls" },
			backup: {
				enabled: true,
				clientTlsSecret: "backup-tls",
				s3: { bucket: "backups", endpoint: "s3.example.com", region: "eu-central-1" },
			},
		},
		worker: { enabled: workerEnabled, sandbox: { namespace: "hephaestus-sandboxes" } },
	};
	const result = spawnSync(
		"helm",
		["template", "heph", path.join(root, CHART), "--namespace", "hephaestus", "--values", "-"],
		{
			input: stringify(values),
			encoding: "utf8",
			timeout: 30_000,
			maxBuffer: 16 * 1024 * 1024,
		},
	);
	if (result.error || result.status !== 0) {
		throw new Error(`Helm topology render failed: ${result.error?.message ?? result.stderr}`);
	}
	return result.stdout;
}

export interface DeployContainer {
	readonly name: string;
	readonly image: string;
	readonly environment: ReadonlyMap<string, string>;
	readonly mounts: readonly string[];
}

function readEnvironment(container: Record<string, unknown>): Map<string, string> {
	if (container.envFrom !== undefined) {
		throw new Error("envFrom hides deployment settings from the role gate");
	}
	if (container.env !== undefined && !Array.isArray(container.env)) {
		throw new Error("Malformed rendered environment list");
	}
	const environment = new Map<string, string>();
	for (const entry of Array.isArray(container.env) ? container.env : []) {
		if (!isRecord(entry) || typeof entry.name !== "string") {
			throw new Error("Malformed rendered environment");
		}
		if (environment.has(entry.name)) {
			throw new Error(`Duplicate environment key ${entry.name}`);
		}
		if (typeof entry.value !== "string" && !isRecord(entry.valueFrom)) {
			throw new Error(`Invalid environment source ${entry.name}`);
		}
		environment.set(
			entry.name,
			typeof entry.value === "string" ? entry.value : JSON.stringify(entry.valueFrom),
		);
	}

	return environment;
}

/** Include init containers and CronJob templates: both can consume a release image or setting. */
export function readDeployContainers(text: string): DeployContainer[] {
	const containers: DeployContainer[] = [];
	for (const document of parseAllDocuments(text)) {
		if (document.errors.length > 0) {
			throw new Error("Invalid rendered workload YAML");
		}
		const resource: unknown = document.toJS();
		if (
			isRecord(resource) &&
			resource.spec !== undefined &&
			(!isRecord(resource.metadata) || typeof resource.metadata.name !== "string")
		) {
			throw new Error("Rendered workload has no metadata.name");
		}
		if (
			!isRecord(resource) ||
			!isRecord(resource.metadata) ||
			typeof resource.metadata.name !== "string"
		) {
			continue;
		}
		const workload = `${String(resource.kind)}/${resource.metadata.name}`;
		const visit = (node: unknown): void => {
			if (!isRecord(node)) {
				return;
			}
			if (Array.isArray(node.containers)) {
				const main: unknown[] = node.containers;
				const init: unknown[] = Array.isArray(node.initContainers) ? node.initContainers : [];
				for (const container of [...main, ...init]) {
					if (
						!isRecord(container) ||
						typeof container.name !== "string" ||
						typeof container.image !== "string"
					) {
						throw new Error("Malformed rendered container");
					}
					const environment = readEnvironment(container);
					containers.push({
						name: `${workload}:${container.name}`,
						image: container.image,
						environment,
						mounts: (Array.isArray(node.volumes) ? node.volumes : []).flatMap((volume: unknown) =>
							isRecord(volume) &&
							isRecord(volume.hostPath) &&
							typeof volume.hostPath.path === "string"
								? [volume.hostPath.path]
								: [],
						),
					});
				}
				return;
			}
			for (const child of Object.values(node)) {
				visit(child);
			}
		};
		visit(resource.spec);
	}
	if (containers.length === 0) {
		throw new Error("Rendered deployment has no containers");
	}
	return containers;
}
