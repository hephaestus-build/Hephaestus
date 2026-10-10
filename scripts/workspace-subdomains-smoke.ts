import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, mkdir, writeFile, cp, rm } from "node:fs/promises";
import { request } from "node:https";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout } from "node:timers/promises";
import { Document, isMap, isScalar, isSeq, parseDocument } from "yaml";

import { readInventory } from "./commit-image-lock.ts";
import { asRecord, asString, parseJson } from "./lib/json.ts";
import { output, run, succeeds } from "./lib/process.ts";

const root = path.join(import.meta.dirname, "..");
const project = `hephaestus-edge-${randomUUID().slice(0, 8)}`;
const directory = await mkdtemp(path.join(tmpdir(), `${project}-`));
const base = "edge.example.invalid";
const image = `${project}-webapp:smoke`;
const env: NodeJS.ProcessEnv = {
	...process.env,
	COMPOSE_ENV_FILES: "",
	TRAEFIK_DNS_ENV_FILE: path.join(directory, "no-dns-credentials.env"),
	GH_APP_PRIVATE_KEY: "",
	GH_AUTH_TOKEN: "",
	APP_HOSTNAME: base,
	IMAGE_TAG: "smoke",
	SENTRY_DSN: "",
};
const composeFiles = ["proxy", "app"].map((stack) =>
	path.join(root, `docker/compose.${stack}.yaml`),
);
const sources = await Promise.all(
	[...composeFiles, path.join(root, "docker/compose.core.yaml")].map(async (file) =>
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
);
const override = path.join(directory, "override.yaml");
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

async function get(port: number, host: string, url: string, method = "GET") {
	const { promise, resolve, reject } = Promise.withResolvers<{
		status: number;
		location?: string;
		cache?: string;
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
				rejectUnauthorized: false,
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

try {
	await mkdir(path.join(directory, "fixture"));
	await writeFile(
		path.join(directory, "fixture/index.html"),
		'<html><script src="/env-config.js"></script>tenant-spa</html>',
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
			"reverse-proxy": { ports: ["127.0.0.1::80", "127.0.0.1::443", "127.0.0.1::8080"], command },
			webapp: { ports: [], depends_on: {}, environment: { SENTRY_DSN: "" } },
			"application-server": {
				entrypoint: ["nginx", "-g", "daemon off;"],
				healthcheck: { disable: true },
				volumes: [`${directory}/api.conf:/etc/nginx/conf.d/default.conf:ro`],
				depends_on: {},
			},
		},
		networks: { "shared-network": { name: project } },
	});

	for (const service of ["reverse-proxy", "webapp"]) {
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

	await run("docker", ["build", "--tag", image, directory]);
	for (const enabled of ["false", "true", "false"]) {
		env.HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED = enabled;
		env.HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN = base;
		env.ACME_CHALLENGE = enabled === "true" ? "dnschallenge.provider" : "httpchallenge.entrypoint";
		env.ACME_CHALLENGE_VALUE = enabled === "true" ? "cloudflare" : "http";
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
							HEPHAESTUS_WORKER_REGISTRATION_TOKEN: "smoke",
						},
					},
				),
			),
			"self-host configuration",
		);
		const configs = asRecord(selfHost.configs, "self-host configs");
		const dynamic = asRecord(configs["traefik-dynamic"], "self-host dynamic config");
		assert.equal(
			asString(dynamic.file, "dynamic config path"),
			path.join(root, "docker/traefik/dynamic.yml"),
		);
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
		const published = await output("docker", [...args, "port", "reverse-proxy", "443"], { env });
		const port = Number(published.trim().split(":").at(-1));
		assert.ok(port > 0);
		await waitForBody(port, base, "/", "tenant-spa");
		const apiPublished = await output("docker", [...args, "port", "reverse-proxy", "8080"], {
			env,
		});
		const apiPort = Number(apiPublished.trim().split(":").at(-1));
		const routerResponse = await fetch(
			`http://127.0.0.1:${apiPort}/api/http/routers/https-tenant@file`,
		);
		assert.equal(routerResponse.status, enabled === "true" ? 200 : 404);
		if (enabled === "true") {
			const router = asRecord(parseJson(await routerResponse.text()), "tenant router");
			assert.equal(router.priority, 6);
			const tls = asRecord(router.tls, "tenant TLS");
			assert.deepEqual(tls.domains, [{ main: base, sans: [`*.${base}`] }]);
			assert.equal(tls.certResolver, "letsencrypt");
		}

		const api = await get(port, base, "/api/auth/csrf");
		assert.equal(api.body, "apex-api");
		for (const url of [
			"/login",
			"/consent",
			"/auth/login",
			"/privacy",
			"/imprint",
			"/terms",
			"/unsubscribe",
			"/admin",
			"/settings",
		]) {
			await expectStatus(port, base, url, 200, `Apex passthrough ${url}`);
		}
		for (const [url, tail] of [
			["/w/acme", "/"],
			["/w/acme?range=1y", "/?range=1y"],
			["/w/acme/activity?range=1y&repo=a%2Fb", "/activity?range=1y&repo=a%2Fb"],
			["/w/acme/user/a%20b?x=%26&x=2", "/user/a%20b?x=%26&x=2"],
		]) {
			assert.ok(url !== undefined && tail !== undefined);
			const response = await get(port, base, url);
			assert.equal(response.status, enabled === "true" ? 308 : 200, url);
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
		if (enabled === "true") {
			const page = await get(port, `acme.${base}`, "/");
			const configPath = /src="(?<path>\/env-config[^"]+)"/u.exec(page.body)?.groups?.path;
			assert.ok(configPath !== undefined);
			const config = await get(port, `acme.${base}`, configPath);
			assert.ok(config.body.includes(`APPLICATION_SERVER_URL: "https://${base}/api"`));
			assert.ok(config.body.includes(`HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: "${base}"`));
			const head = await get(port, base, "/w/acme/activity?x=1", "HEAD");
			assert.equal(head.status, 308);
			await run("docker", [...args, "stop", "webapp"], { env });
			await waitForBody(port, `acme.${base}`, "/activity", "back soon");
			await expectStatus(port, `docs.${base}`, "/", 404);
			await expectStatus(port, `acme.${base}`, "/api/auth/csrf", 404);
		}
		console.log(
			`Verified subdomains=${enabled} at https://127.0.0.1:${port} with Host/SNI ${base} and acme.${base}`,
		);
	}
} catch (error) {
	await run("docker", [...args, "logs", "--no-color", "--tail", "60"], { env });
	throw error;
} finally {
	await run("docker", [...args, "down", "--volumes", "--remove-orphans"], { env });
	await succeeds("docker", ["image", "rm", image]);
	await rm(directory, { recursive: true, force: true });
}
