import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { isMap, isScalar, isSeq, parseDocument } from "yaml";
import { isSet } from "./lib/env.ts";

const STACKS = ["app", "core", "proxy"] as const;
const MANAGEMENT_BIND = `$\${HOSTNAME}.shared-network`;

await test("reference application services do not publish host ports", () => {
	const document = parseDocument(
		readFileSync(new URL("../docker/compose.app.yaml", import.meta.url), "utf8"),
	);
	assert.equal(document.errors.length, 0);
	for (const name of ["webapp", "application-server", "postgres"]) {
		const service = document.getIn(["services", name]);
		assert.ok(isMap(service), `${name} is missing`);
		const ports = service.get("ports");
		assert.ok(
			ports === undefined || (isSeq(ports) && ports.items.length === 0),
			`${name} must be reached through the Docker network, not a published host port`,
		);
	}
});

/** A repository file, addressed relative to this test. */
function read(file: string): string {
	return readFileSync(new URL(file, import.meta.url), "utf8");
}

/** The port of the first `listen` directive in an nginx config. */
function listenPort(conf: string): string | undefined {
	return /^\s*listen (?<port>\d+);$/mu.exec(conf)?.groups?.port;
}

/** The PID file and temp paths an nginx config writes to. */
function writesIn(conf: string): string[] {
	return [...conf.matchAll(/^\s*(?:pid|\w+_temp_path) (?<path>\S+);$/gmu)].map(
		(match) => match.groups?.path ?? "",
	);
}

/** The backend ports of the Traefik services whose name matches `service`, a regex fragment. */
function servicePorts(file: string, service: string): (string | undefined)[] {
	return [
		...read(file).matchAll(
			new RegExp(`services\\.${service}\\.loadbalancer\\.server\\.port=(?<port>\\d+)`, "gu"),
		),
	].map((match) => match.groups?.port);
}

await test("every service that runs the webapp image uses one unprivileged port and one writable directory", () => {
	// The image runs as UID 101 with no capabilities on a read-only root, so a listener, Traefik
	// service or probe left on the old port 80, or a service without the runtime directory, reaches
	// nothing while the container itself still looks up.
	assert.equal(listenPort(read("../webapp/docker/nginx.conf")), "8080");
	const dockerfile = read("../webapp/Dockerfile");
	assert.match(dockerfile, /^EXPOSE 8080$/mu);
	assert.match(dockerfile, /^USER 101:101$/mu);
	const runtimeDirectory = "/tmp/hephaestus-webapp";
	// The PID file and the temp paths have one home, which both server modes include.
	const writes = [
		...writesIn(read("../webapp/docker/nginx-globals.conf")),
		...writesIn(read("../webapp/docker/nginx-http.conf")),
	];
	assert.equal(writes.length, 6, "the shared files set the PID file and all five temp paths");
	for (const target of writes) {
		assert.ok(target.startsWith(`${runtimeDirectory}/`), `${target} is outside the mount`);
	}
	for (const mode of ["nginx-main.conf", "nginx-maintenance.conf"]) {
		const conf = read(`../webapp/docker/${mode}`);
		assert.deepEqual(writesIn(conf), [], `${mode} sets its own PID or temp path`);
		assert.match(conf, /^include \/etc\/nginx\/hephaestus\/globals\.conf;$/mu, mode);
		assert.match(conf, /^\s+include \/etc\/nginx\/hephaestus\/http\.conf;$/mu, mode);
	}

	const probe = ["CMD", "curl", "-f", "http://127.0.0.1:8080/"];
	const mount = `${runtimeDirectory}:uid=101,gid=101,mode=0700,size=16m`;
	for (const [file, service] of [
		["../docker/compose.app.yaml", "webapp"],
		["../docker/compose.proxy.yaml", "maintenance"],
	] as const) {
		const document = parseDocument(read(file));
		const at = `${file} ${service}`;
		const sequence = (key: string) => {
			const node = document.getIn(["services", service, ...key.split(".")], true);
			assert.ok(isSeq(node), `${at} ${key}`);
			return node.toJSON();
		};
		assert.deepEqual(sequence("healthcheck.test"), probe, at);
		assert.equal(document.getIn(["services", service, "read_only"]), true, at);
		assert.deepEqual(sequence("tmpfs"), [mount], at);
		assert.deepEqual(sequence("cap_drop"), ["ALL"], at);
		assert.deepEqual(sequence("security_opt"), ["no-new-privileges:true"], at);
		assert.ok(!document.hasIn(["services", service, "cap_add"]), at);
		assert.ok(!document.hasIn(["services", service, "user"]), at);
	}
	assert.deepEqual(servicePorts("../docker/compose.app.yaml", "https?-webapp"), ["8080", "8080"]);
	assert.deepEqual(servicePorts("../docker/compose.proxy.yaml", "maintenance"), ["8080"]);
	assert.equal(listenPort(read("../webapp/docker/nginx-maintenance.conf")), "8080");
});

/** Every `traefik.http.routers.<name>.<key>=` value in a stack's Compose file. */
function labels(stack: (typeof STACKS)[number], key: string): { router: string; value: string }[] {
	const file = readFileSync(new URL(`../docker/compose.${stack}.yaml`, import.meta.url), "utf8");
	const pattern = new RegExp(`traefik\\.http\\.routers\\.([a-z0-9-]+)\\.${key}=(.*?)"?$`, "gmu");
	return [...file.matchAll(pattern)].flatMap(([, router, value]) =>
		router !== undefined && isSet(value) ? [{ router: `${stack}/${router}`, value }] : [],
	);
}

const rules = STACKS.flatMap((stack) => labels(stack, "rule"));
const priorities = new Map(
	STACKS.flatMap((stack) => labels(stack, "priority")).map(({ router, value }) => [
		router,
		Number(value),
	]),
);

/** The index of the parenthesis that closes the one at index 0, or -1 if the rule opens with none. */
function endOfLeadingGroup(rule: string): number {
	if (!rule.startsWith("(")) {
		return -1;
	}
	let depth = 0;
	for (let i = 0; i < rule.length; i += 1) {
		if (rule[i] === "(") {
			depth += 1;
		} else if (rule[i] === ")") {
			depth -= 1;
			if (depth === 0) {
				return i;
			}
		}
	}
	return -1;
}

await test("a router that routes on the served host set groups its matcher", () => {
	// Traefik binds && tighter than ||, so `Host(a) || Host(b) && PathPrefix(/webhooks)` matches
	// host a on *every* path. On the webhook router, which outranks the others, that hands the
	// whole site to the webhook service. Grouping the matcher is what prevents it, and it is
	// asserted for every router that names the matcher rather than only the ones combining today:
	// an ungrouped matcher reads as correct right up until someone appends a condition to it.
	// The closing parenthesis is found by counting depth, not by looking at the last character —
	// `(A) || (B) && PathPrefix(/)` also starts with `(` and ends with `)` and has the bug.
	const matcherRules = rules.filter(({ value }) => value.includes("APP_HOST_MATCH"));
	assert.notEqual(
		matcherRules.length,
		0,
		"no router routes on APP_HOST_MATCH; the pattern is stale",
	);

	for (const { router, value } of matcherRules) {
		const close = endOfLeadingGroup(value);
		assert.notEqual(close, -1, `${router} does not group its host matcher: ${value}`);
		const rest = value.slice(close + 1).trim();
		assert.ok(
			rest === "" || rest.startsWith("&&"),
			`${router} continues its host matcher outside the group: ${value}`,
		);
	}
});

await test("the canonical redirect takes the browser and leaves the API and the webhooks alone", () => {
	// An open tab and a provider still posting to an older name keep working through a domain move
	// only while /api and /webhooks outrank the redirect, and the redirect only reaches a browser
	// while it outranks the SPA router. Traefik falls back to rule length when priorities tie, which
	// nobody edits on purpose, so the promise install.mdx makes rests on these four numbers.
	const canonical = priorities.get("app/https-canonical");
	assert.notEqual(canonical, undefined, "the canonical-redirect router is gone");

	assert.ok(
		Number(canonical) > Number(priorities.get("app/https-webapp")),
		"the canonical redirect must outrank the SPA router or it never fires",
	);
	for (const router of ["app/https-application-server", "core/https-webhook-server"]) {
		assert.ok(
			Number(canonical) < Number(priorities.get(router)),
			`${router} must outrank the canonical redirect or it stops answering on the other names`,
		);
	}
});

await test("Slack interactivity reaches the server role, every other webhook the webhook role", () => {
	// A button's consent change commits on application-server before Slack is answered, and the
	// webhook role has no route for it. Both rules match the path, so Traefik's priority decides.
	for (const scheme of ["http", "https"]) {
		assert.ok(
			Number(priorities.get(`app/${scheme}-slack-interactivity`)) >
				Number(priorities.get(`core/${scheme}-webhook-server`)),
			`app/${scheme}-slack-interactivity must outrank the webhook-server's /webhooks router`,
		);
	}
	const rule = rules.find(({ router }) => router === "app/https-slack-interactivity")?.value;
	assert.match(String(rule), /\) && Path\(`\/webhooks\/slack\/interactivity`\)$/u);

	const file = readFileSync(new URL("../docker/compose.app.yaml", import.meta.url), "utf8");
	assert.match(file, /routers\.https-slack-interactivity\.service=https-application-server"/u);
	const middlewares =
		/routers\.https-slack-interactivity\.middlewares=(?<middlewares>[^"\n]*)/u.exec(file)?.groups
			?.middlewares ?? "";
	// The controller owns the full path, so the /api router's stripprefix would 404 every press.
	assert.doesNotMatch(middlewares, /stripprefix/u);
	assert.ok(middlewares.split(",").includes("slack-interactivity-body-limit"));

	// The server verifies the press itself, so it is given the signing secret too.
	for (const [stack, role] of [
		["app", "application-server"],
		["core", "webhook-server"],
	] as const) {
		const document = parseDocument(
			readFileSync(new URL(`../docker/compose.${stack}.yaml`, import.meta.url), "utf8"),
		);
		assert.ok(
			document.hasIn([
				"services",
				role,
				"environment",
				"HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET",
			]),
			`${role} is not given the Slack signing secret`,
		);
	}
});

await test("what an operator is told to copy is one host per Host()", () => {
	// Traefik v3 takes one host per Host(): Host(`a`,`b`) is rejected and the router never loads, so
	// an example in that shape would take an instance down. The docs carry exactly that as a
	// counter-example on purpose, so every occurrence is extracted and then split by what it is for:
	// anything assigned to APP_HOST_MATCH is configuration an operator pastes, the rest is prose.
	// The extraction is asserted non-empty per file — a pattern that quietly matches nothing is how
	// the counter-example stayed invisible to this gate.
	for (const path of [
		"../docker/.env.example",
		"../docker/self-host/.env.example",
		"../docs/admin/install.mdx",
	]) {
		const text = readFileSync(new URL(path, import.meta.url), "utf8");
		const occurrences = text
			.split("\n")
			.flatMap((line) => [...line.matchAll(/Host\([^()]*\)/gu)].map(([host]) => ({ line, host })));

		assert.notEqual(
			occurrences.length,
			0,
			`${path} documents no Host() at all; the pattern is stale`,
		);
		for (const { line, host } of occurrences) {
			if (!line.includes("APP_HOST_MATCH=")) {
				continue;
			}
			assert.doesNotMatch(
				host,
				/,/u,
				`${path} tells an operator to write a multi-host Host(): ${host}`,
			);
		}
	}
});

await test("every https router sets HSTS itself, not through the edge", () => {
	// The proxy stack puts security-headers on its https entrypoint, which covers a deployment whose
	// edge is that stack. Staging runs behind someone else's proxy, where no
	// entrypoint middleware of ours exists — and the omission is invisible, because the webapp's
	// nginx sets the other security headers on its own responses. HSTS is the one no response can
	// set for itself, so each router that terminates a public request carries it.
	for (const stack of ["app", "core"] as const) {
		const file = readFileSync(new URL(`../docker/compose.${stack}.yaml`, import.meta.url), "utf8");
		const routers = [...file.matchAll(/traefik\.http\.routers\.(?<name>https-[a-z-]+)\.rule=/gu)]
			.map((match) => match.groups?.name)
			.filter((name) => name !== undefined);
		assert.ok(routers.length > 0, `${stack} declares no https router`);
		for (const router of routers) {
			const attached = new RegExp(`routers\\.${router}\\.middlewares=([^"\n]*)`, "u").exec(
				file,
			)?.[1];
			assert.ok(
				attached?.includes("hsts") === true,
				`${stack}/${router} would serve without HSTS behind a proxy that is not ours`,
			);
		}
	}
});

await test("capability-link pages suppress referrers before scripts or assets load in every deployment", () => {
	const html = readFileSync(new URL("../webapp/index.html", import.meta.url), "utf8");
	const policy = html.indexOf('<meta name="referrer" content="no-referrer" />');
	assert.ok(
		policy !== -1 && policy < html.search(/<(?:script|link)\b/u),
		"static referrer policy must precede assets, not wait for the SPA",
	);
	const nginx = readFileSync(
		new URL("../webapp/docker/security-headers.conf", import.meta.url),
		"utf8",
	);
	assert.match(nginx, /add_header Referrer-Policy "no-referrer" always;/u);
	for (const file of [
		"../docker/compose.proxy.yaml",
		"../docker/self-host/compose.single-host.yaml",
	]) {
		const configuredLabels = parseDocument(
			readFileSync(new URL(file, import.meta.url), "utf8"),
		).getIn(["services", "reverse-proxy", "labels"], true);
		const key = "traefik.http.middlewares.security-headers.headers.referrerPolicy";
		if (isSeq(configuredLabels)) {
			assert.ok(
				configuredLabels.items.some(
					(item) => isScalar(item) && item.value === `${key}=no-referrer`,
				),
			);
		} else {
			assert.ok(isMap(configuredLabels));
			assert.equal(configuredLabels.get(key), "no-referrer");
		}
	}
});

/** Both short and long Compose port syntax can publish a range containing management. */
function assertManagementNotPublished(port: unknown, service: string): void {
	let target: unknown;
	if (isMap(port)) {
		target = port.get("target");
	} else if (isScalar(port)) {
		target = port.value;
	}
	assert.ok(
		typeof target === "string" || typeof target === "number",
		`${service} has an unrecognized published port`,
	);
	const targetRange =
		String(target)
			.split(":")
			.at(-1)
			?.replace(/\/(?:tcp|udp)$/u, "") ?? "";
	const [first, last = first] = targetRange.split("-").map(Number);
	assert.ok(
		first !== undefined && last !== undefined && Number.isFinite(first) && Number.isFinite(last),
	);
	assert.ok(!(first <= 9090 && last >= 9090), `${service} publishes management port 9090`);
}

await test("application metrics stay on the private network in both Compose deployments", () => {
	for (const [file, roles] of [
		["../docker/compose.app.yaml", ["application-server", "application-worker"]],
		["../docker/compose.core.yaml", ["webhook-server"]],
		[
			"../docker/self-host/compose.single-host.yaml",
			["application-server", "application-worker", "webhook-server"],
		],
	] as const) {
		const document = parseDocument(readFileSync(new URL(file, import.meta.url), "utf8"));
		assert.equal(document.errors.length, 0, `${file} must be valid YAML`);
		for (const role of roles) {
			const service = document.getIn(["services", role]);
			assert.ok(isMap(service), `${file}/${role} is missing`);
			const expose = service.get("expose");
			assert.ok(isSeq(expose), `${file}/${role} must expose management internally`);
			assert.ok(expose.items.some((port) => isScalar(port) && String(port.value) === "9090"));
			const managementAddress = service.getIn(["environment", "MANAGEMENT_SERVER_ADDRESS"]);
			if (file.includes("single-host")) {
				assert.ok(managementAddress === undefined || managementAddress === MANAGEMENT_BIND);
			}
			if (!file.includes("single-host")) {
				assert.equal(service.getIn(["environment", "MANAGEMENT_SERVER_ADDRESS"]), MANAGEMENT_BIND);
				assert.equal(service.getIn(["environment", "THC_PORT"]), "8080");
				assert.ok(
					["/livez", "/readyz"].includes(String(service.getIn(["environment", "THC_PATH"]))),
				);
			}
			const ports = service.get("ports");
			if (ports !== undefined) {
				assert.ok(isSeq(ports), `${file}/${role} ports must be a sequence`);
				for (const port of ports.items) {
					assertManagementNotPublished(port, `${file}/${role}`);
				}
			}
		}
		assert.doesNotMatch(
			readFileSync(new URL(file, import.meta.url), "utf8"),
			/loadbalancer\.server\.port=9090/u,
		);
	}
	for (const stack of STACKS) {
		const file = readFileSync(new URL(`../docker/compose.${stack}.yaml`, import.meta.url), "utf8");
		assert.doesNotMatch(file, /traefik\.[^\n]*(?:prometheus|9090)/u);
	}
});

await test("Compose passes the operator's pool size and memory headroom to the worker only", () => {
	const app = parseDocument(
		readFileSync(new URL("../docker/compose.app.yaml", import.meta.url), "utf8"),
	);
	const core = parseDocument(
		readFileSync(new URL("../docker/compose.core.yaml", import.meta.url), "utf8"),
	);
	const singleHost = parseDocument(
		readFileSync(new URL("../docker/self-host/compose.single-host.yaml", import.meta.url), "utf8"),
	);
	for (const key of ["HIKARI_MAXIMUM_POOL_SIZE", "BPL_JVM_HEAD_ROOM"]) {
		// Passed through without a value, so an unset variable leaves the worker's own default in force.
		const worker = ["services", "application-worker", "environment", key];
		assert.ok(app.hasIn(worker), `the worker receives the operator's ${key}`);
		assert.equal(app.getIn(worker), null);
		// The other roles keep their own defaults.
		assert.equal(app.getIn(["services", "application-server", "environment", key]), undefined);
		assert.equal(core.getIn(["services", "webhook-server", "environment", key]), undefined);
		for (const role of ["application-server", "application-worker", "webhook-server"]) {
			assert.equal(
				singleHost.getIn(["services", role, "environment", key]),
				undefined,
				`single-host ${role} receives no ${key}`,
			);
		}
	}
});

await test("maintenance runs the verified webapp image's own maintenance mode, without its application startup or files", () => {
	const document = parseDocument(read("../docker/compose.proxy.yaml"));
	assert.equal(
		document.getIn(["services", "maintenance", "image"]),
		`\${HEPHAESTUS_IMAGE_WEBAPP:?verified release lock required}`,
	);
	const entrypoint = document.getIn(["services", "maintenance", "entrypoint"]);
	assert.ok(isSeq(entrypoint));
	const modeConfig = "/etc/nginx/hephaestus/maintenance.conf";
	assert.deepEqual(entrypoint.toJSON(), ["nginx", "-c", modeConfig, "-g", "daemon off;"]);

	// A read-only service cannot take Compose content configs, and the page must not depend on a
	// release checkout on the host: the image carries both files, and nothing is mounted over them.
	for (const key of ["configs", "volumes", "environment", "command"]) {
		assert.ok(!document.hasIn(["services", "maintenance", key]), `maintenance sets ${key}`);
	}
	const configs = document.get("configs");
	assert.ok(isMap(configs));
	assert.deepEqual(
		configs.items.map((pair) => (isScalar(pair.key) ? pair.key.value : pair.key)),
		["traefik-dynamic", "traefik-start"],
	);

	const dockerfile = read("../webapp/Dockerfile");
	const copied = new Map(
		[...dockerfile.matchAll(/^COPY (?<source>webapp\/docker\/\S+) (?<target>\/\S+)$/gmu)].map(
			(match) => [match.groups?.target, match.groups?.source],
		),
	);
	assert.equal(copied.get(modeConfig), "webapp/docker/nginx-maintenance.conf");
	assert.equal(
		copied.get("/usr/share/nginx/maintenance/index.html"),
		"webapp/docker/maintenance.html",
	);
	const configuration = read("../webapp/docker/nginx-maintenance.conf");
	assert.match(configuration, /^\s+root \/usr\/share\/nginx\/maintenance;$/mu);
	assert.doesNotMatch(configuration, /\/usr\/share\/nginx\/html|conf\.d|hephaestus-webapp;/u);
	// Every file either mode includes from the image is baked into it.
	for (const conf of [configuration, read("../webapp/docker/nginx-main.conf")]) {
		for (const match of conf.matchAll(/^\s*include (?<path>\/etc\/nginx\/hephaestus\/\S+);$/gmu)) {
			const included = match.groups?.path;
			assert.ok(included !== undefined);
			assert.ok(copied.has(included), `${included} is not in the image`);
		}
	}
	assert.match(read("../webapp/docker/maintenance.html"), /We&rsquo;ll be back soon!/u);
});
