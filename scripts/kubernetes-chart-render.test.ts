/**
 * Chart behaviour, read from what Helm actually renders.
 *
 * Each case runs `helm template` on the real chart, a real profile, values that kubernetes-chart.ts
 * prepares from a synthetic lock over the real release image inventory, and operator values that
 * name a Secret but hold no secret value. The assertions read the parsed resources. A render is not
 * a cluster: admission, network enforcement, volume start-up and upgrades stay with the cluster tests.
 *
 * The chart source names no image, so a source render passes the prepared values as a CI run
 * passes its own. One case renders a release package instead, which pins its images itself.
 *
 * The sandboxes render only with the names of what an operator qualified on the sandbox nodes. These
 * cases pass synthetic names: a render proves which names and which authority the server receives,
 * never that a node loads a profile or enforces a process limit or a policy.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { appendFile, cp, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { isDeepStrictEqual } from "node:util";
import { parseAllDocuments } from "yaml";
import { bundle as generatedBundle, CONTROLLER } from "./cluster-admin-bundle.ts";
import { grants as ruleGrants } from "./cluster-preflight.ts";
import {
	adminBundle,
	assertBounded,
	assertReferencesResolve,
	byCodePoint,
	chart,
	componentOf,
	container,
	containers,
	digest,
	env,
	envValue,
	external,
	get,
	imageOf,
	kindOf,
	kubernetesApi,
	type Layer,
	layersFor,
	list,
	mail,
	named,
	nameOf,
	operator,
	podSpec,
	podTemplates,
	prepared,
	profile,
	profiles,
	render,
	rendered,
	reviews,
	sandboxLayer,
	sandboxNamespace,
	selects,
	serverOf,
	stackOn,
	strategy,
	withSandboxes,
	workload,
	workloads,
} from "./kubernetes-chart-render-support.ts";
import { isRecord } from "./lib/json.ts";
import { packageReleaseChart } from "./release-chart.ts";

await test("a release package runs the release's images with the operator's values alone", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-chart-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	// The release's own packaging, with the real Helm: it also reads the package back through
	// `helm show chart` and `helm show values` and refuses one that does not pin these images.
	const packaged = await packageReleaseChart(prepared.release.version, chart, prepared, directory);
	const resources = rendered([profile("small"), operator, reviews], { source: packaged });
	for (const [component, image] of Object.entries(imageOf)) {
		for (const entry of containers(workload(resources, component))) {
			assert.equal(get(entry, "image"), prepared.images[image], `${component} image`);
		}
	}
	const server = workload(resources, "server");
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_IMAGE_REFERENCE"), prepared.images["agent-pi"]);
	assert.equal(envValue(server, "HEPHAESTUS_RELEASE_COMMIT"), prepared.release.commit);
	assert.equal(envValue(server, "APP_VERSION"), "1.2.3");
	// The chart source alone still refuses to render without a run's images.
	assert.match(render([profile("small"), operator, reviews]).stderr, /images|release/u);
});

await test("a release package refuses a later quoted images key, which Helm would render", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-chart-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const source = path.join(directory, "hephaestus");
	await cp(chart, source, { recursive: true });
	const other = { ...prepared.images, webapp: `ghcr.io/other/webapp@${digest("d")}` };
	await appendFile(path.join(source, "values.yaml"), `"images": ${JSON.stringify(other)}\n`);
	await assert.rejects(
		packageReleaseChart(prepared.release.version, source, prepared, directory),
		/does not pin the v1\.2\.3 images/u,
	);
});

await test("small: a rolling server can finish its drain and start within the upgrade's Helm timeout", () => {
	const server = workload(rendered([...layersFor("small"), sandboxLayer]), "server");
	assert.equal(get(server, "spec", "strategy", "type"), "RollingUpdate");
	// Past the timeout, Helm rolls the release back, also after its migration ran.
	const installer = readFileSync(
		new URL("../deploy/platform/k3s-install.sh", import.meta.url),
		"utf8",
	);
	const timeout = /--wait-for-jobs --timeout (?<minutes>\d+)m\b/u.exec(installer)?.groups?.minutes;
	const stop = Number(get(podSpec(server), "terminationGracePeriodSeconds"));
	const probe = get(container(server), "startupProbe");
	const start = Number(get(probe, "periodSeconds")) * Number(get(probe, "failureThreshold"));
	assert.ok(stop + start <= Number(timeout) * 60, `${stop} s to stop, ${start} s to start`);
	assert.ok(Number(get(server, "spec", "progressDeadlineSeconds")) >= stop + start);
});

await test("admin assurance follows the operator's value and defaults to on", () => {
	assert.equal(
		envValue(serverOf(layersFor("small")), "HEPHAESTUS_AUTH_ASSURANCE_ENFORCED"),
		"true",
	);
	assert.equal(
		envValue(serverOf(layersFor("preview")), "HEPHAESTUS_AUTH_ASSURANCE_ENFORCED"),
		"true",
	);
	assert.equal(
		envValue(
			serverOf([...layersFor("small"), { config: { auth: { assuranceEnforced: false } } }]),
			"HEPHAESTUS_AUTH_ASSURANCE_ENFORCED",
		),
		"false",
	);
});

await test("the longest release names render bounded, distinct and consistent objects", () => {
	// Helm accepts release names of up to 53 characters. These two differ only in the last one.
	const releases = [`${"r".repeat(52)}a`, `${"r".repeat(52)}b`];
	const names = new Map<string, Set<string>>();
	for (const release of releases) {
		const resources = rendered([...layersFor("small"), sandboxLayer], { release, namespace: "h" });
		const podLabels = [
			...workloads(resources),
			...resources.filter((candidate) => kindOf(candidate) === "Job"),
		].map((resource) => {
			const labels = get(resource, "spec", "template", "metadata", "labels");
			// The StatefulSet controller names its first pod "<name>-0" and labels it with that name.
			return kindOf(resource) === "StatefulSet" && isRecord(labels)
				? { ...labels, "statefulset.kubernetes.io/pod-name": `${nameOf(resource)}-0` }
				: labels;
		});
		// Sandbox objects carry the release's name, as every other object does.
		names.set(
			release,
			new Set(
				resources.map((resource) => {
					const namespace = get(resource, "metadata", "namespace");
					assert.ok(
						namespace === undefined || typeof namespace === "string",
						"a resource namespace is a string when set",
					);
					return `${String(kindOf(resource))}/${namespace ?? ""}/${nameOf(resource)}`;
				}),
			),
		);
		for (const resource of resources) {
			const label = `${String(kindOf(resource))} ${nameOf(resource)}`;
			// The full release identity stays in the labels, whatever the name became.
			assertBounded(resource, release, label);
			assertReferencesResolve(resources, podLabels, resource, label);
		}
		// The roles reach the bundled services under the names those Services received.
		const database = /^postgresql:\/\/(?<service>[^.:/]+)\.h\.svc:5432\//u.exec(
			String(envValue(workload(resources, "server"), "DATABASE_URL")),
		)?.groups?.service;
		assert.ok(named(resources, "Service", database), `database host ${String(database)}`);
		// The sandboxes' gateway policy in the sandbox namespace carries the release's name.
		assert.ok(
			resources.some(
				(resource) =>
					kindOf(resource) === "NetworkPolicy" &&
					get(resource, "metadata", "namespace") === sandboxNamespace &&
					componentOf(resource) === "sandbox" &&
					nameOf(resource).startsWith(release.slice(0, 20)),
			),
			"gateway policy",
		);
	}
	const [first = new Set<string>(), second = new Set<string>()] = releases.map(
		(release) => names.get(release) ?? new Set<string>(),
	);
	assert.equal(first.size, second.size);
	for (const name of first) {
		assert.equal(second.has(name), false, `both releases render ${name}`);
	}
});

await test("the server receives no stream settings: the receiver stores into the provider event inbox", () => {
	const server = workload(rendered(layersFor("small")), "server");
	for (const variable of list(get(container(server), "env")).map((entry) =>
		String(get(entry, "name")),
	)) {
		assert.doesNotMatch(
			variable,
			/^HEPHAESTUS_WEBHOOK_STREAM_|^HEPHAESTUS_INTEGRATION_CONSUMER_INACTIVE/u,
		);
	}
	assert.match(
		render([...layersFor("small"), { webhook: { stream: { maxAge: "7d" } } }]).stderr,
		/webhook/u,
	);
});

await test("email follows the relay's TLS mode, and authenticates only with a username", () => {
	const relay = { host: "smtp.example.com", from: "hephaestus@example.com" };
	assert.deepEqual(mail({ ...relay, username: "hephaestus" }), ["true", "false", "true", "true"]);
	assert.deepEqual(mail({ ...relay, port: 465, tls: "implicit", username: "hephaestus" }), [
		"true",
		"true",
		"false",
		"false",
	]);
	// An internal relay that takes no credentials.
	assert.deepEqual(mail(relay), ["false", "false", "true", "true"]);
});

await test("the server's operator settings reach the server alone", () => {
	const settings = [
		"HEPHAESTUS_RESEARCH_ORGANIZATION",
		"HEPHAESTUS_WORKSPACE_CREATIONPOLICY",
		"HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED",
		"HEPHAESTUS_PUBLIC_ACTIVITY_REPOSITORY_VISIBILITY_MAX_AGE",
		"HEPHAESTUS_LLM_DISPLAY_CURRENCY",
		"HEPHAESTUS_AUTH_PASSKEYS_INSTANCE_ADMIN_REQUIRED",
		"HEPHAESTUS_AUTH_PASSKEYS_WORKSPACE_ADMIN_REQUIRED",
		"HEPHAESTUS_AUTH_PASSKEYS_RP_ID",
		"HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS",
		"SYNC_RUN_ON_STARTUP",
		"SYNC_TIMEFRAME_DAYS",
		"SYNC_CRON",
		"SYNC_COOLDOWN_MINUTES",
	];
	// The application's defaults under the prod profile (application.yml, application-prod.yml).
	const defaults = workload(rendered(layersFor("small")), "server");
	assert.deepEqual(
		settings.map((name) => envValue(defaults, name)),
		[
			"",
			"ADMIN_ONLY",
			"false",
			"48h",
			"",
			"true",
			"false",
			"",
			"",
			"true",
			"7",
			"0 0 * * * *",
			"60",
		],
	);
	const resources = rendered([
		...layersFor("small"),
		{
			config: {
				researchOrganization: "A research group",
				workspaceCreationPolicy: "SELF_SERVICE",
				publicActivity: { enabled: true, repositoryVisibilityMaxAge: "12h" },
				llmDisplayCurrency: "EUR",
				auth: {
					passkeys: {
						instanceAdminRequired: false,
						workspaceAdminRequired: true,
						rpId: "example.com",
						allowedOrigins: ["https://hephaestus.example.com"],
					},
				},
				sync: {
					runOnStartup: false,
					timeframeDays: 14,
					syncCron: "0 0 3 * * *",
					cooldownMinutes: 15,
				},
			},
		},
	]);
	assert.deepEqual(
		settings.map((name) => envValue(workload(resources, "server"), name)),
		[
			"A research group",
			"SELF_SERVICE",
			"true",
			"12h",
			"EUR",
			"false",
			"true",
			"example.com",
			"https://hephaestus.example.com",
			"false",
			"14",
			"0 0 3 * * *",
			"15",
		],
	);
	for (const workloadRole of ["server"]) {
		for (const retired of [
			"MONITORING_RUN_ON_STARTUP",
			"MONITORING_TIMEFRAME",
			"MONITORING_SYNC_CRON",
			"MONITORING_SYNC_COOLDOWN_IN_MINUTES",
		]) {
			assert.equal(env(workload(resources, workloadRole), retired), undefined, workloadRole);
		}
	}
});

await test("a replacement is ready before an old server drains", () => {
	for (const profileName of profiles) {
		assert.deepEqual(strategy(profileName, "server"), {
			type: "RollingUpdate",
			rollingUpdate: { maxSurge: 1, maxUnavailable: 0 },
		});
	}
	const draining = rendered([...layersFor("small"), { server: { drainTimeoutSeconds: 600 } }]);
	const server = workload(draining, "server");
	assert.equal(get(podSpec(server), "terminationGracePeriodSeconds"), 720);
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_DRAIN_TIMEOUT"), "600s");
	for (const retired of ["Recreate", "RollingUpdate"]) {
		const rejected = render([...layersFor("small"), { server: { rolloutStrategy: retired } }]);
		assert.equal(rejected.ok, false);
		assert.match(rejected.stderr, /rolloutStrategy/u);
	}
	const idle = render([...layersFor("small"), { sandbox: { enabled: false } }]);
	assert.equal(idle.ok, true, idle.stderr);
});

await test("every role that runs git writes the JGit configuration to an emptyDir, in every profile", () => {
	// The root filesystem is read-only, so JGit's default $HOME/.config cannot hold its config lock.
	for (const [name, role] of profiles.flatMap((shipped) =>
		(["server"] as const).map((candidate) => [shipped, candidate] as const),
	)) {
		const pod = workload(rendered(withSandboxes(name)), role);
		const home = String(envValue(pod, "XDG_CONFIG_HOME"));
		const emptyDirs = new Set(
			list(get(podSpec(pod), "volumes"))
				.filter((volume) => get(volume, "emptyDir") !== undefined)
				.map((volume) => get(volume, "name")),
		);
		const writable = list(get(container(pod), "volumeMounts")).filter(
			(mount) => emptyDirs.has(get(mount, "name")) && get(mount, "readOnly") !== true,
		);
		assert.ok(
			writable.some((mount) => home.startsWith(`${String(get(mount, "mountPath"))}/`)),
			`${name} ${role}: XDG_CONFIG_HOME ${home} is not on a writable emptyDir`,
		);
	}
});

await test("a followed commit reports the commit as its version, as the promotion verifies", () => {
	const commit = "c".repeat(40);
	const resources = rendered([...layersFor("small"), { release: { version: commit, commit } }]);
	assert.equal(envValue(workload(resources, "webapp"), "APPLICATION_VERSION"), commit);
	assert.equal(envValue(workload(resources, "server"), "APP_VERSION"), commit);
});

await test("every container of the application image names the release that it runs", () => {
	// The image is promoted without a rebuild, so its JAR cannot know its release. A container
	// without these values writes logs, spans and metrics with the development version.
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	const image = get(container(workload(resources, "server")), "image");
	const identities = resources.flatMap((resource) =>
		[
			...containers(resource),
			...list(get(resource, "spec", "jobTemplate", "spec", "template", "spec", "initContainers")),
			...list(get(resource, "spec", "jobTemplate", "spec", "template", "spec", "containers")),
		]
			.filter((entry) => get(entry, "image") === image)
			.map((entry) => {
				const value = (name: string) =>
					get(
						list(get(entry, "env")).find((variable) => get(variable, "name") === name),
						"value",
					);
				return {
					component: componentOf(resource),
					version: value("APP_VERSION"),
					commit: value("HEPHAESTUS_RELEASE_COMMIT"),
					environment: value("DEPLOYMENT_ENVIRONMENT"),
				};
			}),
	);
	assert.deepEqual(identities.map(({ component }) => String(component)).toSorted(byCodePoint), [
		"migration",
		"server",
	]);
	for (const entry of identities) {
		assert.deepEqual(
			{ version: entry.version, commit: entry.commit },
			{ version: "1.2.3", commit: "c".repeat(40) },
			String(entry.component),
		);
		assert.equal(typeof entry.environment, "string", String(entry.component));
	}
});

await test("the JVM roles request the memory that they may use", () => {
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	for (const component of ["server"]) {
		const resourcesOf = get(container(workload(resources, component)), "resources");
		assert.equal(
			get(resourcesOf, "requests", "memory"),
			get(resourcesOf, "limits", "memory"),
			component,
		);
	}
});

await test("no container holds text that Kubernetes rewrites, beyond the references it means", () => {
	// Kubernetes replaces $(NAME) with the container's variable NAME and $$ with $ in a command, its arguments and
	// each variable's value. A script inlined there, such as SQL with $$ quotes, then runs changed: the chart ships
	// scripts as files.
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const backup = { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 };
	const renders = [
		...profiles.map((name) => rendered([...layersFor(name), sandboxLayer])),
		rendered([
			...layersFor("small"),
			sandboxLayer,
			stackOn,
			{ postgresql: { metrics: { enabled: true }, backup } },
		]),
		rendered([
			...layersFor("small"),
			sandboxLayer,
			{ postgresql: { backup: { ...backup, restore: { enabled: true } } } },
		]),
	];
	// The one reference that the chart makes on purpose: the management listener binds to the Pod's address.
	const intended = new Set(["env MANAGEMENT_SERVER_ADDRESS: $(POD_IP)"]);
	const texts = renders
		.flatMap(podTemplates)
		.flatMap((spec) => [...list(get(spec, "initContainers")), ...list(get(spec, "containers"))])
		.flatMap((entry) => [
			...[...list(get(entry, "command")), ...list(get(entry, "args"))].map((text) => ({
				where: "command",
				text: String(text),
			})),
			...list(get(entry, "env"))
				.filter((variable) => typeof get(variable, "value") === "string")
				.map((variable) => ({
					where: `env ${String(get(variable, "name"))}`,
					text: String(get(variable, "value")),
				})),
		]);
	const found = texts.flatMap(({ where, text }) =>
		[...text.matchAll(/\$\$|\$\([A-Za-z_][A-Za-z0-9_]*\)/gu)].map(
			(match) => `${where}: ${match[0]}`,
		),
	);
	assert.ok(
		texts.some(({ where }) => where === "command"),
		"the renders hold commands",
	);
	assert.deepEqual(
		found.filter((reference) => !intended.has(reference)),
		[],
	);
	// A reference that is no longer made leaves the list, so that the list names only what the chart means.
	assert.deepEqual(
		[...intended].filter((reference) => !found.includes(reference)),
		[],
	);
});

const withSandbox = (change: object): Layer[] => [...layersFor("small"), sandboxLayer, change];

await test("values that the chart does not accept fail the render", () => {
	const withoutPrepared = [profile("small"), operator];
	const { host: _host, ...withoutHost } = operator;
	const { secret: _secret, ...withoutSecret } = operator;
	const { trustedProxies: _trustedProxies, ...withoutProxies } = operator;
	const cases: [string, Layer[], RegExp][] = [
		[
			"an image tag",
			[...layersFor("small"), { images: { webapp: "ghcr.io/hephaestus-build/webapp:latest" } }],
			/images[./]webapp/u,
		],
		["values without the prepared lock file", withoutPrepared, /images|release/u],
		[
			"a generic environment hatch",
			[...layersFor("small"), { server: { extraEnv: [{ name: "X", value: "y" }] } }],
			/extraEnv/u,
		],
		[
			"a pod spec hatch",
			[...layersFor("small"), { webapp: { podSpec: { hostNetwork: true } } }],
			/podSpec/u,
		],
		[
			"network policy turned off",
			[...layersFor("small"), { networkPolicy: { enabled: false } }],
			/enabled/u,
		],
		["no Gateway parent", [...layersFor("small"), { route: { parentRefs: [] } }], /parentRefs/u],
		...(["pods", "cpu", "memory"] as const).map((key): [string, Layer[], RegExp] => [
			`a hand-set sandbox quota (${key})`,
			[...layersFor("small"), { sandbox: { quota: { [key]: key === "pods" ? 10 : "8" } } }],
			/quota/u,
		]),
		[
			"an operator class missing for a sandbox tier",
			[
				...layersFor("small"),
				{ priorityClasses: { create: false, roles: "a", conversations: "", reviews: "c" } },
			],
			/priorityClasses[./]conversations/u,
		],
		[
			"one operator class for both sandbox tiers",
			[
				...layersFor("small"),
				{ priorityClasses: { create: false, roles: "a", conversations: "b", reviews: "b" } },
			],
			/must differ/u,
		],
		[
			"a Gateway parent without a name",
			[...layersFor("small"), { route: { parentRefs: [{ name: "" }] } }],
			/parentRefs[./]0[./]name/u,
		],
		["no host", [profile("small"), prepared, withoutHost], /host/u],
		["no Secret name", [profile("small"), prepared, withoutSecret], /secret[./]name/u],
		["no proxy trust", [profile("small"), prepared, withoutProxies], /trustedProxies/u],
		// Helm merges maps, so only null removes the labels that an earlier layer set.
		[
			"a whole Gateway namespace",
			[...layersFor("small"), { networkPolicy: { gateway: { podLabels: null } } }],
			/podLabels/u,
		],
		[
			"a whole scraper namespace",
			[
				...layersFor("small"),
				{ networkPolicy: { metrics: { namespace: "monitoring", podLabels: {} } } },
			],
			/networkPolicy\.metrics\.podLabels needs at least one label/u,
		],
		[
			"a whole trace collector namespace",
			[
				...layersFor("small"),
				{ networkPolicy: { traceCollector: { namespace: "observability", podLabels: {} } } },
			],
			/networkPolicy\.traceCollector\.podLabels needs at least one label/u,
		],
		[
			"a zero CPU limit",
			[...layersFor("small"), { server: { resources: { limits: { cpu: "0" } } } }],
			/cpu/u,
		],
		[
			"an external database without a host",
			[
				profile("small"),
				prepared,
				operator,
				reviews,
				{ ...external, postgresql: { bundled: false, external: { cidr: "10.0.12.0/24" } } },
			],
			/postgresql\.external\.host is required/u,
		],
		// The single-node profile runs reviews, so an install that skips either sandbox value stops.
		[
			"the single-node profile without a sandbox namespace",
			[profile("small"), prepared, operator, { networkPolicy: { kubernetesApi } }],
			/sandbox\.namespace is required/u,
		],
		[
			"the single-node profile without the Kubernetes API endpoint",
			[profile("small"), prepared, operator, { sandbox: { namespace: sandboxNamespace } }],
			/networkPolicy\.kubernetesApi\.cidr is required/u,
		],
		[
			"a retired server rollout setting",
			withSandbox({ server: { rolloutStrategy: "Recreate" } }),
			/server.*rolloutStrategy/u,
		],
		[
			"no PostgreSQL TLS Secret",
			[...layersFor("small"), { postgresql: { tls: { existingSecret: "" } } }],
			/postgresql\.tls\.existingSecret is required/u,
		],
		[
			"no PostgreSQL TLS Secret for an external database",
			[...layersFor("external"), { postgresql: { tls: { existingSecret: "" } } }],
			/postgresql\.tls\.existingSecret is required/u,
		],
		[
			"an external database without certificate verification",
			[...layersFor("external"), { postgresql: { external: { sslmode: "require" } } }],
			/sslmode/u,
		],
		[
			"an external database in plain text",
			[...layersFor("external"), { postgresql: { external: { sslmode: "disable" } } }],
			/sslmode/u,
		],
		[
			"sandboxes without the API port",
			withSandbox({ networkPolicy: { kubernetesApi: { port: 0 } } }),
			/networkPolicy\.kubernetesApi\.port is required/u,
		],
		[
			"sandboxes without their namespace",
			withSandbox({ sandbox: { namespace: "" } }),
			/sandbox\.namespace is required/u,
		],
		[
			"sandboxes in the release namespace",
			withSandbox({ sandbox: { namespace: "hephaestus" } }),
			/sandbox\.namespace must not be the release namespace/u,
		],
		[
			"a backup of an external database",
			[
				...layersFor("external"),
				{
					postgresql: {
						backup: {
							clientTlsSecret: "hephaestus-postgresql-backup-tls",
							enabled: true,
							s3: { bucket: "backups", endpoint: "s3.example.com", region: "eu" },
						},
					},
				},
			],
			/postgresql\.backup\.enabled needs postgresql\.bundled/u,
		],
		[
			"a backup without its bucket",
			[
				...layersFor("small"),
				{
					postgresql: {
						backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true },
					},
				},
			],
			/postgresql\.backup\.s3\.bucket is required/u,
		],
		[
			"a seccomp profile outside the kubelet's directory",
			withSandbox({ sandbox: { runtime: { seccompProfile: "../agent.json" } } }),
			/seccompProfile/u,
		],
	];
	for (const [label, layers, message] of cases) {
		const result = render(layers);
		assert.equal(result.ok, false, `${label} rendered`);
		assert.match(result.stderr, message, label);
	}
});

await test("a private CA reaches the roles that call a Git provider, and is off by default", () => {
	const caFile = "/etc/hephaestus/private-ca/ca.crt";
	const resources = rendered([
		...layersFor("small"),
		{ config: { privateCa: { existingSecret: "corp-ca", key: "bundle.pem" } } },
	]);
	for (const component of ["server"]) {
		const role = workload(resources, component);
		assert.equal(envValue(role, "SSL_CERT_FILE"), caFile, component);
		const volumes = list(get(role, "spec", "template", "spec", "volumes"));
		const volume = volumes.find((candidate) => get(candidate, "name") === "private-ca");
		assert.equal(get(volume, "secret", "secretName"), "corp-ca", component);
		assert.equal(get(list(get(volume, "secret", "items"))[0], "key"), "bundle.pem");
	}
	const defaults = rendered(layersFor("small"));
	for (const component of ["server"]) {
		assert.equal(envValue(workload(defaults, component), "SSL_CERT_FILE"), undefined, component);
	}
});

await test("the migration knows the configured GitLab, which a connection without a URL used", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			config: {
				gitlab: { enabled: true, oauthClientId: "id", oauthBaseUrl: "https://gitlab.corp.example" },
			},
		},
	]);
	const job = resources.find(
		(resource) =>
			kindOf(resource) === "Job" && /migration/u.test(String(get(resource, "metadata", "name"))),
	);
	const variables = list(get(list(get(job, "spec", "template", "spec", "containers"))[0], "env"));
	assert.equal(
		get(
			variables.find((entry) => get(entry, "name") === "GITLAB_DEFAULT_SERVER_URL"),
			"value",
		),
		"https://gitlab.corp.example",
	);
});

await test("a Git provider on a private network reaches the server", () => {
	const endpoint = { cidr: "10.30.0.0/16", ports: [443] };
	const resources = rendered([
		...layersFor("small"),
		{
			config: { scmPrivateHosts: ["gitlab.corp.example"] },
			networkPolicy: { scmEndpoints: [endpoint] },
		},
	]);
	const rule = {
		to: [{ ipBlock: { cidr: endpoint.cidr } }],
		ports: [{ port: 443, protocol: "TCP" }],
	};
	for (const component of ["server"]) {
		assert.equal(
			envValue(workload(resources, component), "HEPHAESTUS_SCM_EGRESS_PRIVATE_HOSTS"),
			"gitlab.corp.example",
			component,
		);
		const policy = resources.find(
			(resource) =>
				kindOf(resource) === "NetworkPolicy" &&
				get(resource, "metadata", "namespace") === undefined &&
				isDeepStrictEqual(
					get(resource, "spec", "podSelector", "matchLabels"),
					get(workload(resources, component), "spec", "selector", "matchLabels"),
				),
		);
		const egress = list(get(policy, "spec", "egress"));
		assert.equal(
			egress.some((candidate) => isDeepStrictEqual(candidate, rule)),
			true,
			component,
		);
	}
});

await test("a model endpoint on a private network reaches the server, never the metadata range", () => {
	const endpoint = { cidr: "10.20.0.0/16", ports: [8000] };
	const resources = rendered([
		...layersFor("small"),
		{
			config: {
				llmPrivateHosts: ["vllm.lab.example", "10.20.0.0/16"],
				llmPrivateHostsAllowHttp: true,
			},
			networkPolicy: { modelEndpoints: [endpoint] },
		},
	]);
	const rule = {
		to: [{ ipBlock: { cidr: endpoint.cidr } }],
		ports: [{ port: 8000, protocol: "TCP" }],
	};
	for (const component of ["server"]) {
		const role = workload(resources, component);
		assert.equal(
			envValue(role, "HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS"),
			"vllm.lab.example,10.20.0.0/16",
			component,
		);
		assert.equal(envValue(role, "HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS_ALLOW_HTTP"), "true");
		const policy = resources.find(
			(resource) =>
				kindOf(resource) === "NetworkPolicy" &&
				get(resource, "metadata", "namespace") === undefined &&
				isDeepStrictEqual(
					get(resource, "spec", "podSelector", "matchLabels"),
					get(role, "spec", "selector", "matchLabels"),
				),
		);
		const egress = list(get(policy, "spec", "egress"));
		assert.equal(
			egress.some((candidate) => isDeepStrictEqual(candidate, rule)),
			true,
			component,
		);
	}
	for (const refused of [
		{ cidr: "169.254.0.0/16", ports: [80] },
		{ cidr: "0.0.0.0/0", ports: [443] },
		{ cidr: "10.0.0.0/7", ports: [443] },
		// fd::/7 is 00fd::/7, the range that holds ::1.
		{ cidr: "fd::/7", ports: [443] },
		{ cidr: "10.20.0.0/16", ports: [] },
	]) {
		const result = render([
			...layersFor("small"),
			{ networkPolicy: { modelEndpoints: [refused] } },
		]);
		assert.equal(result.ok, false, `${refused.cidr} ${JSON.stringify(refused.ports)} rendered`);
		assert.match(result.stderr, /modelEndpoints/u, refused.cidr);
	}
});

await test("disruption budgets protect singleton stores and permit one of two servers to stop", () => {
	const resources = rendered([prepared, operator, sandboxLayer]);
	const budgets = resources.filter((resource) => kindOf(resource) === "PodDisruptionBudget");
	assert.equal(budgets.length, 2);
	// Singleton stores reject eviction; one server may stop while the other stays available.
	for (const component of ["postgresql", "server"]) {
		const budget = budgets.find((resource) => componentOf(resource) === component);
		assert.ok(budget, component);
		const spec = get(budget, "spec");
		assert.equal(get(spec, "minAvailable"), component === "server" ? undefined : 1, component);
		assert.equal(get(spec, "maxUnavailable"), component === "server" ? 1 : undefined, component);
		const selector = get(spec, "selector", "matchLabels");
		assert.deepEqual(
			selector,
			get(workload(resources, component), "spec", "selector", "matchLabels"),
		);
		for (const resource of resources.filter((item) =>
			["Deployment", "StatefulSet", "Job"].includes(String(kindOf(item))),
		)) {
			assert.equal(
				selects(selector, get(resource, "spec", "template", "metadata", "labels")),
				componentOf(resource) === component,
				`${component} budget / ${nameOf(resource)}`,
			);
		}
	}
});

await test("the singleton small server retains its voluntary-eviction protection", () => {
	const resources = rendered(layersFor("small"));
	const budget = resources.find(
		(resource) => kindOf(resource) === "PodDisruptionBudget" && componentOf(resource) === "server",
	);
	assert.equal(get(budget, "spec", "minAvailable"), 1);
	assert.equal(get(budget, "spec", "maxUnavailable"), undefined);
});

await test("chart list inputs keep their order in environment bindings for each role", () => {
	const bootstrapAdmins = ["github:1234567", "gitlab:@example"];
	const browserExtensionIds = ["a".repeat(32), "b".repeat(32)];
	const origins = ["https://first.example", "https://second.example"];
	for (const populated of [false, true]) {
		const resources = rendered([
			...layersFor("small"),
			{
				config: {
					auth: {
						bootstrapAdmins: populated ? bootstrapAdmins : [],
						browserExtensionIds: populated ? browserExtensionIds : [],
						passkeys: { allowedOrigins: populated ? origins : [] },
					},
					outline: { allowedOrigins: populated ? origins : [] },
				},
			},
		]);
		const server = workload(resources, "server");
		assert.equal(
			envValue(server, "HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS"),
			populated ? bootstrapAdmins.join(",") : "",
		);
		assert.equal(
			envValue(server, "HEPHAESTUS_AUTH_BROWSER_EXTENSION_IDS"),
			populated ? browserExtensionIds.join(",") : "",
		);
		assert.equal(
			envValue(server, "HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS"),
			populated ? origins.join(",") : "",
		);
		for (const component of ["server"]) {
			assert.equal(
				envValue(workload(resources, component), "HEPHAESTUS_INTEGRATION_OUTLINE_ALLOWED_ORIGINS"),
				populated ? origins.join(",") : "",
				component,
			);
		}
	}
});

await test("the admin bundle is the generator's output, and grants the controller that the cluster profile runs", () => {
	assert.equal(
		readFileSync(
			new URL("../deploy/platform/hephaestus-cluster-admin.yaml", import.meta.url),
			"utf8",
		),
		generatedBundle(),
		"run node scripts/cluster-admin-bundle.ts",
	);
	// The release and namespace that the admin guide installs.
	const resources = rendered(layersFor("cluster"), {
		release: "hephaestus",
		namespace: "hephaestus",
	});
	const account = resources.find(
		(resource) =>
			resource.kind === "ServiceAccount" && nameOf(resource).endsWith("-agent-sandbox-controller"),
	);
	assert.ok(account);
	assert.equal(nameOf(account), CONTROLLER.name);
	const binding = adminBundle.find((resource) => resource.kind === "ClusterRoleBinding");
	assert.deepEqual(get(binding, "subjects"), [
		{ kind: "ServiceAccount", name: CONTROLLER.name, namespace: CONTROLLER.namespace },
	]);
	// The controller runs the image of the pinned platform manifest, the one that scan-platform-images checks.
	const pinned = parseAllDocuments(
		readFileSync(new URL("../deploy/platform/k3s-agent-sandbox.yaml", import.meta.url), "utf8"),
	)
		.map((document) => document.toJS() as unknown)
		.find((resource) => isRecord(resource) && resource.kind === "Deployment");
	const controller = resources.find(
		(resource) => resource.kind === "Deployment" && nameOf(resource) === CONTROLLER.name,
	);
	assert.equal(
		get(controller, "spec", "template", "spec", "containers", 0, "image"),
		get(pinned, "spec", "template", "spec", "containers", 0, "image"),
	);
	// Its writes stay in Roles: the sandbox namespace for sandboxes, the release namespace for its lease.
	const roles = resources.filter(
		(resource) => resource.kind === "Role" && nameOf(resource).startsWith(CONTROLLER.name),
	);
	assert.deepEqual(
		roles
			.map((role) => {
				const namespace = get(role, "metadata", "namespace");
				return `${nameOf(role)} ${typeof namespace === "string" ? namespace : "release"}`;
			})
			.toSorted(),
		[`${CONTROLLER.name} ${sandboxNamespace}`, `${CONTROLLER.name}-election release`],
	);
	// Elsewhere the cluster runs the controller: the small profile renders none of it.
	assert.equal(
		rendered(layersFor("small")).some((resource) =>
			nameOf(resource).includes("agent-sandbox-controller"),
		),
		false,
	);
});

await test("the controller's Roles grant exactly what the pinned controller's upstream ClusterRoles grant", () => {
	const upstream = parseAllDocuments(
		readFileSync(new URL("../deploy/platform/k3s-agent-sandbox.yaml", import.meta.url), "utf8"),
	)
		.map((document) => {
			const value: unknown = document.toJS();
			return value;
		})
		.filter((resource) => isRecord(resource) && resource.kind === "ClusterRole")
		.flatMap((role) => ruleGrants(role));
	const ours = rendered(layersFor("cluster"))
		.filter(
			(resource) =>
				resource.kind === "Role" && nameOf(resource).startsWith("heph-agent-sandbox-controller"),
		)
		.flatMap((role) => ruleGrants(role));
	assert.deepEqual([...new Set(ours)].toSorted(), [...new Set(upstream)].toSorted());
});

await test("every profile renders without broker objects, images, ports or credentials", () => {
	for (const name of profiles) {
		const resources = rendered(layersFor(name));
		for (const resource of resources) {
			assert.doesNotMatch(
				JSON.stringify(resource),
				/nats|jetstream/iu,
				`${name}: ${String(kindOf(resource))}`,
			);
			const ports = [
				...list(get(resource, "spec", "ports")),
				...list(get(resource, "spec", "ingress")).flatMap((rule) => list(get(rule, "ports"))),
				...list(get(resource, "spec", "egress")).flatMap((rule) => list(get(rule, "ports"))),
			];
			for (const port of ports) {
				assert.ok(![4222, 8222].includes(Number(get(port, "port"))), name);
			}
		}
		for (const resource of workloads(resources)) {
			for (const entry of containers(resource)) {
				for (const port of list(get(entry, "ports"))) {
					assert.ok(![4222, 8222].includes(Number(get(port, "containerPort"))), name);
				}
			}
		}
	}
});

await test("retired broker settings are rejected instead of silently ignored", () => {
	for (const retired of [{ nats: { bundled: true } }, { images: { nats: "retired" } }]) {
		const result = render([...layersFor("small"), retired]);
		assert.equal(result.ok, false);
		assert.match(result.stderr, /additional properties 'nats' not allowed/iu);
	}
});
