import assert from "node:assert/strict";
import { test } from "node:test";
import { stringify } from "yaml";
import {
	adminBundle,
	amount,
	byCodePoint,
	byPath,
	byTier,
	clusterScoped,
	componentOf,
	container,
	env,
	envValue,
	external,
	fitTotals,
	get,
	gib,
	grants,
	kindOf,
	kubernetesApi,
	type Layer,
	layersFor,
	list,
	memoryFor,
	nameOf,
	nodeRoom,
	operator,
	podRequests,
	podSpec,
	prepared,
	profiles,
	render,
	rendered,
	type Resource,
	reviewPodMi,
	reviewWidth,
	sandboxGib,
	sandboxLayer,
	sandboxNamespace,
	sandboxPods,
	sandboxQuota,
	sandboxRequests,
	sandboxRuntime,
	serverCapacity,
	sum,
	surge,
	templatePriority,
	templatesOf,
	tierHard,
	tierQuota,
	total,
	withSandboxes,
	workload,
	workloads,
} from "./kubernetes-chart-render-support.ts";
import { pressureNodeTaint, priorityClassResources } from "./kubernetes-memory-drill.ts";
import { asRecord } from "./lib/json.ts";
import {
	chartReviewLimits,
	DISK_VOLUMES,
	reviewLimits,
	templateFlags,
} from "./lib/sandbox-footprint-report.ts";

await test("a server with sandboxes receives its runtime settings, its sandbox namespace and only the authority its runtime uses", () => {
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	const server = workload(resources, "server");
	const serverName = nameOf(server);
	// The operator owns the sandbox namespace: the chart writes into it and creates no namespace.
	assert.equal(resources.filter((resource) => kindOf(resource) === "Namespace").length, 0);
	const sandboxes = sandboxNamespace;

	// The selected settings, the same strings that the chart's objects use.
	assert.equal(envValue(server, "SPRING_PROFILES_ACTIVE"), "prod");
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_RUNTIME"), undefined);
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE"), sandboxes);
	// A claim names its kind's warm pool, and passes the gateway Service's address, which the server resolves.
	for (const kind of ["review", "conversation"]) {
		const pool = envValue(server, `HEPHAESTUS_AGENT_SANDBOX_WARM_POOLS_${kind.toUpperCase()}`);
		assert.ok(
			resources.some(
				(resource) =>
					kindOf(resource) === "SandboxWarmPool" &&
					nameOf(resource) === pool &&
					get(resource, "metadata", "namespace") === sandboxes,
			),
			`the ${kind} warm pool that the server names exists in the sandbox namespace`,
		);
	}
	const gatewayService = resources.find(
		(resource) => kindOf(resource) === "Service" && nameOf(resource).endsWith("-sandbox-gateway"),
	);
	assert.ok(gatewayService, "the gateway Service");
	assert.equal(
		envValue(server, "HEPHAESTUS_AGENT_SANDBOX_GATEWAY_URL"),
		`http://${nameOf(gatewayService)}.hephaestus.svc:${String(get(gatewayService, "spec", "ports", 0, "port"))}`,
	);
	for (const variable of [
		"POD_NAME",
		"POD_IP",
		"RUNTIME_CLASS_NAME",
		"SECCOMP_PROFILE",
		"NODE_SELECTOR",
		"TOLERATIONS",
		"PROBE_INTERVAL",
	]) {
		assert.equal(env(server, `HEPHAESTUS_SANDBOX_KUBERNETES_${variable}`), undefined);
	}
	assert.equal(envValue(server, "SANDBOX_API_PORT"), "8081");
	const service = (name: string) =>
		resources.find((resource) => kindOf(resource) === "Service" && nameOf(resource) === name);
	// Existing calls must reach the draining server until it releases its review attempts.
	assert.equal(get(service("heph-sandbox-gateway"), "spec", "publishNotReadyAddresses"), true);
	// A review attempt lives on the worker that took its sandbox's first call.
	assert.equal(get(service("heph-sandbox-gateway"), "spec", "sessionAffinity"), "ClientIP");
	assert.equal(
		get(
			service("heph-sandbox-gateway"),
			"spec",
			"sessionAffinityConfig",
			"clientIP",
			"timeoutSeconds",
		),
		86_400,
	);
	assert.equal(service("heph-sandbox-gateway-probe"), undefined);
	for (const name of ["heph-sandbox-gateway"]) {
		assert.equal(get(service(name), "spec", "type"), "ClusterIP", name);
		assert.equal(get(service(name), "spec", "selector", "app.kubernetes.io/component"), "server");
		assert.equal(get(service(name), "spec", "ports", 0, "port"), 8081, name);
	}
	assert.ok(
		list(get(container(server), "ports")).some(
			(port) => get(port, "name") === "sandbox-gateway" && get(port, "containerPort") === 8081,
		),
	);
	assert.equal(
		env(server, "HEPHAESTUS_WORKER_ID"),
		undefined,
		"the ledger identity stays per process",
	);
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_IMAGE_REFERENCE"), prepared.images["agent-pi"]);
	assert.equal(get(container(server), "image"), prepared.images["application-server"]);
	assert.equal(env(server, "HEPHAESTUS_CONVERSATION_LOOP_SECRET"), undefined);
	assert.equal(
		env(server, "HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX"),
		undefined,
		"no cap per server",
	);

	// The server finishes in-flight reviews beside its replacement. HTTP stays open during the drain,
	// with two additional minutes for the remaining shutdown phases.
	assert.equal(kindOf(server), "Deployment");
	assert.equal(get(server, "spec", "replicas"), 1);
	assert.deepEqual(get(server, "spec", "strategy"), {
		type: "RollingUpdate",
		rollingUpdate: { maxSurge: 1, maxUnavailable: 0 },
	});
	const spec = podSpec(server);
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_DRAIN_TIMEOUT"), "600s");
	assert.equal(get(spec, "terminationGracePeriodSeconds"), 600 + 120);
	assert.deepEqual(
		list(get(spec, "volumes")).find((volume) => get(volume, "name") === "git-cache"),
		{ name: "git-cache", emptyDir: { sizeLimit: "20Gi" } },
	);

	// Restricted server pods with the installation labels that their sandboxes copy, and a token.
	assert.equal(get(spec, "serviceAccountName"), serverName);
	assert.equal(get(spec, "automountServiceAccountToken"), true);
	assert.equal(get(spec, "securityContext", "runAsNonRoot"), true);
	const security = get(container(server), "securityContext");
	assert.equal(get(security, "allowPrivilegeEscalation"), false);
	assert.equal(get(security, "readOnlyRootFilesystem"), true);
	assert.deepEqual(get(security, "capabilities", "drop"), ["ALL"]);
	const labels = get(server, "spec", "template", "metadata", "labels");
	for (const [key, value] of [
		["app.kubernetes.io/name", "hephaestus"],
		["app.kubernetes.io/part-of", "hephaestus"],
		["app.kubernetes.io/instance", "heph"],
		["app.kubernetes.io/component", "server"],
	] as const) {
		assert.equal(get(labels, key), value, key);
	}

	// The chart creates no account in the sandbox namespace: sandbox Pods turn the token off.
	assert.equal(
		resources.filter(
			(resource) =>
				kindOf(resource) === "ServiceAccount" &&
				get(resource, "metadata", "namespace") === sandboxes,
		).length,
		0,
	);
	// One quota for each tier, each selecting its Pods by their PriorityClass, and one that owns no objects.
	const quotas = resources.filter((resource) => kindOf(resource) === "ResourceQuota");
	assert.deepEqual(
		quotas.map((quota) => get(quota, "metadata", "namespace")),
		[sandboxes, sandboxes, sandboxes],
	);
	for (const tier of ["reviews", "conversations"] as const) {
		const quota = tierQuota(resources, tier);
		assert.deepEqual(get(quota, "spec", "scopeSelector", "matchExpressions"), [
			{ scopeName: "PriorityClass", operator: "In", values: [`hephaestus-heph-${tier}`] },
		]);
		// The API server refuses any other resource in a quota scoped by PriorityClass.
		assert.deepEqual(
			Object.keys(asRecord(get(quota, "spec", "hard"), `the ${tier} quota`)).toSorted(),
			["pods", "requests.cpu", "requests.memory"],
		);
	}
	assert.match(
		String(get(sandboxQuota(resources), "spec", "hard", "requests.ephemeral-storage")),
		/^[1-9][0-9]*Mi$/u,
	);
	// Both quotas admit the sandboxes that the server may run.
	assert.equal(get(tierQuota(resources, "reviews"), "spec", "hard", "pods"), "5");
	assert.equal(
		get(tierQuota(resources, "conversations"), "spec", "hard", "pods"),
		envValue(workload(resources, "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY"),
	);

	// Exactly the calls that the runtime makes, each in one namespace, and no cluster-wide authority.
	for (const kind of ["ClusterRole", "ClusterRoleBinding"]) {
		assert.equal(resources.filter((resource) => kindOf(resource) === kind).length, 0, kind);
	}
	const expected = new Map<string | undefined, string[]>([
		[
			sandboxes,
			// A subject's sandbox: its claim, never the Sandbox or its Pod, which the controller owns.
			["create", "delete", "get", "list", "watch"].map(
				(verb) => `extensions.agents.x-k8s.io/sandboxclaims:${verb}`,
			),
		],
	]);
	const roles = resources.filter((resource) => kindOf(resource) === "Role");
	const bindings = resources.filter((resource) => kindOf(resource) === "RoleBinding");
	assert.equal(roles.length, expected.size);
	assert.equal(bindings.length, expected.size);
	for (const [namespace, granted] of expected) {
		const label = namespace ?? "the release namespace";
		const role = roles.find((resource) => get(resource, "metadata", "namespace") === namespace);
		assert.ok(role, label);
		assert.deepEqual(grants(role), granted.toSorted(byCodePoint), label);
		const binding = bindings.find(
			(resource) => get(resource, "metadata", "namespace") === namespace,
		);
		assert.deepEqual(
			get(binding, "roleRef"),
			{ apiGroup: "rbac.authorization.k8s.io", kind: "Role", name: nameOf(role) },
			label,
		);
		assert.deepEqual(
			get(binding, "subjects"),
			[{ kind: "ServiceAccount", name: serverName, namespace: "hephaestus" }],
			label,
		);
	}

	// All sandboxes reach only the servers' gateway port. The credential binds each sandbox to its run.
	const policiesIn = (namespace: string) =>
		resources
			.filter(
				(resource) =>
					kindOf(resource) === "NetworkPolicy" &&
					get(resource, "metadata", "namespace") === namespace,
			)
			.map(nameOf)
			.toSorted(byCodePoint);
	assert.deepEqual(policiesIn(sandboxes), ["heph-sandbox-deny", "heph-sandbox-gateway"]);
	const gateway = resources.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-sandbox-gateway",
	);
	const sources = {
		matchExpressions: [
			{
				key: "hephaestus.build/sandbox-kind",
				operator: "In",
				values: ["review", "conversation"],
			},
		],
	};
	assert.deepEqual(get(gateway, "spec", "podSelector"), sources);
	const serverSelector = get(server, "spec", "selector", "matchLabels");
	assert.deepEqual(get(gateway, "spec", "egress"), [
		{
			to: [
				{
					namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "hephaestus" } },
					podSelector: { matchLabels: serverSelector },
				},
			],
			ports: [{ port: 8081, protocol: "TCP" }],
		},
	]);
	const own = resources.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" &&
			nameOf(resource) === serverName &&
			get(resource, "metadata", "namespace") === undefined,
	);
	assert.deepEqual(get(own, "spec", "podSelector", "matchLabels"), serverSelector);
	// The first rule admits the Gateway on the application port; the sandboxes come next, on 8081 only.
	assert.deepEqual(get(own, "spec", "ingress", 1, "from"), [
		{
			namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": sandboxes } },
			podSelector: sources,
		},
	]);
	assert.deepEqual(get(own, "spec", "ingress", 1, "ports"), [{ port: 8081, protocol: "TCP" }]);
	// The API only at the endpoint the operator qualified, as the plugin sees it after translation.
	const api = list(get(own, "spec", "egress")).filter(
		(rule) => get(rule, "to", 0, "ipBlock", "cidr") === kubernetesApi.cidr,
	);
	assert.deepEqual(api, [
		{
			to: [{ ipBlock: { cidr: kubernetesApi.cidr } }],
			ports: [{ port: kubernetesApi.port, protocol: "TCP" }],
		},
	]);
});

await test("conversation turns use sandboxes and the old loop values are refused", () => {
	for (const name of profiles) {
		const resources = rendered(withSandboxes(name));
		assert.equal(
			resources.some(
				(resource) =>
					componentOf(resource) === "conversation-loop" ||
					nameOf(resource) === "heph-sandbox-tools",
			),
			false,
		);
	}
	const retired = render([...layersFor("small"), { conversationLoop: { turns: 8 } }]);
	assert.equal(retired.ok, false);
	assert.match(retired.stderr, /conversationLoop/u);
});

await test("the conversation quota counts the template requests of each resident sandbox", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			sandbox: {
				conversationTurns: 3,
				templates: {
					conversation: {
						resources: { requests: { cpu: "200m", memory: "768Mi", "ephemeral-storage": "1Gi" } },
					},
				},
			},
		},
	]);
	const quota = get(tierQuota(resources, "conversations"), "spec", "hard");
	assert.equal(get(quota, "pods"), "3");
	assert.equal(amount(get(quota, "requests.cpu")), 0.6);
	assert.equal(amount(get(quota, "requests.memory")), 3 * 768 * 2 ** 20);
	assert.equal(
		amount(get(sandboxQuota(resources), "spec", "hard", "requests.ephemeral-storage")),
		(5 + 3) * 2 ** 30,
	);
});

await test("the retired worker block fails, its turn count included", () => {
	// 1.0 has no worker role: its values moved to `server` and `sandbox`, and an old values file is refused.
	for (const retired of [{ conversationTurns: 8 }, { enabled: true }]) {
		const result = render([...layersFor("small"), { worker: retired }]);
		assert.equal(result.ok, false);
		assert.match(result.stderr, /additional properties 'worker' not allowed/u);
	}
});

await test("sandboxes without the optional runtime settings use the cluster's defaults, and the quota can be left out", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			sandbox: { enabled: true, namespace: sandboxNamespace, quota: { enabled: false } },
			networkPolicy: { kubernetesApi },
		},
	]);
	const server = workload(resources, "server");
	assert.equal(env(server, "HEPHAESTUS_SANDBOX_KUBERNETES_RUNTIME_CLASS_NAME"), undefined);
	assert.equal(env(server, "HEPHAESTUS_SANDBOX_KUBERNETES_SECCOMP_PROFILE"), undefined);
	assert.equal(env(server, "HEPHAESTUS_SANDBOX_KUBERNETES_NODE_SELECTOR"), undefined);
	assert.equal(env(server, "HEPHAESTUS_SANDBOX_KUBERNETES_TOLERATIONS"), undefined);
	assert.equal(resources.filter((resource) => kindOf(resource) === "ResourceQuota").length, 0);
});

await test("what the server holds for its reviews follows the reviews that the sandbox nodes hold", () => {
	// The buildpack's 250 threads of the server, and 8 more per review.
	assert.deepEqual(serverCapacity(1), {
		threads: "258",
		sandboxes: "1",
	});
	assert.deepEqual(serverCapacity(16), {
		threads: "378",
		sandboxes: "16",
	});
	assert.deepEqual(serverCapacity(32), {
		threads: "506",
		sandboxes: "32",
	});
	assert.deepEqual(serverCapacity(64), {
		threads: "762",
		sandboxes: "64",
	});
});

await test("the server runs the reviews that the sandbox nodes hold, and the quota holds no more", () => {
	const base = [
		...layersFor("small"),
		{ sandbox: { reviewMemory: memoryFor(66), conversationTurns: 8, budget: null } },
	];
	// Only the actual review sandboxes consume this quota.
	assert.deepEqual(reviewWidth(base), { sandboxes: "66", pods: "66" });
	// A fraction does not convert to whole MiB, so the chart refuses it.
	const fraction = render([...layersFor("small"), { sandbox: { reviewMemory: "1.5Gi" } }]);
	assert.equal(fraction.ok, false);
	assert.match(fraction.stderr, /reviewMemory/u);
	const noReview = render([
		...layersFor("small"),
		{ sandbox: { reviewMemory: `${reviewPodMi - 1}Mi` } },
	]);
	assert.equal(noReview.ok, false);
	assert.match(noReview.stderr, /reviewMemory holds no review: one review sandbox requests 512Mi/u);
});

await test("review slot admission and quota count the same overridden template requests", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			sandbox: {
				reviewMemory: "1920Mi",
				conversationTurns: 8,
				templates: {
					review: {
						resources: { requests: { cpu: "225m", memory: "640Mi", "ephemeral-storage": "2Gi" } },
					},
				},
			},
		},
	]);
	const review = sandboxRequests(resources, "review");
	const slots = Number(envValue(workload(resources, "server"), "SANDBOX_MAX_CONCURRENT"));
	assert.equal(slots, 3);
	const quota = get(tierQuota(resources, "reviews"), "spec", "hard");
	assert.equal(Number(get(quota, "pods")), slots);
	assert.equal(amount(get(quota, "requests.memory")), slots * review.gib * 2 ** 30);
	assert.equal(amount(get(quota, "requests.cpu")), slots * review.cpu);
	const conversation = sandboxRequests(resources, "conversation");
	const residentConversations = Number(
		get(tierQuota(resources, "conversations"), "spec", "hard", "pods"),
	);
	assert.equal(
		amount(get(sandboxQuota(resources), "spec", "hard", "requests.ephemeral-storage")),
		slots * review.disk + residentConversations * conversation.disk,
	);
});

const capacity = (layers: readonly Layer[]) =>
	envValue(workload(rendered(layers), "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY");

await test("practice reviews run exactly when the sandboxes run, with the installation's Heph turn capacity", () => {
	// Without sandboxes, a submitted review job would wait for a sandbox that never comes.
	assert.equal(envValue(workload(rendered(layersFor("dev")), "server"), "AGENT_ENABLED"), "false");
	// Every shipped profile, also the ones whose sandboxes the operator turns on.
	for (const name of profiles) {
		const resources = rendered(withSandboxes(name));
		assert.equal(envValue(workload(resources, "server"), "AGENT_ENABLED"), "true", name);
		// Without git checkout the server reports practice reviews out of service.
		assert.equal(envValue(workload(resources, "server"), "GIT_CHECKOUT_ENABLED"), "true", name);
		// The server evicts mirrors against the volume that the kubelet bounds, so the two sizes are one value.
		for (const role of ["server"]) {
			const cache = list(get(podSpec(workload(resources, role)), "volumes")).find(
				(volume) => get(volume, "name") === "git-cache",
			);
			const size = String(get(cache, "emptyDir", "sizeLimit"));
			assert.match(size, /^\d+Gi$/u, `${name} ${role}`);
			assert.equal(
				envValue(workload(resources, role), "GIT_MAX_CACHE_BYTES"),
				String(Number.parseInt(size, 10) * 1024 ** 3),
				`${name} ${role}`,
			);
		}
	}
	assert.equal(capacity(layersFor("small")), "11");
	assert.equal(capacity([...layersFor("small"), { sandbox: { conversationTurns: 5 } }]), "5");
	const exhausted = render([
		...layersFor("small"),
		{ server: { resources: { requests: { cpu: "3" } } } },
	]);
	assert.equal(exhausted.ok, false);
	assert.match(exhausted.stderr, /sandbox.budget|no room/u);
	assert.equal(capacity([...layersFor("small"), { sandbox: { conversationTurns: 0 } }]), "0");
	assert.equal(
		env(workload(rendered(layersFor("dev")), "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY"),
		undefined,
	);
});

await test("auto requires an operator budget and explicit counts validate a supplied budget", () => {
	const help = /https:\/\/docs\.hephaestus\.build\/admin\/helm-chart#sandbox-capacity-budget/u;
	for (const budget of [null, { cpu: "3500m" }, { memory: "14196Mi" }]) {
		const result = render([
			...layersFor("cluster"),
			{ sandbox: { conversationTurns: "auto", budget } },
		]);
		assert.equal(result.ok, false);
		assert.match(result.stderr, /sandbox.budget.(?:cpu|memory) is required/u);
		assert.match(result.stderr, help);
	}
	assert.equal(render([...layersFor("cluster"), { sandbox: { conversationTurns: 64 } }]).ok, true);
	const oversizedRoles = render([
		...layersFor("small"),
		{
			sandbox: { enabled: false, conversationTurns: 0 },
			server: { resources: { requests: { cpu: "4" } } },
		},
	]);
	assert.equal(oversizedRoles.ok, false);
	assert.match(oversizedRoles.stderr, /sandbox.budget.cpu cannot hold fixed/u);

	const refused = render([...layersFor("small"), { sandbox: { conversationTurns: 64 } }]);
	assert.equal(refused.ok, false);
	assert.match(refused.stderr, /sandbox.conversationTurns=64 exceeds sandbox.budget/u);
	assert.match(refused.stderr, help);
});

await test("auto is the largest turn count whose rendered steady and drained-upgrade requests fit", () => {
	const backup = {
		postgresql: {
			backup: {
				enabled: true,
				clientTlsSecret: "backup-tls",
				s3: { bucket: "backups", endpoint: "s3.example.com", region: "eu" },
			},
		},
	};
	const overhead = { cpuMillis: 10, memoryMi: 48 };
	const cases = [
		{ name: "small", values: {}, overhead: { cpuMillis: 0, memoryMi: 0 } },
		{ name: "backup", values: backup, overhead: { cpuMillis: 0, memoryMi: 0 } },
		{
			name: "controller",
			values: { sandbox: { controller: { enabled: true } } },
			overhead: { cpuMillis: 0, memoryMi: 0 },
		},
		{
			name: "runtime overhead",
			values: { sandbox: { templates: { runtimeClassName: "gvisor", podOverhead: overhead } } },
			overhead,
		},
		{
			name: "CPU",
			values: { sandbox: { budget: { cpu: "2300m" } } },
			overhead: { cpuMillis: 0, memoryMi: 0 },
		},
		{
			name: "three server replicas",
			values: {
				server: { replicas: 3 },
				postgresql: { parameters: { max_connections: 300 } },
				sandbox: { budget: { cpu: "8", memory: "32Gi" } },
			},
			overhead: { cpuMillis: 0, memoryMi: 0 },
		},
		{
			name: "upgrade memory",
			values: { migration: { resources: { requests: { memory: "4Gi" } } } },
			overhead: { cpuMillis: 0, memoryMi: 0 },
		},
	];
	for (const scenario of cases) {
		const layers = [...layersFor("small"), scenario.values];
		const resources = rendered(layers);
		const turns = Number(
			envValue(workload(resources, "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY"),
		);
		const budget = get(scenario.values, "sandbox", "budget");
		const room = {
			cpu: get(budget, "cpu") === undefined ? nodeRoom().cpu : amount(get(budget, "cpu")),
			memory:
				get(budget, "memory") === undefined ? nodeRoom().memory : amount(get(budget, "memory")),
		};
		const phases = Object.values(fitTotals(resources, scenario.overhead));
		for (const phase of phases) {
			assert.ok(phase.cpu <= room.cpu + 1e-9, `${scenario.name}: CPU fits`);
			assert.ok(phase.memory <= room.memory, `${scenario.name}: memory fits`);
		}
		const template = sandboxRequests(resources, "conversation");
		const next = {
			cpu: template.cpu + scenario.overhead.cpuMillis / 1000,
			memory: template.gib * 2 ** 30 + scenario.overhead.memoryMi * 2 ** 20,
		};
		assert.ok(
			phases.some(
				(phase) =>
					phase.cpu + next.cpu > room.cpu + 1e-9 || phase.memory + next.memory > room.memory,
			),
			`${scenario.name}: one more turn cannot fit`,
		);
		assert.equal(render([...layers, { sandbox: { conversationTurns: turns } }]).ok, true);
		const excess = render([...layers, { sandbox: { conversationTurns: turns + 1 } }]);
		assert.equal(excess.ok, false, `${scenario.name}: explicit excess is refused`);
		assert.match(excess.stderr, /exceeds sandbox.budget/u);
		process.stdout.write(`${scenario.name}: auto=${turns}\n`);
	}
});

await test("the single-node profile's requests, one upgrade beside them and its sandbox quota fit the node it states", () => {
	// The node in profiles/small.yaml, less the platform reservations and hard eviction floor.
	// Images take node disk; the sandbox quotas count the sandboxes' disk requests.
	const node = { ...nodeRoom(), disk: (150 - 40) * 2 ** 30 };
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small"),
		sandboxLayer,
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	]);
	let cpu = 0;
	let memory = 0;
	let disk = 0;
	const add = (spec: unknown, count: number) => {
		const requests = podRequests(spec);
		cpu += requests.cpu * count;
		memory += requests.memory * count;
		// A Pod's node disk: its containers' ephemeral-storage limits, which bound their emptyDir caches too.
		disk +=
			list(get(spec, "containers")).reduce<number>(
				(storage, entry) =>
					storage + amount(get(entry, "resources", "limits", "ephemeral-storage")),
				0,
			) * count;
	};
	for (const resource of workloads(resources)) {
		const replicas = Number(get(resource, "spec", "replicas") ?? 1);
		// The server replacement starts beside its draining predecessor.
		add(podSpec(resource), replicas + (kindOf(resource) === "Deployment" ? surge(resource) : 0));
		for (const claim of list(get(resource, "spec", "volumeClaimTemplates"))) {
			disk += amount(get(claim, "spec", "resources", "requests", "storage"));
		}
	}
	// The migration Job and the stanza Job run beside the upgrade, and one backup at a time.
	for (const job of resources.filter((candidate) => kindOf(candidate) === "Job")) {
		add(podSpec(job), 1);
	}
	const backup = resources.find((resource) => kindOf(resource) === "CronJob");
	add(get(backup, "spec", "jobTemplate", "spec", "template", "spec"), 1);
	const residents = workloads(resources).reduce(
		(requested, resource) =>
			requested +
			podRequests(podSpec(resource)).memory * Number(get(resource, "spec", "replicas") ?? 1),
		0,
	);
	process.stdout.write(
		`single-node role requests: ${gib(residents)} GiB; upgrade additions: ${gib(memory - residents)} GiB\n`,
	);
	// Reviews have drained at the upgrade peak. Conversation sandboxes can remain.
	for (const tier of ["conversations"] as const) {
		const quota = tierQuota(resources, tier);
		cpu += amount(get(quota, "spec", "hard", "requests.cpu"));
		memory += amount(get(quota, "spec", "hard", "requests.memory"));
	}
	disk += amount(get(sandboxQuota(resources), "spec", "hard", "requests.ephemeral-storage"));
	assert.equal(get(workload(resources, "server"), "spec", "replicas"), 1);
	// The backup's request comes out of the turns too.
	assert.equal(
		Number(envValue(workload(resources, "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY")),
		7,
	);
	assert.ok(cpu <= node.cpu, `${cpu.toFixed(2)} CPU requested, ${node.cpu} on the node`);
	assert.ok(
		memory <= node.memory,
		`${gib(memory)} GiB requested, ${gib(node.memory)} GiB on the node`,
	);
	assert.ok(disk <= node.disk, `${gib(disk)} GiB of disk, ${gib(node.disk)} GiB on the node`);
	process.stdout.write(
		`single-node budget: ${cpu.toFixed(2)} CPU, ${gib(memory)} GiB, ${gib(disk)} GiB disk\n`,
	);
});

await test("each tier's quota counts the RuntimeClass overhead of every sandbox Pod that it holds", () => {
	const overhead = { podOverhead: { cpuMillis: 100, memoryMi: 40 } };
	const without = tierHard([
		...layersFor("small"),
		{ sandbox: { conversationTurns: 32, budget: null } },
	]);
	// The same five reviews, in memory that holds each review Pod's overhead too.
	const withOverhead = tierHard([
		...layersFor("small"),
		{
			sandbox: {
				templates: overhead,
				reviewMemory: `${5 * (reviewPodMi + 40)}Mi`,
				conversationTurns: 32,
				budget: null,
			},
		},
	]);
	for (const tier of ["reviews", "conversations"] as const) {
		const { pods, millicpu, memory } = without(tier);
		assert.deepEqual(
			withOverhead(tier),
			{ pods, millicpu: millicpu + pods * 100, memory: memory + pods * 40 * 2 ** 20 },
			tier,
		);
	}
	// In the profile's memory, the overhead leaves room for fewer reviews.
	assert.equal(reviewWidth(layersFor("small")).sandboxes, "5");
	assert.equal(
		reviewWidth([
			...layersFor("small"),
			{
				sandbox: {
					templates: { podOverhead: { cpuMillis: 100, memoryMi: 200 } },
					conversationTurns: 32,
					budget: null,
				},
			},
		]).sandboxes,
		"3",
	);
});

await test("sandbox runtime accepts placement only and template overhead has one home", () => {
	const retired = render([
		...layersFor("small"),
		{ sandbox: { runtime: { podOverhead: { cpuMillis: 1, memoryMi: 1 } } } },
	]);
	assert.equal(retired.ok, false);
	assert.match(retired.stderr, /podOverhead/u);
});

const quotaDisk = (renderedResources: Resource[]) =>
	amount(get(sandboxQuota(renderedResources), "spec", "hard", "requests.ephemeral-storage"));

await test("quotas admit schema-supported template quantities and round fractional quota units up", () => {
	const base = [
		...layersFor("small"),
		{ sandbox: { conversationTurns: 8, reviewMemory: memoryFor(2) } },
	];
	const requests = { cpu: "0.0001k", memory: "1.5Gi", "ephemeral-storage": "1000M" };
	const custom = [
		...layersFor("small"),
		{
			sandbox: {
				conversationTurns: 8,
				reviewMemory: "3Gi",
				templates: {
					review: { resources: { requests } },
					conversation: {
						resources: {
							requests: { cpu: "0.05", memory: "1024Ki", "ephemeral-storage": "1.5Gi" },
						},
					},
				},
			},
		},
	];
	const before = tierHard(base);
	const after = tierHard(custom);
	assert.deepEqual(after("reviews"), {
		...before("reviews"),
		memory: before("reviews").memory + 2048 * 2 ** 20,
	});
	assert.deepEqual(after("conversations"), {
		...before("conversations"),
		memory: before("conversations").memory - 3064 * 2 ** 20,
		millicpu: before("conversations").millicpu - 400,
	});
	const resources = rendered(custom);
	const review = resources.find(
		(resource) => kindOf(resource) === "SandboxTemplate" && nameOf(resource).endsWith("-review"),
	);
	assert.deepEqual(
		get(review, "spec", "podTemplate", "spec", "containers", 0, "resources", "requests"),
		requests,
	);
	assert.equal(quotaDisk(resources) - quotaDisk(rendered(base)), 10_100 * 2 ** 20);
});

await test("the small profile fits its declared budget at steady state and the drained-upgrade peak", () => {
	// Allocatable CPU and memory come from the same declared profile budget as admission.
	const node = nodeRoom();
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small"),
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	]);
	const server = workload(resources, "server");
	assert.equal(get(server, "spec", "replicas"), 1);
	const turns = Number(envValue(server, "HEPHAESTUS_MENTOR_TURN_CAPACITY"));
	const reviewSlots = Number(envValue(server, "SANDBOX_MAX_CONCURRENT"));
	const backup = get(
		resources.find((resource) => kindOf(resource) === "CronJob"),
		"spec",
		"jobTemplate",
		"spec",
		"template",
		"spec",
	);
	const roles = workloads(resources).map((resource): [unknown, number] => [
		podSpec(resource),
		Number(get(resource, "spec", "replicas") ?? 1),
	]);
	// Steady: every role, one backup, every review, and a conversation sandbox for each turn up to the
	// admission limit.
	const conversations = turns;
	const steady = sum(total([...roles, [backup, 1]]), {
		cpu: reviewSlots * sandboxPods.review.cpu + conversations * sandboxPods.conversation.cpu,
		memory: (reviewSlots * sandboxGib.review + conversations * sandboxGib.conversation) * 2 ** 30,
	});
	assert.equal(turns, 7);
	assert.equal(reviewSlots, 5);
	assert.ok(steady.cpu <= node.cpu, `steady: ${steady.cpu.toFixed(2)} CPU of ${node.cpu}`);
	assert.ok(
		steady.memory <= node.memory,
		`steady: ${gib(steady.memory)} of ${gib(node.memory)} GiB`,
	);
	// An upgrade keeps the old server beside its replacement. Other Deployments can surge,
	// and the migration and stanza Jobs can run beside the resident conversation sandboxes.
	const jobs = resources
		.filter((resource) => kindOf(resource) === "Job")
		.map((job): [unknown, number] => [podSpec(job), 1]);
	const upgrade = sum(
		total([
			...workloads(resources).map((resource): [unknown, number] => [
				podSpec(resource),
				Number(get(resource, "spec", "replicas") ?? 1) +
					(kindOf(resource) === "Deployment" ? surge(resource) : 0),
			]),
			...jobs,
			[backup, 1],
		]),
		{
			cpu: conversations * sandboxPods.conversation.cpu,
			memory: conversations * sandboxGib.conversation * 2 ** 30,
		},
	);
	assert.ok(upgrade.cpu <= node.cpu, `upgrade: ${upgrade.cpu.toFixed(2)} CPU of ${node.cpu}`);
	assert.ok(
		upgrade.memory <= node.memory,
		`upgrade: ${gib(upgrade.memory)} of ${gib(node.memory)} GiB`,
	);
	process.stdout.write(
		`declared budget of ${gib(node.memory)} GiB: ${reviewSlots} reviews, steady ${gib(steady.memory)} GiB ${steady.cpu.toFixed(2)} CPU, upgrade ${gib(upgrade.memory)} GiB ${upgrade.cpu.toFixed(2)} CPU\n`,
	);
});

const value = (priority: unknown) => Number(get(priority, "value"));

const inNamespace = (namespace: string) =>
	rendered(layersFor("small"), { release: "hephaestus", namespace })
		.filter((resource) => kindOf(resource) === "PriorityClass")
		.map((resource) => String(get(resource, "metadata", "name")));

await test("roles outrank conversations, which outrank reviews, and a review never preempts", () => {
	const resources = rendered(layersFor("small"));
	const classes = new Map(
		resources
			.filter((resource) => kindOf(resource) === "PriorityClass")
			.map((resource) => [String(get(resource, "metadata", "name")), resource]),
	);
	const conversations = classes.get(String(templatePriority(resources, "conversation")));
	const reviewClass = classes.get(String(templatePriority(resources, "review")));
	assert.ok(conversations && reviewClass, "the server names classes that the chart creates");
	const roleClasses = new Set(
		[...workloads(resources), ...resources.filter((candidate) => kindOf(candidate) === "Job")].map(
			(resource) => get(podSpec(resource), "priorityClassName"),
		),
	);
	assert.equal(roleClasses.size, 1, "every role Pod has the same class");
	const roles = classes.get(String([...roleClasses][0]));
	assert.ok(roles, "the roles' class exists");
	assert.ok(value(roles) > value(conversations) && value(conversations) > value(reviewClass));
	assert.ok(value(roles) < 1_000_000_000, "below the system classes");
	assert.equal(get(reviewClass, "preemptionPolicy"), "Never");
	assert.equal(classes.size, 3);
	for (const priority of classes.values()) {
		assert.equal(get(priority, "globalDefault"), false);
	}
	// Cluster-wide names: another release or namespace gets its own.
	const elsewhere = new Set(inNamespace("other"));
	assert.ok(inNamespace("hephaestus").every((name) => !elsewhere.has(name)));
	const withoutSandboxes = rendered(layersFor("dev")).filter(
		(resource) => kindOf(resource) === "PriorityClass",
	);
	assert.equal(withoutSandboxes.length, 1, "no sandbox classes without sandboxes");
});

await test("on a shared cluster, the chart uses the operator's PriorityClasses and creates none", () => {
	const operators = {
		roles: "platform-high",
		conversations: "people-waiting",
		reviews: "batch-low",
	};
	const resources = rendered([
		...layersFor("small"),
		{ priorityClasses: { create: false, ...operators } },
	]);
	assert.equal(resources.filter((each) => kindOf(each) === "PriorityClass").length, 0);
	for (const pod of [
		...workloads(resources),
		...resources.filter((each) => kindOf(each) === "Job"),
	]) {
		assert.equal(get(podSpec(pod), "priorityClassName"), operators.roles, nameOf(pod));
	}
	assert.equal(templatePriority(resources, "conversation"), operators.conversations);
	for (const tier of ["reviews", "conversations"] as const) {
		assert.deepEqual(
			get(tierQuota(resources, tier), "spec", "scopeSelector", "matchExpressions", 0, "values"),
			[operators[tier]],
			`the ${tier} quota selects the operator's class`,
		);
	}
	assert.equal(templatePriority(resources, "review"), operators.reviews);
});

await test("external services and disabled sandboxes have no disruption budget", () => {
	const resources = rendered([prepared, operator, external]);
	assert.deepEqual(
		resources.filter((resource) => kindOf(resource) === "PodDisruptionBudget").map(componentOf),
		["server"],
	);
});

await test("each sandbox kind has a cold-start template of one container, behind the namespace's own policies", () => {
	const runc = rendered([...layersFor("small"), sandboxLayer]);
	const templates = templatesOf(runc);
	assert.deepEqual(templates.map(nameOf).toSorted(byCodePoint), [
		"heph-sandbox-conversation",
		"heph-sandbox-review",
	]);
	const pools = runc.filter((resource) => kindOf(resource) === "SandboxWarmPool");
	for (const template of templates) {
		assert.equal(get(template, "metadata", "namespace"), sandboxNamespace);
		// The controller's own policy allows the internet; the namespace's policies apply instead.
		assert.equal(get(template, "spec", "networkPolicyManagement"), "Unmanaged");
		// A claim adds the sandbox token and the gateway address and cannot change the template's values.
		assert.equal(get(template, "spec", "envVarsInjectionPolicy"), "Allowed");
		const kind = nameOf(template).replace("heph-sandbox-", "");
		const pod = get(template, "spec", "podTemplate", "spec");
		assert.equal(
			get(template, "spec", "podTemplate", "metadata", "labels", "hephaestus.build/sandbox-kind"),
			kind,
		);
		assert.equal(get(pod, "automountServiceAccountToken"), false);
		// The Job sandboxes' RuntimeClass (sandbox.runtime) is not the templates'; they have their own.
		assert.equal(get(pod, "runtimeClassName"), undefined);
		assert.equal(get(pod, "hostUsers"), false);
		assert.deepEqual(get(pod, "securityContext", "seccompProfile"), { type: "RuntimeDefault" });
		assert.deepEqual(get(pod, "nodeSelector"), sandboxRuntime.nodeSelector);
		assert.equal(get(pod, "initContainers"), undefined);
		const [agent, ...others] = list(get(pod, "containers"));
		assert.equal(others.length, 0);
		assert.equal(get(agent, "securityContext", "allowPrivilegeEscalation"), false);
		assert.deepEqual(get(agent, "securityContext", "capabilities", "drop"), ["ALL"]);
		// The image is read-only: the sandbox writes only to /workspace and /tmp, each bounded by the kind's disk limit.
		assert.equal(get(agent, "securityContext", "readOnlyRootFilesystem"), true);
		const disk = get(agent, "resources", "limits", "ephemeral-storage");
		assert.deepEqual(get(pod, "volumes"), [
			{ name: "workspace", emptyDir: { sizeLimit: disk } },
			{ name: "tmp", emptyDir: { sizeLimit: disk } },
		]);
		assert.deepEqual(get(agent, "volumeMounts"), [
			{ name: "workspace", mountPath: "/workspace" },
			{ name: "tmp", mountPath: "/tmp" },
		]);
		// HOME is writable: it is on /tmp.
		const variable = (name: string) =>
			get(
				list(get(agent, "env")).find((entry) => get(entry, "name") === name),
				"value",
			);
		assert.equal(variable("HOME"), "/tmp/home");
		assert.equal(variable("XDG_CONFIG_HOME"), "/tmp/home/.config");
		const tier = kind === "review" ? "reviews" : "conversations";
		assert.equal(get(pod, "priorityClassName"), `hephaestus-heph-${tier}`);
		const pool = pools.find((candidate) => nameOf(candidate) === nameOf(template));
		assert.deepEqual(get(pool, "spec"), {
			replicas: 0,
			sandboxTemplateRef: { name: nameOf(template) },
		});
	}

	const gvisor = rendered([
		...layersFor("small"),
		sandboxLayer,
		{ sandbox: { templates: { runtimeClassName: "gvisor" } } },
	]);
	for (const template of templatesOf(gvisor)) {
		const pod = get(template, "spec", "podTemplate", "spec");
		assert.equal(get(pod, "runtimeClassName"), "gvisor");
		// gVisor does not support user namespaces (google/gvisor#13303).
		assert.equal(get(pod, "hostUsers"), undefined);
	}

	// The sandbox nodes hold the agent image before any claim needs it, on the templates' class.
	const holder = gvisor.find(
		(resource) => kindOf(resource) === "DaemonSet" && componentOf(resource) === "agent-image",
	);
	assert.equal(get(podSpec(holder), "runtimeClassName"), "gvisor");
	assert.deepEqual(get(podSpec(holder), "nodeSelector"), sandboxRuntime.nodeSelector);
	assert.deepEqual(get(podSpec(holder), "tolerations"), sandboxRuntime.tolerations);
	assert.equal(
		get(container(holder), "image"),
		get(list(get(templates[0], "spec", "podTemplate", "spec", "containers"))[0], "image"),
	);
	assert.equal(get(podSpec(holder), "automountServiceAccountToken"), false);
});

await test("the footprint benchmark runs a review in the container that the review template ships", async () => {
	// What scripts/sandbox-footprint.ts passes to Docker for the template's part of a review container.
	const flags = templateFlags(
		DISK_VOLUMES.map((target) => `volume${target}`),
		await chartReviewLimits(),
	);
	const after = (flag: string) => flags.filter((_, index) => flags[index - 1] === flag);
	const harnessMounts = after("--mount").map((mount) => {
		const fields = mount.split(",");
		return {
			path: fields.find((field) => field.startsWith("dst="))?.slice(4),
			readOnly: fields.includes("readonly"),
		};
	});
	const harnessEnvironment = after("--env").map((entry) => {
		const split = entry.indexOf("=");
		return { name: entry.slice(0, split), value: entry.slice(split + 1) };
	});
	for (const name of profiles) {
		const review = templatesOf(rendered(withSandboxes(name))).find(
			(template) => nameOf(template) === "heph-sandbox-review",
		);
		const agent = list(get(review, "spec", "podTemplate", "spec", "containers"))[0];
		assert.deepEqual(
			harnessMounts.toSorted(byPath),
			list(get(agent, "volumeMounts"))
				.map((mount) => ({
					path: get(mount, "mountPath"),
					readOnly: get(mount, "readOnly") === true,
				}))
				.toSorted(byPath),
			`${name}: the benchmark mounts what the review template mounts`,
		);
		assert.equal(
			flags.includes("--read-only"),
			get(agent, "securityContext", "readOnlyRootFilesystem") === true,
		);
		// The benchmark runs the review kind's programs, not the driver that reads the sandbox kind.
		assert.deepEqual(
			harnessEnvironment,
			list(get(agent, "env")).filter((entry) => get(entry, "name") !== "HEPHAESTUS_SANDBOX_KIND"),
			`${name}: the benchmark sets the review template's environment`,
		);
		const limits = get(agent, "resources", "limits");
		assert.deepEqual(
			{ memory: after("--memory")[0], cpus: after("--cpus")[0] },
			reviewLimits(stringify({ sandbox: { templates: { review: { resources: { limits } } } } })),
			`${name}: the benchmark runs under the review template's limits`,
		);
	}
});

await test("the cluster profile renders only namespaced objects, and its PriorityClasses are the admin bundle's", () => {
	const resources = rendered(layersFor("cluster"));
	assert.deepEqual(
		resources.filter((resource) => clusterScoped.has(String(resource.kind))).map(nameOf),
		[],
	);
	const classNames = new Set(
		adminBundle.filter((resource) => resource.kind === "PriorityClass").map(nameOf),
	);
	assert.deepEqual([...classNames].toSorted(), [
		"hephaestus-conversations",
		"hephaestus-reviews",
		"hephaestus-roles",
	]);
	// Every Pod that the release runs, and every sandbox template, names one of the bundle's classes.
	const priorityNames = resources
		.map((resource) =>
			get(resource, "kind") === "SandboxTemplate"
				? get(resource, "spec", "podTemplate", "spec", "priorityClassName")
				: get(resource, "spec", "template", "spec", "priorityClassName"),
		)
		.filter((name) => typeof name === "string");
	assert.ok(priorityNames.length > 0);
	for (const name of priorityNames) {
		assert.ok(classNames.has(name), name);
	}
	// The default runtime with user namespaces: no RuntimeClass, no host users.
	for (const template of resources.filter((resource) => resource.kind === "SandboxTemplate")) {
		const pod = get(template, "spec", "podTemplate", "spec");
		assert.equal(get(pod, "runtimeClassName"), undefined, nameOf(template));
		assert.equal(get(pod, "hostUsers"), false, nameOf(template));
	}
});

await test("the admin bundle's PriorityClasses are the ones that the chart creates on a cluster of its own", () => {
	const created = rendered(layersFor("small")).filter(
		(resource) => resource.kind === "PriorityClass",
	);
	const classes = adminBundle.filter((resource) => resource.kind === "PriorityClass");
	assert.deepEqual(byTier(classes), byTier(created));
});

await test("the parallel pressure drill waits for the chart's priorities and excludes role and sandbox Pods from its node", () => {
	const resources = rendered([
		...layersFor("dev"),
		{
			sandbox: {
				enabled: true,
				namespace: sandboxNamespace,
				templates: { runtimeClassName: "gvisor" },
			},
			networkPolicy: { kubernetesApi },
		},
	]);
	assert.ok(
		resources.some(
			(resource) => kindOf(resource) === "Deployment" && nameOf(resource) === "heph-server",
		),
	);
	assert.ok(
		!resources.some(
			(resource) => kindOf(resource) === "Deployment" && nameOf(resource) === "heph-worker",
		),
	);
	const priorities = resources.filter((resource) => kindOf(resource) === "PriorityClass");
	assert.deepEqual(
		priorities.map((resource) => `priorityclass/${nameOf(resource)}`).toSorted(),
		priorityClassResources.toSorted(),
	);
	const templates = resources.filter((resource) => kindOf(resource) === "SandboxTemplate");
	assert.ok(templates.length > 0);
	for (const template of templates) {
		assert.equal(get(template, "spec", "podTemplate", "spec", "runtimeClassName"), "gvisor");
	}
	const pods = [
		...workloads(resources).map(podSpec),
		...templates.map((resource) => get(resource, "spec", "podTemplate", "spec")),
	];
	assert.ok(pods.length > 0);
	for (const pod of pods) {
		const tolerations = list(get(pod, "tolerations") ?? []);
		assert.equal(
			tolerations.some(
				(toleration) =>
					(get(toleration, "effect") === undefined ||
						get(toleration, "effect") === "" ||
						get(toleration, "effect") === "NoSchedule") &&
					(get(toleration, "key") === pressureNodeTaint ||
						((get(toleration, "key") === undefined || get(toleration, "key") === "") &&
							get(toleration, "operator") === "Exists")),
			),
			false,
			"the pressure node must not admit production role or sandbox Pods",
		);
	}
});
