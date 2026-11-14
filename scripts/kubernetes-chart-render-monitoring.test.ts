import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { isDeepStrictEqual } from "node:util";
import { parseAllDocuments } from "yaml";
import {
	alertRules,
	alertRulesOn,
	assertDatabaseSetup,
	chart,
	componentOf,
	container,
	databaseSetupDirectory,
	exporterParts,
	get,
	inStack,
	kindOf,
	layersFor,
	list,
	metricsOn,
	monitorJobs,
	nameOf,
	operator,
	podSpec,
	postgresqlPeers,
	prepared,
	profile,
	render,
	rendered,
	type Resource,
	reviews,
	ruleResources,
	stackComponents,
	stackOn,
	workload,
} from "./kubernetes-chart-render-support.ts";
import { asArray, asRecord, parseJson } from "./lib/json.ts";
import { packageReleaseChart } from "./release-chart.ts";

await test("the alert rules render as the tested file, and only when asked", () => {
	assert.deepEqual(ruleResources(rendered(layersFor("small"))), []);
	const resources = ruleResources(rendered([...layersFor("small"), alertRulesOn]));
	assert.deepEqual(resources.map(kindOf), ["ConfigMap", "PrometheusRule"]);
	// The rule selector of the operator's Prometheus finds both by the labels that it asks for.
	for (const resource of resources) {
		assert.equal(get(resource, "metadata", "labels", "release"), "monitoring");
	}
	const [configMap, prometheusRule] = resources;
	assert.equal(get(configMap, "data", "hephaestus-alerts.yml"), alertRules);
	assert.deepEqual(
		get(prometheusRule, "spec", "groups"),
		get(parseAllDocuments(alertRules)[0]?.toJS(), "groups"),
	);
});

const dashboardConfig = (resources: Resource[]) =>
	resources.filter(
		(resource) =>
			kindOf(resource) === "ConfigMap" && get(resource, "data", "overview.json") !== undefined,
	);

await test("external monitoring receives the release dashboards without a second monitoring stack", () => {
	assert.deepEqual(dashboardConfig(rendered(layersFor("small"))), []);
	const resources = rendered([
		...layersFor("small"),
		{
			observability: { dashboards: { namespace: "monitoring" } },
		},
	]);
	const [config] = dashboardConfig(resources);
	assert.ok(config);
	assert.equal(get(config, "metadata", "namespace"), "monitoring");
	assert.equal(nameOf(config), "heph-victoria-metrics-dashboards");
	assert.deepEqual(
		get(config, "data"),
		get(dashboardConfig(rendered([...layersFor("small"), stackOn]))[0], "data"),
	);
	assert.ok(
		!resources.some(
			(resource) =>
				componentOf(resource) === "victoria-metrics" && kindOf(resource) !== "ConfigMap",
		),
	);
	assert.ok(
		!resources.some(
			(resource) => kindOf(resource) === "Namespace" && nameOf(resource) === "monitoring",
		),
	);
});

await test("external dashboard namespace names stay strings in the rendered resource", () => {
	for (const namespace of ["123", "true"]) {
		const resources = rendered([
			...layersFor("small"),
			{ observability: { dashboards: { namespace } } },
		]);
		const config = resources.find(
			(resource) =>
				kindOf(resource) === "ConfigMap" && get(resource, "data", "overview.json") !== undefined,
		);
		assert.equal(get(config, "metadata", "namespace"), namespace);
	}
});

await test("the bundled monitoring stack accepts its own namespace explicitly", () => {
	const resources = rendered([
		...layersFor("small"),
		stackOn,
		{
			observability: { dashboards: { namespace: "hephaestus" } },
		},
	]);
	const dashboards = resources.filter(
		(resource) =>
			kindOf(resource) === "ConfigMap" && get(resource, "data", "overview.json") !== undefined,
	);
	assert.equal(dashboards.length, 1);
	assert.equal(get(dashboards[0], "metadata", "namespace"), "hephaestus");
});

await test("the bundled monitoring stack cannot mount dashboards from another namespace", () => {
	assert.throws(
		() =>
			rendered([
				...layersFor("small"),
				stackOn,
				{
					observability: { dashboards: { namespace: "monitoring" } },
				},
			]),
		/must be the release namespace/u,
	);
});

await test("the monitoring stack scrapes this release, evaluates its rules and notifies, all in its namespace", () => {
	const resources = rendered([...layersFor("small"), stackOn]);
	const stack = resources.filter((resource) => inStack(resource));
	for (const component of stackComponents) {
		const entry = container(workload(resources, component));
		assert.equal(get(entry, "image"), prepared.images[component], component);
	}
	// Pod discovery in the release's namespace only; nothing cluster-wide.
	assert.deepEqual(
		resources.filter((resource) => String(kindOf(resource)).startsWith("Cluster")).map(nameOf),
		[],
	);
	const role = stack.find((resource) => kindOf(resource) === "Role");
	assert.deepEqual(get(role, "rules"), [
		{ apiGroups: [""], resources: ["pods"], verbs: ["get", "list", "watch"] },
	]);
	const metricsConfigMaps = stack.filter(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "victoria-metrics",
	);
	const scrape = String(
		get(
			metricsConfigMaps.find((resource) => get(resource, "data", "scrape.yml") !== undefined),
			"data",
			"scrape.yml",
		),
	);
	assert.match(scrape, /names: \["hephaestus"\]/u);
	assert.match(scrape, /regex: "hephaestus;heph;management"/u);
	// vmui serves each dashboard file of the release, and a panel that a person reads needs a valid file.
	const dashboards = metricsConfigMaps.find(
		(resource) => get(resource, "data", "overview.json") !== undefined,
	);
	assert.equal(
		metricsConfigMaps.filter((resource) => get(resource, "data", "overview.json") !== undefined)
			.length,
		1,
	);
	assert.equal(get(dashboards, "metadata", "namespace"), "hephaestus");
	const dashboardFiles = asRecord(get(dashboards, "data"), "dashboards");
	assert.deepEqual(Object.keys(dashboardFiles).toSorted(), [
		"database.json",
		"messaging.json",
		"model-calls.json",
		"overview.json",
		"reviews-and-feedback.json",
	]);
	for (const [name, file] of Object.entries(dashboardFiles)) {
		const rows = get(parseJson(String(file)), "rows");
		assert.ok(Array.isArray(rows) && rows.length > 0, name);
		for (const panel of rows.flatMap((row) => asArray(get(row, "panels"), name))) {
			const queries = asArray(get(panel, "expr"), name);
			assert.ok(queries.length > 0, `${name}: a panel without a query`);
			assert.ok(typeof get(panel, "title") === "string", `${name}: a panel without a title`);
			assert.equal(asArray(get(panel, "alias"), name).length, queries.length, `${name}: aliases`);
		}
	}
	const metricsArgs = get(
		workload(resources, "victoria-metrics"),
		"spec",
		"template",
		"spec",
		"containers",
		0,
		"args",
	);
	assert.ok(
		Array.isArray(metricsArgs) && metricsArgs.includes("-vmui.customDashboardsPath=/dashboards"),
	);
	// vmalert evaluates the release's rules, except the groups whose exporters the stack does not run: their series
	// never exist here, and HephaestusDeployMetricsMissing, an absent() rule, would fire for ever.
	assert.deepEqual(ruleResources(resources), []);
	const vmalert = workload(resources, "vmalert");
	const ruleMap = stack.find(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "vmalert",
	);
	assert.ok(ruleMap);
	assert.equal(get(podSpec(vmalert), "volumes", 0, "configMap", "name"), nameOf(ruleMap));
	const tested = list(get(parseAllDocuments(alertRules)[0]?.toJS(), "groups"));
	const loaded = list(
		get(
			parseAllDocuments(String(get(ruleMap, "data", "hephaestus-alerts.yml")))[0]?.toJS(),
			"groups",
		),
	);
	const postgres = loaded.find((group) => get(group, "name") === "hephaestus-postgresql");
	const deadlocks = asRecord(
		list(get(postgres, "rules")).find(
			(rule) => get(rule, "alert") === "HephaestusDatabaseDeadlocks",
		),
		"deadlock rule",
	);
	assert.equal(
		deadlocks.expr,
		'sum by (namespace, datname) (increase_prometheus(pg_stat_database_deadlocks{job="postgresql"}[15m])) > 0',
	);
	// The exported rules stay valid PromQL. The bundled engine changes only this counter rollup.
	deadlocks.expr =
		'sum by (namespace, datname) (increase(pg_stat_database_deadlocks{job="postgresql"}[15m])) > 0';
	assert.deepEqual(
		loaded,
		tested.filter(
			(group) =>
				!["hephaestus-kubernetes", "hephaestus-pull-deploy"].includes(String(get(group, "name"))),
		),
	);
	assert.ok(!JSON.stringify(loaded).includes("HephaestusDeployMetricsMissing"));
	// Only VictoriaMetrics calls the API, so only it mounts a token; every stack Pod stays restricted.
	for (const component of stackComponents) {
		const spec = podSpec(workload(resources, component));
		assert.equal(get(spec, "automountServiceAccountToken"), component === "victoria-metrics");
		assert.equal(get(spec, "securityContext", "runAsNonRoot"), true, component);
		for (const volume of list(get(spec, "volumes"))) {
			assert.equal(get(volume, "hostPath"), undefined, component);
		}
		const security = get(container(workload(resources, component)), "securityContext");
		assert.equal(get(security, "readOnlyRootFilesystem"), true, component);
		assert.deepEqual(get(security, "capabilities", "drop"), ["ALL"], component);
	}
	// Each role admits the scraper on its management port, and nothing else of the stack.
	const metricsPeer = {
		podSelector: {
			matchLabels: {
				"app.kubernetes.io/name": "hephaestus",
				"app.kubernetes.io/instance": "heph",
				"app.kubernetes.io/component": "victoria-metrics",
			},
		},
	};
	for (const component of ["server"]) {
		const policy = resources.find(
			(resource) =>
				kindOf(resource) === "NetworkPolicy" &&
				isDeepStrictEqual(
					get(resource, "spec", "podSelector", "matchLabels", "app.kubernetes.io/component"),
					component,
				),
		);
		assert.ok(
			list(get(policy, "spec", "ingress")).some(
				(rule) =>
					isDeepStrictEqual(get(rule, "from"), [metricsPeer]) &&
					isDeepStrictEqual(get(rule, "ports"), [{ port: "management", protocol: "TCP" }]),
			),
			component,
		);
	}
});

await test("the monitoring stack is off by default and needs its Alertmanager configuration", () => {
	const resources = rendered(layersFor("small"));
	assert.deepEqual(
		resources.filter((resource) => inStack(resource)),
		[],
	);
	assert.match(
		render([...layersFor("small"), { observability: { stack: { enabled: true } } }]).stderr,
		/observability\.stack\.alertmanager\.configSecret is required/u,
	);
});

await test("a release package ships the alert rules without their promtool cases", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-chart-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const packaged = await packageReleaseChart(prepared.release.version, chart, prepared, directory);
	// A relative name: GNU tar on Windows reads the "C:" of an absolute path as a remote host.
	const listing = spawnSync("tar", ["-tzf", path.basename(packaged)], {
		cwd: path.dirname(packaged),
		encoding: "utf8",
	});
	assert.equal(listing.status, 0, listing.stderr);
	const files = listing.stdout.split("\n");
	assert.ok(files.includes("hephaestus/files/hephaestus-alerts.yml"), "the rules");
	assert.ok(
		!files.some((file) => file.endsWith("hephaestus-alerts.test.yml")),
		"no promtool cases",
	);
	const resources = rendered([profile("small"), operator, reviews, alertRulesOn], {
		source: packaged,
	});
	assert.equal(get(ruleResources(resources)[0], "data", "hephaestus-alerts.yml"), alertRules);
});

await test("traces reach an in-cluster collector on the endpoint's port, only while tracing is on", () => {
	const collector = {
		namespace: "observability",
		podLabels: { "app.kubernetes.io/name": "otel-collector" },
	};
	const layers = (tracing: object, traceCollector: object = collector) => [
		...layersFor("small"),
		{ config: { tracing }, networkPolicy: { traceCollector } },
	];
	/** The components whose NetworkPolicy opens the collector on `port`, across every policy. */
	const opened = (tracing: object, port: number, traceCollector?: object) => {
		const resources = rendered(layers(tracing, traceCollector));
		const rule = {
			to: [
				{
					namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "observability" } },
					podSelector: { matchLabels: collector.podLabels },
				},
			],
			ports: [{ port, protocol: "TCP" }],
		};
		return resources
			.filter((resource) => kindOf(resource) === "NetworkPolicy")
			.filter((policy) =>
				list(get(policy, "spec", "egress")).some((egress) => isDeepStrictEqual(egress, rule)),
			)
			.map((policy) =>
				["server"].find((component) =>
					isDeepStrictEqual(
						get(policy, "spec", "podSelector", "matchLabels"),
						get(workload(resources, component), "spec", "selector", "matchLabels"),
					),
				),
			)
			.toSorted((left = "", right = "") => left.localeCompare(right));
	};
	const exporters = ["server"];
	for (const [endpoint, port] of [
		["http://otel-collector.observability.svc:4318/v1/traces", 4318],
		["http://otel-collector.observability.svc:04318/v1/traces", 4318],
		["http://otel-collector.observability.svc/v1/traces", 80],
		["https://otel-collector.observability.svc/v1/traces", 443],
		["http://[fd00::1]:4318/v1/traces", 4318],
		["http://[fd00::1]/v1/traces", 80],
	] as const) {
		assert.deepEqual(
			opened({ otlpEnabled: true, otlpEndpoint: endpoint }, port),
			exporters,
			endpoint,
		);
	}
	const endpoint = "http://otel-collector.observability.svc:4318/v1/traces";
	assert.deepEqual(opened({ otlpEnabled: false, otlpEndpoint: endpoint }, 4318), []);
	assert.deepEqual(
		opened({ otlpEnabled: true, otlpEndpoint: endpoint }, 4318, { namespace: "", podLabels: {} }),
		[],
	);
	for (const port of ["0", "65536"]) {
		const result = render(
			layers({
				otlpEnabled: true,
				otlpEndpoint: `http://otel-collector.observability.svc:${port}/v1/traces`,
			}),
		);
		assert.equal(result.ok, false, port);
		assert.match(result.stderr, /otlpEndpoint names a port outside 1-65535/u);
	}
});

await test("the PostgreSQL exporter runs apart from the database, and its role Job only beside the bundled server", () => {
	// Off by default: no exporter, no Job, and PostgreSQL admits no monitoring client.
	const off = rendered(layersFor("small"));
	assert.deepEqual(exporterParts(off).map(nameOf), []);
	assert.deepEqual(monitorJobs(off).map(nameOf), []);
	assert.ok(!postgresqlPeers(off).includes("postgresql-exporter"));

	const on = rendered([...layersFor("small"), metricsOn]);
	// Turning the exporter on does not touch the database's Pod, so PostgreSQL does not restart.
	assert.deepEqual(workload(on, "postgresql"), workload(off, "postgresql"));
	const exporter = container(workload(on, "postgresql-exporter"));
	assert.equal(get(exporter, "image"), prepared.images["postgres-exporter"]);
	const args = list(get(exporter, "args"));
	assert.ok(args.includes("--collector.stat_statements.limit=20"), String(args));
	assert.ok(args.includes("--no-collector.stat_statements.include_query"), String(args));
	// The transaction ID age that HephaestusDatabaseTransactionIdAgeHigh reads.
	assert.ok(args.includes("--collector.database_wraparound"), String(args));
	const exporterEnv = list(get(exporter, "env"));
	const entry = (name: string) => exporterEnv.find((candidate) => get(candidate, "name") === name);
	assert.match(
		String(get(entry("DATA_SOURCE_URI"), "value")),
		/^heph-postgresql\.hephaestus\.svc:5432\/hephaestus\?sslmode=verify-full&/u,
	);
	assert.equal(get(entry("DATA_SOURCE_USER"), "value"), "hephaestus_monitor");
	assert.equal(
		get(entry("DATA_SOURCE_PASS"), "valueFrom", "secretKeyRef", "key"),
		"DATABASE_MONITOR_PASSWORD",
	);
	const [job] = monitorJobs(on);
	assert.ok(job, "one monitor Job");
	assert.equal(nameOf(job), "heph-postgresql-monitor-1");
	const script = String(
		list(get(job, "spec", "template", "spec", "containers", 0, "command")).at(-1),
	);
	// The chart's monitor script, from the ConfigMap: PostgreSQL gets only the SCRAM verifier of the password, and the
	// password is set on every revision, so a rotated key takes effect.
	assert.match(
		script,
		new RegExp(`^exec sh ${databaseSetupDirectory}/database-monitor\\.sh$`, "mu"),
	);
	assert.doesNotMatch(
		script,
		/psql/u,
		"the Job's command holds no SQL, which Kubernetes would rewrite",
	);
	assertDatabaseSetup(on, job, list(get(job, "spec", "template", "spec", "containers"))[0]);
	assert.deepEqual(postgresqlPeers(on).slice(-2), ["postgresql-exporter", "postgresql-monitor"]);

	// An external PostgreSQL is the operator's to prepare: the exporter reads it, and no Job runs.
	const externalDatabase = rendered([...layersFor("external"), metricsOn]);
	assert.notEqual(container(workload(externalDatabase, "postgresql-exporter")), undefined);
	assert.deepEqual(monitorJobs(externalDatabase).map(nameOf), []);

	// The stack scrapes the exporter under a job of its own, on the port that its policy opens.
	const stacked = rendered([...layersFor("small"), stackOn, metricsOn]);
	const scrape = stacked
		.filter((resource) => kindOf(resource) === "ConfigMap")
		.map((resource) => JSON.stringify(get(resource, "data")))
		.find((data) => data.includes("scrape_configs"));
	assert.ok(scrape !== undefined && scrape.includes("job_name: postgresql"), scrape);
	const exporterPolicy = stacked.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-postgresql-exporter",
	);
	assert.deepEqual(
		list(get(exporterPolicy, "spec", "ingress")).map((rule) => get(rule, "ports", 0, "port")),
		["metrics"],
	);
});
