/**
 * Installs the chart on a disposable cluster and checks that it runs: the CI cluster jobs and
 * `vp run dev:kubernetes` both use it, so that a developer runs what CI runs.
 *
 *   prepare  namespaces, Secrets, certificates and the operator's values for the images in a file
 *   install  `helm upgrade --install` with the dev profile and the prepared values, until ready
 *   verify   every workload ready, and each worker's isolation probe passed
 *   backup-drill  pgBackRest to a throwaway S3 server, then a point-in-time restore
 *   render   the manifests of those values for synthetic images, with backups on and with an external
 *            PostgreSQL, for a schema check without a cluster
 *   dev      a local k3d cluster with a registry, this checkout's images built and pushed, then the
 *            three steps above
 *
 * The cluster is the caller's: the script creates none, except `dev`. Every generated value stays
 * in the work directory, which only the user can read; the script prints no secret value.
 */
import { createHash, randomBytes } from "node:crypto";
import { chmod, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";

import { readInventory } from "./commit-image-lock.ts";
import type { ChartImage } from "./kubernetes-chart.ts";
import { asRecord, asString, parseJson } from "./lib/json.ts";
import { output, run } from "./lib/process.ts";
import { parseResolvedImages } from "./resolve-release-images.ts";

const ROOT = path.join(import.meta.dirname, "..");
const CHART = path.join(ROOT, "deploy", "chart", "hephaestus");

export const RELEASE = "heph";
export const NAMESPACE = "hephaestus";
export const SANDBOXES = "hephaestus-sandboxes";
const GATEWAY_NAMESPACE = "gateway";
const EXTERNAL_POSTGRESQL = "external-postgresql";
const HOST = "hephaestus.cluster.test";

/** The Gateway API resource definitions that the route needs, at a pinned release and checksum. */
const GATEWAY_API = {
	url: "https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.3/standard-install.yaml",
	sha256: "356c2b8e286a964dc20995435659ad1095386329974793af10bbfe3eff977fe5",
};

/** What `verify` reads in a worker's log once its probe passed (KubernetesIsolationProbe). */
export const PROBE_PASSED = "The sandbox isolation probe passed";

export type ChartImages = Record<ChartImage, string>;

/**
 * The chart's images: the first-party ones that a run resolved, by index digest, and the upstream
 * ones at the inventory's pins.
 */
export function chartImagesFrom(
	resolved: readonly { image: string; repository: string; indexDigest: string }[],
	upstream: readonly { name: string; repository: string; digest: string }[],
): ChartImages {
	const references = new Map<string, string>();
	for (const image of resolved) {
		references.set(image.image, `${image.repository}@${image.indexDigest}`);
	}
	for (const image of upstream) {
		references.set(image.name, `${image.repository}@${image.digest}`);
	}
	return mapImages((name) => {
		const reference = references.get(name);
		if (reference === undefined) {
			throw new Error(`no image for ${name}: the image file and the inventory name none`);
		}
		return reference;
	});
}

/** One reference for each chart image. */
export function mapImages(reference: (name: ChartImage) => string): ChartImages {
	return {
		"agent-pi": reference("agent-pi"),
		"application-server": reference("application-server"),
		nats: reference("nats"),
		postgres: reference("postgres"),
		webapp: reference("webapp"),
	};
}

export interface Endpoint {
	address: string;
	port: number;
}

/** The Kubernetes API as the network plugin sees it: the endpoint of the default `kubernetes` Service. */
export function apiEndpoint(slices: unknown): Endpoint {
	const { items } = asRecord(slices, "EndpointSlice list");
	if (!Array.isArray(items) || items.length === 0) {
		throw new Error("the kubernetes Service has no EndpointSlice");
	}
	const slice = asRecord(items[0], "EndpointSlice");
	const { endpoints, ports } = slice;
	if (
		!Array.isArray(endpoints) ||
		!Array.isArray(ports) ||
		endpoints.length === 0 ||
		ports.length === 0
	) {
		throw new Error("the kubernetes EndpointSlice names no address or port");
	}
	const { addresses } = asRecord(endpoints[0], "endpoint");
	if (!Array.isArray(addresses) || addresses.length === 0) {
		throw new Error("the kubernetes endpoint has no address");
	}
	const { port } = asRecord(ports[0], "endpoint port");
	if (typeof port !== "number") {
		throw new Error("the kubernetes endpoint port is not a number");
	}
	return { address: asString(addresses[0], "endpoint address"), port };
}

export interface ValuesOptions {
	images: ChartImages;
	commit: string;
	api: Endpoint;
	/** The host and CIDR of an operator's PostgreSQL; the bundled one when absent. */
	externalPostgresql?: { host: string; cidr: string };
}

/** The operator's values for a test install: every required value, and the worker enabled. */
export function operatorValues(options: ValuesOptions): Record<string, unknown> {
	const cidr = options.api.address.includes(":")
		? `${options.api.address}/128`
		: `${options.api.address}/32`;
	return {
		release: { version: "v0.0.0-cluster", commit: options.commit },
		images: options.images,
		host: HOST,
		trustedProxies: String.raw`10\.\d{1,3}\.\d{1,3}\.\d{1,3}`,
		secret: { name: `${RELEASE}-app` },
		route: { parentRefs: [{ name: "cluster", namespace: GATEWAY_NAMESPACE }] },
		networkPolicy: {
			gateway: { namespace: GATEWAY_NAMESPACE, podLabels: { app: "gateway" } },
			kubernetesApi: { cidr, port: options.api.port },
		},
		postgresql: {
			tls: { existingSecret: `${RELEASE}-postgresql-tls` },
			...(options.externalPostgresql === undefined
				? {}
				: { bundled: false, external: options.externalPostgresql }),
		},
		worker: {
			enabled: true,
			sandbox: { namespace: SANDBOXES },
			// A CI runner has four CPUs: two workers fit beside the other roles at this request.
			resources: {
				requests: { cpu: "250m", memory: "2Gi", "ephemeral-storage": "256Mi" },
				limits: { cpu: "2", memory: "2Gi", "ephemeral-storage": "4Gi" },
			},
			gitCache: { sizeLimit: "2Gi" },
		},
	};
}

const secretValue = (bytes: number) => randomBytes(bytes).toString("hex");

/** The release Secret: every required key, generated, and nothing that reaches a provider. */
export function applicationSecret(): Record<string, string> {
	const key = secretValue(16);
	return {
		DATABASE_USERNAME: "hephaestus",
		DATABASE_PASSWORD: secretValue(16),
		NATS_USERNAME: `nats${secretValue(8)}`,
		NATS_PASSWORD: secretValue(16),
		HEPHAESTUS_SECURITY_ENCRYPTION_KEY: key,
		HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY: key,
		HEPHAESTUS_AUTH_STATE_COOKIE_KEY: randomBytes(32).toString("base64"),
		WEBHOOK_SECRET: secretValue(32),
		WEBHOOK_ROUTING_SECRET: secretValue(32),
		HEPHAESTUS_WORKER_REGISTRATION_TOKEN: secretValue(32),
	};
}

async function kubectl(args: string[], input?: string): Promise<string> {
	return output("kubectl", args, input === undefined ? {} : { input });
}

/** Applies a manifest from standard input, so that no generated value appears in an argument list. */
async function apply(manifest: unknown): Promise<void> {
	await kubectl(
		["apply", "--server-side", "--force-conflicts", "-f", "-"],
		JSON.stringify(manifest),
	);
}

const restricted = { "pod-security.kubernetes.io/enforce": "restricted" };

async function namespace(name: string, labels: Record<string, string> = restricted): Promise<void> {
	await apply({ apiVersion: "v1", kind: "Namespace", metadata: { name, labels } });
}

async function secret(
	name: string,
	namespaceName: string,
	data: Record<string, string>,
): Promise<void> {
	await apply({
		apiVersion: "v1",
		kind: "Secret",
		metadata: { name, namespace: namespaceName },
		type: "Opaque",
		stringData: data,
	});
}

async function openssl(work: string, args: string[]): Promise<void> {
	await run("openssl", args, { cwd: work, stdout: "ignore", stderr: "ignore" });
}

/** A certificate and key that the work directory's CA signs. */
async function certificate(
	work: string,
	name: string,
	subject: string,
	extension: string,
): Promise<void> {
	await writeFile(path.join(work, `${name}.ext`), `${extension}\n`);
	await openssl(work, [
		"req",
		"-newkey",
		"rsa:3072",
		"-nodes",
		"-subj",
		subject,
		"-keyout",
		`${name}.key`,
		"-out",
		`${name}.csr`,
	]);
	await openssl(work, [
		"x509",
		"-req",
		"-in",
		`${name}.csr`,
		"-CA",
		"ca.crt",
		"-CAkey",
		"ca.key",
		"-CAcreateserial",
		"-days",
		"7",
		"-extfile",
		`${name}.ext`,
		"-out",
		`${name}.crt`,
	]);
}

/** A CA, a server certificate for each of the hosts, and the backup client certificate. */
async function certificates(
	work: string,
	hosts: readonly string[],
): Promise<Record<string, string>> {
	await openssl(work, [
		"req",
		"-x509",
		"-newkey",
		"rsa:3072",
		"-nodes",
		"-days",
		"7",
		"-subj",
		"/CN=Hephaestus cluster test CA",
		"-keyout",
		"ca.key",
		"-out",
		"ca.crt",
	]);
	const issue = async (name: string, subject: string, extension: string) =>
		certificate(work, name, subject, extension);
	const [primary] = hosts;
	if (primary === undefined) {
		throw new Error("the PostgreSQL certificate needs a host name");
	}
	await issue(
		"tls",
		`/CN=${primary}`,
		`subjectAltName=${hosts.map((host) => `DNS:${host}`).join(",")}`,
	);
	await issue("backup", "/CN=hephaestus-backup", "extendedKeyUsage=clientAuth");
	const read = async (file: string) => readFile(path.join(work, file), "utf8");
	return {
		"ca.crt": await read("ca.crt"),
		"tls.crt": await read("tls.crt"),
		"tls.key": await read("tls.key"),
		"backup.crt": await read("backup.crt"),
		"backup.key": await read("backup.key"),
	};
}

/** Installs the pinned Gateway API definitions on a cluster that has none; a cluster's own stay as they are. */
async function gatewayApi(work: string): Promise<void> {
	const installed = await output("kubectl", ["get", "crd", "-o", "name"]);
	if (
		installed
			.split("\n")
			.includes(
				"customresourcedefinition.apiextensions.k8s.io/httproutes.gateway.networking.k8s.io",
			)
	) {
		return;
	}
	const response = await fetch(GATEWAY_API.url);
	if (!response.ok) {
		throw new Error(`could not download the Gateway API definitions: HTTP ${response.status}`);
	}
	const body = Buffer.from(await response.arrayBuffer());
	const digest = createHash("sha256").update(body).digest("hex");
	if (digest !== GATEWAY_API.sha256) {
		throw new Error(
			`the Gateway API definitions have SHA-256 ${digest}, not the pinned ${GATEWAY_API.sha256}`,
		);
	}
	const file = path.join(work, "gateway-api.yaml");
	await writeFile(file, body);
	await kubectl(["apply", "--server-side", "-f", file]);
}

/** An operator's PostgreSQL in a namespace of its own, from the release image, with TLS. */
async function externalPostgresql(
	image: string,
	tls: Record<string, string>,
	password: string,
): Promise<void> {
	await namespace(EXTERNAL_POSTGRESQL);
	await secret("postgresql", EXTERNAL_POSTGRESQL, {
		"tls.crt": tls["tls.crt"] ?? "",
		"tls.key": tls["tls.key"] ?? "",
		POSTGRES_PASSWORD: password,
	});
	const labels = { app: "postgresql" };
	const restrictedContainer = {
		runAsNonRoot: true,
		allowPrivilegeEscalation: false,
		readOnlyRootFilesystem: true,
		capabilities: { drop: ["ALL"] },
	};
	await apply({
		apiVersion: "v1",
		kind: "Service",
		metadata: { name: "postgresql", namespace: EXTERNAL_POSTGRESQL },
		spec: { selector: labels, ports: [{ port: 5432, targetPort: 5432 }] },
	});
	await apply({
		apiVersion: "apps/v1",
		kind: "StatefulSet",
		metadata: { name: "postgresql", namespace: EXTERNAL_POSTGRESQL },
		spec: {
			serviceName: "postgresql",
			replicas: 1,
			selector: { matchLabels: labels },
			template: {
				metadata: { labels },
				spec: {
					automountServiceAccountToken: false,
					securityContext: {
						runAsUser: 999,
						runAsGroup: 999,
						fsGroup: 999,
						seccompProfile: { type: "RuntimeDefault" },
					},
					containers: [
						{
							name: "postgresql",
							image,
							args: [
								"-c",
								"ssl=on",
								"-c",
								"ssl_cert_file=/etc/postgresql/tls/tls.crt",
								"-c",
								"ssl_key_file=/etc/postgresql/tls/tls.key",
							],
							env: [
								{ name: "POSTGRES_USER", value: "hephaestus" },
								{ name: "POSTGRES_DB", value: "hephaestus" },
								{
									name: "POSTGRES_PASSWORD",
									valueFrom: { secretKeyRef: { name: "postgresql", key: "POSTGRES_PASSWORD" } },
								},
							],
							ports: [{ containerPort: 5432 }],
							securityContext: restrictedContainer,
							readinessProbe: {
								exec: { command: ["pg_isready", "-h", "127.0.0.1", "-U", "hephaestus"] },
								periodSeconds: 5,
							},
							resources: {
								requests: { cpu: "100m", memory: "256Mi" },
								limits: { cpu: "1", memory: "1Gi" },
							},
							volumeMounts: [
								{ name: "data", mountPath: "/var/lib/postgresql" },
								{ name: "run", mountPath: "/var/run/postgresql" },
								{ name: "tmp", mountPath: "/tmp" },
								{ name: "tls", mountPath: "/etc/postgresql/tls", readOnly: true },
							],
						},
					],
					volumes: [
						{ name: "data", emptyDir: {} },
						{ name: "run", emptyDir: {} },
						{ name: "tmp", emptyDir: {} },
						{
							name: "tls",
							secret: {
								secretName: "postgresql",
								items: [
									{ key: "tls.crt", path: "tls.crt" },
									{ key: "tls.key", path: "tls.key" },
								],
								defaultMode: 0o440,
							},
						},
					],
				},
			},
		},
	});
	await kubectl([
		"-n",
		EXTERNAL_POSTGRESQL,
		"rollout",
		"status",
		"statefulset/postgresql",
		"--timeout=10m",
	]);
}

interface PrepareOptions {
	work: string;
	images: ChartImages;
	commit: string;
	/** The pod CIDR that holds an external PostgreSQL; the bundled one when absent. */
	externalCidr?: string;
}

export async function prepare(options: PrepareOptions): Promise<void> {
	await mkdir(options.work, { recursive: true, mode: 0o700 });
	await chmod(options.work, 0o700);
	await gatewayApi(options.work);
	await namespace(NAMESPACE);
	await namespace(SANDBOXES);
	await namespace(GATEWAY_NAMESPACE, {});
	const application = applicationSecret();
	const externalHost = `postgresql.${EXTERNAL_POSTGRESQL}.svc`;
	const hosts =
		options.externalCidr === undefined
			? [`${RELEASE}-postgresql.${NAMESPACE}.svc`]
			: [externalHost];
	const tls = await certificates(options.work, hosts);
	await secret(`${RELEASE}-app`, NAMESPACE, application);
	await secret(`${RELEASE}-postgresql-tls`, NAMESPACE, tls);
	if (options.externalCidr !== undefined) {
		await externalPostgresql(options.images.postgres, tls, application.DATABASE_PASSWORD ?? "");
	}
	const slices = parseJson(
		await kubectl([
			"get",
			"endpointslices",
			"-n",
			"default",
			"-l",
			"kubernetes.io/service-name=kubernetes",
			"-o",
			"json",
		]),
	);
	const values = operatorValues({
		images: options.images,
		commit: options.commit,
		api: apiEndpoint(slices),
		...(options.externalCidr === undefined
			? {}
			: { externalPostgresql: { host: externalHost, cidr: options.externalCidr } }),
	});
	await writeFile(path.join(options.work, "values.yaml"), `${JSON.stringify(values, null, 2)}\n`, {
		mode: 0o600,
	});
}

export async function install(work: string, extra: readonly string[] = []): Promise<void> {
	await run("helm", [
		"upgrade",
		"--install",
		RELEASE,
		CHART,
		"--namespace",
		NAMESPACE,
		"--values",
		path.join(CHART, "profiles", "dev.yaml"),
		"--values",
		path.join(work, "values.yaml"),
		...extra,
		// The documented upgrade (docs/admin/kubernetes.mdx § Upgrades), without --rollback-on-failure:
		// a failed run keeps its objects for the failure record.
		"--wait=watcher",
		"--wait-for-jobs",
		"--timeout",
		"20m",
	]);
}

export async function verify(): Promise<void> {
	for (const kind of ["deployment", "statefulset"]) {
		const listed = await kubectl(["-n", NAMESPACE, "get", kind, "-o", "name"]);
		const names = listed.split("\n").filter(Boolean);
		for (const name of names) {
			await kubectl(["-n", NAMESPACE, "rollout", "status", name, "--timeout=15m"]);
		}
	}
	// The Pods of the current workers: a rollout's old Pods can still be terminating.
	const listed = asRecord(
		parseJson(
			await kubectl([
				"-n",
				NAMESPACE,
				"get",
				"pods",
				"-l",
				"app.kubernetes.io/component=worker",
				"-o",
				"json",
			]),
		),
		"Pod list",
	).items;
	const workers = (Array.isArray(listed) ? listed : [])
		.map((pod) => asRecord(asRecord(pod, "Pod").metadata, "Pod metadata"))
		.filter((metadata) => metadata.deletionTimestamp === undefined)
		.map((metadata) => `pod/${asString(metadata.name, "Pod name")}`);
	if (workers.length === 0) {
		throw new Error("no worker pod runs");
	}
	for (const worker of workers) {
		const log = await kubectl(["-n", NAMESPACE, "logs", worker, "--tail=-1"]);
		if (!log.includes(PROBE_PASSED)) {
			throw new Error(`${worker} logged no passed isolation probe`);
		}
	}
	const leftovers = await kubectl([
		"-n",
		SANDBOXES,
		"get",
		"pods",
		"-l",
		"hephaestus.build/isolation-probe=true",
		"-o",
		"name",
	]);
	if (leftovers.trim() !== "") {
		throw new Error(`isolation probe pods stayed: ${leftovers.trim()}`);
	}
	process.stdout.write(
		`${workers.length} workers passed the isolation probe; every workload is ready\n`,
	);
}

const STORAGE_NAMESPACE = "backup-storage";
const BUCKET = "hephaestus-backups";
/** An S3 server for the drill alone: rclone serves a directory, each subdirectory a bucket. */
const S3_IMAGE =
	"docker.io/rclone/rclone:1.75.2@sha256:2687085f718d3c628f7fdfb77c52a1d344332aed543110bb88088f2c70d43eb5";

async function psql(sql: string): Promise<string> {
	return kubectl([
		"-n",
		NAMESPACE,
		"exec",
		`${RELEASE}-postgresql-0`,
		"-c",
		"postgresql",
		"--",
		"psql",
		"-U",
		"hephaestus",
		"-d",
		"hephaestus",
		"-v",
		"ON_ERROR_STOP=1",
		"-At",
		"-c",
		sql,
	]);
}

/** Waits until the Job completed; a Job that failed ends the wait at once. */
async function waitForJob(job: string, timeout = "15m"): Promise<void> {
	const ended = new AbortController();
	const wait = async (condition: "complete" | "failed") =>
		output(
			"kubectl",
			[
				"-n",
				NAMESPACE,
				"wait",
				`--for=condition=${condition}`,
				`job/${job}`,
				`--timeout=${timeout}`,
			],
			{ signal: ended.signal },
		);
	// A wait for the failed condition that ends without it, at its timeout or aborted, leaves the outcome to the other.
	const failed = (async () => {
		try {
			await wait("failed");
		} catch {
			return;
		}
		throw new Error(`job/${job} failed: kubectl -n ${NAMESPACE} logs job/${job}`);
	})();
	try {
		await Promise.race([wait("complete"), failed]);
	} finally {
		// The losing wait would otherwise hold its kubectl, and this process, until its timeout.
		ended.abort();
	}
}

/** The release's current Helm revision, which names the Jobs of that revision. */
async function revision(): Promise<number> {
	const history = parseJson(
		await output("helm", ["-n", NAMESPACE, "history", RELEASE, "--max", "1", "-o", "json"]),
	);
	const latest = Array.isArray(history) ? asRecord(history[0], "revision").revision : undefined;
	if (typeof latest !== "number") {
		throw new Error("helm reports no revision of the release");
	}
	return latest;
}

/** An S3-compatible endpoint with TLS from the work directory's CA, and its bucket, empty and with new keys. */
async function s3Storage(work: string): Promise<{ key: string; secret: string }> {
	// A server of an earlier drill holds its own keys and backups.
	await kubectl(["delete", "namespace", STORAGE_NAMESPACE, "--ignore-not-found", "--wait=true"]);
	await namespace(STORAGE_NAMESPACE, {});
	await certificate(
		work,
		"s3",
		`/CN=s3.${STORAGE_NAMESPACE}.svc`,
		`subjectAltName=DNS:s3.${STORAGE_NAMESPACE}.svc`,
	);
	const credentials = { key: secretValue(10), secret: secretValue(20) };
	await secret("s3", STORAGE_NAMESPACE, {
		"tls.crt": await readFile(path.join(work, "s3.crt"), "utf8"),
		"tls.key": await readFile(path.join(work, "s3.key"), "utf8"),
		S3_KEY: credentials.key,
		S3_SECRET: credentials.secret,
	});
	const labels = { app: "s3" };
	await apply({
		apiVersion: "v1",
		kind: "Service",
		metadata: { name: "s3", namespace: STORAGE_NAMESPACE },
		spec: { selector: labels, ports: [{ port: 443, targetPort: 443 }] },
	});
	await apply({
		apiVersion: "apps/v1",
		kind: "Deployment",
		metadata: { name: "s3", namespace: STORAGE_NAMESPACE },
		spec: {
			selector: { matchLabels: labels },
			template: {
				metadata: { labels },
				spec: {
					automountServiceAccountToken: false,
					initContainers: [
						{
							name: "bucket",
							image: S3_IMAGE,
							command: ["mkdir", "-p", `/data/${BUCKET}`],
							volumeMounts: [{ name: "data", mountPath: "/data" }],
						},
					],
					containers: [
						{
							name: "s3",
							image: S3_IMAGE,
							args: [
								"serve",
								"s3",
								"/data",
								"--addr",
								":443",
								"--cert",
								"/tls/tls.crt",
								"--key",
								"/tls/tls.key",
								"--auth-key",
								"$(S3_KEY),$(S3_SECRET)",
							],
							env: ["S3_KEY", "S3_SECRET"].map((name) => ({
								name,
								valueFrom: { secretKeyRef: { name: "s3", key: name } },
							})),
							ports: [{ containerPort: 443 }],
							readinessProbe: { tcpSocket: { port: 443 }, periodSeconds: 2 },
							volumeMounts: [
								{ name: "data", mountPath: "/data" },
								{ name: "tls", mountPath: "/tls", readOnly: true },
							],
						},
					],
					volumes: [
						{ name: "data", emptyDir: {} },
						{
							name: "tls",
							secret: {
								secretName: "s3",
								items: [
									{ key: "tls.crt", path: "tls.crt" },
									{ key: "tls.key", path: "tls.key" },
								],
							},
						},
					],
				},
			},
		},
	});
	await kubectl(["-n", STORAGE_NAMESPACE, "rollout", "status", "deployment/s3", "--timeout=5m"]);
	return credentials;
}

/**
 * The backup and restore drill of docs/admin/kubernetes.mdx § Backup and restore, on the bundled
 * PostgreSQL: a full backup, a row that only the WAL archive holds, a dropped table, and then a
 * point-in-time restore through the chart's restore setting that brings both rows back.
 */
export async function backupDrill(work: string, podCidr: string): Promise<void> {
	const credentials = await s3Storage(work);
	// Other field managers than `prepare`'s, so that these keys join the Secrets' existing ones.
	const join = async (name: string, data: Record<string, string>) => {
		await kubectl(
			["apply", "--server-side", "--field-manager=hephaestus-backup-drill", "-f", "-"],
			JSON.stringify({
				apiVersion: "v1",
				kind: "Secret",
				metadata: { name, namespace: NAMESPACE },
				stringData: data,
			}),
		);
	};
	await join(`${RELEASE}-app`, {
		BACKUP_S3_KEY: credentials.key,
		BACKUP_S3_KEY_SECRET: credentials.secret,
		BACKUP_CIPHER_PASS: randomBytes(48).toString("base64"),
	});
	await join(`${RELEASE}-postgresql-tls`, {
		"s3-ca.crt": await readFile(path.join(work, "ca.crt"), "utf8"),
	});
	const backup = (restore: { enabled: boolean; time: string }) => ({
		postgresql: {
			backup: {
				enabled: true,
				s3: {
					bucket: BUCKET,
					endpoint: `s3.${STORAGE_NAMESPACE}.svc`,
					region: "us-east-1",
					uriStyle: "path",
					cidr: podCidr,
					// The default port: pgBackRest signs a request for the host name without a port.
					port: 443,
					privateCa: true,
				},
				restore,
			},
		},
	});
	const layer = async (name: string, values: object) => {
		const file = path.join(work, `${name}.yaml`);
		await writeFile(file, `${JSON.stringify(values, null, 2)}\n`, { mode: 0o600 });
		return ["--values", file];
	};
	const backupOn = await layer("backup", backup({ enabled: false, time: "" }));
	await install(work, backupOn);
	// PostgreSQL reads the bucket's keys at its start, and an install that changes no value of its Pod keeps it.
	await kubectl(["-n", NAMESPACE, "rollout", "restart", `statefulset/${RELEASE}-postgresql`]);
	await kubectl([
		"-n",
		NAMESPACE,
		"rollout",
		"status",
		`statefulset/${RELEASE}-postgresql`,
		"--timeout=10m",
	]);
	await waitForJob(`${RELEASE}-postgresql-backup-stanza-${await revision()}`);
	process.stdout.write("backup drill: the stanza exists and the WAL archive works\n");

	await psql(
		"DROP TABLE IF EXISTS drill_marker; CREATE TABLE drill_marker (note text); INSERT INTO drill_marker VALUES ('in the full backup')",
	);
	await kubectl([
		"-n",
		NAMESPACE,
		"delete",
		"job",
		"drill-full",
		"--ignore-not-found",
		"--wait=true",
	]);
	await kubectl([
		"-n",
		NAMESPACE,
		"create",
		"job",
		"drill-full",
		`--from=cronjob/${RELEASE}-postgresql-backup-full`,
	]);
	await waitForJob("drill-full");
	process.stdout.write("backup drill: a full backup completed\n");
	await psql("INSERT INTO drill_marker VALUES ('only in the WAL archive')");
	const now = await psql(
		"SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00'",
	);
	const target = now.trim();
	await psql("SELECT pg_sleep(2); DROP TABLE drill_marker");
	// `check` switches the WAL segment and waits until the archive holds it.
	await kubectl([
		"-n",
		NAMESPACE,
		"exec",
		`${RELEASE}-postgresql-0`,
		"-c",
		"pgbackrest",
		"--",
		"pgbackrest",
		"--stanza=hephaestus",
		"check",
	]);

	await install(work, await layer("restore", backup({ enabled: true, time: target })));
	await waitForJob(`${RELEASE}-postgresql-backup-restore-${await revision()}`, "20m");
	process.stdout.write(`backup drill: restored to ${target}\n`);
	await install(work, backupOn);
	const rows = await psql("SELECT note FROM drill_marker ORDER BY note");
	const notes = rows.trim().split("\n");
	if (notes.join("|") !== "in the full backup|only in the WAL archive") {
		throw new Error(`the restored table holds ${JSON.stringify(notes)}, not both rows`);
	}
	process.stdout.write("backup drill: both rows are back, and the dropped table is restored\n");
	await verify();
}

/** Every manifest that the cluster jobs install, for synthetic images: no cluster, no registry. */
export async function render(): Promise<string> {
	const inventory = await readInventory(path.join(ROOT, "security", "release-images.json"));
	const images = chartImagesFrom(
		inventory.images.map((image) => ({
			image,
			repository: `ghcr.io/hephaestus-build/${image}`,
			indexDigest: `sha256:${"0".repeat(64)}`,
		})),
		inventory.upstream,
	);
	const base = { images, commit: "0".repeat(40), api: { address: "172.18.0.2", port: 6443 } };
	const variants = [
		{
			...operatorValues(base),
			postgresql: {
				tls: { existingSecret: `${RELEASE}-postgresql-tls` },
				backup: {
					enabled: true,
					s3: { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" },
				},
			},
		},
		operatorValues({
			...base,
			externalPostgresql: { host: "postgresql.external.svc", cidr: "192.168.0.0/16" },
		}),
	];
	const work = await mkdtemp(path.join(tmpdir(), "hephaestus-render-"));
	try {
		const manifests = [];
		for (const [index, values] of variants.entries()) {
			const file = path.join(work, `values-${index}.yaml`);
			await writeFile(file, JSON.stringify(values));
			const layers = [
				"--namespace",
				NAMESPACE,
				"--values",
				path.join(CHART, "profiles", "dev.yaml"),
				"--values",
				file,
			];
			// Helm's own checks first, warnings included. Its report goes to stderr: stdout is the manifests.
			await run("helm", ["lint", "--strict", CHART, ...layers], { stdout: "stderr" });
			manifests.push(await output("helm", ["template", RELEASE, CHART, ...layers]));
		}
		return manifests.join("\n---\n");
	} finally {
		await rm(work, { recursive: true, force: true });
	}
}

/** The first-party images of this checkout, built and pushed to the local registry, by digest. */
async function buildLocal(registry: string): Promise<ChartImages> {
	const push = async (image: string, tag: string) => {
		const repository = `${registry}/hephaestus-build/${image}`;
		await run("docker", ["tag", `hephaestus-local/${image}:${tag}`, `${repository}:${tag}`]);
		await run("docker", ["push", "--quiet", `${repository}:${tag}`], { stdout: "ignore" });
		// The registry's digest, which `docker push` records on the local image.
		const digests = await output("docker", [
			"inspect",
			"--format",
			"{{json .RepoDigests}}",
			`${repository}:${tag}`,
		]);
		const pushed = parseJson(digests.trim());
		const digest = Array.isArray(pushed)
			? pushed
					.map(String)
					.find((entry) => entry.startsWith(`${repository}@`))
					?.split("@")[1]
			: undefined;
		if (digest === undefined) {
			throw new Error(`the registry reported no digest for ${repository}:${tag}`);
		}
		return { image, repository, indexDigest: digest };
	};
	const tag = "dev";
	const build = async (image: string, context: string, dockerfile?: string) => {
		await run(
			"docker",
			[
				"build",
				"--quiet",
				"-t",
				`hephaestus-local/${image}:${tag}`,
				...(dockerfile === undefined ? [] : ["-f", dockerfile]),
				context,
			],
			{
				cwd: ROOT,
				stdout: "ignore",
			},
		);
	};
	await build("webapp", ".", "webapp/Dockerfile");
	await build("agent-pi", "docker/agents", "docker/agents/pi/Dockerfile");
	await build("postgres", "docker/postgres");
	await run(
		path.join(ROOT, "server", process.platform === "win32" ? "gradlew.bat" : "gradlew"),
		[":application:bootJar", "-q"],
		{
			cwd: path.join(ROOT, "server"),
		},
	);
	const libs = path.join(ROOT, "server", "application", "build", "libs");
	const built = await readdir(libs);
	const jarName = built.find(
		(name) =>
			name.startsWith("hephaestus-application-") &&
			name.endsWith(".jar") &&
			!name.endsWith("-plain.jar"),
	);
	if (jarName === undefined) {
		throw new Error(`bootJar wrote no application JAR to ${libs}`);
	}
	const jar = path.join(libs, jarName);
	const workflow = await readFile(path.join(ROOT, ".github", "workflows", "cicd.yml"), "utf8");
	const runImage = /paketobuildpacks\/ubuntu-noble-run-tiny@sha256:[a-f0-9]{64}/u.exec(
		workflow,
	)?.[0];
	if (runImage === undefined) {
		throw new Error("cicd.yml pins no run image");
	}
	await run(
		"pack",
		[
			"build",
			`hephaestus-local/application-server:${tag}`,
			"--path",
			jar,
			"--descriptor",
			path.join(ROOT, "server", "application", "project.toml"),
			"--run-image",
			runImage,
			"--trust-builder",
			"--platform",
			`linux/${process.arch === "arm64" ? "arm64" : "amd64"}`,
		],
		{ stdout: "ignore" },
	);
	const inventory = await readInventory(path.join(ROOT, "security", "release-images.json"));
	const resolved = [];
	for (const image of ["webapp", "agent-pi", "postgres", "application-server"]) {
		resolved.push(await push(image, tag));
	}
	return chartImagesFrom(resolved, inventory.upstream);
}

/** Whether k3d has a cluster or a registry of the name. */
async function k3dHas(kind: "cluster" | "registry", name: string): Promise<boolean> {
	const listed = parseJson(await output("k3d", [kind, "list", "-o", "json"]));
	return Array.isArray(listed) && listed.some((entry) => asRecord(entry, kind).name === name);
}

async function dev(work: string): Promise<void> {
	const cluster = "hephaestus";
	const registry = "k3d-hephaestus-registry.localhost";
	const registryPort = "5111";
	await mkdir(work, { recursive: true, mode: 0o700 });
	if (!(await k3dHas("registry", registry))) {
		await run("k3d", [
			"registry",
			"create",
			"hephaestus-registry.localhost",
			"--port",
			registryPort,
		]);
	}
	if (!(await k3dHas("cluster", cluster))) {
		// The chart takes only ghcr.io and docker.io references, as a release lock has them. The nodes
		// pull ghcr.io from the local registry, which serves this checkout's builds by digest.
		const mirrors = path.join(work, "registries.yaml");
		await writeFile(
			mirrors,
			`mirrors:\n  ghcr.io:\n    endpoint:\n      - http://${registry}:5000\n`,
		);
		await run("k3d", [
			"cluster",
			"create",
			cluster,
			"--agents",
			"1",
			"--registry-use",
			`${registry}:5000`,
			"--registry-config",
			mirrors,
			"--k3s-arg",
			"--kubelet-arg=pod-max-pids=4096@all",
		]);
	}
	const images = await buildLocal(`localhost:${registryPort}`);
	const inCluster = mapImages((name) =>
		images[name].replace(`localhost:${registryPort}/`, "ghcr.io/"),
	);
	const head = await output("git", ["rev-parse", "HEAD"], { cwd: ROOT });
	await prepare({ work, images: inCluster, commit: head.trim() });
	await install(work);
	await verify();
}

if (import.meta.main) {
	const { positionals, values } = parseArgs({
		allowPositionals: true,
		options: {
			work: { type: "string", default: path.join(ROOT, ".context", "kubernetes-cluster") },
			images: { type: "string" },
			commit: { type: "string" },
			"external-postgresql-cidr": { type: "string" },
			"pod-cidr": { type: "string" },
			set: { type: "string", multiple: true, default: [] },
		},
	});
	const [command = ""] = positionals;
	const work = path.resolve(values.work);
	switch (command) {
		case "prepare": {
			if (values.images === undefined || values.commit === undefined) {
				throw new Error("prepare needs --images <resolved images file> and --commit <sha>");
			}
			const inventory = await readInventory(path.join(ROOT, "security", "release-images.json"));
			const images = chartImagesFrom(
				parseResolvedImages(await readFile(values.images, "utf8")),
				inventory.upstream,
			);
			await prepare({
				work,
				images,
				commit: values.commit,
				...(values["external-postgresql-cidr"] === undefined
					? {}
					: { externalCidr: values["external-postgresql-cidr"] }),
			});
			break;
		}
		case "install": {
			await install(
				work,
				values.set.flatMap((setting) => ["--set", setting]),
			);
			break;
		}
		case "verify": {
			await verify();
			break;
		}
		case "dev": {
			await dev(work);
			break;
		}
		case "backup-drill": {
			const cidr = values["pod-cidr"];
			if (cidr === undefined) {
				throw new Error(
					"backup-drill needs --pod-cidr <the cluster's Pod CIDR>, for the storage egress rule",
				);
			}
			await backupDrill(work, cidr);
			break;
		}
		case "render": {
			process.stdout.write(await render());
			break;
		}
		default: {
			throw new Error(
				"usage: kubernetes-cluster <prepare|install|verify|backup-drill|render|dev> [--work dir]",
			);
		}
	}
}
