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
} from "./kubernetes-chart-render-support.ts";
import { isRecord } from "./lib/json.ts";

await test("the webapp runs the non-root image with one writable subtree on 8080", () => {
	const resources = rendered(layersFor("small"));
	const webapp = workload(resources, "webapp");
	const spec = podSpec(webapp);
	assert.equal(get(spec, "securityContext", "runAsUser"), 101);
	assert.equal(get(spec, "securityContext", "runAsGroup"), 101);
	assert.equal(get(spec, "securityContext", "fsGroup"), 101);
	assert.equal(get(spec, "initContainers"), undefined);
	const entry = container(webapp);
	assert.equal(get(entry, "command"), undefined);
	assert.equal(get(entry, "args"), undefined);
	assert.deepEqual(get(entry, "ports"), [{ name: "http", containerPort: 8080 }]);
	assert.deepEqual(get(entry, "volumeMounts"), [
		{ name: "runtime", mountPath: "/tmp/hephaestus-webapp" },
	]);
	assert.deepEqual(get(spec, "volumes"), [{ name: "runtime", emptyDir: { sizeLimit: "16Mi" } }]);
	for (const probe of ["startupProbe", "livenessProbe", "readinessProbe"]) {
		assert.deepEqual(get(entry, probe, "httpGet"), { path: "/", port: "http" }, probe);
	}
	const service = resources.find(
		(resource) => kindOf(resource) === "Service" && componentOf(resource) === "webapp",
	);
	assert.deepEqual(get(service, "spec", "ports"), [
		{ name: "http", port: 8080, targetPort: "http" },
	]);
});

await test("the webapp takes its security contact from one URI value", () => {
	const contact = "mailto:security@example.invalid";
	const webapp = workload(
		rendered([...layersFor("small"), { webapp: { securityContact: contact } }]),
		"webapp",
	);
	assert.equal(envValue(webapp, "SECURITY_CONTACT"), contact);
	assert.equal(envValue(workload(rendered(layersFor("small")), "webapp"), "SECURITY_CONTACT"), "");
	for (const refused of ["mailto:a b@example.invalid", "javascript:alert(1)"]) {
		assert.match(
			render([...layersFor("small"), { webapp: { securityContact: refused } }]).stderr,
			/securityContact/u,
			refused,
		);
	}
});

await test("the webapp serves the operator's legal pages from a ConfigMap, read-only", () => {
	const webapp = workload(
		rendered([...layersFor("small"), { webapp: { legalOverrides: "site-legal" } }]),
		"webapp",
	);
	assert.deepEqual(get(container(webapp), "volumeMounts"), [
		{ name: "runtime", mountPath: "/tmp/hephaestus-webapp" },
		{
			name: "legal-overrides",
			mountPath: "/usr/share/nginx/html/legal-overrides",
			readOnly: true,
		},
	]);
	assert.deepEqual(get(podSpec(webapp), "volumes", 1), {
		name: "legal-overrides",
		configMap: { name: "site-legal" },
	});
	assert.match(
		render([...layersFor("small"), { webapp: { legalOverrides: "../etc" } }]).stderr,
		/legalOverrides/u,
	);
});

const filters = (candidate: unknown) => list(get(candidate, "filters"));

const backends = (candidate: unknown) => get(candidate, "backendRefs");

await test("the route attaches to the platform Gateway and strips only /api", () => {
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
	assert.equal(rules.length, 3);
	assert.deepEqual(backends(rule("PathPrefix", "/webhooks")), [
		{ name: "heph-server", port: 8080 },
	]);
	assert.deepEqual(backends(rule("PathPrefix", "/api")), [{ name: "heph-server", port: 8080 }]);
	assert.deepEqual(backends(rule("PathPrefix", "/")), [{ name: "heph-webapp", port: 8080 }]);
	assert.deepEqual(
		filters(rule("PathPrefix", "/api")).filter((filter) => get(filter, "type") === "URLRewrite"),
		[
			{
				type: "URLRewrite",
				urlRewrite: { path: { type: "ReplacePrefixMatch", replacePrefixMatch: "/" } },
			},
		],
	);
	for (const candidate of rules) {
		const types = new Set(filters(candidate).map((filter) => get(filter, "type")));
		assert.ok(types.has("ResponseHeaderModifier"), "HSTS on every rule");
		assert.equal(
			types.has("URLRewrite"),
			candidate === rule("PathPrefix", "/api"),
			"only /api is rewritten",
		);
		// Only /api asks the controller to disable both deadlines, for the Heph live stream.
		assert.deepEqual(
			get(candidate, "timeouts"),
			candidate === rule("PathPrefix", "/api")
				? { request: "0s", backendRequest: "0s" }
				: undefined,
			"only /api sets timeouts",
		);
		assert.equal(get(candidate, "retry"), undefined, "no rule retries");
	}
	const hsts = filters(rules[0]).find((filter) => get(filter, "type") === "ResponseHeaderModifier");
	assert.deepEqual(get(hsts, "responseHeaderModifier", "set"), [
		{ name: "Strict-Transport-Security", value: "max-age=2592000; includeSubDomains" },
	]);
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
		for (const [component, port] of [
			["server", "http"],
			["webapp", "http"],
		] as const) {
			const ingress = list(get(policy(component), "spec", "ingress"));
			// The server's sandbox gateway admits the sandboxes and the loop, on its own port.
			const gatewayRules = component === "server" && runsReviews(name) ? 1 : 0;
			assert.equal(ingress.length, 1 + gatewayRules, `${component}: no scraper is configured`);
			// Only the Gateway reaches the application port.
			assert.deepEqual(get(ingress[0], "from"), [gatewayPeer], component);
			assert.deepEqual(get(ingress[0], "ports"), [{ port, protocol: "TCP" }], component);
			for (const rule of ingress.slice(1)) {
				// No rule of the sandbox gateway names the Gateway, so no public route reaches port 8081.
				assert.deepEqual(get(rule, "ports"), [{ port: 8081, protocol: "TCP" }], component);
				assert.ok(!isDeepStrictEqual(get(rule, "from"), [gatewayPeer]), component);
			}
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

await test("one value turns browser fault reports on for the server and for the browser", () => {
	for (const browserFaultReports of [false, true]) {
		const resources = rendered([...layersFor("small"), { config: { browserFaultReports } }]);
		for (const component of ["server", "webapp"]) {
			assert.equal(
				envValue(workload(resources, component), "BROWSER_FAULT_REPORTS"),
				String(browserFaultReports),
				component,
			);
		}
	}
});

await test("workspace hosts reach only the SPA and share the apex origin settings", () => {
	const layers = [profile("small"), prepared, operator, reviews];
	const off = rendered(layers);
	assert.equal(off.filter((resource) => kindOf(resource) === "HTTPRoute").length, 1);
	const resources = rendered([...layers, { workspaceSubdomains: { enabled: true } }]);
	const routes = resources.filter((resource) => kindOf(resource) === "HTTPRoute");
	const tenant = routes.find(
		(resource) => get(resource, "spec", "hostnames", 0) === "*.hephaestus.example.com",
	);
	assert.ok(tenant);
	assert.deepEqual(
		list(get(tenant, "spec", "rules"))
			.flatMap((rule) => list(get(rule, "backendRefs")))
			.map((backend) => get(backend, "name")),
		["heph-webapp"],
	);
	for (const component of ["server", "webapp"]) {
		const deployment = resources.find(
			(resource) => kindOf(resource) === "Deployment" && componentOf(resource) === component,
		);
		const variables = list(get(podSpec(deployment), "containers", 0, "env"));
		assert.equal(
			get(
				variables.find((entry) => get(entry, "name") === "HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED"),
				"value",
			),
			"true",
		);
		assert.equal(
			get(
				variables.find(
					(entry) => get(entry, "name") === "HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN",
				),
				"value",
			),
			"hephaestus.example.com",
		);
	}
});

await test("tenant hostname validation checks double hyphens only in the workspace label", () => {
	for (const host of ["hephaestus.example.com", "heph--prod.example.org"]) {
		const resources = rendered([
			profile("small"),
			prepared,
			operator,
			reviews,
			{ host, workspaceSubdomains: { enabled: true } },
		]);
		const routing = resources.find(
			(resource) =>
				kindOf(resource) === "ConfigMap" &&
				componentOf(resource) === "webapp" &&
				typeof get(resource, "data", "maps.conf") === "string",
		);
		const maps = get(routing, "data", "maps.conf");
		assert.equal(typeof maps, "string");
		const pattern = /"~(?<pattern>[^"\n]+)" 1;/u.exec(String(maps))?.groups?.pattern;
		assert.ok(pattern !== undefined);
		const accepts = new RegExp(pattern, "u");
		assert.ok(accepts.test(`team-one.${host}`));
		assert.ok(accepts.test(`abc.${host}`));
		for (const slug of ["team--one", "api", "ab", "-team", "team-", "a".repeat(52)]) {
			assert.equal(accepts.test(`${slug}.${host}`), false, slug);
		}
	}
});
