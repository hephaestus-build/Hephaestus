/**
 * Installs the chart on a disposable cluster and checks that it runs: the CI cluster jobs and
 * `vp run dev:kubernetes` both use it, so that a developer runs what CI runs.
 *
 *   prepare  the agent-sandbox controller of the platform manifest, namespaces, Secrets, certificates
 *            and the operator's values for the images in a file; a second run keeps every value.
 *            --gateway routes the release through k3s's Traefik
 *   gateway  Traefik's Gateway provider and the Gateway `cluster`, on a k3d cluster that publishes 443
 *   install  `helm upgrade --install` with the dev profile, or each --profile in order, and the
 *            prepared values, until ready
 *   upgrade  install, and check that PostgreSQL and NATS kept their Pods
 *   verify   every workload ready, each server's isolation probe passed, and a sandbox of each
 *            SandboxTemplate passed the sandbox isolation gate (scripts/sandbox-isolation.ts)
 *   request  one HTTPS request to the webapp and the API through that Gateway
 *   backup-drill  pgBackRest to a throwaway S3 server, then a point-in-time restore on a fresh node
 *   turn-roll  a conversation turn that runs `bash` in its tools Pod through a rollout restart of
 *            the server and then of the conversation loop, with the capacity bench's fake model
 *            provider. --pod-cidr opens the provider to the release
 *   gvisor   the pinned gVisor of k3s-containerd.toml.tmpl, unpacked into --work, for k3s nodes to mount
 *   render   the manifests of those values for synthetic images, with backups on and with an external
 *            PostgreSQL, for a schema check without a cluster
 *   release-smoke  the one-host install of docs/admin/k3s.md on
 *            this machine: the release's k3s-install.sh, then every role ready, reviews on, the
 *            shutdown budget, requests through the Gateway, a second run and an upgrade
 *   dev      a local k3d cluster with a registry, this checkout's images built and pushed, the three
 *            steps above, and a Gateway on https://hephaestus.localhost; a second run reuses the cluster
 *   live     runs the credential-free sandbox suite in a Pod from the chart server template
 *   down     removes the qualification release and keeps its data and the shared cluster
 *   reset    deletes the dev cluster, its data, its registry and the work directory
 *
 * The cluster is the caller's: the script creates none, except `dev` and `release-smoke`. Every
 * generated value stays in the work directory, which only the user can read; the script prints no
 * secret value.
 */
import { spawn } from "node:child_process";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { once } from "node:events";
import {
	access,
	chmod,
	mkdir,
	mkdtemp,
	open,
	readdir,
	readFile,
	rename,
	rm,
	writeFile,
} from "node:fs/promises";
import { BlockList, connect, isIP } from "node:net";
import { homedir, tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { isDeepStrictEqual, parseArgs, parseEnv } from "node:util";

import { readInventory } from "./commit-image-lock.ts";
import type { ChartImage } from "./kubernetes-chart.ts";
import { submitTurn } from "./lib/capacity-chat.ts";
import { rollOutput } from "./lib/capacity-replay.ts";
import { bindReplayModel, syntheticApi, syntheticSession } from "./lib/capacity-seed.ts";
import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { resolveCiTool } from "./lib/ci-tool-resolver.ts";
import { claimDevNamespace, DEV_OWNER, ownsDevNamespace } from "./lib/dev-namespace.ts";
import { gvisorPin } from "./lib/gvisor-pin.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { exitStatus, output, run, succeeds } from "./lib/process.ts";
import { parseResolvedImages } from "./resolve-release-images.ts";
import { isolationGate, type Target } from "./sandbox-isolation.ts";

const ROOT = path.join(import.meta.dirname, "..");
const CHART = path.join(ROOT, "deploy", "chart", "hephaestus");
const AGENT_SANDBOX = path.join(ROOT, "deploy", "platform", "k3s-agent-sandbox.yaml");
const CONTAINERD_TEMPLATE = path.join(ROOT, "deploy", "platform", "k3s-containerd.toml.tmpl");
const RUNTIME_CLASS = path.join(ROOT, "deploy", "platform", "k3s-runtimeclass.yaml");

export const RELEASE = "heph";
export const NAMESPACE = "hephaestus";
export const SANDBOXES = "hephaestus-sandboxes";
/** The kinds of the chart's SandboxTemplates (templates/sandbox-templates.yaml). */
const SANDBOX_KINDS = ["review", "conversation"] as const;
/** The port of the application API on the server's Pods. */
const APPLICATION_PORT = 8080;
const GATEWAY_NAMESPACE = "gateway";
const EXTERNAL_POSTGRESQL = "external-postgresql";
const HOST = "hephaestus.cluster.test";
/** The cluster and the host of `dev`. Browsers resolve every `*.localhost` name to the loopback address. */
export const DEV_CLUSTER = "hephaestus";
export const DEV_HOST = "hephaestus.localhost";
const DEV_WORK = path.join(homedir(), ".hephaestus", "kubernetes-cluster");

/** What `verify` reads in a server's log once its probe passed (KubernetesIsolationProbe). */
export const PROBE_PASSED = "The sandbox isolation probe passed";
const PROBE_FAILED = "The cluster does not hold the sandbox boundary";
const PROBE_WAIT_MS = 10 * 60_000;

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
		alertmanager: reference("alertmanager"),
		"application-server": reference("application-server"),
		nats: reference("nats"),
		postgres: reference("postgres"),
		"postgres-exporter": reference("postgres-exporter"),
		"victoria-metrics": reference("victoria-metrics"),
		vmalert: reference("vmalert"),
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

/** The operator's values for a test install: every required value, and the sandboxes enabled. */
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
		sandbox: { enabled: true, namespace: SANDBOXES },
		...(options.signIn === undefined
			? {}
			: {
					config: {
						github: { oauthClientId: options.signIn.clientId },
						auth: { bootstrapAdmins: options.signIn.bootstrapAdmins.split(",").filter(Boolean) },
					},
				}),
	};
}

const secretValue = (bytes: number) => randomBytes(bytes).toString("hex");

/** The release Secret: every required key, generated, and nothing that reaches a provider. */
export function applicationSecret(): Record<string, string> {
	const key = secretValue(16);
	return {
		DATABASE_ADMIN_USERNAME: "postgres",
		DATABASE_ADMIN_PASSWORD: secretValue(16),
		DATABASE_OWNER_PASSWORD: secretValue(16),
		DATABASE_APP_PASSWORD: secretValue(16),
		NATS_USERNAME: `heph${secretValue(16)}`,
		NATS_PASSWORD: `heph${secretValue(16)}`,
		HEPHAESTUS_SECURITY_ENCRYPTION_KEY: key,
		HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY: key,
		HEPHAESTUS_AUTH_STATE_COOKIE_KEY: randomBytes(32).toString("base64"),
		HEPHAESTUS_CONVERSATION_LOOP_SECRET: randomBytes(32).toString("hex"),
		WEBHOOK_SECRET: secretValue(32),
		WEBHOOK_ROUTING_SECRET: secretValue(32),
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
	const { bytes: body } = await resolveCiTool(await ciToolPin("gateway-api"));
	const file = path.join(work, "gateway-api.yaml");
	await writeFile(file, body);
	await kubectl(["apply", "--server-side", "-f", file]);
}

/**
 * The pinned gVisor for this machine's architecture, unpacked into `directory` as the one-host
 * installer unpacks it into /usr/local/bin: `runsc`, its containerd shim and `gvisor-bin/`. A CI
 * cluster mounts them into its k3s nodes.
 */
async function gvisor(directory: string): Promise<void> {
	const { release, sha512 } = gvisorPin(await readFile(CONTAINERD_TEMPLATE, "utf8"));
	if (process.arch !== "x64" && process.arch !== "arm64") {
		throw new Error(`gVisor has no release for ${process.arch}`);
	}
	const expected = sha512[process.arch];
	const arch = process.arch === "x64" ? "x86_64" : "aarch64";
	const response = await fetch(
		`https://storage.googleapis.com/gvisor/releases/release/${release}/${arch}/gvisor.tar.bz2`,
		{ signal: AbortSignal.timeout(300_000) },
	);
	if (!response.ok) {
		throw new Error(`the gVisor download failed: HTTP ${response.status}`);
	}
	const archive = Buffer.from(await response.arrayBuffer());
	if (createHash("sha512").update(archive).digest("hex") !== expected) {
		throw new Error("the gVisor archive does not match the pinned SHA-512");
	}
	await mkdir(directory, { recursive: true });
	const file = path.join(directory, "gvisor.tar.bz2");
	await writeFile(file, archive);
	await run("tar", ["-xjf", file, "-C", directory]);
	await rm(file);
}

/**
 * The agent-sandbox controller of the platform manifest, as the one-host installer gives k3s, and its
 * resource definitions ready. A second run applies the same file again.
 */
async function agentSandbox(): Promise<void> {
	await kubectl(["apply", "--server-side", "-f", AGENT_SANDBOX]);
	// The one-host platform's RuntimeClass. Only a cluster whose nodes have runsc can run its Pods.
	await kubectl(["apply", "--server-side", "-f", RUNTIME_CLASS]);
	const definitions = [
		"crd/sandboxes.agents.x-k8s.io",
		"crd/sandboxtemplates.extensions.agents.x-k8s.io",
		"crd/sandboxclaims.extensions.agents.x-k8s.io",
		"crd/sandboxwarmpools.extensions.agents.x-k8s.io",
	];
	// A new definition's .status.conditions is null until the API server first writes its status. A condition wait
	// fails on a null list rather than waiting, but a JSONPath index into it waits. Once the list exists, the
	// condition wait waits for Established.
	await kubectl([
		"wait",
		"--for=jsonpath={.status.conditions[0].type}",
		...definitions,
		"--timeout=2m",
	]);
	await kubectl(["wait", "--for=condition=Established", ...definitions, "--timeout=2m"]);
	await kubectl([
		"-n",
		"agent-sandbox-system",
		"rollout",
		"status",
		"deployment/agent-sandbox-controller",
		"--timeout=5m",
	]);
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

/** A file of the chart's `files/` folder. */
async function chartFile(file: string): Promise<string> {
	return readFile(path.join(CHART, "files", file), "utf8");
}

/**
 * The operator's step for an external PostgreSQL: the chart's roles, as docs/admin/external-database.md § Set up the database roles
 * runs them. The passwords travel on standard input, never in an argument list.
 */
async function externalDatabaseRoles(data: Readonly<Record<string, string>>): Promise<void> {
	const owner = data.DATABASE_OWNER_PASSWORD ?? "";
	const app = data.DATABASE_APP_PASSWORD ?? "";
	// As the operator runs it: database-roles.sh with the chart's files beside it, and the passwords in its
	// environment, so that psql sends only their verifiers.
	const directory = "/tmp/hephaestus-database";
	const lines = [`mkdir -p ${directory}`];
	for (const file of [
		"database-quiet.sql",
		"database-roles.sql",
		"scram-sha-256.pl",
		"database-roles.sh",
	]) {
		lines.push(
			`cat > ${directory}/${file} <<'HEPHAESTUS_FILE'`,
			await chartFile(file),
			"HEPHAESTUS_FILE",
		);
	}
	lines.push(
		`export DATABASE_OWNER_PASSWORD='${owner}' DATABASE_APP_PASSWORD='${app}'`,
		`sh ${directory}/database-roles.sh -U hephaestus -d hephaestus`,
		"",
	);
	const shell = lines.join("\n");
	await kubectl(
		[
			"-n",
			EXTERNAL_POSTGRESQL,
			"exec",
			"-i",
			"postgresql-0",
			"-c",
			"postgresql",
			"--",
			"sh",
			"-es",
		],
		shell,
	);
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
	await agentSandbox();
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
	await secret("hephaestus-postgresql-backup-tls", NAMESPACE, {
		"ca.crt": asString(tls["ca.crt"], "PostgreSQL CA"),
		"tls.crt": asString(tls["backup.crt"], "backup certificate"),
		"tls.key": asString(tls["backup.key"], "backup key"),
	});
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
		await externalPostgresql(options.images.postgres, tls, data.DATABASE_ADMIN_PASSWORD ?? "");
		await externalDatabaseRoles(data);
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

export async function install(
	work: string,
	extra: readonly string[] = [],
	profiles: readonly string[] = ["dev"],
): Promise<void> {
	await run("helm", [
		"upgrade",
		"--install",
		RELEASE,
		CHART,
		"--namespace",
		NAMESPACE,
		...profiles.flatMap((profile) => ["--values", path.join(CHART, "profiles", `${profile}.yaml`)]),
		"--values",
		path.join(work, "values.yaml"),
		...extra,
		// The documented upgrade (docs/admin/upgrade.mdx § Upgrade a schema-compatible release), without --rollback-on-failure:
		// a failed run keeps its objects for the failure record.
		"--wait=watcher",
		"--wait-for-jobs",
		"--timeout",
		"20m",
	]);
}

/** A one-shot server Pod: retain its authority and networking; replace only its image and restart policy. */
export function livePod(deployment: unknown, image: string, name: string): Record<string, unknown> {
	const root = asRecord(deployment, "server Deployment");
	const metadata = asRecord(root.metadata, "server metadata");
	const template = asRecord(asRecord(root.spec, "server spec").template, "server template");
	const spec = asRecord(template.spec, "server Pod spec");
	const containers = asArray(spec.containers, "server containers");
	if (containers.length !== 1) {
		throw new Error("the qualification needs the chart's single-container server template");
	}
	const container = asRecord(containers[0], "server container");
	if (
		container.command !== undefined ||
		container.args !== undefined ||
		container.imagePullPolicy === "Always"
	) {
		throw new Error(
			"the qualification needs the chart server's image entrypoint and IfNotPresent pull policy",
		);
	}
	return {
		apiVersion: "v1",
		kind: "Pod",
		metadata: {
			...asRecord(template.metadata, "server template metadata"),
			name,
			namespace: metadata.namespace,
		},
		spec: { ...spec, restartPolicy: "Never", containers: [{ ...container, image }] },
	};
}

/**
 * Run the package job's compiled main/test classpath on the installed server image and chart server authority.
 * This adapter suite has its own health listener; install/verify separately qualifies application readiness.
 */
export async function qualifySandboxes(
	packaged: boolean,
	selectedNamespace?: string,
): Promise<void> {
	const namespaceName = selectedNamespace ?? NAMESPACE;
	if (!/^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$/u.test(namespaceName)) {
		throw new Error("live qualification requires a valid namespace");
	}
	if (process.env.CI !== "true" && selectedNamespace === undefined) {
		throw new Error(
			"local live qualification requires --namespace to name the reserved shared qualification release",
		);
	}
	const selectedContext = await output("kubectl", ["config", "current-context"]);
	const context = selectedContext.trim();
	if (context !== `k3d-${DEV_CLUSTER}`) {
		throw new Error(`live qualification requires context k3d-${DEV_CLUSTER}`);
	}
	let reservation: Awaited<ReturnType<typeof open>> | undefined;
	const lock = path.join(DEV_WORK, "live.lock");
	if (process.env.CI !== "true") {
		const admin = process.env.KUBECONFIG ?? (await requireDevCluster());
		if (!(await ownsDevNamespace(admin, namespaceName, ROOT))) {
			throw new Error("Start the qualification release from this worktree before running live");
		}
		await mkdir(DEV_WORK, { recursive: true, mode: 0o700 });
		reservation = await open(lock, "wx", 0o600).catch(() => {
			throw new Error(
				`The shared live qualification is busy; check the owner before removing ${lock}`,
			);
		});
	}
	try {
		await runSandboxQualification(packaged, namespaceName);
	} finally {
		if (reservation !== undefined) {
			await reservation.close();
			await rm(lock);
		}
	}
}

async function runSandboxQualification(packaged: boolean, namespaceName: string): Promise<void> {
	const started = performance.now();
	const server = `${RELEASE}-server`;
	const deployment = asRecord(
		parseJson(await kubectl(["-n", namespaceName, "get", "deployment", server, "-o", "json"])),
		"server Deployment",
	);
	const deploymentSpec = asRecord(deployment.spec, "server spec");
	const { replicas } = deploymentSpec;
	if (typeof replicas !== "number" || !Number.isSafeInteger(replicas) || replicas < 0) {
		throw new Error("the server Deployment has no replica count");
	}
	const template = asRecord(deploymentSpec.template, "server template");
	const containers = asArray(
		asRecord(template.spec, "server Pod spec").containers,
		"server containers",
	);
	const base = asString(asRecord(containers[0], "server container").image, "server image");
	if (!/@sha256:[a-f0-9]{64}$/u.test(base)) {
		throw new Error("the qualification requires the installed server image by digest");
	}
	await run(
		path.join(ROOT, "server", process.platform === "win32" ? "gradlew.bat" : "gradlew"),
		[
			":application:kubernetesLiveBundle",
			...(packaged ? ["-PpackagedServer=true"] : []),
			"--console=plain",
		],
		{ cwd: path.join(ROOT, "server") },
	);
	const bundle = path.join(ROOT, "server", "application", "build", "kubernetes-live");
	await writeFile(
		path.join(bundle, "Dockerfile"),
		[
			`FROM ${process.env.CI === "true" ? base : base.replace("ghcr.io/", "localhost:5111/")}`,
			"COPY --chown=1000:1000 . /workspace/live/",
			"ENV HEPHAESTUS_KUBERNETES_LIVE=true",
			"ENV BPL_JAVA_NMT_ENABLED=false",
			'ENTRYPOINT ["/cnb/lifecycle/launcher"]',
			'CMD ["--", "java", "-cp", "/workspace/live/test:/workspace/live/main:/workspace/live/lib/*", "de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesLiveSuite"]',
			"",
		].join("\n"),
	);
	const suffix = randomBytes(5).toString("hex");
	const image = `hephaestus-sandbox-live:${suffix}`;
	const name = `${RELEASE}-sandbox-live-${suffix}`;
	// Validate the server template before any scale or image mutation.
	const pod = livePod(deployment, image, name);
	await run("docker", ["build", "--tag", image, bundle]);
	let scaled = false;
	let created = false;
	try {
		await run("k3d", ["image", "import", "--mode", "direct", "--cluster", DEV_CLUSTER, image]);
		await kubectl(["-n", namespaceName, "scale", `deployment/${server}`, "--replicas=0"]);
		scaled = true;
		await kubectl([
			"-n",
			namespaceName,
			"wait",
			"--for=delete",
			"pod",
			"-l",
			`app.kubernetes.io/instance=${RELEASE},app.kubernetes.io/component=server`,
			"--timeout=9m",
		]);
		await apply(pod);
		created = true;
		// logs --follow can return ContainerCreating before the first container starts.
		await kubectl([
			"-n",
			namespaceName,
			"wait",
			"--for=jsonpath={.status.phase}=Running",
			`pod/${name}`,
			"--timeout=5m",
		]);
		await run("kubectl", [
			"-n",
			namespaceName,
			"logs",
			"--follow",
			name,
			"--pod-running-timeout=5m",
			"--request-timeout=25m",
		]);
		await kubectl([
			"-n",
			namespaceName,
			"wait",
			"--for=jsonpath={.status.containerStatuses[0].state.terminated}",
			`pod/${name}`,
			"--timeout=30s",
		]);
		const ended = asRecord(
			parseJson(await kubectl(["-n", namespaceName, "get", "pod", name, "-o", "json"])),
			"qualification Pod",
		);
		const status = asRecord(ended.status, "qualification status");
		const statuses = asArray(status.containerStatuses, "qualification container statuses");
		const terminated = asRecord(
			asRecord(asRecord(statuses[0], "qualification container").state, "qualification state")
				.terminated,
			"qualification exit",
		);
		if (terminated.exitCode !== 0) {
			throw new Error(
				`Kubernetes live qualification failed with exit ${String(terminated.exitCode)}`,
			);
		}
		process.stdout.write(
			`Kubernetes live qualification passed in ${Math.round((performance.now() - started) / 1000)} seconds; kernel evidence is in the qualification log.\n`,
		);
	} finally {
		try {
			if (created) {
				await kubectl(["-n", namespaceName, "delete", "pod", name, "--wait=true", "--timeout=2m"]);
			}
		} finally {
			try {
				if (scaled) {
					await kubectl([
						"-n",
						namespaceName,
						"scale",
						`deployment/${server}`,
						`--replicas=${replicas}`,
					]);
					await kubectl([
						"-n",
						namespaceName,
						"rollout",
						"status",
						`deployment/${server}`,
						"--timeout=10m",
					]);
				}
			} finally {
				await succeeds("docker", ["image", "rm", image]);
			}
		}
	}
}

const STATEFUL = new Set(["postgresql", "nats"]);

/** The UID of each running PostgreSQL and NATS Pod, by Pod name, from `kubectl get pods -o json`. */
export function statefulPods(listed: unknown): Map<string, string> {
	const pods = asArray(asRecord(listed, "Pod list").items, "Pods")
		.map((pod) => asRecord(asRecord(pod, "Pod").metadata, "Pod metadata"))
		.filter(
			(metadata) =>
				metadata.deletionTimestamp === undefined &&
				isRecord(metadata.labels) &&
				STATEFUL.has(String(metadata.labels["app.kubernetes.io/component"])),
		);
	return new Map(
		pods.map((metadata) => [
			asString(metadata.name, "Pod name"),
			asString(metadata.uid, "Pod UID"),
		]),
	);
}

/** The Pods of `before` that `after` replaced or lost. */
export function restartedPods(
	before: ReadonlyMap<string, string>,
	after: ReadonlyMap<string, string>,
): string[] {
	return [...before].filter(([name, uid]) => after.get(name) !== uid).map(([name]) => name);
}

async function listStatefulPods(): Promise<Map<string, string>> {
	return statefulPods(parseJson(await kubectl(["-n", NAMESPACE, "get", "pods", "-o", "json"])));
}

/**
 * Runs an upgrade and fails if it restarted PostgreSQL or NATS. A Pod template that changes on every
 * release, such as a version label, would cut the database and the broker on each upgrade.
 */
export async function keepsStatefulPods(upgrade: () => Promise<void>): Promise<void> {
	const before = await listStatefulPods();
	if (before.size === 0) {
		throw new Error("no PostgreSQL or NATS Pod runs before the upgrade");
	}
	await upgrade();
	const restarted = restartedPods(before, await listStatefulPods());
	if (restarted.length > 0) {
		throw new Error(`the upgrade restarted ${restarted.join(", ")}`);
	}
	process.stdout.write(`the upgrade kept ${[...before.keys()].join(", ")}\n`);
}

/** A stopped server can leave a finished probe to the Job TTL; an active or failed probe is still a leftover. */
export function remainingProbeResources(listed: unknown): string[] {
	const resources = asArray(asRecord(listed, "Probe resources").items, "Probe resources").map(
		(entry) => {
			const resource = asRecord(entry, "Probe resource");
			const metadata = asRecord(resource.metadata, "Probe metadata");
			return {
				kind: asString(resource.kind, "Probe kind"),
				name: asString(metadata.name, "Probe name"),
				uid: metadata.uid,
				owners: metadata.ownerReferences,
				probe:
					isRecord(metadata.labels) &&
					metadata.labels["hephaestus.build/isolation-probe"] === "true",
				status: isRecord(resource.status) ? resource.status : {},
			};
		},
	);
	const completed = new Set(
		resources
			.filter(
				(resource) =>
					resource.probe &&
					resource.kind === "Job" &&
					Array.isArray(resource.status.conditions) &&
					resource.status.conditions.some(
						(condition: unknown) =>
							isRecord(condition) && condition.type === "Complete" && condition.status === "True",
					),
			)
			.map((resource) => asString(resource.uid, "Completed probe Job UID")),
	);
	return resources
		.filter((resource) => {
			if (!resource.probe) {
				return true;
			}
			if (resource.kind === "Job") {
				return !completed.has(asString(resource.uid, "Probe Job UID"));
			}
			return !(
				resource.kind === "Pod" &&
				resource.status.phase === "Succeeded" &&
				Array.isArray(resource.owners) &&
				resource.owners.some(
					(owner: unknown) =>
						isRecord(owner) &&
						owner.kind === "Job" &&
						owner.controller === true &&
						typeof owner.uid === "string" &&
						completed.has(owner.uid),
				)
			);
		})
		.map((resource) => `${resource.kind.toLowerCase()}/${resource.name}`);
}

/** Every workload ready and each server's isolation probe passed; the number of those servers. */
export async function verify(): Promise<number> {
	for (const kind of ["deployment", "statefulset", "daemonset"]) {
		const listed = await kubectl(["-n", NAMESPACE, "get", kind, "-o", "name"]);
		const names = listed.split("\n").filter(Boolean);
		for (const name of names) {
			await kubectl(["-n", NAMESPACE, "rollout", "status", name, "--timeout=15m"]);
		}
	}
	// The Pods of the current servers: a rollout's old Pods can still be terminating.
	const listed = asRecord(
		parseJson(
			await kubectl([
				"-n",
				NAMESPACE,
				"get",
				"pods",
				"-l",
				"app.kubernetes.io/component=server",
				"-o",
				"json",
			]),
		),
		"Pod list",
	).items;
	const servers = (Array.isArray(listed) ? listed : [])
		.map((pod) => asRecord(asRecord(pod, "Pod").metadata, "Pod metadata"))
		.filter((metadata) => metadata.deletionTimestamp === undefined)
		.map((metadata) => `pod/${asString(metadata.name, "Pod name")}`);
	if (servers.length === 0) {
		throw new Error("no server pod runs");
	}
	// Readiness does not wait for the probe: the server serves its API while the probe runs, and starts no
	// sandbox until it passes. A failed probe is final for this check; the server tries again on its schedule.
	const probeDeadline = Date.now() + PROBE_WAIT_MS;
	for (const pod of servers) {
		for (;;) {
			const log = await kubectl(["-n", NAMESPACE, "logs", pod, "--tail=-1"]);
			if (log.includes(PROBE_PASSED)) {
				break;
			}
			const failed = log.split("\n").find((line) => line.includes(PROBE_FAILED));
			if (failed !== undefined) {
				throw new Error(`${pod} failed its isolation probe: ${failed}`);
			}
			if (Date.now() > probeDeadline) {
				throw new Error(
					`${pod} logged no passed isolation probe within ${PROBE_WAIT_MS / 60_000} minutes`,
				);
			}
			await sleep(5000);
		}
	}
	// The probe deletes each Job after reading its result. A stopped server can leave a completed Job
	// to the TTL controller; only that Job and its succeeded Pod may remain after an upgrade.
	let leftovers = "";
	for (let tries = 0; tries < 30; tries += 1) {
		const probes = await kubectl([
			"-n",
			SANDBOXES,
			"get",
			"jobs,pods",
			"-l",
			"hephaestus.build/isolation-probe=true",
			"-o",
			"json",
		]);
		leftovers = remainingProbeResources(parseJson(probes)).join("\n");
		if (leftovers === "") {
			break;
		}
		await sleep(2000);
	}
	if (leftovers !== "") {
		throw new Error(`isolation probe Jobs or Pods stayed: ${leftovers}`);
	}
	// A sandbox of each template kind reaches the server's gateway and nothing else: not the
	// management port of the server that serves it.
	const service = asRecord(
		parseJson(
			await kubectl([
				"-n",
				NAMESPACE,
				"get",
				"service",
				`${RELEASE}-sandbox-gateway`,
				"-o",
				"json",
			]),
		),
		"gateway Service",
	);
	const sandboxGateway = {
		host: asString(asRecord(service.spec, "spec").clusterIP, "gateway"),
		port: 8081,
	};
	const serverPod = asRecord(
		parseJson(await kubectl(["-n", NAMESPACE, "get", servers[0] ?? "", "-o", "json"])),
		"server Pod",
	);
	const management = asArray(asRecord(serverPod.spec, "spec").containers, "containers")
		.flatMap((container) => asArray(asRecord(container, "container").ports ?? [], "ports"))
		.map((port) => asRecord(port, "port"))
		.find((port) => port.name === "management")?.containerPort;
	const serverIp = asString(asRecord(serverPod.status, "status").podIP, "server address");
	// The gateway port is the only port of the server's Pods that a sandbox reaches: never the
	// application port, by its Service or by any Pod.
	const server = asRecord(
		parseJson(
			await kubectl(["-n", NAMESPACE, "get", "service", `${RELEASE}-server`, "-o", "json"]),
		),
		"server Service",
	);
	const applicationPorts: Target[] = [
		{
			name: "the server Service's application port",
			host: asString(asRecord(server.spec, "spec").clusterIP, "server address"),
			port: APPLICATION_PORT,
			reachable: false,
		},
	];
	const gatewayPorts: Target[] = [];
	for (const pod of await runningPods("server")) {
		applicationPorts.push({
			name: `the application port of server ${pod.name}`,
			host: pod.ip,
			port: APPLICATION_PORT,
			reachable: false,
		});
		gatewayPorts.push({
			name: `the gateway port of server ${pod.name}`,
			host: pod.ip,
			port: sandboxGateway.port,
			reachable: true,
		});
	}
	for (const kind of SANDBOX_KINDS) {
		await isolationGate({
			namespace: SANDBOXES,
			template: `${RELEASE}-sandbox-${kind}`,
			gateway: sandboxGateway,
			reach: gatewayPorts,
			forbid: [
				{
					name: "the server's management port",
					host: serverIp,
					port: Number(management),
					reachable: false,
				},
				...applicationPorts,
			],
		});
	}
	// It opens the boundary for a minute, so only the disposable CI cluster runs it.
	const controlled = process.env.CI === "true";
	if (controlled) {
		await negativeControl(sandboxGateway);
	}
	process.stdout.write(
		`${servers.length} servers passed the isolation probe; the ${SANDBOX_KINDS.join(", ")} templates passed the sandbox isolation gate${controlled ? ", and failed it without the sandbox namespace's policies" : ""}; every workload is ready\n`,
	);
	return servers.length;
}

/** The name and address of each running, not terminating Pod of one component of the release. */
async function runningPods(component: string): Promise<{ name: string; ip: string }[]> {
	const { items } = asRecord(
		parseJson(
			await kubectl([
				"-n",
				NAMESPACE,
				"get",
				"pods",
				"-l",
				`app.kubernetes.io/instance=${RELEASE},app.kubernetes.io/component=${component}`,
				"-o",
				"json",
			]),
		),
		"Pod list",
	);
	return (Array.isArray(items) ? items : [])
		.map((item) => asRecord(item, "Pod"))
		.filter(
			(pod) =>
				asRecord(pod.metadata, "Pod metadata").deletionTimestamp === undefined &&
				asRecord(pod.status, "Pod status").phase === "Running",
		)
		.map((pod) => ({
			name: asString(asRecord(pod.metadata, "Pod metadata").name, "Pod name"),
			ip: asString(asRecord(pod.status, "Pod status").podIP, "Pod address"),
		}));
}

/**
 * The gate's negative control: without the sandbox namespace's NetworkPolicies, the gate must fail,
 * and a sandbox must reach its peer. A gate that passes without the policies proves nothing about
 * them, as on a network plugin that does not enforce NetworkPolicy. The policies come back whatever
 * the gate answers, applied server-side by the field manager that owned them before, which is Helm's:
 * a client-side apply would take their fields from Helm, and the next upgrade would conflict.
 */
async function negativeControl(sandboxGateway: { host: string; port: number }): Promise<void> {
	// kubectl leaves managedFields out of its output unless asked, and both the restore and its check read them.
	const policiesWithOwners = [
		"-n",
		SANDBOXES,
		"get",
		"networkpolicies",
		"--show-managed-fields",
		"-o",
		"json",
	];
	const saved = asRecord(parseJson(await kubectl(policiesWithOwners)), "NetworkPolicy list").items;
	const items = Array.isArray(saved) ? saved : [];
	if (items.length === 0) {
		throw new Error(`${SANDBOXES} has no NetworkPolicy, so the gate has nothing to control`);
	}
	const manager = soleManager(items);
	const policies = items.map((item) => restorable(item));
	let answer: unknown;
	try {
		// Inside the try: a delete that fails half way still restores every policy.
		await kubectl(["-n", SANDBOXES, "delete", "networkpolicies", "--all", "--wait=true"]);
		await isolationGate({
			namespace: SANDBOXES,
			template: `${RELEASE}-sandbox-${SANDBOX_KINDS[0]}`,
			gateway: sandboxGateway,
		});
	} catch (error) {
		answer = error;
	} finally {
		await kubectl(
			["apply", "--server-side", `--field-manager=${manager}`, "--force-conflicts", "-f", "-"],
			JSON.stringify({ apiVersion: "v1", kind: "List", items: policies }),
		);
	}
	const restored = asRecord(
		parseJson(await kubectl(policiesWithOwners)),
		"NetworkPolicy list",
	).items;
	const failures = restoreFailures(Array.isArray(restored) ? restored : [], manager);
	if (failures.length > 0) {
		throw new Error(
			`the restored NetworkPolicies are not as ${manager} left them: ${failures.join("; ")}`,
		);
	}
	const message = answer instanceof Error ? answer.message : "";
	if (!message.includes("reaches another sandbox")) {
		throw new Error(
			answer === undefined
				? "the sandbox isolation gate passed without the sandbox namespace's NetworkPolicies, so it cannot show that they hold"
				: `the sandbox isolation gate failed without the policies, but not by reaching the peer sandbox: ${message}`,
		);
	}
}

/** The managers that own fields of these objects, in the order they appear. */
function managersOf(items: readonly unknown[]): { manager: string }[] {
	return items.flatMap((item) =>
		managedFieldsOf(item).map((field) => ({
			manager: String(asRecord(field, "managedFields entry").manager),
		})),
	);
}

function managedFieldsOf(item: unknown): unknown[] {
	const fields = asRecord(asRecord(item, "object").metadata, "metadata").managedFields;
	return Array.isArray(fields) ? fields : [];
}

/** The objects that name no field manager at all: a read without `--show-managed-fields` shows them so. */
function withoutOwners(items: readonly unknown[]): string[] {
	return items
		.filter((item) => managedFieldsOf(item).length === 0)
		.map((item) => String(asRecord(asRecord(item, "object").metadata, "metadata").name));
}

/**
 * The one field manager that owns every field of these objects: Helm's, by an update after an install and by a
 * server-side apply after an upgrade. An object without owners, or another owner beside it, is no state that the
 * control can copy, so it fails closed.
 */
export function soleManager(items: readonly unknown[]): string {
	const unowned = withoutOwners(items);
	if (unowned.length > 0) {
		throw new Error(
			`the NetworkPolicies ${unowned.join(", ")} name no field manager, so the control cannot restore them as they were`,
		);
	}
	const managers = new Set(managersOf(items).map((entry) => entry.manager));
	if (managers.size !== 1) {
		throw new Error(
			`the NetworkPolicies have ${String(managers.size)} field managers, not one, so the control cannot restore them as they were`,
		);
	}
	return [...managers][0] ?? "";
}

/**
 * Why the restored objects are not as `manager` left them: an object that names no owner, which proves nothing,
 * or another manager that owns a field of one.
 */
export function restoreFailures(items: readonly unknown[], manager: string): string[] {
	const foreign = [...new Set(managersOf(items).map((entry) => entry.manager))].filter(
		(name) => name !== manager,
	);
	return [
		...withoutOwners(items).map((name) => `${name} names no field manager`),
		...foreign.map((name) => `${name} owns fields beside ${manager}`),
	];
}

/** A listed object as its applier wrote it: without the fields that the API server owns. */
function restorable(item: unknown): Record<string, unknown> {
	const { metadata, spec, apiVersion, kind } = asRecord(item, "NetworkPolicy");
	const owned = asRecord(metadata, "metadata");
	return {
		apiVersion,
		kind,
		metadata: {
			name: owned.name,
			namespace: owned.namespace,
			labels: owned.labels,
			annotations: owned.annotations,
		},
		spec,
	};
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
		"postgres",
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

/** A values file in the work directory, as the Helm arguments that add it to an install. */
async function layer(work: string, name: string, values: object): Promise<string[]> {
	const file = path.join(work, `${name}.yaml`);
	await writeFile(file, `${JSON.stringify(values, null, 2)}\n`, { mode: 0o600 });
	return ["--values", file];
}

/**
 * The backup and restore drill of docs/admin/backup-restore.mdx, on the bundled
 * PostgreSQL: a full backup, a row that only the WAL archive holds, a dropped table, and then a
 * point-in-time restore on a replacement node and volume that brings both rows back.
 */
export async function backupDrill(work: string, podCidr: string): Promise<void> {
	const context =
		process.env.CI === "true" ? await output("kubectl", ["config", "current-context"]) : "";
	if (context.trim() !== `k3d-${DEV_CLUSTER}`) {
		throw new Error("the destructive replacement-node drill requires the disposable CI cluster");
	}
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
	// Manual backup Jobs exercise recovery. Future schedules keep CronJobs idle while Helm owns suspend.
	const scheduled = new Date(Date.now() + 24 * 60 * 60 * 1000);
	const schedule = [
		scheduled.getUTCMinutes(),
		scheduled.getUTCHours(),
		scheduled.getUTCDate(),
		scheduled.getUTCMonth() + 1,
		"*",
	].join(" ");
	// Pausing application replicas lowers the automatic budget, but WAL replay needs the source setting.
	const maxConnections = Number(await psql("SHOW max_connections"));
	if (!Number.isSafeInteger(maxConnections)) {
		throw new Error("the source database returned an invalid max_connections setting");
	}
	const backup = (restore: { enabled: boolean; time: string }) => ({
		postgresql: {
			parameters: { max_connections: maxConnections },
			backup: {
				clientTlsSecret: "hephaestus-postgresql-backup-tls",
				enabled: true,
				schedule: { full: schedule, differential: schedule },
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
	const backupOn = await layer(work, "backup", backup({ enabled: false, time: "" }));
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

	const paused = ["--set", "migration.enabled=false,server.replicas=0"];
	await install(work, [...backupOn, ...paused]);
	await kubectl(["-n", NAMESPACE, "scale", `statefulset/${RELEASE}-postgresql`, "--replicas=0"]);
	await kubectl([
		"-n",
		NAMESPACE,
		"wait",
		"--for=delete",
		"pod",
		"-l",
		"app.kubernetes.io/component in (postgresql,server)",
		"--timeout=5m",
	]);
	const claim = `data-${RELEASE}-postgresql-0`;
	const before = asRecord(
		parseJson(await kubectl(["-n", NAMESPACE, "get", "pvc", claim, "-o", "json"])),
		"source claim",
	);
	const originalVolume = asString(asRecord(before.spec, "claim spec").volumeName, "source volume");
	const template = asRecord(
		parseJson(
			await kubectl(["-n", NAMESPACE, "get", "statefulset", `${RELEASE}-postgresql`, "-o", "json"]),
		),
		"StatefulSet",
	);
	const claimTemplate = asRecord(
		asArray(asRecord(template.spec, "StatefulSet spec").volumeClaimTemplates, "claims")[0],
		"claim template",
	);
	const nodes = asArray(
		asRecord(parseJson(await kubectl(["get", "nodes", "-o", "json"])), "nodes").items,
		"nodes.items",
	)
		.filter((node) => asRecord(asRecord(node, "node").spec, "node spec").unschedulable !== true)
		.map((node) =>
			asString(asRecord(asRecord(node, "node").metadata, "node metadata").name, "node name"),
		);
	const nodeName = `${DEV_CLUSTER}-restore-${process.pid}`;
	await run("k3d", [
		"node",
		"create",
		nodeName,
		"--cluster",
		DEV_CLUSTER,
		"--role",
		"agent",
		"--k3s-arg",
		"--kubelet-arg=pod-max-pids=4096",
		"--k3s-node-label",
		`hephaestus.build/restore-drill=${process.pid}`,
		"--wait",
		"--timeout",
		"120s",
	]);
	const replacement = asArray(
		asRecord(
			parseJson(
				await kubectl([
					"get",
					"nodes",
					"-l",
					`hephaestus.build/restore-drill=${process.pid}`,
					"-o",
					"json",
				]),
			),
			"replacement nodes",
		).items,
		"replacement nodes.items",
	);
	if (replacement.length !== 1) {
		throw new Error("the drill needs exactly one replacement node");
	}
	const replacementName = asString(
		asRecord(asRecord(replacement[0], "node").metadata, "node metadata").name,
		"replacement node name",
	);
	const cordoned: string[] = [];
	let failure: Error | undefined;
	try {
		await kubectl(["-n", NAMESPACE, "delete", "pvc", claim, "--wait=true", "--timeout=5m"]);
		await kubectl(["wait", "--for=delete", `pv/${originalVolume}`, "--timeout=5m"]);
		// Existing Pods, including the S3 repository, keep running; only fresh storage must land elsewhere.
		for (const node of nodes) {
			await kubectl(["cordon", node]);
			cordoned.push(node);
		}
		await kubectl(
			["apply", "-f", "-"],
			JSON.stringify({
				apiVersion: "v1",
				kind: "PersistentVolumeClaim",
				metadata: { name: claim, namespace: NAMESPACE },
				spec: claimTemplate.spec,
			}),
		);
		await install(work, [
			...(await layer(work, "restore", backup({ enabled: true, time: target }))),
			...paused,
		]);
		const job = `${RELEASE}-postgresql-backup-restore-${await revision()}`;
		await waitForJob(job, "20m");
		const pods = asArray(
			asRecord(
				parseJson(
					await kubectl(["-n", NAMESPACE, "get", "pods", "-l", `job-name=${job}`, "-o", "json"]),
				),
				"restore Pods",
			).items,
			"restore Pods.items",
		);
		if (
			pods.length === 0 ||
			pods.some(
				(pod) =>
					asRecord(asRecord(pod, "restore Pod").spec, "Pod spec").nodeName !== replacementName,
			)
		) {
			throw new Error("the restore did not run exclusively on the replacement node");
		}
		process.stdout.write(`backup drill: restored to ${target} on a new node, claim and volume\n`);
	} catch (error) {
		failure =
			error instanceof Error ? error : new Error("the restore drill failed", { cause: error });
	} finally {
		const cleanup = await Promise.allSettled(
			cordoned.map(async (node) => kubectl(["uncordon", node])),
		);
		for (const result of cleanup) {
			if (result.status === "rejected") {
				failure ??= new Error("could not uncordon a source node", { cause: result.reason });
			}
		}
	}
	if (failure !== undefined) {
		throw failure;
	}
	await install(work, [...backupOn, ...paused]);
	let recovered = false;
	let recoveryFailure: unknown;
	for (let attempt = 0; attempt < 150; attempt += 1) {
		try {
			const recovery = await psql("SELECT pg_is_in_recovery()");
			if (recovery.trim() === "f") {
				recovered = true;
				break;
			}
		} catch (error) {
			recoveryFailure = error;
		}
		await sleep(2000);
	}
	if (!recovered) {
		throw new Error("PostgreSQL did not finish recovery before clone lockdown", {
			cause: recoveryFailure,
		});
	}
	const restoredPod = asRecord(
		parseJson(
			await kubectl(["-n", NAMESPACE, "get", "pod", `${RELEASE}-postgresql-0`, "-o", "json"]),
		),
		"restored PostgreSQL Pod",
	);
	if (asRecord(restoredPod.spec, "Pod spec").nodeName !== replacementName) {
		throw new Error("PostgreSQL did not start on the replacement node");
	}
	const rows = await psql("SELECT note FROM drill_marker ORDER BY note");
	const notes = rows.trim().split("\n");
	if (notes.join("|") !== "in the full backup|only in the WAL archive") {
		throw new Error(`the restored table holds ${JSON.stringify(notes)}, not both rows`);
	}
	process.stdout.write("backup drill: both rows are back, and the dropped table is restored\n");
	await psql(await readFile(path.join(CHART, "files", "restore-clone-lockdown.sql"), "utf8"));
	await install(work, backupOn);
	await verify();
}

/** The fake model provider's namespace: the capacity fixture renders only `capacity-bench-*` names. */
const ROLL_PROVIDER = "capacity-bench-turn-roll";
/** The synthetic workspace and admin that `scripts/capacity-bench/seed-identities.sql` links. */
const ROLL_WORKSPACE = "capacity-bench-course";
const ROLL_ADMIN = "capacity-bench-000";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const DNS_LABEL = /^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$/u;

/**
 * A one-shot Pod from the chart's server template with the e2e sign-in, for the drill's synthetic
 * admin. The production profile refuses that sign-in, so the Pod runs the e2e profile on the
 * release's database and secrets. It listens on loopback, and a readiness gate that nothing sets
 * keeps it out of every Service: only `kubectl port-forward` reaches it.
 */
export function signInPod(deployment: unknown, name: string): Record<string, unknown> {
	const root = asRecord(deployment, "server Deployment");
	const metadata = asRecord(root.metadata, "server metadata");
	const template = asRecord(asRecord(root.spec, "server spec").template, "server template");
	const spec = asRecord(template.spec, "server Pod spec");
	const containers = asArray(spec.containers, "server containers");
	if (containers.length !== 1) {
		throw new Error("the sign-in Pod needs the chart's single-container server template");
	}
	const container = asRecord(containers[0], "server container");
	const overrides: Record<string, string> = {
		SPRING_PROFILES_ACTIVE: "e2e",
		// The production profile's database settings. Kubernetes expands the chart's variables above.
		SPRING_DATASOURCE_URL: "jdbc:$(DATABASE_URL)",
		SPRING_DATASOURCE_USERNAME: "$(DATABASE_USERNAME)",
		SPRING_DATASOURCE_PASSWORD: "$(DATABASE_PASSWORD)",
		// The migration Job owns the schema, and the release's server consumes the broker.
		SPRING_LIQUIBASE_ENABLED: "false",
		SPRING_DOCKER_COMPOSE_ENABLED: "false",
		HEPHAESTUS_SYNC_NATS_ENABLED: "false",
		HEPHAESTUS_SYNC_RUN_ON_STARTUP: "false",
		// The e2e profile turns GitLab on; keep the release's choice.
		HEPHAESTUS_INTEGRATION_GITLAB_ENABLED: "$(GITLAB_ENABLED)",
		// The synthetic admin has no passkey.
		HEPHAESTUS_AUTH_ASSURANCE_ENFORCED: "false",
		SERVER_ADDRESS: "127.0.0.1",
		// The release's issuer and passkey origins are HTTPS, which refuse the e2e profile's insecure cookies. The
		// drill reads its session token from the response header, so a secure cookie is no obstacle over the port-forward.
		HEPHAESTUS_AUTH_COOKIE_SECURE: "true",
	};
	const env = asArray(container.env ?? [], "server env")
		.map((variable) => asRecord(variable, "server env variable"))
		.filter((variable) => !Object.hasOwn(overrides, String(variable.name)));
	return {
		apiVersion: "v1",
		kind: "Pod",
		metadata: {
			...asRecord(template.metadata, "server template metadata"),
			name,
			namespace: metadata.namespace,
		},
		spec: {
			...spec,
			restartPolicy: "Never",
			readinessGates: [{ conditionType: "hephaestus.build/never-ready" }],
			containers: [
				{
					...container,
					env: [...env, ...Object.entries(overrides).map(([key, value]) => ({ name: key, value }))],
				},
			],
		},
	};
}

/** The local port that `kubectl port-forward` reports: `Forwarding from 127.0.0.1:<port> -> <port>`. */
export function forwardedPort(text: string): number | undefined {
	const port = /^Forwarding from 127\.0\.0\.1:(?<port>\d+) -> \d+$/mu.exec(text)?.groups?.port;
	return port === undefined ? undefined : Number(port);
}

/** A local port to `port` of the Pod, until `stop`. */
async function portForward(pod: string, port: number): Promise<{ port: number; stop: () => void }> {
	const child = spawn(
		"kubectl",
		["-n", NAMESPACE, "port-forward", "--address", "127.0.0.1", `pod/${pod}`, `:${port}`],
		{ stdio: ["ignore", "pipe", "inherit"] },
	);
	const stop = () => {
		child.kill("SIGTERM");
	};
	const local = Promise.withResolvers<number>();
	let seen = "";
	child.stdout.on("data", (chunk: Buffer) => {
		seen += chunk.toString();
		const found = forwardedPort(seen);
		if (found !== undefined) {
			local.resolve(found);
		}
	});
	child.once("error", local.reject);
	child.once("exit", (code, signal) => {
		local.reject(new Error(`kubectl port-forward ended with ${exitStatus(code, signal)}`));
	});
	const timer = setTimeout(() => {
		local.reject(new Error(`kubectl port-forward to ${pod} opened no port in 60 seconds`));
	}, 60_000);
	try {
		return { port: await local.promise, stop };
	} catch (error) {
		stop();
		throw error;
	} finally {
		clearTimeout(timer);
	}
}

/** The drill turn's status, attempt and bound tools Pod, from the database. */
export interface TurnState {
	status: string;
	attempt: number;
	pod: string | null;
}

export function turnState(text: string): TurnState {
	const row = asRecord(parseJson(text), "turn state");
	const { status, attempt, pod } = row;
	if (typeof status !== "string" || typeof attempt !== "number") {
		throw new TypeError(`the turn state has no status or attempt: ${text}`);
	}
	if (pod !== null && (typeof pod !== "string" || !DNS_LABEL.test(pod))) {
		throw new TypeError(`the turn's tools Pod has no valid name: ${text}`);
	}
	return { status, attempt, pod };
}

/** What the drill turn left: its status and attempt, its answer, and each `bash` result in order. */
export interface TurnOutcome {
	status: string;
	attempt: number;
	answer: string;
	bash: readonly { isError: boolean; text: string }[];
}

export function turnOutcome(text: string): TurnOutcome {
	const row = asRecord(parseJson(text), "turn outcome");
	const { status, attempt, answer } = row;
	if (typeof status !== "string" || typeof attempt !== "number" || typeof answer !== "string") {
		throw new TypeError("the turn outcome has no status, attempt or answer");
	}
	const bash = asArray(row.bash, "bash results").map((entry) => {
		const result = asRecord(entry, "bash result");
		return { isError: result.isError === true, text: asString(result.text, "bash result text") };
	});
	return { status, attempt, answer, bash };
}

/** One check of the drill: its claim, and why it failed when it did. */
export interface RollCheck {
	claim: string;
	failure: string | undefined;
}

/** The checks that the turn still runs `recorded`'s attempt with the same tools Pod. */
export function heldChecks(
	after: string,
	recorded: { attempt: number; pod: string },
	observed: TurnState,
): RollCheck[] {
	return [
		{
			claim: `${after}, the turn is RUNNING`,
			failure: observed.status === "RUNNING" ? undefined : `the turn is ${observed.status}`,
		},
		{
			claim: `${after}, the turn runs attempt ${recorded.attempt}`,
			failure:
				observed.attempt === recorded.attempt ? undefined : `it runs attempt ${observed.attempt}`,
		},
		{
			claim: `${after}, the attempt holds tools Pod ${recorded.pod}`,
			failure:
				observed.pod === recorded.pod ? undefined : `it holds ${observed.pod ?? "no tools Pod"}`,
		},
	];
}

/** The checks of the ended turn: DONE on its first attempt, every `bash` call succeeded, and the answer has their output. */
export function outcomeChecks(outcome: TurnOutcome): RollCheck[] {
	const outputs = outcome.bash.map((_, index) => rollOutput(index + 1));
	const failed = outcome.bash.findIndex(
		(result, index) => result.isError || !result.text.includes(outputs[index] ?? ""),
	);
	let bashFailure: string | undefined;
	if (outcome.bash.length === 0) {
		bashFailure = "the turn stored no bash result";
	} else if (failed !== -1) {
		bashFailure = `bash call ${failed + 1} returned ${JSON.stringify(outcome.bash[failed]?.text.slice(0, 200))}`;
	}
	const missing = outputs.filter((printed) => !outcome.answer.includes(printed));
	return [
		{
			claim: "the turn ended DONE",
			failure: outcome.status === "DONE" ? undefined : `it ended ${outcome.status}`,
		},
		{
			claim: "the turn ran one attempt",
			failure: outcome.attempt === 1 ? undefined : `it ran ${outcome.attempt} attempts`,
		},
		{
			claim: `each of the ${outcome.bash.length} bash calls succeeded in the tools Pod`,
			failure: bashFailure,
		},
		{
			claim: "the answer contains the output of each bash call",
			failure:
				missing.length === 0
					? undefined
					: `the answer lacks ${missing.join(", ")}: ${outcome.answer}`,
		},
	];
}

function report(checks: readonly RollCheck[]): void {
	for (const { claim, failure } of checks) {
		if (failure !== undefined) {
			throw new Error(`turn roll: ${claim}: no, ${failure}`);
		}
		process.stdout.write(`turn roll: ${claim}\n`);
	}
}

/** Reads `read` every five seconds until `done`, for up to `minutes`. */
async function until<T>(
	what: string,
	minutes: number,
	read: () => Promise<T>,
	done: (value: T) => boolean,
): Promise<T> {
	const deadline = Date.now() + minutes * 60_000;
	for (;;) {
		const value = await read();
		if (done(value)) {
			return value;
		}
		if (Date.now() > deadline) {
			throw new Error(`turn roll: ${what} in ${minutes} minutes; last ${JSON.stringify(value)}`);
		}
		await sleep(5000);
	}
}

async function toolsJob(name: string): Promise<boolean> {
	const found = await kubectl([
		"-n",
		SANDBOXES,
		"get",
		"job",
		name,
		"--ignore-not-found",
		"-o",
		"name",
	]);
	return found.trim() !== "";
}

async function rollRestart(deployment: string): Promise<void> {
	await kubectl(["-n", NAMESPACE, "rollout", "restart", `deployment/${deployment}`]);
	// The rollout ends when the new Pods are available; an old loop Pod can still end its turn.
	await kubectl([
		"-n",
		NAMESPACE,
		"rollout",
		"status",
		`deployment/${deployment}`,
		"--timeout=15m",
	]);
}

/**
 * The roll drill of docs/admin/runtime-roles.mdx § The conversation loop: a conversation turn that runs
 * `bash` in its tools Pod outlives a rollout restart of the server and then of the loop. The fake
 * model provider of the capacity bench scripts the turn: a 30 s command after each result, until
 * the drill releases it after both rollouts, then an answer with each command's output.
 */
export async function turnRoll(
	work: string,
	podCidr: string,
	extra: readonly string[],
	profiles: readonly string[],
): Promise<void> {
	const replay = path.join(work, "turn-roll-replay.json");
	const manifest = path.join(work, "turn-roll-provider.json");
	await writeFile(
		replay,
		JSON.stringify({
			provenance: "assumed",
			source: "turn-roll drill: a fast fake, so the commands set the turn's length",
			samples: [{ ttftMs: 100, durationMs: 500, inputTokens: 100, outputTokens: 20, status: 200 }],
		}),
	);
	await rm(manifest, { force: true });
	await run(process.execPath, [
		path.join(ROOT, "scripts", "capacity-fixture.ts"),
		"--namespace",
		ROLL_PROVIDER,
		"--replay",
		replay,
		"--out",
		manifest,
		"--tool-mode",
		"roll",
		"--client",
		NAMESPACE,
	]);
	// A provider of an earlier drill holds its release.
	await kubectl(["delete", "namespace", ROLL_PROVIDER, "--ignore-not-found", "--wait=true"]);
	await kubectl(["apply", "-f", manifest]);
	const signIn = `${RELEASE}-turn-roll-sign-in`;
	let forward: { port: number; stop: () => void } | undefined;
	try {
		await kubectl(["-n", ROLL_PROVIDER, "rollout", "status", "deployment/replay", "--timeout=5m"]);
		// docs/admin/private-network.md § Admit a model endpoint on a private network, with the Pod network as that network.
		const host = `replay.${ROLL_PROVIDER}.svc.cluster.local`;
		await install(
			work,
			[
				...extra,
				...(await layer(work, "turn-roll", {
					config: { llmPrivateHosts: [host], llmPrivateHostsAllowHttp: true },
					networkPolicy: { modelEndpoints: [{ cidr: podCidr, ports: [8080] }] },
				})),
			],
			profiles,
		);
		const server = parseJson(
			await kubectl(["-n", NAMESPACE, "get", "deployment", `${RELEASE}-server`, "-o", "json"]),
		);
		await kubectl(["-n", NAMESPACE, "delete", "pod", signIn, "--ignore-not-found", "--wait=true"]);
		await apply(signInPod(server, signIn));
		try {
			await kubectl([
				"-n",
				NAMESPACE,
				"wait",
				"--for=jsonpath={.status.phase}=Running",
				`pod/${signIn}`,
				"--timeout=5m",
			]);
			// The container's readiness probe passes; the Pod's readiness gate keeps it out of the Service.
			await kubectl([
				"-n",
				NAMESPACE,
				"wait",
				"--for=jsonpath={.status.containerStatuses[0].ready}=true",
				`pod/${signIn}`,
				"--timeout=10m",
			]);
		} catch (error) {
			// The cleanup below deletes the Pod, so what it shows must be printed now.
			for (const args of [
				["describe", "pod", signIn],
				["logs", signIn, "--tail=200"],
			]) {
				const shown = await kubectl(["-n", NAMESPACE, ...args]).catch(String);
				process.stdout.write(`turn-roll: kubectl ${args.join(" ")}\n${shown}\n`);
			}
			throw error;
		}
		forward = await portForward(signIn, 8080);
		const base = `http://127.0.0.1:${forward.port}`;
		const token = await syntheticSession(base, ROLL_ADMIN, true);
		await psql(
			await readFile(path.join(ROOT, "scripts", "capacity-bench", "seed-identities.sql"), "utf8"),
		);
		await bindReplayModel(
			syntheticApi(base, token),
			ROLL_WORKSPACE,
			`http://${host}:8080/v1`,
			// The longest turn: AgentBindingLimits.MAX_HEPH_TURN_SECONDS.
			900,
		);
		const submitted = await submitTurn(`${base}/workspaces/${ROLL_WORKSPACE}/mentor`, token, {
			requestId: randomUUID(),
			text: "Run the turn-roll command.",
		});
		if ("status" in submitted || !UUID.test(submitted.id)) {
			throw new Error(`turn roll: the question was refused: ${JSON.stringify(submitted)}`);
		}
		process.stdout.write(`turn roll: asked turn ${submitted.id}\n`);
		const state = async () =>
			turnState(
				await psql(
					`SELECT json_build_object('status', t.status, 'attempt', t.attempt, 'pod', p.name) FROM mentor_turn t LEFT JOIN conversation_tools_pod p ON p.turn_id = t.id AND p.attempt = t.attempt WHERE t.id = '${submitted.id}'`,
				),
			);
		const running = await until(
			"the turn did not run bash in a tools Pod",
			5,
			state,
			(observed) =>
				observed.status !== "QUEUED" && (observed.status !== "RUNNING" || observed.pod !== null),
		);
		const { pod } = running;
		if (running.status !== "RUNNING" || pod === null) {
			throw new Error(`turn roll: the turn ended ${running.status} before it held a tools Pod`);
		}
		const recorded = { attempt: running.attempt, pod };
		process.stdout.write(
			`turn roll: attempt ${recorded.attempt} runs bash in tools Pod ${recorded.pod}\n`,
		);
		report([
			{
				claim: `the tools Job ${pod} exists`,
				failure: (await toolsJob(pod)) ? undefined : "it does not",
			},
		]);

		await rollRestart(`${RELEASE}-server`);
		report([
			...heldChecks("after the server roll", recorded, await state()),
			{
				claim: `after the server roll, the tools Job ${pod} exists`,
				failure: (await toolsJob(pod)) ? undefined : "it is gone",
			},
		]);
		await rollRestart(`${RELEASE}-conversation-loop`);
		report(heldChecks("after the loop roll", recorded, await state()));

		// The fake answers at the next model call, after the command that runs now.
		await kubectl([
			"-n",
			ROLL_PROVIDER,
			"exec",
			"deployment/replay",
			"--",
			"node",
			"-e",
			"fetch('http://127.0.0.1:8080/release',{method:'POST'}).then((r)=>process.exit(r.status===204?0:1))",
		]);
		await until(
			"the turn did not end",
			5,
			state,
			(observed) => !["QUEUED", "RUNNING"].includes(observed.status),
		);
		const outcome = turnOutcome(
			await psql(
				`SELECT json_build_object('status', t.status, 'attempt', t.attempt, 'answer', coalesce((SELECT string_agg(e.text, E'\\n' ORDER BY e.seq) FROM mentor_entry e WHERE e.turn_id = t.id AND e.kind = 'HEPH'), ''), 'bash', coalesce((SELECT json_agg(json_build_object('isError', coalesce((e.payload->'message'->>'isError')::boolean, false), 'text', coalesce((SELECT string_agg(c->>'text', '') FROM jsonb_array_elements(CASE WHEN jsonb_typeof(e.payload->'message'->'content') = 'array' THEN e.payload->'message'->'content' ELSE '[]'::jsonb END) c), '')) ORDER BY e.seq) FROM mentor_entry e WHERE e.turn_id = t.id AND e.kind = 'TOOL_RESULT' AND e.payload->'message'->>'toolName' = 'bash'), '[]'::json)) FROM mentor_turn t WHERE t.id = '${submitted.id}'`,
			),
		);
		report(outcomeChecks(outcome));
		// The server removes the tools Pod when the attempt ends.
		await until(
			"the tools Job stayed",
			5,
			async () => toolsJob(pod),
			(present) => !present,
		);
		report([{ claim: `the tools Job ${pod} is removed`, failure: undefined }]);
	} finally {
		forward?.stop();
		await kubectl(["-n", NAMESPACE, "delete", "pod", signIn, "--ignore-not-found", "--wait=false"]);
		// Without its provider, a turn that a failed drill left fails at its next model call.
		await kubectl(["delete", "namespace", ROLL_PROVIDER, "--ignore-not-found", "--wait=false"]);
	}
}

/** The release and host of the release smoke. The release name is the one the platform files name. */
const ONE_HOST_RELEASE = "hephaestus";
export const SMOKE_HOST = "hephaestus-smoke.invalid";

/**
 * The namespace and Secret of the Gateway's certificate, as the certificates asset names them: the
 * smoke reads the CA of its local issuer from there.
 */
export async function gatewayCertificate(
	source: string,
): Promise<{ namespace: string; secretName: string }> {
	const { parseAllDocuments } = await import("yaml");
	for (const document of parseAllDocuments(source)) {
		const resource: unknown = document.toJS();
		if (
			isRecord(resource) &&
			resource.kind === "Certificate" &&
			isRecord(resource.metadata) &&
			resource.metadata.name === "hephaestus-tls" &&
			isRecord(resource.spec)
		) {
			return {
				namespace: asString(resource.metadata.namespace, "Certificate namespace"),
				secretName: asString(resource.spec.secretName, "Certificate secretName"),
			};
		}
	}
	throw new Error("the certificates file has no Certificate hephaestus-tls");
}

/** A YAML document that starts with its own `---` marker, so that documents can be concatenated. */
const marked = (text: string) => (text.startsWith("---") ? text : `---\n${text}`);

/**
 * The certificates asset as the release smoke installs it: the smoke's host name, and a local CA as
 * the issuer `gateway`, because Let's Encrypt cannot reach a runner. Every other document stays as
 * the operator's copy has it.
 */
export async function smokeCertificates(source: string, host: string): Promise<string> {
	// Imported here: only the release smoke installs the repository's packages. The cluster jobs run
	// the other commands of this script with Node alone.
	const { parseAllDocuments, stringify } = await import("yaml");
	const documents = parseAllDocuments(source.replaceAll("hephaestus.example.com", host));
	if (!Array.isArray(documents) || documents.some((document) => document.errors.length > 0)) {
		throw new Error("the certificates file is not valid YAML");
	}
	const kept = documents.filter((document) => document.get("kind") !== "ClusterIssuer");
	if (kept.length !== documents.length - 1) {
		throw new Error("the certificates file names more or less than one ClusterIssuer");
	}
	const root = {
		apiVersion: "cert-manager.io/v1",
		kind: "ClusterIssuer",
		metadata: { name: "smoke-root" },
		spec: { selfSigned: {} },
	};
	const ca = {
		apiVersion: "cert-manager.io/v1",
		kind: "Certificate",
		metadata: { name: "smoke-ca", namespace: "cert-manager" },
		spec: {
			isCA: true,
			commonName: "Hephaestus release smoke CA",
			secretName: "smoke-ca",
			issuerRef: { kind: "ClusterIssuer", name: "smoke-root" },
		},
	};
	const issuer = {
		apiVersion: "cert-manager.io/v1",
		kind: "ClusterIssuer",
		metadata: { name: "gateway" },
		spec: { ca: { secretName: "smoke-ca" } },
	};
	return [
		...[root, ca, issuer].map((document) => marked(stringify(document))),
		...kept.map((document) => marked(document.toString())),
	].join("");
}

const UNIT_SECONDS: Readonly<Record<string, number>> = { h: 3600, m: 60, s: 1 };

/** Seconds of a Go duration such as `5m`, `5m0s` or `1h2m3s`. */
export function durationSeconds(duration: string): number {
	const parts = [...duration.matchAll(/(?<value>\d+)(?<unit>[hms])/gu)];
	if (parts.length === 0 || parts.map((part) => part[0]).join("") !== duration) {
		throw new Error(`not a duration of hours, minutes and seconds: ${duration}`);
	}
	return parts.reduce(
		(sum, { groups }) => sum + Number(groups?.value) * (UNIT_SECONDS[groups?.unit ?? ""] ?? 0),
		0,
	);
}

/**
 * The shutdown budget and the reservations that the kubelet and logind run with, against the assets:
 * the kubelet's live configuration has the asset's grace periods, reservations and eviction
 * thresholds, and logind lets a shutdown wait at least that long. No reboot happens here.
 */
async function shutdownBudget(node: string, assets: string): Promise<void> {
	const { parse } = await import("yaml");
	const wanted = asRecord(
		parse(await readFile(path.join(assets, "k3s-kubelet.yaml"), "utf8")),
		"kubelet asset",
	);
	const live = asRecord(
		asRecord(
			parseJson(await kubectl(["get", "--raw", `/api/v1/nodes/${node}/proxy/configz`])),
			"configz",
		).kubeletconfig,
		"kubelet configuration",
	);
	for (const key of ["shutdownGracePeriod", "shutdownGracePeriodCriticalPods"]) {
		const configured = durationSeconds(asString(wanted[key], key));
		const running = durationSeconds(asString(live[key], key));
		if (configured !== running) {
			throw new Error(
				`the kubelet runs with ${key} ${running}s, not the configured ${configured}s`,
			);
		}
	}
	if (live.podPidsLimit !== wanted.podPidsLimit) {
		throw new Error(`the kubelet runs with podPidsLimit ${String(live.podPidsLimit)}`);
	}
	for (const key of ["kubeReserved", "systemReserved", "evictionHard"]) {
		if (!isDeepStrictEqual(live[key], wanted[key])) {
			throw new Error(`the kubelet runs with ${key} ${JSON.stringify(live[key])}`);
		}
	}
	const delay = await output("busctl", [
		"get-property",
		"org.freedesktop.login1",
		"/org/freedesktop/login1",
		"org.freedesktop.login1.Manager",
		"InhibitDelayMaxUSec",
	]);
	const seconds = Number(/^t (?<usec>\d+)$/u.exec(delay.trim())?.groups?.usec) / 1_000_000;
	const grace = durationSeconds(asString(wanted.shutdownGracePeriod, "shutdownGracePeriod"));
	if (!(seconds >= grace)) {
		throw new Error(
			`logind lets a shutdown wait ${seconds}s, less than the ${grace}s grace period`,
		);
	}
	process.stdout.write(
		`the kubelet holds a shutdown for ${grace}s, and logind allows ${seconds}s\n`,
	);
}

/** One request through the Gateway at the node's address: the status code and the redirect target. */
async function gatewayAnswer(
	url: string,
	node: string,
	ca: string,
): Promise<{ code: number; location: string }> {
	const written = await output("curl", [
		"--silent",
		"--show-error",
		"--max-time",
		"10",
		"--noproxy",
		"*",
		"--cacert",
		ca,
		"--resolve",
		`${SMOKE_HOST}:80:${node}`,
		"--resolve",
		`${SMOKE_HOST}:443:${node}`,
		"--output",
		"/dev/null",
		"--write-out",
		"%{http_code} %{redirect_url}",
		url,
	]);
	const [code = "", location = ""] = written.trim().split(" ");
	return { code: Number(code), location };
}

/**
 * The routes of docs/admin/helm-chart.md § Gateway routes, through Traefik: the redirect to HTTPS,
 * the webapp, the server behind the `/api` rewrite, and the webhook path, over TLS that the
 * certificate of the Gateway's listener carries.
 */
async function gatewayRequests(
	work: string,
	node: string,
	tlsSecret: { namespace: string; secretName: string },
): Promise<void> {
	const tls = asRecord(
		asRecord(
			parseJson(
				await kubectl([
					"-n",
					tlsSecret.namespace,
					"get",
					"secret",
					tlsSecret.secretName,
					"-o",
					"json",
				]),
			),
			"Secret",
		).data,
		"Secret data",
	);
	const ca = path.join(work, "gateway-ca.crt");
	await writeFile(ca, Buffer.from(asString(tls["ca.crt"], "ca.crt"), "base64"));
	const origin = `https://${SMOKE_HOST}`;
	const checks = [
		{
			url: `http://${SMOKE_HOST}/`,
			passes: (answer: { code: number; location: string }) =>
				answer.code === 301 && [`${origin}/`, `${origin}:443/`].includes(answer.location),
		},
		{ url: `${origin}/`, passes: (answer: { code: number }) => answer.code === 200 },
		{
			url: `${origin}/api/auth/client/configuration?clientId=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`,
			passes: (answer: { code: number }) => answer.code === 200,
		},
		// The receiver answers a GET without a delivery; a status of 500 or more is the Gateway's.
		{
			url: `${origin}/webhooks/github`,
			passes: (answer: { code: number }) => answer.code > 0 && answer.code < 500,
		},
	];
	for (const check of checks) {
		let last = "no answer";
		// Traefik applies a route a few seconds after the Gateway accepts it.
		for (let attempt = 0; attempt < 30; attempt += 1) {
			try {
				const answer = await gatewayAnswer(check.url, node, ca);
				if (check.passes(answer)) {
					process.stdout.write(`${check.url}: ${answer.code}\n`);
					last = "";
					break;
				}
				last = `${answer.code} ${answer.location}`.trim();
			} catch (error) {
				last = error instanceof Error ? error.message : String(error);
			}
			await sleep(2000);
		}
		if (last !== "") {
			throw new Error(`${check.url} through the Gateway: ${last}`);
		}
	}
}

interface SmokeOptions {
	work: string;
	/** The directory of the unpacked k3s bundle of the release, after its signature check. */
	assets: string;
	/** The joint lock of the release, downloaded: a draft release's assets need a token. */
	lock: string;
	release: string;
	/** The architecture of this runner, which the node must report: amd64 or arm64. */
	architecture: string;
	/** Public qualification evidence, written only after every install/upgrade check passes. */
	receipt?: string;
}

/** A digest of the release Secret's data, so that two runs compare it without printing a value. */
async function secretDigest(): Promise<string> {
	const data = asRecord(
		asRecord(
			parseJson(
				await kubectl(["-n", NAMESPACE, "get", "secret", `${ONE_HOST_RELEASE}-app`, "-o", "json"]),
			),
			"Secret",
		).data,
		"Secret data",
	);
	const sorted = Object.keys(data)
		.toSorted()
		.map((key) => [key, data[key]]);
	return createHash("sha256").update(JSON.stringify(sorted)).digest("hex");
}

/** Every current server is Ready and runs reviews with repository checkouts. */
async function reviewsOn(): Promise<void> {
	const pods = asArray(
		asRecord(
			parseJson(
				await kubectl([
					"-n",
					NAMESPACE,
					"get",
					"pods",
					"-l",
					"app.kubernetes.io/component=server",
					"-o",
					"json",
				]),
			),
			"Pod list",
		).items,
		"Pods",
	)
		.map((pod) => asRecord(pod, "Pod"))
		.filter(
			(pod) =>
				asRecord(pod.metadata, "Pod metadata").deletionTimestamp === undefined &&
				asRecord(pod.status, "Pod status").phase === "Running",
		);
	for (const pod of pods) {
		const name = asString(asRecord(pod.metadata, "Pod metadata").name, "Pod name");
		const ready = asArray(asRecord(pod.status, "Pod status").conditions, "conditions")
			.map((condition) => asRecord(condition, "condition"))
			.some((condition) => condition.type === "Ready" && condition.status === "True");
		const env = asArray(asRecord(pod.spec, "Pod spec").containers, "containers")
			.flatMap((container) => asArray(asRecord(container, "container").env ?? [], "env"))
			.map((variable) => asRecord(variable, "env variable"));
		const checkout = env.find((variable) => variable.name === "GIT_CHECKOUT_ENABLED")?.value;
		if (!ready || checkout !== "true") {
			throw new Error(
				`server ${name}: Ready ${String(ready)}, GIT_CHECKOUT_ENABLED ${String(checkout)}`,
			);
		}
	}
	process.stdout.write(`${pods.length} servers are Ready and check out repositories for reviews\n`);
}

/**
 * The one-host install as docs/admin/k3s.md runs it: the
 * release's k3s-install.sh from its unpacked bundle, with a local CA in place of Let's Encrypt, and without the DNS and
 * clock checks that a runner cannot pass. A runner of 16 GiB installs with the small profile. Then the checks around it, a second run that must change
 * nothing, and an upgrade that rolls every role except PostgreSQL and NATS.
 */
export async function releaseSmoke(options: SmokeOptions): Promise<void> {
	const lock = asRecord(parseJson(await readFile(options.lock, "utf8")), "release lock");
	const commit = asString(lock.commit, "release commit");
	const source = await output("git", ["rev-parse", "HEAD"], { cwd: ROOT });
	if (source.trim() !== commit || lock.release !== options.release) {
		throw new Error("The k3s qualification source and release must match the locked artifact");
	}
	await mkdir(options.work, { recursive: true, mode: 0o700 });
	await chmod(options.work, 0o700);
	const version = options.release.replace(/^v/u, "");
	const certificatesSource = await readFile(
		path.join(options.assets, "k3s-certificates.yaml"),
		"utf8",
	);
	const gatewayTls = await gatewayCertificate(certificatesSource);
	const certificatesFile = path.join(options.work, "smoke-certificates.yaml");
	await writeFile(certificatesFile, await smokeCertificates(certificatesSource, SMOKE_HOST));
	const installer = [
		path.join(options.assets, "k3s-install.sh"),
		// Hosted runner labels do not pin a kernel. This smoke explicitly qualifies its host;
		// the installer still verifies the effective policy and allocatable before startup.
		"--allow-unqualified-host",
		"--version",
		version,
		"--host",
		SMOKE_HOST,
		// A first install needs a login provider. The smoke never signs in, so any client ID will do.
		"--github-client-id",
		"release-smoke",
		"--certificates",
		certificatesFile,
		"--lock",
		options.lock,
		"--skip-check",
		"dns",
		// Hosted runners take their time from the hypervisor and may not report an NTP sync. The
		// check stays strict for operators.
		"--skip-check",
		"clock",
	];
	// No terminal: the installer asks nothing, and every value comes from its flags.
	await run("sh", installer, {
		env: { GITHUB_OAUTH_CLIENT_SECRET: "release-smoke-secret" },
		stdin: "ignore",
	});
	process.env.KUBECONFIG = path.join(options.assets, "kubeconfig");
	const servers = await verify();
	await reviewsOn();

	const node = asRecord(
		asArray(
			asRecord(parseJson(await kubectl(["get", "nodes", "-o", "json"])), "Node list").items,
			"nodes",
		)[0],
		"Node",
	);
	const status = asRecord(node.status, "Node status");
	const info = asRecord(status.nodeInfo, "Node info");
	const architecture = asString(info.architecture, "Node architecture");
	if (architecture !== options.architecture) {
		throw new Error(`expected a native ${options.architecture} node, not ${architecture}`);
	}
	await shutdownBudget(
		asString(asRecord(node.metadata, "Node metadata").name, "Node name"),
		options.assets,
	);
	const address = asArray(status.addresses, "Node addresses")
		.map((entry) => asRecord(entry, "Node address"))
		.find((entry) => entry.type === "InternalIP");
	const nodeAddress = asString(address?.address, "Node InternalIP");
	await gatewayRequests(options.work, nodeAddress, gatewayTls);

	// The same command again: it keeps every key of the Secret, and the release stays ready.
	const secretBefore = await secretDigest();
	await run("sh", installer, {
		env: { GITHUB_OAUTH_CLIENT_SECRET: "release-smoke-secret" },
		stdin: "ignore",
	});
	if ((await secretDigest()) !== secretBefore) {
		throw new Error("a second run of k3s-install.sh changed the release Secret");
	}
	await verify();
	process.stdout.write("a second run of k3s-install.sh kept the Secret and every workload\n");

	// An upgrade that rolls every role but PostgreSQL and NATS, with a new migration Job: the new server
	// proves the cluster with the isolation probe.
	await keepsStatefulPods(async () =>
		run("helm", [
			"upgrade",
			ONE_HOST_RELEASE,
			path.join(options.assets, `chart-${version}`, "hephaestus"),
			"--namespace",
			NAMESPACE,
			"--reuse-values",
			"--set",
			`release.version=${options.release}-smoke`,
			"--wait=watcher",
			"--wait-for-jobs",
			"--timeout",
			"20m",
		]),
	);
	await verify();
	await reviewsOn();
	await gatewayRequests(options.work, nodeAddress, gatewayTls);

	if (options.receipt !== undefined) {
		const host = parseEnv(await readFile("/etc/os-release", "utf8"));
		await writeFile(
			options.receipt,
			`${JSON.stringify(
				{
					schemaVersion: 2,
					release: options.release,
					commit,
					testedAt: new Date().toISOString(),
					host: {
						architecture,
						os: `${asString(host.NAME, "host OS")} ${asString(host.VERSION_ID, "host OS version")}`,
						kernel: asString(info.kernelVersion, "node kernel"),
						kubeletVersion: asString(info.kubeletVersion, "kubelet version"),
					},
					servers,
					checks: {
						install: true,
						secondInstall: true,
						upgrade: true,
						workloadsReady: true,
						isolation: true,
						reviewsEnabled: true,
						gateway: true,
						shutdownBudget: true,
						statefulPodsPreserved: true,
						secretsPreserved: true,
					},
				},
				null,
				2,
			)}\n`,
		);
	}
	process.stdout.write(
		`k3s ${asString(info.kubeletVersion, "kubelet version")} on ${architecture}: the release ${options.release} serves through the Gateway after an install, a second run and an upgrade; ${servers} servers passed the isolation probe\n`,
	);
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
	// The dev profile with backups and with an external PostgreSQL, and the managed-cluster profile.
	const variants: { profile: string; values: Record<string, unknown> }[] = [
		{
			profile: "dev",
			values: {
				...operatorValues(base),
				postgresql: {
					tls: { existingSecret: `${RELEASE}-postgresql-tls` },
					backup: {
						clientTlsSecret: "hephaestus-postgresql-backup-tls",
						enabled: true,
						s3: {
							bucket: "hephaestus-backups",
							endpoint: "s3.example.com",
							region: "eu-central-1",
						},
					},
				},
			},
		},
		{
			profile: "dev",
			values: operatorValues({
				...base,
				externalPostgresql: { host: "postgresql.external.svc", cidr: "192.168.0.0/16" },
			}),
		},
		{ profile: "cluster", values: operatorValues(base) },
	];
	const work = await mkdtemp(path.join(tmpdir(), "hephaestus-render-"));
	try {
		const manifests = [];
		for (const [index, { profile, values }] of variants.entries()) {
			const file = path.join(work, `values-${index}.yaml`);
			await writeFile(file, JSON.stringify(values));
			const layers = [
				"--namespace",
				NAMESPACE,
				"--values",
				path.join(CHART, "profiles", `${profile}.yaml`),
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
	const tag = `dev-${createHash("sha256").update(ROOT).digest("hex").slice(0, 12)}`;
	const build = async (
		image: string,
		context: string,
		dockerfile?: string,
		buildContexts: string[] = [],
	) => {
		await run(
			"docker",
			[
				"build",
				"--quiet",
				"-t",
				`hephaestus-local/${image}:${tag}`,
				...buildContexts.flatMap((named) => ["--build-context", named]),
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
	await build("agent-pi", "docker/agents", "docker/agents/pi/Dockerfile", [
		"agent-runtime=server/application/src/main/resources/agent",
	]);
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
 * The Docker memory that `dev` needs: the roles peak at less than 6 GiB, a review
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

const LOOPBACK_ADDRESSES = new BlockList();
LOOPBACK_ADDRESSES.addSubnet("127.0.0.0", 8, "ipv4");
LOOPBACK_ADDRESSES.addAddress("::1", "ipv6");

function isLoopbackPublication(binding: unknown): boolean {
	if (!isRecord(binding) || typeof binding.HostIp !== "string") {
		return false;
	}
	const family = isIP(binding.HostIp);
	return family !== 0 && LOOPBACK_ADDRESSES.check(binding.HostIp, family === 4 ? "ipv4" : "ipv6");
}

export type ClusterState = "absent" | "stopped" | "without-port" | "needs-reset" | "running";

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
	const apiBindings = nodes
		.map((node) => asRecord(node, "node"))
		.map((node) => (isRecord(node.portMappings) ? node.portMappings["6443/tcp"] : undefined))
		.filter((bindings) => bindings !== undefined);
	if (
		apiBindings.length === 0 ||
		!apiBindings.every(
			(bindings) =>
				Array.isArray(bindings) && bindings.length > 0 && bindings.every(isLoopbackPublication),
		)
	) {
		return "needs-reset";
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
			// curl stops retrying a Gateway that never answers after two minutes.
			"--retry-max-time",
			"120",
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

/** The shared local cluster publishes both entry points only on this machine. */
export function devClusterArguments(mirrors: string): string[] {
	return [
		"cluster",
		"create",
		DEV_CLUSTER,
		"--agents",
		"1",
		"--api-port",
		"127.0.0.1:random",
		"--registry-use",
		`${DEV_REGISTRY}:5000`,
		"--registry-config",
		mirrors,
		"--port",
		"127.0.0.1:443:443@loadbalancer",
		"--k3s-arg",
		"--kubelet-arg=pod-max-pids=4096@all",
	];
}

/** Publish credentials atomically, only when this cluster's identity or endpoint changed. */
async function devKubeconfig(work: string): Promise<string> {
	await mkdir(work, { recursive: true, mode: 0o700 });
	await chmod(work, 0o700);
	const kubeconfig = path.join(work, "kubeconfig");
	const content = await output("k3d", ["kubeconfig", "get", DEV_CLUSTER]).catch(() => {
		throw new Error("The local cluster kubeconfig could not be read");
	});
	const saved = await readFile(kubeconfig, "utf8").catch(() => "");
	if (content !== saved) {
		const pending = path.join(work, `kubeconfig-${randomBytes(8).toString("hex")}`);
		await writeFile(pending, content, { mode: 0o600 });
		await rename(pending, kubeconfig);
	}
	await chmod(kubeconfig, 0o600);
	process.env.KUBECONFIG = kubeconfig;
	return kubeconfig;
}

/** Cleanup never creates or starts a cluster. */
export async function requireDevCluster(work = DEV_WORK): Promise<string> {
	const state = clusterState(parseJson(await output("k3d", ["cluster", "list", "-o", "json"])));
	if (state !== "running") {
		throw new Error(`The shared cluster is ${state}; cleanup requires the running local cluster`);
	}
	return devKubeconfig(work);
}

/** Shared infrastructure only: host edit loops install no chart release. */
export async function ensureDevCluster(work = DEV_WORK): Promise<string> {
	await preflight();
	await mkdir(work, { recursive: true, mode: 0o700 });
	await chmod(work, 0o700);
	if (!(await k3dHasRegistry(DEV_REGISTRY))) {
		try {
			await run("k3d", [
				"registry",
				"create",
				"hephaestus-registry.localhost",
				"--port",
				"127.0.0.1:5111",
			]);
		} catch {
			if (!(await k3dHasRegistry(DEV_REGISTRY))) {
				throw new Error("The shared k3d registry could not be created");
			}
		}
	}
	const state = clusterState(parseJson(await output("k3d", ["cluster", "list", "-o", "json"])));
	if (state === "without-port" || state === "needs-reset") {
		throw new Error(
			`the cluster ${DEV_CLUSTER} needs a loopback API binding and a published HTTPS port. Run vp run dev:kubernetes:reset`,
		);
	}
	if (state === "stopped") {
		await run("k3d", ["cluster", "start", DEV_CLUSTER]);
	}
	if (state === "absent") {
		// All checkouts use the same mirror; a failed concurrent create must never delete the winner's cluster.
		const mirrors = path.join(work, "registries.yaml");
		await writeFile(
			mirrors,
			`mirrors:\n  ghcr.io:\n    endpoint:\n      - http://${DEV_REGISTRY}:5000\n`,
		);
		try {
			await run("k3d", devClusterArguments(mirrors));
		} catch {
			let running = false;
			for (let tries = 0; tries < 60; tries += 1) {
				if (
					clusterState(parseJson(await output("k3d", ["cluster", "list", "-o", "json"]))) ===
					"running"
				) {
					running = true;
					break;
				}
				await sleep(1000);
			}
			if (!running) {
				throw new Error(
					"k3d could not create the shared cluster; check the other dev commands before resetting",
				);
			}
		}
	}
	return devKubeconfig(work);
}

async function dev(work: string): Promise<void> {
	const admin = await ensureDevCluster(work);
	await claimDevNamespace(admin, NAMESPACE, ROOT, restricted);
	const savedFile = path.join(work, "sign-in.json");
	const signIn = devSignIn(process.env, await savedSignIn(savedFile));
	const registryPort = "5111";
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
	const state = clusterState(parseJson(await output("k3d", ["cluster", "list", "-o", "json"])));
	if (state !== "absent") {
		if (state === "stopped") {
			await run("k3d", ["cluster", "start", DEV_CLUSTER]);
		}
		await mkdir(work, { recursive: true, mode: 0o700 });
		const kubeconfig = path.join(work, "kubeconfig");
		await run("k3d", ["kubeconfig", "write", DEV_CLUSTER, "--output", kubeconfig], {
			stdout: "ignore",
		});
		await chmod(kubeconfig, 0o600);
		process.env.KUBECONFIG = kubeconfig;
		const namespaces = asRecord(
			parseJson(
				await kubectl(["get", "namespaces", "-l", "hephaestus.build/dev-host=true", "-o", "json"]),
			),
			"namespace list",
		);
		if (asArray(namespaces.items, "namespaces").length > 0) {
			throw new Error(
				"Other host edit loops own namespaces in this shared cluster. Stop each JVM and run dev:kubernetes:worktree:reset in each worktree before resetting the cluster.",
			);
		}
	}
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
			packaged: { type: "boolean", default: false },
			namespace: { type: "string" },
			images: { type: "string" },
			commit: { type: "string" },
			"external-postgresql-cidr": { type: "string" },
			"pod-cidr": { type: "string" },
			gateway: { type: "boolean", default: false },
			set: { type: "string", multiple: true, default: [] },
			profile: { type: "string", multiple: true, default: ["dev"] },
			assets: { type: "string" },
			lock: { type: "string" },
			release: { type: "string" },
			architecture: { type: "string" },
			receipt: { type: "string" },
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
		case "install":
		case "upgrade": {
			const settings = values.set.flatMap((setting) => ["--set", setting]);
			const upgrade = async () => install(work, settings, values.profile);
			await (command === "upgrade" ? keepsStatefulPods(upgrade) : upgrade());
			break;
		}
		case "live": {
			await qualifySandboxes(values.packaged, values.namespace);
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
			process.env.KUBECONFIG = await requireDevCluster(work);
			if (!(await ownsDevNamespace(process.env.KUBECONFIG, NAMESPACE, ROOT))) {
				break;
			}
			// Keep the database available until the server's drain has ended.
			const server = await kubectl([
				"-n",
				NAMESPACE,
				"get",
				"deployment",
				`${RELEASE}-server`,
				"--ignore-not-found",
				"-o",
				"name",
			]);
			if (server.trim() !== "") {
				await kubectl(["-n", NAMESPACE, "scale", `deployment/${RELEASE}-server`, "--replicas=0"]);
				const listed = await kubectl([
					"-n",
					NAMESPACE,
					"get",
					"pods",
					"-l",
					`app.kubernetes.io/instance=${RELEASE},app.kubernetes.io/component=server`,
					"-o",
					"name",
				]);
				const pods = listed.trim().split("\n").filter(Boolean);
				if (pods.length > 0) {
					await kubectl(["-n", NAMESPACE, "wait", "--for=delete", ...pods, "--timeout=15m"]);
				}
			}
			await kubectl([
				"delete",
				"namespace",
				SANDBOXES,
				"--ignore-not-found",
				"--wait=true",
				"--timeout=5m",
			]);
			await run("helm", [
				"uninstall",
				RELEASE,
				"-n",
				NAMESPACE,
				"--ignore-not-found",
				"--wait",
				"--timeout=5m",
			]);
			await kubectl(["annotate", "namespace", NAMESPACE, `${DEV_OWNER}-`]);
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
		case "turn-roll": {
			const cidr = values["pod-cidr"];
			if (cidr === undefined) {
				throw new Error(
					"turn-roll needs --pod-cidr <the cluster's Pod CIDR>, for the fake model provider's egress rule",
				);
			}
			await turnRoll(
				work,
				cidr,
				values.set.flatMap((setting) => ["--set", setting]),
				values.profile,
			);
			break;
		}
		case "render": {
			process.stdout.write(await render());
			break;
		}
		case "gvisor": {
			await gvisor(work);
			break;
		}
		case "release-smoke": {
			const { assets, lock, release, architecture } = values;
			if (
				assets === undefined ||
				lock === undefined ||
				release === undefined ||
				architecture === undefined
			) {
				throw new Error(
					"release-smoke needs --assets <directory of the unpacked k3s bundle> --lock <joint lock> --release <vX.Y.Z> --architecture <amd64|arm64>",
				);
			}
			await releaseSmoke({
				work,
				assets,
				lock,
				release,
				architecture,
				...(values.receipt === undefined ? {} : { receipt: values.receipt }),
			});
			break;
		}
		default: {
			throw new Error(
				"usage: kubernetes-cluster <prepare|gateway|install|upgrade|verify|request|backup-drill|turn-roll|render|gvisor|live|dev|down|reset|release-smoke> [--work dir]",
			);
		}
	}
}
