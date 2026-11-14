import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";
import {
	amount,
	assertCaOnly,
	assertDatabaseSetup,
	bundled,
	byCodePoint,
	chart,
	componentOf,
	container,
	containers,
	databaseSetupDirectory,
	databaseUserOf,
	env,
	envValue,
	external,
	get,
	installNotes,
	javaCapacityConstant,
	kindOf,
	type Layer,
	layersFor,
	list,
	maxConnections,
	memoryFor,
	migrationJobOf,
	mountsS3Ca,
	named,
	nameOf,
	operator,
	podSpec,
	podTemplate,
	prepared,
	profile,
	profiles,
	render,
	rendered,
	type Resource,
	reviews,
	sandboxLayer,
	sandboxPods,
	secretKeysOf,
	secretName,
	tlsSecret,
	verifyFull,
	withSandboxes,
	workload,
	workloads,
} from "./kubernetes-chart-render-support.ts";
import { isRecord } from "./lib/json.ts";

await test("every role verifies the bundled PostgreSQL at its Service name with the CA alone", () => {
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	const service = resources.find(
		(resource) => kindOf(resource) === "Service" && componentOf(resource) === "postgresql",
	);
	assert.ok(service, "the PostgreSQL Service");
	const url = `postgresql://${nameOf(service)}.hephaestus.svc:5432/hephaestus?${verifyFull}`;
	for (const component of ["server"]) {
		const role = workload(resources, component);
		assert.equal(envValue(role, "DATABASE_URL"), url, component);
		assertCaOnly(role, component);
	}

	// The image's own entrypoint starts the server with TLS, from the certificate and key alone.
	const postgresql = workload(resources, "postgresql");
	const entry = container(postgresql);
	assert.equal(get(entry, "command"), undefined);
	assert.deepEqual(list(get(entry, "args")).slice(0, 6), [
		"-c",
		"ssl=on",
		"-c",
		"ssl_cert_file=/etc/postgresql/tls/tls.crt",
		"-c",
		"ssl_key_file=/etc/postgresql/tls/tls.key",
	]);
	assert.deepEqual(
		list(get(entry, "volumeMounts")).filter((mount) => get(mount, "name") === "tls"),
		[{ name: "tls", mountPath: "/etc/postgresql/tls", readOnly: true }],
	);
	assert.deepEqual(
		list(get(podSpec(postgresql), "volumes")).filter(
			(volume) => get(volume, "secret") !== undefined,
		),
		[
			{
				name: "tls",
				secret: {
					secretName: tlsSecret,
					items: [
						{ key: "tls.crt", path: "tls.crt" },
						{ key: "tls.key", path: "tls.key" },
					],
					defaultMode: 288,
				},
			},
		],
	);
	const security = get(podSpec(postgresql), "securityContext");
	assert.deepEqual(
		[get(security, "runAsUser"), get(security, "runAsGroup"), get(security, "fsGroup")],
		[999, 999, 999],
	);
	assert.equal(get(entry, "securityContext", "readOnlyRootFilesystem"), true);
	// No other pod projects the server's key.
	assert.deepEqual(
		workloads(resources)
			.filter((resource) =>
				list(get(podSpec(resource), "volumes")).some((volume) =>
					list(get(volume, "secret", "items")).some((item) => get(item, "key") === "tls.key"),
				),
			)
			.map(componentOf),
		["postgresql"],
	);
});

const checksum = (layers: readonly Layer[]) =>
	get(
		workload(rendered(layers), "postgresql"),
		"spec",
		"template",
		"metadata",
		"annotations",
		"checksum/backup",
	);

await test("backups archive the WAL from PostgreSQL and read its data through mutual TLS only", () => {
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small"),
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	]);
	const postgresql = workload(resources, "postgresql");
	const [database, server] = list(get(podSpec(postgresql), "containers"));
	assert.ok(
		list(get(database, "args")).includes(
			"archive_command=pgbackrest --stanza=hephaestus archive-push %p",
		),
	);
	assert.deepEqual(get(server, "command"), ["pgbackrest", "server"]);
	// The repository's credentials reach both sides by reference only.
	for (const side of [database, server]) {
		for (const [variable, key] of [
			["PGBACKREST_REPO1_S3_KEY", "BACKUP_S3_KEY"],
			["PGBACKREST_REPO1_S3_KEY_SECRET", "BACKUP_S3_KEY_SECRET"],
			["PGBACKREST_REPO1_CIPHER_PASS", "BACKUP_CIPHER_PASS"],
		] as const) {
			const entry = list(get(side, "env")).find((candidate) => get(candidate, "name") === variable);
			assert.deepEqual(
				get(entry, "valueFrom", "secretKeyRef"),
				{ name: secretName, key },
				variable,
			);
		}
	}
	const jobs = resources.filter((resource) =>
		["CronJob", "Job"].includes(String(kindOf(resource))),
	);
	assert.deepEqual(
		jobs.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`).toSorted(byCodePoint),
		[
			"CronJob/heph-postgresql-backup-diff",
			"CronJob/heph-postgresql-backup-full",
			"Job/heph-migration-1",
			"Job/heph-postgresql-backup-stanza-1",
		],
	);
	for (const job of jobs.filter((resource) => kindOf(resource) === "CronJob")) {
		assert.equal(get(job, "spec", "concurrencyPolicy"), "Forbid");
		assert.equal(get(job, "spec", "timeZone"), "Etc/UTC");
		const pod = get(job, "spec", "jobTemplate", "spec", "template", "spec");
		assert.equal(get(pod, "automountServiceAccountToken"), false);
		// The backup Pods mount no PostgreSQL volume: they read through the TLS server.
		assert.equal(
			list(get(pod, "volumes")).some(
				(volume) => get(volume, "persistentVolumeClaim") !== undefined,
			),
			false,
		);
		const keys = list(get(pod, "volumes"))
			.flatMap((volume) => list(get(volume, "secret", "items")))
			.map((item) => String(get(item, "key")))
			.toSorted(byCodePoint);
		assert.deepEqual(keys, ["ca.crt", "tls.crt", "tls.key"]);
	}
	const config = resources.find(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "postgresql-backup",
	);
	assert.match(
		String(get(config, "data", "server.conf")),
		/^tls-server-auth=hephaestus-backup=hephaestus$/mu,
	);
	assert.match(
		String(get(config, "data", "client.conf")),
		/^pg1-host=heph-postgresql\.hephaestus\.svc$/mu,
	);
	assert.match(
		String(get(config, "data", "client.conf")),
		/^repo1-s3-bucket=hephaestus-backups$/mu,
	);
	assert.doesNotMatch(JSON.stringify(config), /key-secret|cipher-pass/u);
	// PostgreSQL admits the TLS server's port from the backup Jobs only.
	const policy = resources.find(
		(resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-postgresql",
	);
	const backupIngress = list(get(policy, "spec", "ingress")).find(
		(rule) => get(rule, "ports", 0, "port") === 8432,
	);
	assert.equal(
		get(backupIngress, "from", 0, "podSelector", "matchLabels", "app.kubernetes.io/component"),
		"postgresql-backup",
	);
	assert.deepEqual(get(policy, "spec", "policyTypes"), ["Ingress", "Egress"]);
	// The backup Jobs reach the TLS server through PostgreSQL's Service.
	const service = resources.find(
		(resource) => kindOf(resource) === "Service" && nameOf(resource) === "heph-postgresql",
	);
	assert.deepEqual(
		list(get(service, "spec", "ports")).map((port) => get(port, "port")),
		[5432, 8432],
	);
	// A change of the repository restarts PostgreSQL, whose archive command reads it.
	const bucket = {
		postgresql: {
			backup: {
				clientTlsSecret: "hephaestus-postgresql-backup-tls",
				enabled: true,
				s3: { ...s3, bucket: "other-backups" },
			},
		},
	};
	assert.equal(
		typeof checksum([
			...layersFor("small"),
			{
				postgresql: {
					backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
				},
			},
		]),
		"string",
	);
	assert.notEqual(
		checksum([
			...layersFor("small"),
			{
				postgresql: {
					backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
				},
			},
		]),
		checksum([...layersFor("small"), bucket]),
	);
});

await test("a restore stops PostgreSQL and the database roles, suspends the backups, and replays to the asked time", () => {
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const enabled = [
		...layersFor("small"),
		sandboxLayer,
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	];
	const time = "2026-10-24 19:25:31+00";
	const restoring = rendered([
		...enabled,
		{ postgresql: { backup: { restore: { enabled: true, time } } } },
	]);
	for (const component of ["postgresql", "server"]) {
		assert.equal(get(workload(restoring, component), "spec", "replicas"), 0, component);
	}
	const jobs = restoring.filter((resource) =>
		["CronJob", "Job"].includes(String(kindOf(resource))),
	);
	// No stanza Job: it would reach a PostgreSQL that does not run.
	assert.deepEqual(
		jobs.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`).toSorted(byCodePoint),
		[
			"CronJob/heph-postgresql-backup-diff",
			"CronJob/heph-postgresql-backup-full",
			"Job/heph-postgresql-backup-restore-1",
		],
	);
	for (const job of jobs.filter((resource) => kindOf(resource) === "CronJob")) {
		assert.equal(get(job, "spec", "suspend"), true, nameOf(job));
	}
	const restore = jobs.find((resource) => kindOf(resource) === "Job");
	const [entry] = list(get(podSpec(restore), "containers"));
	assert.equal(
		get(
			list(get(entry, "env")).find((variable) => get(variable, "name") === "RESTORE_TIME"),
			"value",
		),
		time,
	);
	assert.ok(list(get(entry, "command")).some((part) => String(part).includes("--type=time")));
	// The restore Job alone mounts PostgreSQL's volume.
	assert.deepEqual(
		list(get(podSpec(restore), "volumes"))
			.map((volume) => get(volume, "persistentVolumeClaim", "claimName"))
			.filter((claim) => claim !== undefined),
		["data-heph-postgresql-0"],
	);

	// Ending the restore starts the release again with its backups.
	const running = rendered(enabled);
	for (const component of ["postgresql", "server"]) {
		assert.equal(get(workload(running, component), "spec", "replicas"), 1, component);
	}
	for (const job of running.filter((resource) => kindOf(resource) === "CronJob")) {
		assert.equal(get(job, "spec", "suspend"), false, nameOf(job));
	}
	assert.equal(named(running, "Job", "heph-postgresql-backup-restore-1"), false);
});

await test("a private S3 CA reaches every Pod that reads the repository, and no other", () => {
	const s3 = {
		bucket: "hephaestus-backups",
		endpoint: "s3.internal",
		region: "local",
		privateCa: true,
	};
	const resources = rendered([
		...layersFor("small"),
		sandboxLayer,
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	]);
	const config = resources.find(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "postgresql-backup",
	);
	for (const file of ["server.conf", "client.conf"]) {
		assert.match(
			String(get(config, "data", file)),
			/^repo1-storage-ca-file=\/etc\/hephaestus\/s3-ca\/s3-ca\.crt$/mu,
			file,
		);
	}
	const readers = [
		...workloads(resources).map((resource) => ({
			name: String(componentOf(resource)),
			spec: podSpec(resource),
		})),
		...resources
			.filter((resource) => kindOf(resource) === "CronJob")
			.map((resource) => ({
				name: nameOf(resource),
				spec: get(resource, "spec", "jobTemplate", "spec", "template", "spec"),
			})),
		...resources
			.filter((resource) => kindOf(resource) === "Job")
			.map((resource) => ({ name: nameOf(resource), spec: podSpec(resource) })),
	];
	assert.deepEqual(
		readers
			.filter((reader) => mountsS3Ca(reader.spec))
			.map((reader) => reader.name)
			.toSorted(byCodePoint),
		[
			"heph-postgresql-backup-diff",
			"heph-postgresql-backup-full",
			"heph-postgresql-backup-stanza-1",
			"postgresql",
		],
	);
});

await test("a release that changes neither image restarts PostgreSQL, in every profile", () => {
	// A Pod template that names the release would roll both on every upgrade. The application roles
	// report the release they run, so their Pods carry it on purpose.
	const next = { release: { version: "v1.2.4", commit: "b".repeat(40) } };
	for (const name of profiles.filter((candidate) => bundled(candidate))) {
		for (const component of ["postgresql"]) {
			assert.deepEqual(
				podTemplate([...withSandboxes(name), next], component),
				podTemplate(withSandboxes(name), component),
				`${name} ${component}`,
			);
		}
	}
});

await test("every revision migrates the database once, in a Job that only reaches PostgreSQL", () => {
	const first = rendered([...layersFor("small"), sandboxLayer]);
	const migration = first.find(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
	);
	assert.ok(migration, "one migration Job");
	assert.equal(nameOf(migration), "heph-migration-1");
	assert.equal(get(migration, "spec", "backoffLimit"), 0);
	const entry = container(migration);
	assert.equal(get(entry, "image"), get(container(workload(first, "server")), "image"));
	assert.equal(
		get(
			list(get(entry, "env")).find(
				(variable) => get(variable, "name") === "SPRING_PROFILES_ACTIVE",
			),
			"value",
		),
		"prod,migrate",
	);
	assert.deepEqual(
		list(get(entry, "env")).map((variable) => get(variable, "name")),
		[
			"APP_VERSION",
			"HEPHAESTUS_RELEASE_COMMIT",
			"DEPLOYMENT_ENVIRONMENT",
			"GITLAB_DEFAULT_SERVER_URL",
			"SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE",
			"SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE",
			"SPRING_PROFILES_ACTIVE",
			"DATABASE_URL",
			"DATABASE_USERNAME",
			"DATABASE_PASSWORD",
		],
	);
	// It starts once PostgreSQL accepts connections: on a first install, both start together.
	const [wait] = list(get(podSpec(migration), "initContainers"));
	const waitEnv = (name: string) =>
		list(get(wait, "env")).find((variable) => get(variable, "name") === name);
	assert.equal(get(waitEnv("DATABASE_HOST"), "value"), "heph-postgresql.hephaestus.svc");
	assert.equal(get(waitEnv("DATABASE_PORT"), "value"), "5432");
	// pg_isready runs as a user that the image's passwd does not name: without PGUSER it never dials.
	assert.equal(get(waitEnv("PGUSER"), "value"), "hephaestus_owner");
	assert.match(String(list(get(wait, "command")).at(-1)), /pg_isready/u);
	// No Helm hook: the Job is part of the release, so `--wait-for-jobs` waits for it.
	assert.equal(get(migration, "metadata", "annotations", "helm.sh/hook"), undefined);
	const policy = first.find(
		(resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-migration",
	);
	assert.deepEqual(get(policy, "spec", "policyTypes"), ["Ingress", "Egress"]);
	assert.equal(list(get(policy, "spec", "egress")).length, 1);
	assert.equal(get(policy, "spec", "egress", 0, "ports", 0, "port"), 5432);
	// The server waits for the schema; it does not migrate.
	assert.equal(envValue(workload(first, "server"), "SPRING_PROFILES_ACTIVE"), "prod");
	// A restore stops every database role, and nothing migrates the restored database.
	const restoring = rendered([
		...layersFor("small"),
		{
			postgresql: {
				backup: {
					clientTlsSecret: "hephaestus-postgresql-backup-tls",
					enabled: true,
					s3: { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" },
					restore: { enabled: true },
				},
			},
		},
	]);
	assert.equal(
		restoring.some(
			(resource) => componentOf(resource) === "migration" && kindOf(resource) === "Job",
		),
		false,
	);
});

await test("PostgreSQL containers reserve their full memory limits in bundled profiles", () => {
	for (const name of ["small", "preview", "dev"] as const) {
		const backup = {
			enabled: true,
			clientTlsSecret: "backup-tls",
			s3: { bucket: "test-backups", endpoint: "s3.example.com", region: "eu-central-1" },
		};
		const resources = rendered([...layersFor(name), { postgresql: { backup } }]);
		const restoring = rendered([
			...layersFor(name),
			{ postgresql: { backup: { ...backup, restore: { enabled: true } } } },
		]);
		const stanza = resources.find(
			(resource) => kindOf(resource) === "Job" && nameOf(resource).includes("-stanza-"),
		);
		const scheduled = resources.find(
			(resource) => kindOf(resource) === "CronJob" && componentOf(resource) === "postgresql-backup",
		);
		const restore = restoring.find(
			(resource) => kindOf(resource) === "Job" && nameOf(resource).includes("-restore-"),
		);
		assert.ok(stanza && scheduled && restore, name);
		const specs = [
			podSpec(workload(resources, "postgresql")),
			podSpec(stanza),
			get(scheduled, "spec", "jobTemplate", "spec", "template", "spec"),
			podSpec(restore),
		];
		for (const spec of specs) {
			const entries = list(get(spec, "containers"));
			assert.ok(entries.length > 0);
			for (const entry of entries) {
				const limit = amount(get(entry, "resources", "limits", "memory"));
				assert.ok(limit > 0);
				assert.equal(
					amount(get(entry, "resources", "requests", "memory")),
					limit,
					`${name}: ${String(get(entry, "name"))}`,
				);
			}
		}
	}
});

await test("PostgreSQL has a lower kubelet OOM adjustment than review and conversation sandboxes", () => {
	// Kubelet's Burstable formula: kubernetes.io/docs/concepts/scheduling-eviction/node-pressure-eviction/.
	// Sandbox Pod totals bound each container request. Actual OOM scores also depend on process memory use.
	const nodeBytes = 16 * 2 ** 30;
	const adjustment = (requestBytes: number) =>
		Math.min(999, Math.max(2, 1000 - Math.floor((1000 * requestBytes) / nodeBytes)));
	const resources = rendered(layersFor("small"));
	const postgres = workload(resources, "postgresql");
	const limits = get(container(postgres), "resources", "limits");
	const requests = get(container(postgres), "resources", "requests");
	assert.ok(amount(get(requests, "cpu")) < amount(get(limits, "cpu")), "CPU remains burstable");
	const score = adjustment(amount(get(requests, "memory")));
	for (const sandbox of [sandboxPods.review, sandboxPods.conversation]) {
		assert.ok(
			score < adjustment(sandbox.gib * 2 ** 30),
			"database precedes sandbox in OOM protection",
		);
	}
});

await test("each database purpose logs in with its own role, and no Hephaestus process as the administrator", () => {
	const bundledRelease = rendered([...layersFor("small"), sandboxLayer]);
	for (const component of ["server"]) {
		const role = workload(bundledRelease, component);
		assert.equal(databaseUserOf(container(role)), "hephaestus_app", component);
		assert.deepEqual(
			get(env(role, "DATABASE_PASSWORD"), "valueFrom", "secretKeyRef", "key"),
			"DATABASE_APP_PASSWORD",
			component,
		);
		assert.ok(
			!secretKeysOf(container(role)).some((key) => key.startsWith("DATABASE_ADMIN_")),
			component,
		);
	}
	const migration = migrationJobOf(bundledRelease);
	assert.equal(databaseUserOf(container(migration)), "hephaestus_owner");
	assert.deepEqual(
		secretKeysOf(container(migration)).filter((key) => key.startsWith("DATABASE_")),
		["DATABASE_OWNER_PASSWORD"],
	);
	// Only the init container that sets up the roles holds the administrator's login.
	const roles = list(get(podSpec(migration), "initContainers")).find(
		(entry) => get(entry, "name") === "database-roles",
	);
	assert.notEqual(roles, undefined, "the database-roles init container");
	assert.deepEqual(secretKeysOf(roles), [
		"DATABASE_ADMIN_USERNAME",
		"DATABASE_ADMIN_PASSWORD",
		"DATABASE_OWNER_PASSWORD",
		"DATABASE_APP_PASSWORD",
	]);
	// It runs the chart's script from the ConfigMap, the same script that the integration tests run: its command
	// holds no SQL, which Kubernetes would rewrite.
	assert.deepEqual(get(roles, "command"), ["sh", `${databaseSetupDirectory}/database-roles.sh`]);
	assertDatabaseSetup(bundledRelease, migration, roles);
	const postgresql = workload(bundledRelease, "postgresql");
	assert.deepEqual(secretKeysOf(container(postgresql)), [
		"DATABASE_ADMIN_USERNAME",
		"DATABASE_ADMIN_PASSWORD",
	]);
	// An operator's PostgreSQL: the chart never holds its administrator's login.
	const externalMigration = migrationJobOf(rendered(layersFor("external")));
	assert.equal(
		list(get(podSpec(externalMigration), "initContainers")).some(
			(entry) => get(entry, "name") === "database-roles",
		),
		false,
	);
	assert.ok(
		!secretKeysOf(container(externalMigration)).some((key) => key.startsWith("DATABASE_ADMIN_")),
	);
});

await test("every role verifies an external PostgreSQL with the CA alone", () => {
	const resources = rendered(layersFor("external"));
	assert.equal(
		workloads(resources).some((resource) => componentOf(resource) === "postgresql"),
		false,
	);
	const url = `postgresql://${external.postgresql.external.host}:5432/hephaestus?${verifyFull}`;
	const role = workload(resources, "server");
	assert.equal(envValue(role, "DATABASE_URL"), url);
	assertCaOnly(role, "server");
});

await test("the database admits only the roles that hold its login", () => {
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	// The database admits only the roles that hold its login.
	const database = resources.find(
		(resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource).endsWith("postgresql"),
	);
	assert.ok(database, "the PostgreSQL NetworkPolicy");
	const admitted = new Set(
		list(get(database, "spec", "ingress", 0, "from")).map((peer) =>
			get(peer, "podSelector", "matchLabels", "app.kubernetes.io/component"),
		),
	);
	assert.ok(admitted.has("server"));
	assert.ok(!admitted.has("webapp"));
});

const counts = (resources: Resource[]) => [get(workload(resources, "server"), "spec", "replicas")];

await test("replica counts stop the server and restore the release", () => {
	const enabled = [...layersFor("small"), sandboxLayer];
	const running = rendered(enabled);
	assert.deepEqual(counts(running), [1]);

	// Stopped: the objects stay, so the server keeps its Deployment, its policies and the sandbox namespace's.
	const stopped = rendered([...enabled, { server: { replicas: 0 } }]);
	assert.deepEqual(counts(stopped), [0]);
	assert.deepEqual(
		stopped.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`),
		running.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`),
	);
	assert.equal(get(workload(stopped, "server"), "spec", "strategy", "type"), "RollingUpdate");
	for (const component of ["postgresql", "webapp"]) {
		assert.equal(get(workload(stopped, component), "spec", "replicas"), 1, component);
	}

	// Restoring the prior counts renders the release as it was.
	const restored = rendered([...enabled, { server: { replicas: 1 } }]);
	assert.deepEqual(restored, running);

	// Without sandboxes the chart writes nothing into the sandbox namespace.
	const disabled = rendered([...layersFor("small"), { sandbox: { enabled: false } }]);
	assert.equal(
		disabled.some((resource) => componentOf(resource) === "sandbox"),
		false,
	);
});

await test("a restore clone starts PostgreSQL alone before lockdown and migrations", () => {
	const resources = rendered([
		profile("small"),
		prepared,
		operator,
		reviews,
		{
			server: { replicas: 0 },
			migration: { enabled: false },
		},
	]);
	assert.equal(
		resources.some(
			(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
		),
		false,
	);
	assert.equal(
		get(
			resources.find(
				(resource) => kindOf(resource) === "StatefulSet" && componentOf(resource) === "postgresql",
			),
			"spec",
			"replicas",
		),
		1,
	);
	for (const component of ["server"]) {
		assert.equal(
			get(
				resources.find(
					(resource) => kindOf(resource) === "Deployment" && componentOf(resource) === component,
				),
				"spec",
				"replicas",
			),
			0,
		);
	}
});

await test("small PostgreSQL overrides the image calculator and gives each database role its own pool", () => {
	const resources = rendered([prepared, profile("small"), operator, reviews]);
	const postgres = workload(resources, "postgresql");
	const args = list(get(container(postgres), "args"));
	const settings = Object.fromEntries(
		args.flatMap((arg, index) => {
			if (args[index - 1] !== "-c" || typeof arg !== "string") {
				return [];
			}
			const equals = arg.indexOf("=");
			return [[arg.slice(0, equals), arg.slice(equals + 1)]];
		}),
	);
	assert.equal(get(container(postgres), "command"), undefined, "the image entrypoint still runs");
	assert.equal(settings.shared_buffers, "256MB");
	assert.equal(settings.work_mem, "2MB");
	assert.equal(settings.hash_mem_multiplier, "1");
	assert.equal(settings.max_connections, "120");
	assert.equal(settings.maintenance_work_mem, "64MB");
	assert.equal(settings.autovacuum_work_mem, "32MB");
	assert.equal(settings.autovacuum_max_workers, "3");
	assert.equal(settings.effective_cache_size, "1GB");
	assert.equal(settings.max_parallel_workers_per_gather, "0");
	assert.equal(settings.max_wal_size, "1GB");
	assert.equal(
		get(container(postgres), "resources", "limits", "memory"),
		"2Gi",
		"the lower trial ceiling is not enabled",
	);
	const migration = resources.find(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
	);
	assert.ok(migration);
	for (const [role, maximum, minimum] of [
		[workload(resources, "server"), "42", "2"],
		[migration, "2", "1"],
	] as const) {
		assert.equal(envValue(role, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"), maximum);
		assert.equal(envValue(role, "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE"), minimum);
	}
});

await test("restore configuration preserves max_connections while application roles are paused", () => {
	const base = [prepared, operator, reviews, { sandbox: { enabled: true } }];
	const source = maxConnections(base);
	assert.ok(typeof source === "string");
	const paused = {
		migration: { enabled: false },
		server: { replicas: 0 },
	};
	assert.equal(maxConnections([...base, paused]), "max_connections=120");
	const preserved = {
		postgresql: {
			parameters: { max_connections: Number(source.slice("max_connections=".length)) },
		},
	};
	assert.equal(maxConnections([...base, paused, preserved]), source);
	assert.equal(maxConnections([...base, preserved]), source);
});

await test("the database budget holds the runtime pools, rollout, listeners and reserves", () => {
	const base = [
		...layersFor("small"),
		{
			postgresql: {
				metrics: { enabled: true },
				backup: {
					enabled: true,
					clientTlsSecret: "backup-tls",
					s3: { bucket: "backups", endpoint: "s3.example.com", region: "eu" },
				},
			},
		},
	];

	const loads = javaCapacityConstant("agent/mentor/sandbox/TurnContextLoads.java", "LOADS_AT_ONCE");
	const permits = javaCapacityConstant("agent/proxy/LlmCallPermits.java", "MAINTENANCE_THREADS");
	const monitorSql = readFileSync(path.join(chart, "files/database-monitor.sql"), "utf8");
	const monitorLimit = /CONNECTION LIMIT (?<count>\d+)/u.exec(monitorSql)?.groups?.count;
	assert.ok(monitorLimit !== undefined, "the exporter must have a cluster-wide connection bound");
	const monitor = Number(monitorLimit);
	const defaults = [prepared, operator, reviews, { sandbox: { enabled: true } }];
	for (const { layers, expectedPool, expectedRequired, expectedMaximum } of [
		{ layers: base, expectedPool: 42, expectedRequired: 110, expectedMaximum: 120 },
		{
			layers: [...base, { server: { inboxClaimers: 12 } }],
			expectedPool: 46,
			expectedRequired: 118,
			expectedMaximum: 120,
		},
		{
			layers: [...base, { sandbox: { reviewMemory: memoryFor(8) } }],
			expectedPool: 51,
			expectedRequired: 128,
			expectedMaximum: 140,
		},
		{
			layers: [
				...base,
				{ server: { replicas: 2 }, sandbox: { budget: { cpu: "8", memory: "32Gi" } } },
			],
			expectedPool: 42,
			expectedRequired: 204,
			expectedMaximum: 220,
		},
		{ layers: defaults, expectedPool: 48, expectedRequired: 228, expectedMaximum: 240 },
	]) {
		const result = render(layers);
		assert.ok(result.ok, result.stderr);
		const { resources } = result;
		const server = workload(resources, "server");
		const pool = Number(envValue(server, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"));
		const handlers = Number(envValue(server, "HEPHAESTUS_INTEGRATION_CONSUMER_CLAIMERS"));
		const captures = Number(envValue(server, "SANDBOX_MAX_CONCURRENT"));
		const boot = 1;
		const bounded = handlers + 3 * captures + 2 * loads + permits + boot;
		const syncReserve = 4 * 2;
		const httpReserve = 4;
		assert.equal(
			pool,
			bounded + syncReserve + httpReserve,
			"the pool follows the actual runtime caps and reserve",
		);
		const migration = resources.find(
			(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
		);
		assert.ok(migration);
		const peakServers = 2 * Number(get(server, "spec", "replicas"));
		const backupSessions = 2;
		const probeAndSetupSessions = 3;
		const ordinary =
			peakServers * (pool + 5) +
			Number(envValue(migration, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE")) +
			monitor +
			backupSessions +
			probeAndSetupSessions;
		const postgres = workload(resources, "postgresql");
		const database = containers(postgres).find((entry) => get(entry, "name") === "postgresql");
		assert.ok(isRecord(database));
		const args = list(get(database, "args"));
		const setting = (name: string) => {
			const value = args.findLast((arg) => typeof arg === "string" && arg.startsWith(`${name}=`));
			assert.equal(typeof value, "string", name);
			return Number(String(value).slice(name.length + 1));
		};
		const maximum = setting("max_connections");
		const reserved = setting("superuser_reserved_connections");
		const margin = 4;
		assert.equal(pool, expectedPool);
		assert.equal(ordinary + reserved + margin, expectedRequired);
		assert.ok(pool >= bounded, "the declared holders fit before reserve");
		assert.ok(
			ordinary <= maximum - reserved - margin,
			`${ordinary} ordinary clients leave reserved slots and margin`,
		);
		assert.equal(maximum, expectedMaximum);
		const tooSmall = render([
			...layers,
			{ postgresql: { parameters: { max_connections: ordinary + reserved + margin - 1 } } },
		]);
		assert.equal(tooSmall.ok, false);
		assert.match(tooSmall.stderr, /max_connections needs at least.*exporter=.*operator margin=/u);
		const shortPool = render([
			...layers,
			{ server: { databasePool: { maximumPoolSize: pool - 1 } } },
		]);
		assert.equal(shortPool.ok, false);
		assert.match(shortPool.stderr, /maximumPoolSize=.*needs at least/u);
	}
	// Explicit limits remain operator values after the complete rollout budget is checked.
	assert.equal(
		maxConnections([...defaults, { server: { databasePool: { maximumPoolSize: 200 } } }]),
		"max_connections=840",
	);
	assert.equal(
		maxConnections([...defaults, { postgresql: { parameters: { max_connections: 250 } } }]),
		"max_connections=250",
	);
});

await test("database pool and PostgreSQL settings reject invalid operator values", () => {
	for (const layer of [
		{ server: { databasePool: { maximumPoolSize: 1, minimumIdle: 2 } } },
		{ migration: { databasePool: { maximumPoolSize: 0 } } },
		{ postgresql: { parameters: { max_connections: "80" } } },
		{ postgresql: { parameters: { work_mem: "unbounded" } } },
		{ postgresql: { parameters: { fsync: "off" } } },
	]) {
		assert.equal(render([prepared, profile("small"), operator, reviews, layer]).ok, false);
	}
});

await test("every resident small-profile PostgreSQL container reserves its full memory ceiling", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			postgresql: {
				backup: {
					clientTlsSecret: "hephaestus-postgresql-backup-tls",
					enabled: true,
					s3: { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" },
				},
			},
		},
	]);
	const postgres = workload(resources, "postgresql");
	const postgresContainers = list(get(podSpec(postgres), "containers"));
	assert.equal(postgresContainers.length, 2);
	for (const resident of postgresContainers) {
		assert.equal(
			get(resident, "resources", "requests", "memory"),
			get(resident, "resources", "limits", "memory"),
			String(get(resident, "name")),
		);
	}
});

await test("an install warns when the bundled PostgreSQL has no backup, and only then", () => {
	const warning = "WARNING: the bundled PostgreSQL has no backup.";
	const backupDocs = "https://docs.hephaestus.build/admin/backup-restore#back-up-the-database";
	const unprotected = installNotes(layersFor("small"));
	assert.ok(unprotected.includes(warning), unprotected);
	assert.ok(unprotected.includes(backupDocs), unprotected);

	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const backedUp = installNotes([
		...layersFor("small"),
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	]);
	assert.ok(!backedUp.includes(warning), backedUp);
	// An external database is backed up where it runs, which the chart cannot see.
	assert.ok(!installNotes(layersFor("external")).includes(warning));
});
