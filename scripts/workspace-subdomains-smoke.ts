import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, mkdir, writeFile, cp, rm } from "node:fs/promises";
import { request } from "node:https";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout } from "node:timers/promises";
import { Document, isMap, isScalar, isSeq, parseDocument } from "yaml";

import { readInventory } from "./commit-image-lock.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES, output, run, succeeds } from "./lib/process.ts";

const root = path.join(import.meta.dirname, "..");
const project = `hephaestus-edge-${randomUUID().slice(0, 8)}`;
const directory = await mkdtemp(path.join(tmpdir(), `${project}-`));
console.log(`Smoke project=${project}, directory=${directory}, source=${root}`);
const base = "edge.example.invalid";
let ca: Buffer;
const image = `${project}-webapp:smoke`;
const env: NodeJS.ProcessEnv = {
	...process.env,
	COMPOSE_ENV_FILES: "",
	TRAEFIK_DNS_CREDENTIALS_DIRECTORY: path.join(directory, "dns"),
	GH_APP_PRIVATE_KEY: "",
	GH_AUTH_TOKEN: "",
	APP_HOSTNAME: base,
	IMAGE_TAG: "smoke",
};
const productionFiles = ["proxy", "app"].map((stack) =>
	path.join(root, `docker/compose.${stack}.yaml`),
);
const composeFiles = productionFiles.map((file) =>
	path.join(directory, "release-1", path.basename(file)),
);
const sources = await Promise.all(
	[...productionFiles, path.join(root, "docker/compose.core.yaml")].map(async (file) =>
		readFile(file, "utf8"),
	),
);
for (const source of sources) {
	for (const match of source.matchAll(/\$\{(?<key>[A-Z_]+):\?/gu)) {
		const key = match.groups?.key;
		if (key !== undefined) {
			env[key] = "smoke";
		}
	}
}
const inventory = await readInventory(path.join(root, "security/release-images.json"));
const traefik = inventory.upstream.find((pin) => pin.name === "traefik");
assert.ok(traefik);
env.HEPHAESTUS_IMAGE_TRAEFIK = `${traefik.repository}@${traefik.digest}`;
env.HEPHAESTUS_IMAGE_WEBAPP = image;
env.HEPHAESTUS_IMAGE_APPLICATION_SERVER = image;
const proxyCommand = parseDocument(sources[0] ?? "").getIn([
	"services",
	"reverse-proxy",
	"command",
]);
assert.ok(isSeq(proxyCommand));
const command = proxyCommand.items.map((item) => {
	assert.ok(isScalar(item) && typeof item.value === "string");
	return item.value;
});
command.push(
	`--providers.docker.constraints=Label(\`com.docker.compose.project\`, \`${project}\`)`,
	"--certificatesresolvers.letsencrypt.acme.caserver=https://127.0.0.1:9/directory",
	"--api.insecure=true",
	"--log.level=DEBUG",
	"--log.format=json",
);
const override = path.join(directory, "override.yaml");
let composePrepared = false;
const args = [
	"compose",
	"--project-name",
	project,
	...composeFiles.flatMap((file) => ["--file", file]),
	"--file",
	override,
];
const dockerfile = await readFile(path.join(root, "webapp/Dockerfile"), "utf8");
const runtime = dockerfile.slice(dockerfile.indexOf("FROM nginx:"));
assert.ok(runtime.startsWith("FROM nginx:"));
const fixtureIndex = '<html><script src="/env-config.js"></script>tenant-spa</html>';
/** The one writable directory of every service that runs the webapp image. */
const RUNTIME_DIR = "/tmp/hephaestus-webapp";

function selectRelease(release: string) {
	for (const file of productionFiles) {
		const index = args.findIndex((arg) => arg.endsWith(path.basename(file)));
		assert.ok(index !== -1);
		args[index] = path.join(directory, release, path.basename(file));
	}
}

async function publishedPort(target: number) {
	const published = await output("docker", [...args, "port", "reverse-proxy", String(target)], {
		env,
	});
	const port = Number(published.trim().split(":").at(-1));
	assert.ok(port > 0);
	return port;
}

async function get(port: number, host: string, url: string, method = "GET") {
	const { promise, resolve, reject } = Promise.withResolvers<{
		status: number;
		location?: string;
		cache?: string;
		contentType?: string;
		server?: string | string[];
		body: string;
	}>();
	{
		const req = request(
			{
				hostname: "127.0.0.1",
				port,
				servername: host,
				path: url,
				method,
				ca,
				headers: { Host: host },
				timeout: 5000,
			},
			(response) => {
				let body = "";
				response.setEncoding("utf8");
				response.on("data", (chunk: string) => {
					body += chunk;
				});
				response.on("end", () =>
					resolve({
						status: response.statusCode ?? 0,
						location: response.headers.location,
						cache: response.headers["cache-control"],
						contentType: response.headers["content-type"],
						server: response.headers.server,
						body,
					}),
				);
			},
		);
		req.on("error", reject);
		req.on("timeout", () => {
			req.destroy(new Error(`Edge request timed out at ${host}${url}`));
		});
		req.end();
	}
	return promise;
}

async function expectStatus(
	port: number,
	host: string,
	url: string,
	status: number,
	message = url,
) {
	const response = await get(port, host, url);
	assert.equal(response.status, status, message);
}

async function waitForBody(port: number, host: string, url: string, expected: string) {
	for (let attempt = 0; attempt < 60; attempt += 1) {
		try {
			const response = await get(port, host, url);
			if (response.body.includes(expected)) {
				return;
			}
		} catch {
			// The listener can start after the container does.
		}
		await setTimeout(500);
	}
	assert.fail(`The edge did not serve ${expected} at ${host}${url}`);
}

/**
 * A service that runs the webapp image serves under the production Compose hardening: the image's
 * non-root user, a read-only root file system with the runtime directory as its only writable
 * mount, no capabilities and no privilege gain. Returns the container id.
 */
async function assertRestricted(service: string) {
	const listing = await output("docker", [...args, "ps", "--quiet", service], { env });
	const id = listing.trim();
	const container = asRecord(
		asArray(parseJson(await output("docker", ["inspect", id])), `${service} inspect`)[0],
		`${service} container`,
	);
	const config = asRecord(container.Config, `${service} config`);
	assert.equal(config.User, "101:101", service);
	const host = asRecord(container.HostConfig, `${service} host config`);
	assert.equal(host.ReadonlyRootfs, true, service);
	assert.deepEqual(host.CapDrop, ["ALL"], service);
	assert.deepEqual(asArray(host.CapAdd ?? [], `${service} added capabilities`), [], service);
	assert.ok(
		asArray(host.SecurityOpt, `${service} security options`).includes("no-new-privileges:true"),
		service,
	);
	assert.deepEqual(Object.keys(asRecord(host.Tmpfs, `${service} tmpfs`)), [RUNTIME_DIR], service);
	const exec = async (...argv: string[]) => {
		const stdout = await output("docker", ["exec", id, ...argv]);
		return stdout.trim();
	};
	assert.equal(await exec("id", "-u"), "101", service);
	assert.equal(await exec("stat", "-c", "%u:%g:%a", RUNTIME_DIR), "101:101:700", service);
	// nginx wrote its PID into the mount; nothing outside it can be written.
	assert.equal(
		await succeeds("docker", ["exec", id, "test", "-s", `${RUNTIME_DIR}/nginx.pid`]),
		true,
		service,
	);
	for (const target of ["/usr/share/nginx/html/probe", "/tmp/probe", "/etc/nginx/probe"]) {
		assert.equal(
			await succeeds("docker", ["exec", id, "touch", target]),
			false,
			`${service} ${target}`,
		);
	}
	// The configured health probe passes now, without waiting for Docker's interval.
	const probe = asRecord(config.Healthcheck ?? {}, `${service} health check`).Test;
	if (Array.isArray(probe) && probe[0] === "CMD") {
		const probeArgv = asArray(probe, `${service} probe`)
			.slice(1)
			.map((part) => asString(part, `${service} probe part`));
		assert.equal(await succeeds("docker", ["exec", id, ...probeArgv]), true, `${service} probe`);
	}
	return id;
}

async function assertRestrictedImageServices() {
	const webapp = await assertRestricted("webapp");
	// The entrypoint generated the served index in the runtime directory and left the build output
	// as built.
	assert.equal(
		await output("docker", ["exec", webapp, "cat", "/usr/share/nginx/html/index.html"]),
		fixtureIndex,
	);
	assert.match(
		await output("docker", ["exec", webapp, "cat", `${RUNTIME_DIR}/index.html`]),
		/src="\/env-config\.[a-f0-9]{8}\.js"/u,
	);
	await assertRestricted("maintenance");
	await assertRestricted("application-server");
}

async function waitForApiWithdrawal(port: number) {
	for (let attempt = 0; attempt < 60; attempt += 1) {
		const response = await fetch(`http://127.0.0.1:${port}/api/http/routers`, {
			signal: AbortSignal.timeout(5000),
		});
		assert.equal(response.status, 200);
		const routers = asArray(parseJson(await response.text()), "edge routers").map((router) =>
			asRecord(router, "edge router"),
		);
		if (
			!routers.some((router) =>
				["http-application-server@docker", "https-application-server@docker"].includes(
					asString(router.name, "router name"),
				),
			)
		) {
			console.log("Fixture API routers withdrawn from Traefik");
			return;
		}
		await setTimeout(500);
	}
	assert.fail("The edge did not withdraw the fixture API routers");
}

try {
	for (const release of ["release-1", "release-2", "release-3"]) {
		await mkdir(path.join(directory, release));
		for (const file of productionFiles) {
			await cp(file, path.join(directory, release, path.basename(file)));
		}
	}
	await run(
		"openssl",
		[
			"req",
			"-x509",
			"-newkey",
			"rsa:2048",
			"-noenc",
			"-days",
			"1",
			"-subj",
			"/CN=Hephaestus edge smoke CA",
			"-addext",
			"basicConstraints=critical,CA:TRUE",
			"-keyout",
			path.join(directory, "ca.key"),
			"-out",
			path.join(directory, "ca.pem"),
		],
		{ stdout: "ignore", stderr: "ignore" },
	);
	await run(
		"openssl",
		[
			"req",
			"-x509",
			"-newkey",
			"rsa:2048",
			"-noenc",
			"-days",
			"1",
			"-CA",
			path.join(directory, "ca.pem"),
			"-CAkey",
			path.join(directory, "ca.key"),
			"-subj",
			`/CN=${base}`,
			"-addext",
			"basicConstraints=critical,CA:FALSE",
			"-addext",
			"extendedKeyUsage=serverAuth",
			"-addext",
			`subjectAltName=DNS:${base},DNS:*.${base},DNS:a.b.${base},DNS:acme.${base}.evil.invalid`,
			"-keyout",
			path.join(directory, "tls.key"),
			"-out",
			path.join(directory, "tls.pem"),
		],
		{ stdout: "ignore", stderr: "ignore" },
	);
	ca = await readFile(path.join(directory, "ca.pem"));
	const dynamicTemplate = asString(
		parseDocument(sources[0] ?? "").get("x-traefik-dynamic"),
		"inline edge template",
	);
	const testDynamic = dynamicTemplate.replace(
		"tls:\n",
		"tls:\n  certificates:\n    - certFile: /smoke/tls.pem\n      keyFile: /smoke/tls.key\n",
	);
	const proxyFixture = parseDocument(sources[0] ?? "");
	const dynamicNode = proxyFixture.get("x-traefik-dynamic", true);
	assert.ok(isScalar(dynamicNode));
	dynamicNode.value = testDynamic;
	for (const release of ["release-1", "release-2", "release-3"]) {
		await writeFile(path.join(directory, release, "compose.proxy.yaml"), proxyFixture.toString());
	}
	await mkdir(path.join(directory, "dns"));
	const credential = `smoke-token-${randomUUID()}`;
	await writeFile(path.join(directory, "dns/token"), credential, { mode: 0o600 });
	await mkdir(path.join(directory, "fixture"));
	await writeFile(path.join(directory, "fixture/index.html"), fixtureIndex);
	await mkdir(path.join(directory, "fixture/assets"));
	await writeFile(
		path.join(directory, "fixture/assets/smoke.svg"),
		'<svg xmlns="http://www.w3.org/2000/svg"/>',
	);
	await cp(path.join(root, "webapp/docker"), path.join(directory, "webapp/docker"), {
		recursive: true,
	});
	await mkdir(path.join(directory, "webapp/src/environment"), { recursive: true });
	await cp(
		path.join(root, "webapp/src/environment/index.ts"),
		path.join(directory, "webapp/src/environment/index.ts"),
	);
	await writeFile(
		path.join(directory, "Dockerfile"),
		runtime.replace("COPY --from=build /repo/webapp/dist", "COPY fixture/"),
	);
	await writeFile(
		path.join(directory, "api.conf"),
		'server { listen 8080; access_log off; location / { return 200 "apex-api"; } }',
	);
	const overrideDocument = new Document({
		services: {
			"reverse-proxy": {
				ports: [80, 443, 8080].map((target) => ({ target, host_ip: "127.0.0.1" })),
				command,
				volumes: [
					`${directory}/tls.pem:/smoke/tls.pem:ro`,
					`${directory}/tls.key:/smoke/tls.key:ro`,
				],
			},
			webapp: { ports: [], depends_on: {} },
			// The mock API runs the webapp image's nginx without its entrypoint, as maintenance does,
			// so it gets the same read-only root and runtime directory.
			"application-server": {
				ports: [],
				entrypoint: ["nginx", "-g", "daemon off;"],
				healthcheck: { disable: true },
				read_only: true,
				tmpfs: [`${RUNTIME_DIR}:uid=101,gid=101,mode=0700,size=16m`],
				cap_drop: ["ALL"],
				security_opt: ["no-new-privileges:true"],
				volumes: [`${directory}/api.conf:/etc/nginx/conf.d/default.conf:ro`],
				depends_on: {},
			},
		},
		networks: { "shared-network": { name: project } },
	});

	for (const service of ["reverse-proxy", "webapp", "application-server"]) {
		const ports = overrideDocument.getIn(["services", service, "ports"], true);
		assert.ok(isSeq(ports));
		ports.tag = "!override";
	}
	for (const service of ["webapp", "application-server"]) {
		const dependencies = overrideDocument.getIn(["services", service, "depends_on"], true);
		assert.ok(isMap(dependencies));
		dependencies.tag = "!reset";
	}
	await writeFile(override, overrideDocument.toString());
	composePrepared = true;

	await run("docker", ["build", "--tag", image, directory]);
	for (const invalid of [
		{ HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "invalid" },
		{
			HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "true",
			HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: "wrong.example.invalid",
		},
		{
			HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "true",
			HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: base,
			ACME_CHALLENGE: "httpchallenge.entrypoint",
		},
		{
			HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "false",
			HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: "wrong.example.invalid",
			ACME_CHALLENGE: "dnschallenge.provider",
		},
		{
			ACME_CHALLENGE: "dnschallenge.provider",
			TRAEFIK_DNS_CREDENTIAL_VARIABLE: "CF_DNS_API_TOKEN",
		},
		{
			ACME_CHALLENGE: "dnschallenge.provider",
			TRAEFIK_DNS_CREDENTIALS_DIRECTORY: path.join(directory, "empty-dns"),
		},
	]) {
		const result = spawnSync("docker", [...args, "run", "--rm", "--no-deps", "reverse-proxy"], {
			env: { ...env, ...invalid },
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
			timeout: 30_000,
		});
		assert.equal(result.status, 1, result.stderr);
		assert.match(result.stdout + result.stderr, /Invalid workspace edge configuration:/u);
	}

	for (const { enabled, dns } of [
		{ enabled: "false", dns: false },
		{ enabled: "false", dns: true },
		{ enabled: "true", dns: true },
		{ enabled: "false", dns: true },
	]) {
		env.HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED = enabled;
		env.HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN = base;
		env.ACME_CHALLENGE = dns ? "dnschallenge.provider" : "httpchallenge.entrypoint";
		env.ACME_CHALLENGE_VALUE = dns ? "cloudflare" : "http";
		const selfHost = asRecord(
			parseJson(
				await output(
					"docker",
					[
						"compose",
						"--project-name",
						project,
						"--file",
						path.join(root, "docker/self-host/compose.yaml"),
						"config",
						"--format",
						"json",
					],
					{
						env: {
							...env,
							ACME_EMAIL: "smoke@example.invalid",
							POSTGRES_PASSWORD: "smoke",
						},
					},
				),
			),
			"self-host configuration",
		);
		const configs = asRecord(selfHost.configs, "self-host configs");
		const dynamic = asRecord(configs["traefik-dynamic"], "self-host dynamic config");
		assert.equal(dynamic.file, undefined);
		const content = asString(dynamic.content, "rendered edge template").replaceAll("$$", "$");
		assert.equal(
			parseDocument(content.slice(0, content.indexOf("{{"))).getIn([
				"tls",
				"options",
				"default",
				"minVersion",
			]),
			"VersionTLS12",
		);
		assert.ok(content.includes("{{ $base :="));
		assert.ok(content.includes(`https://\${1}.`));
		assert.ok(!JSON.stringify(selfHost).includes(credential));
		await run(
			"docker",
			[
				...args,
				"up",
				"--detach",
				"--no-deps",
				"--force-recreate",
				"reverse-proxy",
				"webapp",
				"application-server",
				"maintenance",
			],
			{ env },
		);
		let port = await publishedPort(443);
		await waitForBody(port, base, "/", "tenant-spa");
		await assertRestrictedImageServices();
		await assert.rejects(
			get(port, "untrusted.example.invalid", "/"),
			(error: unknown) =>
				error instanceof Error &&
				"code" in error &&
				(error.code === "ERR_TLS_CERT_ALTNAME_INVALID" ||
					error.code === "DEPTH_ZERO_SELF_SIGNED_CERT"),
		);
		const apiPort = await publishedPort(8080);
		const routerResponse = await fetch(
			`http://127.0.0.1:${apiPort}/api/http/routers/https-tenant@file`,
		);
		assert.equal(routerResponse.status, enabled === "true" ? 200 : 404);
		if (enabled === "true") {
			const router = asRecord(parseJson(await routerResponse.text()), "tenant router");
			assert.equal(router.priority, 6);
			const tls = asRecord(router.tls, "tenant TLS");
			assert.equal(tls.certResolver, "letsencrypt");
		}

		const proxyListing = await output("docker", [...args, "ps", "--quiet", "reverse-proxy"], {
			env,
		});
		const proxyId = proxyListing.trim();
		const configurationLines = await output("docker", ["logs", proxyId]);
		const configurations = configurationLines
			.trim()
			.split("\n")
			.map((line) => asRecord(parseJson(line), "proxy log"))
			.filter(
				(entry) => entry.providerName === "file" && entry.message === "Configuration received",
			);
		assert.ok(configurations.length > 0, "The file provider must accept the edge configuration");
		const configuration = asRecord(configurations.at(-1)?.config, "file configuration");
		const tls = asRecord(configuration.tls, "file TLS");
		if (dns) {
			const stores = asRecord(tls.stores, "TLS stores");
			const defaultStore = asRecord(stores.default, "default TLS store");
			assert.deepEqual(defaultStore.defaultGeneratedCert, {
				resolver: "letsencrypt",
				domain: { main: base, sans: [`*.${base}`] },
			});
		} else {
			assert.equal(tls.stores, undefined);
		}
		const inspection = await output("docker", ["inspect", proxyId]);
		assert.ok(!inspection.includes(credential));
		const mountedDynamic = await output("docker", [
			"exec",
			proxyId,
			"cat",
			"/etc/traefik/dynamic.yml",
		]);
		assert.equal(
			parseDocument(mountedDynamic.slice(0, mountedDynamic.indexOf("{{"))).getIn([
				"tls",
				"options",
				"default",
				"minVersion",
			]),
			"VersionTLS12",
		);
		const container = asRecord(
			asArray(parseJson(inspection), "proxy inspect")[0],
			"proxy container",
		);
		const mounts = asArray(container.Mounts, "proxy mounts").map((mount) =>
			asRecord(mount, "mount"),
		);
		assert.equal(mounts.find((mount) => mount.Destination === "/run/secrets/dns")?.RW, false);
		for (const slug of ["abc", "a".repeat(51)]) {
			await expectStatus(port, base, `/w/${slug}`, enabled === "true" ? 301 : 200);
			await expectStatus(port, `${slug}.${base}`, "/", enabled === "true" ? 200 : 404);
		}
		if (enabled === "false" && args.includes(composeFiles[0] ?? "")) {
			dynamicNode.value = `${testDynamic}\n# Config revision\n`;
			for (const release of ["release-1", "release-2", "release-3"]) {
				await writeFile(
					path.join(directory, release, "compose.proxy.yaml"),
					proxyFixture.toString(),
				);
			}
			await run("docker", [...args, "up", "--detach", "--no-deps", "reverse-proxy"], { env });
			const changedListing = await output("docker", [...args, "ps", "--quiet", "reverse-proxy"], {
				env,
			});
			assert.notEqual(
				changedListing.trim(),
				proxyId,
				"An inline config change must recreate the proxy",
			);
			for (const release of ["release-2", "release-3"]) {
				selectRelease(release);
				await run("docker", [...args, "up", "--detach", "--no-deps", "reverse-proxy"], { env });
			}
			await rm(path.join(directory, "release-1"), { recursive: true });
			await rm(path.join(directory, "release-2"), { recursive: true });
			const retainedListing = await output("docker", [...args, "ps", "--quiet", "reverse-proxy"], {
				env,
			});
			assert.equal(
				retainedListing.trim(),
				changedListing.trim(),
				"Moving an unchanged Compose model must keep its proxy",
			);
			await run("docker", ["stop", retainedListing.trim()]);
			await run("docker", ["start", retainedListing.trim()]);
			port = await publishedPort(443);
			await waitForBody(port, base, "/", "tenant-spa");
		}
		const post = await get(port, base, "/w/acme", "POST");
		assert.equal(post.status, 405);
		for (const url of ["/api", "/api/auth/csrf", "/apiary", "/api/smoke.svg"]) {
			const api = await get(port, base, url);
			assert.equal(api.status, 200, url);
			assert.equal(api.body, "apex-api", url);
		}
		for (const [url, tail] of [
			["/w/acme", "/"],
			["/w/acme?range=1y", "/?range=1y"],
			["/w/acme/activity?range=1y&repo=a%2Fb", "/activity?range=1y&repo=a%2Fb"],
			["/w/acme/user/a%20b?x=%26&x=2", "/user/a%20b?x=%26&x=2"],
		]) {
			assert.ok(url !== undefined && tail !== undefined);
			const response = await get(port, base, url);
			assert.equal(response.status, enabled === "true" ? 301 : 200, url);
			if (enabled === "true") {
				assert.equal(response.location, `https://acme.${base}${tail}`);
				assert.equal(response.cache, "no-store");
			}
		}
		for (const slug of ["docs", "pr123", "bad--name", "-bad", "bad-", "ab", "a".repeat(52)]) {
			await expectStatus(port, base, `/w/${slug}`, 200, `Invalid redirect ${slug}`);
			await expectStatus(port, `${slug}.${base}`, "/", 404, `Invalid tenant ${slug}`);
		}
		await expectStatus(port, `a.b.${base}`, "/", 404);
		await expectStatus(port, `acme.${base}.evil.invalid`, "/", 404);
		const tenant = await get(port, `acme.${base}`, "/activity?range=1y");
		assert.equal(tenant.status, enabled === "true" ? 200 : 404);
		for (const url of [
			"/api",
			"/api/auth/csrf",
			"/auth/login",
			"/oauth/callback",
			"/oauth2/authorization/github",
			"/login/oauth2/code/github",
			"/consent",
			"/webhooks/github",
			"/actuator/health",
		]) {
			await expectStatus(port, `acme.${base}`, url, 404, `Tenant isolation ${url}`);
		}
		const fixtureListing = await output(
			"docker",
			[...args, "ps", "--quiet", "application-server"],
			{
				env,
			},
		);
		const fixtureId = fixtureListing.trim();
		const fixture = asRecord(
			asArray(parseJson(await output("docker", ["inspect", fixtureId])), "fixture inspect")[0],
			"fixture container",
		);
		const labels = asRecord(asRecord(fixture.Config, "fixture config").Labels, "fixture labels");
		assert.equal(labels["com.docker.compose.project"], project);
		assert.equal(labels["com.docker.compose.service"], "application-server");
		console.log(`Withdrawing owned fixture API container=${fixtureId}, project=${project}`);
		await run("docker", [...args, "rm", "--stop", "--force", "application-server"], { env });
		await waitForApiWithdrawal(await publishedPort(8080));
		await waitForBody(port, base, "/", "tenant-spa");
		for (const [url, method] of [
			["/api/user/consent", "GET"],
			["/api", "GET"],
			["/apiary", "GET"],
			["/webhooks", "GET"],
			["/webhooks/github", "GET"],
			["/webhooks-extra", "GET"],
			["/api/smoke.svg", "GET"],
			["/webhooks/smoke.svg", "GET"],
			["/api/user/consent", "POST"],
			["/webhooks/github", "POST"],
		]) {
			assert.ok(url !== undefined && method !== undefined);
			const response = await get(port, base, url, method);
			console.log(`Backend fallback ${method} ${url}: ${JSON.stringify(response)}`);
			assert.equal(response.server, "nginx", `NGINX must answer ${method} ${url}`);
			assert.equal(response.status, 503, `Backend fallback ${method} ${url}`);
			assert.ok(!response.body.includes("tenant-spa"), url);
		}
		for (const url of [
			"/",
			"/login",
			"/consent",
			"/auth/login",
			"/privacy",
			"/imprint",
			"/terms",
			"/unsubscribe",
			"/admin",
			"/settings",
			"/some/deep/link",
		]) {
			await expectStatus(port, base, url, 200, `Apex passthrough without API ${url}`);
		}
		await expectStatus(port, base, "/assets/smoke.svg", 200);
		await expectStatus(port, base, "/assets/missing.svg", 404);
		await expectStatus(port, base, "/missing.svg", 404);
		const apexPage = await get(port, base, "/");
		const apexConfigPath = /src="(?<path>\/env-config[^"]+)"/u.exec(apexPage.body)?.groups?.path;
		assert.ok(apexConfigPath !== undefined);
		const apexConfig = await get(port, base, apexConfigPath);
		assert.equal(apexConfig.status, 200);
		assert.ok(apexConfig.body.includes(`APPLICATION_SERVER_URL: "https://${base}/api"`));
		assert.equal(apexConfig.cache, "public, max-age=31536000, immutable");
		if (apexConfigPath !== "/env-config.00000000.js") {
			await expectStatus(port, base, "/env-config.00000000.js", 404);
		}
		const discovery = await get(port, base, "/.well-known/hephaestus.json");
		assert.equal(discovery.status, 200);
		assert.equal(discovery.cache, "no-store");
		assert.deepEqual(parseJson(discovery.body), {
			apexOrigin: `https://${base}`,
			baseDomain: base,
			workspaceSubdomainsEnabled: enabled === "true",
		});
		// The smoke stack sets no SECURITY_CONTACT, so nginx answers a real 404, not the SPA shell.
		await expectStatus(port, base, "/.well-known/security.txt", 404);
		if (enabled === "true") {
			const page = await get(port, `acme.${base}`, "/");
			const configPath = /src="(?<path>\/env-config[^"]+)"/u.exec(page.body)?.groups?.path;
			assert.ok(configPath !== undefined);
			const config = await get(port, `acme.${base}`, configPath);
			assert.ok(config.body.includes(`APPLICATION_SERVER_URL: "https://${base}/api"`));
			const head = await get(port, base, "/w/acme/activity?x=1", "HEAD");
			assert.equal(head.status, 308);
			await run("docker", [...args, "stop", "webapp"], { env });
			await waitForBody(port, `acme.${base}`, "/activity", "back soon");
			await expectStatus(port, `docs.${base}`, "/", 404);
			await expectStatus(port, `acme.${base}`, "/api/auth/csrf", 404);
		}
		console.log(
			`Verified subdomains=${enabled}, DNS-01=${dns} at https://127.0.0.1:${port} with Host/SNI ${base} and acme.${base}`,
		);
	}
} catch (error) {
	if (composePrepared) {
		await run("docker", [...args, "logs", "--no-color", "--tail", "60"], { env });
	}
	throw error;
} finally {
	if (composePrepared) {
		await run("docker", [...args, "down", "--volumes", "--remove-orphans"], { env });
		console.log(`Cleaned Compose project=${project}`);
	}
	const imageRemoved = await succeeds("docker", ["image", "rm", image]);
	console.log(`Smoke image=${image}, removed=${imageRemoved}`);
	await rm(directory, { recursive: true, force: true });
}
