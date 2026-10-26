/**
 * Installs the chart on a disposable cluster and checks that it runs: the CI cluster jobs and
 * `vp run dev:kubernetes` both use it, so that a developer runs what CI runs.
 *
 *   prepare  namespaces, Secrets, certificates and the operator's values for the images in a file;
 *            a second run keeps every value. --gateway routes the release through k3s's Traefik
 *   gateway  Traefik's Gateway provider and the Gateway `cluster`, on a k3d cluster that publishes 443
 *   install  `helm upgrade --install` with the dev profile and the prepared values, until ready
 *   verify   every workload ready, and each worker's isolation probe passed
 *   request  one HTTPS request to the webapp and the API through that Gateway
 *   backup-drill  pgBackRest to a throwaway S3 server, then a point-in-time restore
 *   render   the manifests of those values for synthetic images, with backups on and with an external
 *            PostgreSQL, for a schema check without a cluster
 *   dev      a local k3d cluster with a registry, this checkout's images built and pushed, the three
 *            steps above, and a Gateway on https://hephaestus.localhost; a second run reuses the cluster
 *   down     stops the dev cluster and keeps its data
 *   reset    deletes the dev cluster, its data, its registry and the work directory
 *
 * The cluster is the caller's: the script creates none, except `dev`. Every generated value stays
 * in the work directory, which only the user can read; the script prints no secret value.
 */
import { createHash, randomBytes } from "node:crypto";
import { once } from "node:events";
import { access, chmod, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { connect } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { readInventory } from "./commit-image-lock.ts";
import type { ChartImage } from "./kubernetes-chart.ts";
import { asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { output, run, succeeds } from "./lib/process.ts";
import { parseResolvedImages } from "./resolve-release-images.ts";

const ROOT = path.join(import.meta.dirname, "..");
const CHART = path.join(ROOT, "deploy", "chart", "hephaestus");

export const RELEASE = "heph";
export const NAMESPACE = "hephaestus";
export const SANDBOXES = "hephaestus-sandboxes";
const GATEWAY_NAMESPACE = "gateway";
const EXTERNAL_POSTGRESQL = "external-postgresql";
const HOST = "hephaestus.cluster.test";
/** The cluster and the host of `dev`. Browsers resolve every `*.localhost` name to the loopback address. */
export const DEV_CLUSTER = "hephaestus";
export const DEV_HOST = "hephaestus.localhost";
const DEV_WORK = path.join(ROOT, ".context", "kubernetes-cluster");

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
	/** The Gateway that serves the route; a placeholder that no request reaches when absent. */
	gateway?: Gateway;
	/** A GitHub OAuth app's client ID and the first admins, for a cluster that someone signs in to. */
	signIn?: { clientId: string; bootstrapAdmins: string };
}

/** The host that a Gateway serves, and the namespace and labels of its data plane's Pods. */
export interface Gateway {
	host: string;
	namespace: string;
	podLabels: Record<string, string>;
}

/** The operator's values for a test install: every required value, and the worker enabled. */
export function operatorValues(options: ValuesOptions): Record<string, unknown> {
	const cidr = options.api.address.includes(":")
		? `${options.api.address}/128`
		: `${options.api.address}/32`;
	return {
		release: { version: "v0.0.0-cluster", commit: options.commit },
		images: options.images,
		host: options.gateway?.host ?? HOST,
		trustedProxies: String.raw`10\.\d{1,3}\.\d{1,3}\.\d{1,3}`,
		secret: { name: `${RELEASE}-app` },
		route: { parentRefs: [{ name: "cluster", namespace: GATEWAY_NAMESPACE }] },
		networkPolicy: {
			gateway: {
				namespace: options.gateway?.namespace ?? GATEWAY_NAMESPACE,
				podLabels: options.gateway?.podLabels ?? { app: "gateway" },
			},
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
		...(options.signIn === undefined
			? {}
			: {
					config: {
						github: { oauthClientId: options.signIn.clientId },
						auth: { bootstrapAdmins: options.signIn.bootstrapAdmins },
					},
				}),
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
	type = "Opaque",
): Promise<void> {
	await apply({
		apiVersion: "v1",
		kind: "Secret",
		metadata: { name, namespace: namespaceName },
		type,
		stringData: data,
	});
}

/** A Secret's values, decoded; empty when it does not exist. A failed kubectl call throws. */
async function secretData(name: string, namespaceName: string): Promise<Record<string, string>> {
	const found = await kubectl([
		"-n",
		namespaceName,
		"get",
		"secret",
		name,
		"--ignore-not-found",
		"-o",
		"json",
	]);
	if (found.trim() === "") {
		return {};
	}
	const { data } = asRecord(parseJson(found), "Secret");
	return Object.fromEntries(
		Object.entries(data === undefined ? {} : asRecord(data, "Secret data")).map(([key, value]) => [
			key,
			Buffer.from(asString(value, key), "base64").toString("utf8"),
		]),
	);
}

/**
 * The existing values, and a generated value for each missing key: a run never rotates a value,
 * because the database and the sessions keep the first ones. Only `reset` starts over.
 */
export function mergeSecret(
	existing: Readonly<Record<string, string>>,
	generated: Readonly<Record<string, string>>,
): { data: Record<string, string>; added: string[] } {
	return {
		data: { ...generated, ...existing },
		added: Object.keys(generated).filter((key) => !(key in existing)),
	};
}

async function openssl(work: string, args: string[]): Promise<void> {
	await run("openssl", args, { cwd: work, stdout: "ignore", stderr: "ignore" });
}

/** Below this many days of validity left, a run issues a certificate again. */
const RENEW_DAYS = 30;

/** Whether the work directory holds the certificate, valid for more than `RENEW_DAYS`. */
async function fresh(work: string, name: string): Promise<boolean> {
	return succeeds(
		"openssl",
		["x509", "-checkend", String(RENEW_DAYS * 86_400), "-noout", "-in", `${name}.crt`],
		{ cwd: work },
	);
}

/** The work directory's CA, made once: every certificate of the cluster and the browser trust it. */
async function certificateAuthority(work: string): Promise<void> {
	if (await fresh(work, "ca")) {
		return;
	}
	// A new CA signs every certificate again.
	for (const name of ["tls", "backup", "gateway"]) {
		await rm(path.join(work, `${name}.crt`), { force: true });
	}
	await openssl(work, [
		"req",
		"-x509",
		"-newkey",
		"rsa:3072",
		"-nodes",
		"-days",
		"3650",
		"-subj",
		"/CN=Hephaestus cluster test CA",
		"-keyout",
		"ca.key",
		"-out",
		"ca.crt",
	]);
}

/**
 * A certificate and key that the work directory's CA signs, issued again when it is missing or near
 * its end. 397 days is the longest validity that browsers accept.
 */
async function certificate(
	work: string,
	name: string,
	subject: string,
	extension: string,
): Promise<void> {
	if (await fresh(work, name)) {
		return;
	}
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
		"397",
		"-extfile",
		`${name}.ext`,
		"-out",
		`${name}.crt`,
	]);
}

/** The CA, a server certificate for each of the hosts, and the backup client certificate. */
async function certificates(
	work: string,
	hosts: readonly string[],
): Promise<Record<string, string>> {
	await certificateAuthority(work);
	const [primary] = hosts;
	if (primary === undefined) {
		throw new Error("the PostgreSQL certificate needs a host name");
	}
	await certificate(
		work,
		"tls",
		`/CN=${primary}`,
		`subjectAltName=${hosts.map((host) => `DNS:${host}`).join(",")}`,
	);
	await certificate(work, "backup", "/CN=hephaestus-backup", "extendedKeyUsage=clientAuth");
	const read = async (file: string) => readFile(path.join(work, file), "utf8");
	return {
		"ca.crt": await read("ca.crt"),
		"tls.crt": await read("tls.crt"),
		"tls.key": await read("tls.key"),
		"backup.crt": await read("backup.crt"),
		"backup.key": await read("backup.key"),
	};
}

/** Whether the cluster holds certificates of a CA that the work directory no longer has. */
export async function lostCa(work: string): Promise<boolean> {
	const hasCa = await access(path.join(work, "ca.key")).then(
		() => true,
		() => false,
	);
	return !hasCa && Object.keys(await secretData(`${RELEASE}-postgresql-tls`, NAMESPACE)).length > 0;
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
	gateway?: Gateway;
	signIn?: ValuesOptions["signIn"];
	/** The GitHub OAuth app's client secret; the release Secret keeps an earlier one when absent. */
	clientSecret?: string;
}

/** The Secret of the Gateway's certificate, in the Gateway's namespace. */
const GATEWAY_TLS = "gateway-tls";

/**
 * The namespaces, the Secrets and the values of a release. A second run on the same cluster keeps
 * every value and renews only a certificate near its end. It returns whether the client secret
 * changed, which the server reads only at start.
 */
export async function prepare(options: PrepareOptions): Promise<{ rotated: boolean }> {
	await mkdir(options.work, { recursive: true, mode: 0o700 });
	await chmod(options.work, 0o700);
	// k3s installs the Gateway API definitions with Traefik, through a Helm chart that cannot adopt
	// definitions that someone else installed first. `gateway` waits for them.
	if (options.gateway === undefined) {
		await gatewayApi(options.work);
	}
	await namespace(NAMESPACE);
	await namespace(SANDBOXES);
	await namespace(GATEWAY_NAMESPACE, {});
	if (await lostCa(options.work)) {
		throw new Error(
			`the cluster keeps its certificates, but ${options.work} lost the CA. Run vp run dev:kubernetes:reset`,
		);
	}
	const externalHost = `postgresql.${EXTERNAL_POSTGRESQL}.svc`;
	const hosts =
		options.externalCidr === undefined
			? [`${RELEASE}-postgresql.${NAMESPACE}.svc`]
			: [externalHost];
	const tls = await certificates(options.work, hosts);
	const tlsName = `${RELEASE}-postgresql-tls`;
	const currentTls = await secretData(tlsName, NAMESPACE);
	const renewed =
		Object.keys(currentTls).length > 0 &&
		Object.entries(tls).some(([key, value]) => currentTls[key] !== value);
	await secret(tlsName, NAMESPACE, tls);
	const statefulSet = `statefulset/${RELEASE}-postgresql`;
	if (renewed && (await succeeds("kubectl", ["-n", NAMESPACE, "get", statefulSet]))) {
		// PostgreSQL reads its certificate at start.
		await kubectl(["-n", NAMESPACE, "rollout", "restart", statefulSet]);
	}
	const current = await secretData(`${RELEASE}-app`, NAMESPACE);
	const { data } = mergeSecret(current, applicationSecret());
	const previous = current.GITHUB_OAUTH_CLIENT_SECRET ?? "";
	if (options.clientSecret !== undefined) {
		data.GITHUB_OAUTH_CLIENT_SECRET = options.clientSecret;
	} else if (options.signIn !== undefined && previous === "") {
		throw new Error(
			"the cluster has no GitHub client secret: set GITHUB_OAUTH_CLIENT_ID and GITHUB_OAUTH_CLIENT_SECRET",
		);
	}
	await secret(`${RELEASE}-app`, NAMESPACE, data);
	if (options.externalCidr !== undefined) {
		await externalPostgresql(options.images.postgres, tls, data.DATABASE_PASSWORD ?? "");
	}
	if (options.gateway !== undefined) {
		const { host } = options.gateway;
		await certificate(
			options.work,
			"gateway",
			`/CN=${host}`,
			`subjectAltName=DNS:${host}\nextendedKeyUsage=serverAuth`,
		);
		const read = async (file: string) => readFile(path.join(options.work, file), "utf8");
		// Traefik reads a changed certificate without a restart.
		await secret(
			GATEWAY_TLS,
			GATEWAY_NAMESPACE,
			{ "tls.crt": await read("gateway.crt"), "tls.key": await read("gateway.key") },
			"kubernetes.io/tls",
		);
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
		...(options.gateway === undefined ? {} : { gateway: options.gateway }),
		...(options.signIn === undefined ? {} : { signIn: options.signIn }),
		...(options.externalCidr === undefined
			? {}
			: { externalPostgresql: { host: externalHost, cidr: options.externalCidr } }),
	});
	await writeFile(path.join(options.work, "values.yaml"), `${JSON.stringify(values, null, 2)}\n`, {
		mode: 0o600,
	});
	return {
		rotated:
			options.clientSecret !== undefined && previous !== "" && previous !== options.clientSecret,
	};
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

/** Each tool that `dev` runs, the arguments that make it print its version, and where to get it. */
const DEV_TOOLS: readonly (readonly [string, readonly string[], string])[] = [
	["docker", ["--version"], "https://docs.docker.com/get-started/get-docker/"],
	["k3d", ["version"], "https://k3d.io/stable/#installation"],
	["kubectl", ["version", "--client"], "https://kubernetes.io/docs/tasks/tools/"],
	["helm", ["version"], "https://helm.sh/docs/intro/install/"],
	["pack", ["--version"], "https://github.com/buildpacks/pack/releases"],
	["openssl", ["version"], "the system's package manager"],
	["curl", ["--version"], "the system's package manager"],
];

/**
 * The Docker memory that `dev` needs: the roles and two workers peak at about 6 GiB, a review
 * sandbox adds 3. Docker Desktop reports about 97 % of its memory setting, so a setting of 10 GiB
 * passes.
 */
export const DEV_MEMORY = 9.5 * 1024 ** 3;

export interface DevCheck {
	missing: readonly string[];
	/** Docker's memory in bytes; undefined when the daemon does not answer. */
	dockerMemory: number | undefined;
	helmVersion: string | undefined;
	/** The networks that no container uses, when Docker has no address pool left for a new one. */
	unusedNetworks?: number;
	/** Whether another program holds 127.0.0.1:443, which a new cluster publishes. */
	portBusy?: boolean;
}

export type DevProblemKind = "tool" | "docker" | "memory" | "network" | "port" | "helm";

export interface DevProblem {
	kind: DevProblemKind;
	message: string;
}

/** Each reason why `dev` cannot start, in words that say what to do. */
export function devProblems(check: DevCheck): DevProblem[] {
	const problems: DevProblem[] = DEV_TOOLS.filter(([tool]) => check.missing.includes(tool)).map(
		([tool, , source]) => ({
			kind: "tool",
			message: `${tool} is not on PATH. Install it from ${source}.`,
		}),
	);
	if (!check.missing.includes("docker")) {
		if (check.dockerMemory === undefined || !Number.isFinite(check.dockerMemory)) {
			problems.push({
				kind: "docker",
				message: "Docker does not answer. Start Docker Desktop or the Docker daemon.",
			});
		} else if (check.dockerMemory < DEV_MEMORY) {
			const gib = (check.dockerMemory / 1024 ** 3).toFixed(1);
			problems.push({
				kind: "memory",
				message:
					`Docker has ${gib} GiB of memory; the cluster and a practice review need 10 GiB. ` +
					"In Docker Desktop, set Settings > Resources > Memory to 10 GiB or more.",
			});
		}
	}
	if (check.unusedNetworks !== undefined) {
		problems.push({
			kind: "network",
			message:
				`Docker has no address pool left for the cluster network. ${check.unusedNetworks} networks ` +
				"have no container, often from stopped Compose stacks. Run docker network prune to remove them.",
		});
	}
	if (check.portBusy === true) {
		problems.push({
			kind: "port",
			message:
				"Another program listens on 127.0.0.1:443, where the cluster serves. Stop it. On macOS, " +
				"sudo lsof -iTCP:443 -sTCP:LISTEN names it.",
		});
	}
	if (check.helmVersion !== undefined && !check.helmVersion.startsWith("v4.")) {
		problems.push({
			kind: "helm",
			message: `helm is ${check.helmVersion}; the chart needs Helm 4.`,
		});
	}
	return problems;
}

async function preflight(): Promise<void> {
	const missing = [];
	for (const [tool, args] of DEV_TOOLS) {
		if (!(await succeeds(tool, [...args]))) {
			missing.push(tool);
		}
	}
	const dockerMemory = missing.includes("docker")
		? undefined
		: await output("docker", ["info", "--format", "{{.MemTotal}}"]).then(Number, () => undefined);
	const helmVersion = missing.includes("helm")
		? undefined
		: await output("helm", ["version", "--template", "{{.Version}}"]).then((version) =>
				version.trim(),
			);
	const newCluster =
		dockerMemory !== undefined &&
		!(await succeeds("docker", ["network", "inspect", `k3d-${DEV_CLUSTER}`]));
	const problems = devProblems({
		missing,
		dockerMemory,
		helmVersion,
		...(newCluster ? { ...(await networkPools()), portBusy: await portBusy(443) } : {}),
	});
	if (problems.length > 0) {
		process.stderr.write(
			`vp run dev:kubernetes cannot start:\n${problems.map((problem) => `  - ${problem.message}\n`).join("")}`,
		);
		process.exit(1);
	}
}

/**
 * The unused networks, when Docker cannot create the cluster network: each Compose project keeps a
 * network until someone removes it, and the default address pools hold about 30.
 */
async function networkPools(): Promise<{ unusedNetworks?: number }> {
	const probe = "hephaestus-preflight";
	try {
		const failure = await output("docker", ["network", "create", probe]).then(() => "", String);
		if (!failure.includes("address pools")) {
			return {};
		}
	} finally {
		await succeeds("docker", ["network", "rm", probe]);
	}
	const unused = await output("docker", ["network", "ls", "--filter", "dangling=true", "--quiet"]);
	return { unusedNetworks: unused.split("\n").filter(Boolean).length };
}

/**
 * Whether a program accepts connections on the loopback port. A connection tells for any user; a
 * trial bind does not, because macOS and Linux refuse a non-root bind to a port below 1024.
 */
async function portBusy(port: number): Promise<boolean> {
	const socket = connect({ host: "127.0.0.1", port });
	try {
		await once(socket, "connect");
		return true;
	} catch {
		return false;
	} finally {
		socket.destroy();
	}
}

export type ClusterState = "absent" | "stopped" | "without-port" | "running";

/** The state of the `dev` cluster in `k3d cluster list -o json`. */
export function clusterState(listed: unknown): ClusterState {
	const entry = (Array.isArray(listed) ? listed : [])
		.map((item) => asRecord(item, "cluster"))
		.find((item) => item.name === DEV_CLUSTER);
	if (entry === undefined) {
		return "absent";
	}
	const nodes = Array.isArray(entry.nodes) ? entry.nodes : [];
	const published = nodes
		.map((node) => asRecord(node, "node"))
		.some(
			(node) =>
				node.role === "loadbalancer" &&
				isRecord(node.portMappings) &&
				"443/tcp" in node.portMappings,
		);
	if (!published) {
		return "without-port";
	}
	return entry.serversRunning === 0 ? "stopped" : "running";
}

/** Whether k3d has a registry of the name. */
async function k3dHasRegistry(name: string): Promise<boolean> {
	const listed = parseJson(await output("k3d", ["registry", "list", "-o", "json"]));
	return Array.isArray(listed) && listed.some((entry) => asRecord(entry, "registry").name === name);
}

/** k3s ships Traefik and the Gateway API definitions, with Traefik's Gateway provider off. */
const TRAEFIK = { namespace: "kube-system", podLabels: { "app.kubernetes.io/name": "traefik" } };

/** The Gateway that `dev` and the k3d CI job serve the release through. */
export const TRAEFIK_GATEWAY: Gateway = { host: DEV_HOST, ...TRAEFIK };

/** Traefik's Gateway provider, and the Gateway `cluster` on Traefik's HTTPS entry point. */
export async function gateway(host: string): Promise<void> {
	await apply({
		apiVersion: "helm.cattle.io/v1",
		kind: "HelmChartConfig",
		metadata: { name: "traefik", namespace: TRAEFIK.namespace },
		spec: {
			valuesContent:
				"providers:\n  kubernetesGateway:\n    enabled: true\ngateway:\n  enabled: false\n",
		},
	});
	// `kubectl wait` fails at once while the GatewayClass does not exist yet, so a loop waits for it.
	for (let attempt = 1; ; attempt += 1) {
		if (
			await succeeds("kubectl", [
				"wait",
				"--for=condition=Accepted",
				"gatewayclass/traefik",
				"--timeout=1s",
			])
		) {
			break;
		}
		if (attempt === 30) {
			throw new Error(
				"Traefik accepted no GatewayClass in 5 minutes. See kubectl -n kube-system logs deployment/traefik",
			);
		}
		await sleep(10_000);
	}
	await apply({
		apiVersion: "gateway.networking.k8s.io/v1",
		kind: "Gateway",
		metadata: { name: "cluster", namespace: GATEWAY_NAMESPACE },
		spec: {
			gatewayClassName: "traefik",
			listeners: [
				{
					name: "https",
					protocol: "HTTPS",
					// Traefik's HTTPS entry point; its Service publishes it on 443.
					port: 8443,
					hostname: host,
					tls: { certificateRefs: [{ name: GATEWAY_TLS }] },
					allowedRoutes: { namespaces: { from: "All" } },
				},
			],
		},
	});
}

/** One HTTPS request to the webapp and one to the API, through the Gateway on 127.0.0.1:443. */
export async function request(work: string, host: string): Promise<void> {
	for (const route of ["/", "/api/identity-providers"]) {
		await run("curl", [
			"--fail",
			"--silent",
			"--show-error",
			"--output",
			"/dev/null",
			"--retry",
			"10",
			"--retry-all-errors",
			"--cacert",
			path.join(work, "ca.crt"),
			"--resolve",
			`${host}:443:127.0.0.1`,
			`https://${host}${route}`,
		]);
	}
	process.stdout.write(`https://${host} answered through the Gateway\n`);
}

/** A GitHub OAuth app and the first admins. A run stores them without the secret, which the release Secret holds. */
export interface DevSignIn {
	clientId: string;
	bootstrapAdmins: string;
	clientSecret?: string;
}

/**
 * The GitHub OAuth app from the shell, or else the app of an earlier run, so that a run without the
 * variables keeps the sign-in.
 */
export function devSignIn(
	environment: NodeJS.ProcessEnv,
	saved: DevSignIn | undefined,
): DevSignIn | undefined {
	const clientId = environment.GITHUB_OAUTH_CLIENT_ID ?? "";
	const clientSecret = environment.GITHUB_OAUTH_CLIENT_SECRET ?? "";
	if (clientId === "" && clientSecret === "") {
		return saved === undefined
			? undefined
			: {
					clientId: saved.clientId,
					bootstrapAdmins: environment.HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS ?? saved.bootstrapAdmins,
				};
	}
	if (clientId === "" || clientSecret === "") {
		throw new Error("set both GITHUB_OAUTH_CLIENT_ID and GITHUB_OAUTH_CLIENT_SECRET, or neither");
	}
	return {
		clientId,
		clientSecret,
		bootstrapAdmins: environment.HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS ?? saved?.bootstrapAdmins ?? "",
	};
}

/** The OAuth app of an earlier run, from the work directory. */
async function savedSignIn(file: string): Promise<DevSignIn | undefined> {
	const saved = await readFile(file, "utf8").then(parseJson, () => undefined);
	if (saved === undefined) {
		return undefined;
	}
	const record = asRecord(saved, "saved sign-in");
	return {
		clientId: asString(record.clientId, "client ID"),
		bootstrapAdmins: asString(record.bootstrapAdmins, "bootstrap admins"),
	};
}

/** The commands that make a system trust the work directory's CA. */
export function trustSteps(platform: NodeJS.Platform, ca: string): string[] {
	const steps: string[] = [];
	if (platform === "darwin") {
		steps.push(
			`security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db "${ca}"`,
		);
	}
	if (platform === "linux") {
		steps.push(
			`sudo cp "${ca}" /usr/local/share/ca-certificates/hephaestus-dev.crt`,
			"sudo update-ca-certificates",
		);
	}
	// mkcert installs a CA into the system store and into Firefox's and Chrome's own stores.
	steps.push(`Or, with mkcert: CAROOT="${path.dirname(ca)}/mkcert" mkcert -install`);
	return steps;
}

/** What `dev` prints when the release answers: where to open it, how to sign in, how to stop it. */
export function devSummary(options: {
	host: string;
	ca: string;
	signIn: boolean;
	platform: NodeJS.Platform;
}): string {
	const url = `https://${options.host}`;
	const signIn = options.signIn
		? ["Sign in with GitHub."]
		: [
				"Sign-in needs a GitHub OAuth app. Create one with the homepage",
				`${url} and the callback ${url}/api/login/oauth2/code/github, then run`,
				"  GITHUB_OAUTH_CLIENT_ID=... GITHUB_OAUTH_CLIENT_SECRET=... \\",
				"  HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS=github:@<you> vp run dev:kubernetes",
			];
	return [
		"",
		`Hephaestus runs at ${url}`,
		`The local CA ${options.ca} signed its certificate. To trust it once:`,
		...trustSteps(options.platform, options.ca).map((step) => `  ${step}`),
		...signIn,
		"",
		`Logs:        kubectl --context k3d-${DEV_CLUSTER} -n ${NAMESPACE} logs -f deployment/${RELEASE}-server`,
		"Stop:        vp run dev:kubernetes:down    (keeps the data)",
		"Start over:  vp run dev:kubernetes:reset   (deletes the cluster, its data and the CA)",
		"",
	].join("\n");
}

const DEV_REGISTRY = "k3d-hephaestus-registry.localhost";

async function dev(work: string): Promise<void> {
	await preflight();
	const savedFile = path.join(work, "sign-in.json");
	const signIn = devSignIn(process.env, await savedSignIn(savedFile));
	const registryPort = "5111";
	await mkdir(work, { recursive: true, mode: 0o700 });
	if (!(await k3dHasRegistry(DEV_REGISTRY))) {
		await run("k3d", [
			"registry",
			"create",
			"hephaestus-registry.localhost",
			"--port",
			registryPort,
		]);
	}
	const state = clusterState(parseJson(await output("k3d", ["cluster", "list", "-o", "json"])));
	if (state === "without-port") {
		throw new Error(
			`the cluster ${DEV_CLUSTER} publishes no HTTPS port: an older run created it. Run vp run dev:kubernetes:reset`,
		);
	}
	if (state === "stopped") {
		await run("k3d", ["cluster", "start", DEV_CLUSTER]);
	}
	if (state === "absent") {
		// The chart takes only ghcr.io and docker.io references, as a release lock has them. The nodes
		// pull ghcr.io from the local registry, which serves this checkout's builds by digest.
		const mirrors = path.join(work, "registries.yaml");
		await writeFile(
			mirrors,
			`mirrors:\n  ghcr.io:\n    endpoint:\n      - http://${DEV_REGISTRY}:5000\n`,
		);
		try {
			await run("k3d", [
				"cluster",
				"create",
				DEV_CLUSTER,
				"--agents",
				"1",
				"--registry-use",
				`${DEV_REGISTRY}:5000`,
				"--registry-config",
				mirrors,
				"--port",
				"127.0.0.1:443:443@loadbalancer",
				"--k3s-arg",
				"--kubelet-arg=pod-max-pids=4096@all",
			]);
		} catch (error) {
			// A failed create can leave a half-made cluster that blocks the next one.
			await succeeds("k3d", ["cluster", "delete", DEV_CLUSTER]);
			throw new Error("k3d could not create the cluster", { cause: error });
		}
	}
	// Every kubectl and helm call below talks to this cluster, whatever the current context is.
	const kubeconfig = path.join(work, "kubeconfig");
	await run("k3d", ["kubeconfig", "write", DEV_CLUSTER, "--output", kubeconfig], {
		stdout: "ignore",
	});
	process.env.KUBECONFIG = kubeconfig;
	// Before the builds, which take minutes.
	if (await lostCa(work)) {
		throw new Error(
			`the cluster keeps its certificates, but ${work} lost the CA. Run vp run dev:kubernetes:reset`,
		);
	}
	const images = await buildLocal(`localhost:${registryPort}`);
	const inCluster = mapImages((name) =>
		images[name].replace(`localhost:${registryPort}/`, "ghcr.io/"),
	);
	const head = await output("git", ["rev-parse", "HEAD"], { cwd: ROOT });
	const { rotated } = await prepare({
		work,
		images: inCluster,
		commit: head.trim(),
		gateway: TRAEFIK_GATEWAY,
		...(signIn === undefined ? {} : { signIn }),
		...(signIn?.clientSecret === undefined ? {} : { clientSecret: signIn.clientSecret }),
	});
	if (signIn !== undefined) {
		const { clientSecret: _secret, ...stored } = signIn;
		await writeFile(savedFile, `${JSON.stringify(stored)}\n`, { mode: 0o600 });
	}
	const ca = path.join(work, "ca.crt");
	await mkdir(path.join(work, "mkcert"), { recursive: true });
	await writeFile(path.join(work, "mkcert", "rootCA.pem"), await readFile(ca));
	await gateway(DEV_HOST);
	await install(work);
	if (rotated) {
		// The server reads the secret at start, and a changed Secret alone rolls no Pod.
		await kubectl(["-n", NAMESPACE, "rollout", "restart", `deployment/${RELEASE}-server`]);
	}
	await verify();
	await request(work, DEV_HOST);
	process.stdout.write(
		devSummary({ host: DEV_HOST, ca, signIn: signIn !== undefined, platform: process.platform }),
	);
}

/** Deletes the dev cluster, its registry and the work directory, and says what it removed. */
async function reset(work: string): Promise<void> {
	await run("k3d", ["cluster", "delete", DEV_CLUSTER]);
	await run("k3d", ["registry", "delete", DEV_REGISTRY]).catch(() => undefined);
	await rm(work, { recursive: true, force: true });
	process.stdout.write(
		[
			`Removed the cluster ${DEV_CLUSTER} with its data, the registry ${DEV_REGISTRY} with its images,`,
			`and ${work} with the CA, the Secrets' copies and the saved OAuth app.`,
			"A system that trusts the old CA keeps it; the next run makes a new one.",
			"",
		].join("\n"),
	);
}

if (import.meta.main) {
	const { positionals, values } = parseArgs({
		allowPositionals: true,
		options: {
			work: { type: "string", default: DEV_WORK },
			images: { type: "string" },
			commit: { type: "string" },
			"external-postgresql-cidr": { type: "string" },
			"pod-cidr": { type: "string" },
			gateway: { type: "boolean", default: false },
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
				...(values.gateway ? { gateway: TRAEFIK_GATEWAY } : {}),
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
		case "gateway": {
			await gateway(DEV_HOST);
			break;
		}
		case "request": {
			await request(work, DEV_HOST);
			break;
		}
		case "dev": {
			await dev(work);
			break;
		}
		case "down": {
			await run("k3d", ["cluster", "stop", DEV_CLUSTER]);
			break;
		}
		case "reset": {
			if (work !== DEV_WORK) {
				throw new Error("reset deletes only the dev work directory; it takes no --work");
			}
			await reset(work);
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
				"usage: kubernetes-cluster <prepare|gateway|install|verify|request|backup-drill|render|dev|down|reset> [--work dir]",
			);
		}
	}
}
