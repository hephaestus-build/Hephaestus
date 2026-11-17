import assert from "node:assert/strict";
import { test } from "node:test";
import { isDeepStrictEqual } from "node:util";
import {
	componentOf,
	container,
	env,
	envValue,
	gatewayPods,
	get,
	kindOf,
	layersFor,
	list,
	nameOf,
	operator,
	parentRefs,
	podSpec,
	prepared,
	profile,
	render,
	rendered,
	reviews,
	runsReviews,
	sandboxNamespace,
	workload,
	type Resource,
} from "./kubernetes-chart-render-support.ts";
import { isRecord } from "./lib/json.ts";

await test("the server serves the web app, so the chart runs no web app workload", () => {
	for (const layers of [
		layersFor("small"),
		[...layersFor("small"), { workspaceSubdomains: { enabled: true } }],
	]) {
		assert.deepEqual(
			rendered(layers)
				.filter((resource) => componentOf(resource) === "webapp")
				.map(nameOf),
			[],
		);
	}
});

await test("the server takes the security contact from one URI value", () => {
	const contact = "mailto:security@example.invalid";
	const server = workload(
		rendered([...layersFor("small"), { server: { securityContact: contact } }]),
		"server",
	);
	assert.equal(envValue(server, "SECURITY_CONTACT"), contact);
	assert.equal(envValue(workload(rendered(layersFor("small")), "server"), "SECURITY_CONTACT"), "");
	for (const refused of ["mailto:a b@example.invalid", "javascript:alert(1)"]) {
		assert.match(
			render([...layersFor("small"), { server: { securityContact: refused } }]).stderr,
			/securityContact/u,
			refused,
		);
	}
});

await test("the server serves the operator's legal pages from a ConfigMap, read-only", () => {
	const legal = { name: "legal-overrides", mountPath: "/etc/hephaestus/legal", readOnly: true };
	const plain = workload(rendered(layersFor("small")), "server");
	assert.equal(env(plain, "LEGAL_OVERRIDES_DIRECTORY"), undefined);
	assert.ok(
		!list(get(container(plain), "volumeMounts")).some((mount) => isDeepStrictEqual(mount, legal)),
	);
	assert.ok(
		!list(get(podSpec(plain), "volumes")).some((volume) => get(volume, "name") === legal.name),
	);
	const server = workload(
		rendered([...layersFor("small"), { server: { legalOverrides: "site-legal" } }]),
		"server",
	);
	assert.equal(envValue(server, "LEGAL_OVERRIDES_DIRECTORY"), legal.mountPath);
	assert.deepEqual(
		list(get(container(server), "volumeMounts")).filter(
			(mount) => get(mount, "name") === legal.name,
		),
		[legal],
	);
	assert.deepEqual(
		list(get(podSpec(server), "volumes")).filter((volume) => get(volume, "name") === legal.name),
		[{ name: legal.name, configMap: { name: "site-legal" } }],
	);
	assert.match(
		render([...layersFor("small"), { server: { legalOverrides: "../etc" } }]).stderr,
		/legalOverrides/u,
	);
});

const filters = (candidate: unknown) => list(get(candidate, "filters"));

const backends = (candidate: unknown) => get(candidate, "backendRefs");

await test("the route attaches to the platform Gateway and sends every path to the server whole", () => {
	const resources = rendered(layersFor("small"));
	const routes = resources.filter((resource) => kindOf(resource) === "HTTPRoute");
	assert.equal(routes.length, 1);
	const route = routes[0];
	assert.deepEqual(get(route, "spec", "parentRefs"), parentRefs);
	assert.deepEqual(get(route, "spec", "hostnames"), [operator.host]);
	const rules = list(get(route, "spec", "rules"));
	const rule = (type: string, value: string) => {
		const found = rules.filter((candidate) =>
			list(get(candidate, "matches")).some(
				(match) => get(match, "path", "type") === type && get(match, "path", "value") === value,
			),
		);
		assert.equal(found.length, 1, `${type} ${value}`);
		return found[0];
	};
	// The provider webhooks and the web app fall under the root rule.
	assert.equal(rules.length, 2);
	assert.deepEqual(backends(rule("PathPrefix", "/api")), [{ name: "heph-server", port: 8080 }]);
	assert.deepEqual(backends(rule("PathPrefix", "/")), [{ name: "heph-server", port: 8080 }]);
	for (const candidate of rules) {
		// The server serves the paths it receives and sends its own headers, HSTS included.
		assert.deepEqual(filters(candidate), [], "no rule has a filter");
		// Only /api asks the controller to disable both deadlines, for the Hephaestus live stream.
		assert.deepEqual(
			get(candidate, "timeouts"),
			candidate === rule("PathPrefix", "/api")
				? { request: "0s", backendRequest: "0s" }
				: undefined,
			"only /api sets timeouts",
		);
		assert.equal(get(candidate, "retry"), undefined, "no rule retries");
	}
	// The Services the route names exist and listen on the same port.
	for (const backend of rules.flatMap((candidate) => list(backends(candidate)))) {
		const service = resources.find(
			(resource) => kindOf(resource) === "Service" && nameOf(resource) === get(backend, "name"),
		);
		assert.equal(get(service, "spec", "ports", 0, "port"), get(backend, "port"));
	}
});

for (const name of ["small", "external"] as const) {
	await test(`${name}: network policy denies by default and names exact peers`, () => {
		const resources = rendered(layersFor(name));
		const policies = resources.filter((resource) => kindOf(resource) === "NetworkPolicy");
		const policy = (component: string) => {
			const found = policies.find((candidate) => nameOf(candidate) === `heph-${component}`);
			assert.ok(found, component);
			return found;
		};
		const deny = policy("default-deny");
		assert.deepEqual(get(deny, "spec"), {
			podSelector: {
				matchLabels: {
					"app.kubernetes.io/name": "hephaestus",
					"app.kubernetes.io/instance": "heph",
				},
			},
			policyTypes: ["Ingress", "Egress"],
		});
		for (const candidate of policies) {
			for (const rule of [
				...list(get(candidate, "spec", "ingress")),
				...list(get(candidate, "spec", "egress")),
			]) {
				for (const peer of [...list(get(rule, "from")), ...list(get(rule, "to"))]) {
					if (get(peer, "ipBlock") !== undefined) {
						continue;
					}
					const selector = get(peer, "podSelector");
					const labels = get(selector, "matchLabels");
					assert.ok(
						(isRecord(labels) && Object.keys(labels).length > 0) ||
							list(get(selector, "matchExpressions")).length > 0,
						`${nameOf(candidate)} admits a whole namespace`,
					);
				}
			}
		}
		const gatewayPeer = {
			namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "gateway-system" } },
			podSelector: { matchLabels: gatewayPods },
		};
		assert.equal(
			policies.some((candidate) => nameOf(candidate) === "heph-webapp"),
			false,
			"no web app workload",
		);
		const ingress = list(get(policy("server"), "spec", "ingress"));
		// The server's sandbox gateway admits the sandboxes and the loop, on its own port.
		const gatewayRules = runsReviews(name) ? 1 : 0;
		assert.equal(ingress.length, 1 + gatewayRules, "no scraper is configured");
		// Only the Gateway reaches the application port.
		assert.deepEqual(get(ingress[0], "from"), [gatewayPeer]);
		assert.deepEqual(get(ingress[0], "ports"), [{ port: "http", protocol: "TCP" }]);
		for (const rule of ingress.slice(1)) {
			// No rule of the sandbox gateway names the Gateway, so no public route reaches port 8081.
			assert.deepEqual(get(rule, "ports"), [{ port: 8081, protocol: "TCP" }]);
			assert.ok(!isDeepStrictEqual(get(rule, "from"), [gatewayPeer]));
		}
		if (runsReviews(name)) {
			const [, sandboxes] = list(get(policy("server"), "spec", "ingress"));
			assert.deepEqual(get(sandboxes, "from"), [
				{
					namespaceSelector: {
						matchLabels: { "kubernetes.io/metadata.name": sandboxNamespace },
					},
					podSelector: {
						matchExpressions: [
							{
								key: "hephaestus.build/sandbox-kind",
								operator: "In",
								values: ["review", "conversation"],
							},
						],
					},
				},
			]);
		}
		const serverEgress = list(get(policy("server"), "spec", "egress"));
		if (name === "external") {
			assert.ok(
				serverEgress.some(
					(rule) =>
						get(rule, "to", 0, "ipBlock", "cidr") === "10.0.12.0/24" &&
						get(rule, "ports", 0, "port") === 5432,
				),
			);
		}
		// Internet HTTPS never includes private ranges.
		const internet = serverEgress.filter(
			(rule) => get(rule, "to", 0, "ipBlock", "cidr") === "0.0.0.0/0",
		);
		assert.equal(internet.length, 1);
		assert.deepEqual(get(internet[0], "ports"), [{ port: 443, protocol: "TCP" }]);
		assert.ok(list(get(internet[0], "to", 0, "ipBlock", "except")).includes("10.0.0.0/8"));
	});
}

await test("Slack callbacks use the canonical API origin in either workspace-host mode", () => {
	for (const enabled of [false, true]) {
		const resources = rendered([
			...layersFor("small"),
			{
				host: "callbacks.example.test",
				workspaceSubdomains: { enabled },
				config: { slack: { enabled: true, clientId: "slack-client" } },
			},
		]);
		const server = workload(resources, "server");
		assert.equal(envValue(server, "HEPHAESTUS_AUTH_ISSUER"), "https://callbacks.example.test");
		assert.equal(envValue(server, "SPRING_PROFILES_ACTIVE"), "prod");
		assert.equal(env(server, "HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI"), undefined);
	}
});

await test("one value turns browser fault reports on for the server, which serves the browser", () => {
	for (const browserFaultReports of [false, true]) {
		const resources = rendered([...layersFor("small"), { config: { browserFaultReports } }]);
		assert.equal(
			envValue(workload(resources, "server"), "BROWSER_FAULT_REPORTS"),
			String(browserFaultReports),
		);
	}
});

await test("one value turns the workspace MCP endpoint on for the server, off by default", () => {
	assert.equal(
		envValue(workload(rendered(layersFor("small")), "server"), "HEPHAESTUS_MCP_ENABLED"),
		"false",
	);
	const resources = rendered([...layersFor("small"), { config: { mcpEnabled: true } }]);
	assert.equal(envValue(workload(resources, "server"), "HEPHAESTUS_MCP_ENABLED"), "true");
});

const routeOf = (resources: Resource[]) =>
	resources.find((resource) => kindOf(resource) === "HTTPRoute");

await test("with MCP on, OAuth discovery on the host root reaches the server without a rewrite", () => {
	const off = routeOf(rendered(layersFor("small")));
	const on = routeOf(rendered([...layersFor("small"), { config: { mcpEnabled: true } }]));
	// The root rule already sends /.well-known to the server, so MCP adds no rule of its own.
	assert.deepEqual(get(on, "spec"), get(off, "spec"));
	const root = list(get(on, "spec", "rules")).find((rule) =>
		list(get(rule, "matches")).some((match) => get(match, "path", "value") === "/"),
	);
	assert.deepEqual(get(root, "backendRefs"), [{ name: "heph-server", port: 8080 }]);
	assert.deepEqual(filters(root), []);
});

await test("workspace hosts reach the server and share the apex origin settings", () => {
	const layers = [profile("small"), prepared, operator, reviews];
	const off = rendered(layers);
	assert.equal(off.filter((resource) => kindOf(resource) === "HTTPRoute").length, 1);
	const resources = rendered([...layers, { workspaceSubdomains: { enabled: true } }]);
	const routes = resources.filter((resource) => kindOf(resource) === "HTTPRoute");
	const tenant = routes.find(
		(resource) => get(resource, "spec", "hostnames", 0) === "*.hephaestus.example.com",
	);
	assert.ok(tenant);
	// The server refuses the API and sign-in on a workspace host and sends its own HSTS there.
	for (const rule of list(get(tenant, "spec", "rules"))) {
		assert.deepEqual(filters(rule), []);
		assert.deepEqual(backends(rule), [{ name: "heph-server", port: 8080 }]);
	}
	const server = workload(resources, "server");
	assert.equal(envValue(server, "HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED"), "true");
	assert.equal(
		envValue(server, "HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN"),
		"hephaestus.example.com",
	);
});

await test("an apex host with a double hyphen still serves its workspace hosts", () => {
	// Only a workspace label may not hold a double hyphen, so the chart accepts such an apex host.
	for (const host of ["hephaestus.example.com", "heph--prod.example.org"]) {
		const resources = rendered([
			profile("small"),
			prepared,
			operator,
			reviews,
			{ host, workspaceSubdomains: { enabled: true } },
		]);
		assert.ok(
			resources.some(
				(resource) =>
					kindOf(resource) === "HTTPRoute" && get(resource, "spec", "hostnames", 0) === `*.${host}`,
			),
			host,
		);
		assert.equal(
			envValue(workload(resources, "server"), "HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN"),
			host,
		);
	}
});
