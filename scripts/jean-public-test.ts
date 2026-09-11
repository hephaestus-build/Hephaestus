import { spawn } from "node:child_process";
import { createHmac } from "node:crypto";
import { access, open, readFile, readlink, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { Client } from "pg";

import { isHostname, positivePort, readEnvFile, requiredEnv } from "./lib/env.ts";
import { output, run, succeeds } from "./lib/process.ts";

const root = join(import.meta.dirname, "..");
// No default: the host this runs against is an operator's own instance, and naming one here would
// publish it. The run says which host it needs and stops.
const host = requiredEnv(process.env, "HEPHAESTUS_PUBLIC_TEST_HOST");
if (!isHostname(host)) throw new Error("HEPHAESTUS_PUBLIC_TEST_HOST must be a DNS hostname");
const origin = `https://${host}`;
const appPort = positivePort(
	process.env.HEPHAESTUS_PUBLIC_TEST_APP_PORT ?? "38085",
	"HEPHAESTUS_PUBLIC_TEST_APP_PORT",
);
const managementPort = positivePort(
	process.env.HEPHAESTUS_PUBLIC_TEST_MANAGEMENT_PORT ?? "38086",
	"HEPHAESTUS_PUBLIC_TEST_MANAGEMENT_PORT",
);
const postgresPort = positivePort(
	process.env.HEPHAESTUS_PUBLIC_TEST_POSTGRES_PORT ?? "55432",
	"HEPHAESTUS_PUBLIC_TEST_POSTGRES_PORT",
);
const container = process.env.HEPHAESTUS_PUBLIC_TEST_WEBAPP_CONTAINER ?? "heph-local-webapp";
const traefikFile =
	process.env.HEPHAESTUS_PUBLIC_TEST_TRAEFIK_FILE ??
	"/data/coolify/proxy/dynamic/hephaestus-local-test.yaml";
const logFile = process.env.HEPHAESTUS_PUBLIC_TEST_SERVER_LOG ?? "/tmp/heph-public-server.log";
const pidFile = process.env.HEPHAESTUS_PUBLIC_TEST_PID_FILE ?? "/tmp/heph-public-server.pid";
const nginxFile = process.env.HEPHAESTUS_PUBLIC_TEST_NGINX_CONF ?? "/tmp/heph-local-nginx.conf";

async function request(path: string, init?: RequestInit): Promise<Response> {
	return fetch(`${origin}${path}`, {
		...init,
		redirect: "manual",
		signal: AbortSignal.timeout(15_000),
	});
}

async function gateway(): Promise<string> {
	try {
		return (
			await output("docker", [
				"network",
				"inspect",
				"coolify",
				"--format",
				"{{(index .IPAM.Config 0).Gateway}}",
			])
		).trim();
	} catch {
		return "10.0.4.1";
	}
}

async function writeConfigs(): Promise<void> {
	await writeFile(
		join(root, "webapp/dist/env-config.js"),
		`window.__ENV__ = ${JSON.stringify(
			{
				APPLICATION_VERSION: "DEV-public-test",
				APPLICATION_CLIENT_URL: origin,
				APPLICATION_SERVER_URL: `${origin}/api`,
				XSRF_COOKIE_NAME: "__Host-XSRF-TOKEN",
				SENTRY_ENVIRONMENT: "local-public-test",
				SENTRY_DSN: "",
				LEGAL_PROFILE: "",
				TANSTACK_DEVTOOLS_ENABLED: "false",
				GIT_BRANCH: (await output("git", ["branch", "--show-current"], { cwd: root })).trim(),
				GIT_COMMIT: (await output("git", ["rev-parse", "--short", "HEAD"], { cwd: root })).trim(),
				DEPLOYED_AT: new Date().toISOString(),
			},
			undefined,
			2,
		)};\n`,
	);
	await writeFile(
		nginxFile,
		`server { listen 80; server_name _; root /usr/share/nginx/html; index index.html; access_log off; location /assets/ { try_files $uri =404; add_header Cache-Control "public, max-age=31536000, immutable" always; } location / { try_files $uri $uri/ /index.html; add_header Cache-Control "no-cache" always; } }\n`,
	);
	if (
		await access(dirname(traefikFile))
			.then(() => true)
			.catch(() => false)
	)
		await writeFile(traefikFile, `${JSON.stringify(await traefikConfig(), undefined, 2)}\n`);
}

async function traefikConfig(): Promise<Record<string, unknown>> {
	const redirect = "hephaestus-test-redirect-to-https";
	const gzip = "hephaestus-test-gzip";
	return {
		http: {
			middlewares: {
				[redirect]: { redirectScheme: { scheme: "https" } },
				[gzip]: { compress: {} },
				"hephaestus-test-api-strip-prefix": { stripPrefix: { prefixes: ["/api"] } },
			},
			routers: {
				"hephaestus-test-http": {
					entryPoints: ["http"],
					rule: `Host(\`${host}\`)`,
					service: "hephaestus-test-webapp",
					middlewares: [redirect],
					priority: 1,
				},
				"hephaestus-test-api-https": {
					entryPoints: ["https"],
					rule: `Host(\`${host}\`) && PathPrefix(\`/api\`)`,
					service: "hephaestus-test-backend",
					middlewares: ["hephaestus-test-api-strip-prefix", gzip],
					tls: { certResolver: "letsencrypt" },
					priority: 100,
				},
				"hephaestus-test-webhooks-https": {
					entryPoints: ["https"],
					rule: `Host(\`${host}\`) && PathPrefix(\`/webhooks\`)`,
					service: "hephaestus-test-backend",
					middlewares: [gzip],
					tls: { certResolver: "letsencrypt" },
					priority: 110,
				},
				"hephaestus-test-webapp-https": {
					entryPoints: ["https"],
					rule: `Host(\`${host}\`)`,
					service: "hephaestus-test-webapp",
					middlewares: [gzip],
					tls: { certResolver: "letsencrypt" },
					priority: 1,
				},
			},
			services: {
				"hephaestus-test-backend": {
					loadBalancer: { servers: [{ url: `http://${await gateway()}:${appPort}` }] },
				},
				"hephaestus-test-webapp": {
					loadBalancer: { servers: [{ url: `http://${container}:80` }] },
				},
			},
		},
	};
}

async function stopBackend(): Promise<void> {
	try {
		await access(pidFile);
	} catch {
		return;
	}
	const pid = Number((await readFile(pidFile, "utf8")).trim());
	if (!Number.isSafeInteger(pid) || pid <= 1) throw new Error(`Invalid backend PID in ${pidFile}`);
	if (!processExists(pid)) {
		await rm(pidFile, { force: true });
		return;
	}
	if (!(await isBackendProcess(pid)))
		throw new Error(`Refusing to stop PID ${pid}: it is not this worktree's backend`);
	process.kill(-pid, "SIGTERM");
	for (let attempt = 0; attempt < 50 && processGroupExists(pid); attempt++)
		await new Promise((resolve) => {
			setTimeout(resolve, 100);
		});
	if (processGroupExists(pid)) process.kill(-pid, "SIGKILL");
	for (let attempt = 0; attempt < 20 && processGroupExists(pid); attempt++)
		await new Promise((resolve) => {
			setTimeout(resolve, 100);
		});
	if (processGroupExists(pid)) throw new Error(`Backend process group ${pid} did not stop`);
	await rm(pidFile, { force: true });
}

function processExists(pid: number): boolean {
	try {
		process.kill(pid, 0);
		return true;
	} catch (error) {
		if (typeof error === "object" && error !== null && Reflect.get(error, "code") === "ESRCH")
			return false;
		throw error;
	}
}

function processGroupExists(pid: number): boolean {
	try {
		process.kill(-pid, 0);
		return true;
	} catch (error) {
		if (typeof error === "object" && error !== null && Reflect.get(error, "code") === "ESRCH")
			return false;
		throw error;
	}
}

async function isBackendProcess(pid: number): Promise<boolean> {
	try {
		const [cwd, command] = await Promise.all([
			readlink(`/proc/${pid}/cwd`),
			readFile(`/proc/${pid}/cmdline`, "utf8"),
		]);
		return (
			cwd === join(root, "server") &&
			command.includes("GradleWrapperMain") &&
			command.includes(":application:bootRun")
		);
	} catch {
		return false;
	}
}

async function startBackend(): Promise<void> {
	await stopBackend();
	const fileEnv = await readEnvFile(join(root, "server/.env"));
	const env = {
		...fileEnv,
		...process.env,
		HEPHAESTUS_AUTH_DEV_LOGIN_ENABLED: "false",
		HEPHAESTUS_DEV_TRIGGER_ENABLED: "false",
		HEPHAESTUS_WORKSPACE_INIT_DEFAULT: "false",
		APPLICATION_HOST_URL: origin,
		HEPHAESTUS_WEBAPP_URL: origin,
		HEPHAESTUS_AUTH_ISSUER: origin,
		HEPHAESTUS_AUTH_API_BASE_PATH: fileEnv.HEPHAESTUS_AUTH_API_BASE_PATH ?? "/api",
		HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI: `${origin}/api/oauth/callback/slack`,
		POSTGRES_PORT: String(postgresPort),
		NATS_SERVER: process.env.HEPHAESTUS_PUBLIC_TEST_NATS_SERVER ?? "nats://localhost:4222",
		SERVER_PORT: String(appPort),
		MANAGEMENT_PORT: String(managementPort),
		SPRING_DOCKER_COMPOSE_ENABLED: "false",
		SERVER_FORWARD_HEADERS_STRATEGY: "native",
		JAVA_TOOL_OPTIONS:
			`${fileEnv.JAVA_TOOL_OPTIONS ?? ""} -Djava.net.preferIPv4Stack=true -Dhephaestus.auth.dev-login-enabled=false -Dhephaestus.dev.trigger-enabled=false`.trim(),
		HEPHAESTUS_SYNC_RUN_ON_STARTUP: fileEnv.HEPHAESTUS_SYNC_RUN_ON_STARTUP ?? "false",
		HEPHAESTUS_SYNC_BACKFILL_ENABLED: fileEnv.HEPHAESTUS_SYNC_BACKFILL_ENABLED ?? "false",
	};
	const log = await open(logFile, "a");
	const child = spawn("./gradlew", [":application:bootRun", "-Pprofiles=local", "--no-daemon"], {
		cwd: join(root, "server"),
		env: { ...process.env, ...env },
		stdio: ["ignore", log.fd, log.fd],
		detached: true,
	});
	child.unref();
	await writeFile(pidFile, `${child.pid}\n`, { mode: 0o600 });
	for (let attempt = 0; attempt < 90; attempt++) {
		if (
			await fetch(`http://localhost:${managementPort}/actuator/health/readiness`)
				.then((response) => response.ok)
				.catch(() => false)
		)
			return;
		await new Promise((resolve) => {
			setTimeout(resolve, 2000);
		});
	}
	throw new Error(`Backend did not become ready. Inspect ${logFile}`);
}

async function smoke(): Promise<void> {
	console.log(`Smoke testing ${origin}`);
	if (!(await request("/")).ok) throw new Error("public webapp is unavailable");
	if (
		!(await (await request("/env-config.js")).text()).includes(
			`APPLICATION_SERVER_URL": "${origin}/api`,
		)
	)
		throw new Error("runtime API URL is incorrect");
	if (/"providerType"\s*:\s*"DEV"/.test(await (await request("/api/identity-providers")).text()))
		throw new Error("Passwordless dev login is exposed");
	if ((await request("/api/auth/me")).status !== 401)
		throw new Error("/api/auth/me must return 401");
	if ((await request("/api/api/dev/trigger-review", { method: "POST" })).ok)
		throw new Error("Dev review trigger is exposed");
	const env = { ...(await readEnvFile(join(root, "server/.env"))), ...process.env };
	if (env.GITHUB_OAUTH_CLIENT_ID) {
		const login = await request("/api/auth/login?provider=github&returnTo=/");
		const authorization = await request("/api/oauth2/authorization/github");
		if (login.headers.get("location") !== `${origin}/api/oauth2/authorization/github`)
			throw new Error("GitHub login produced an incorrect authorization redirect");
		const location = authorization.headers.get("location");
		if (
			!location ||
			new URL(location).searchParams.get("redirect_uri") !==
				`${origin}/api/login/oauth2/code/github`
		)
			throw new Error("GitHub OAuth produced an incorrect callback URI");
	}
	const secret = env.HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET;
	if (secret) {
		const body = JSON.stringify({
			type: "url_verification",
			challenge: "hephaestus-public-test-ok",
		});
		const timestamp = Math.floor(Date.now() / 1000).toString();
		const signature = `v0=${createHmac("sha256", secret).update(`v0:${timestamp}:${body}`).digest("hex")}`;
		const response = await request("/webhooks/slack", {
			method: "POST",
			headers: {
				"content-type": "application/json",
				"x-slack-request-timestamp": timestamp,
				"x-slack-signature": signature,
			},
			body,
		});
		if ((await response.text()) !== "hephaestus-public-test-ok")
			throw new Error("Slack challenge smoke failed");
	}
	await seedStatus();
	console.log("Smoke OK.");
}

async function seedStatus(): Promise<void> {
	const env = { ...(await readEnvFile(join(root, "server/.env"))), ...process.env };
	const account = env.GITLAB_GROUP_PATH;
	if (!env.GITLAB_PAT || !account) {
		console.log("SCM seed: skipped (GITLAB_PAT or GITLAB_GROUP_PATH missing)");
		return;
	}
	if (!/^[A-Za-z0-9._/-]+$/.test(account))
		throw new Error("SCM seed has an invalid account identifier");
	const database = new Client({
		host: "localhost",
		port: postgresPort,
		user: env.POSTGRES_USER ?? "root",
		password: env.POSTGRES_PASSWORD ?? "root",
		database: env.POSTGRES_DB ?? "hephaestus",
		connectionTimeoutMillis: 2000,
	});
	await database.connect();
	try {
		const [connections, repositories, duplicates, mentors] = await Promise.all([
			database.query<{ count: string }>(
				"SELECT count(*) FROM workspace w JOIN connection c ON c.workspace_id=w.id WHERE w.status='ACTIVE' AND lower(w.account_login)=lower($1) AND c.kind='GITLAB' AND c.state='ACTIVE'",
				[account],
			),
			database.query<{ count: string }>(
				"SELECT count(*) FROM repository_to_monitor r JOIN workspace w ON w.id=r.workspace_id WHERE w.status='ACTIVE' AND lower(w.account_login)=lower($1)",
				[account],
			),
			database.query<{ count: string }>(
				"SELECT count(*) FROM (SELECT lower(w.account_login), c.kind FROM workspace w JOIN connection c ON c.workspace_id=w.id AND c.state='ACTIVE' AND c.kind IN ('GITHUB','GITLAB') WHERE w.status='ACTIVE' GROUP BY lower(w.account_login),c.kind HAVING count(DISTINCT w.id) > 1) d",
			),
			database.query<{ count: string }>(
				"SELECT count(*) FROM workspace w JOIN workspace_agent_binding ab ON ab.workspace_id=w.id AND ab.purpose='MENTOR' LEFT JOIN llm_model im ON im.id=ab.instance_model_id LEFT JOIN llm_connection ic ON ic.id=im.connection_id LEFT JOIN workspace_llm_model wlm ON wlm.id=ab.workspace_model_id AND wlm.workspace_id=w.id LEFT JOIN workspace_llm_connection wlc ON wlc.id=wlm.connection_id AND wlc.workspace_id=w.id WHERE w.status='ACTIVE' AND lower(w.account_login)=lower($1) AND w.mentor_enabled=true AND ab.enabled=true AND ((ab.instance_model_id IS NOT NULL AND im.enabled=true AND ic.enabled=true) OR (ab.workspace_model_id IS NOT NULL AND wlm.enabled=true AND wlc.enabled=true)) AND EXISTS (SELECT 1 FROM workspace_membership mem JOIN identity_link il ON il.external_actor_id=mem.user_id AND il.disabled_at IS NULL JOIN account_feature af ON af.account_id=il.account_id AND af.flag='mentor_access' WHERE mem.workspace_id=w.id)",
				[account],
			),
		]);
		const active = connections.rows[0]?.count ?? "0";
		const monitored = repositories.rows[0]?.count ?? "0";
		const duplicate = duplicates.rows[0]?.count ?? "0";
		const mentor = mentors.rows[0]?.count ?? "0";
		console.log(
			`SCM seed: activeConnections=${active} monitoredRepositories=${monitored} duplicateScmAccounts=${duplicate}`,
		);
		console.log(`Mentor seed: ready=${mentor}`);
		if (active !== "1" || monitored === "0" || duplicate !== "0" || mentor !== "1")
			throw new Error("public-test seed is incomplete or ambiguous");
	} finally {
		await database.end();
	}
}

async function start(): Promise<void> {
	if (process.env.HEPHAESTUS_PUBLIC_TEST_SKIP_WEBAPP_BUILD !== "true")
		await run("vp", ["run", "build:webapp"], { cwd: root });
	await writeConfigs();
	await succeeds("docker", ["rm", "-f", container]);
	await run("docker", [
		"run",
		"-d",
		"--name",
		container,
		"--restart",
		"unless-stopped",
		"--network",
		"coolify",
		"-v",
		`${root}/webapp/dist:/usr/share/nginx/html:ro`,
		"-v",
		`${nginxFile}:/etc/nginx/conf.d/default.conf:ro`,
		"nginx:stable-alpine",
	]);
	try {
		await startBackend();
		await smoke();
	} catch (error) {
		const cleanupErrors: unknown[] = [];
		await stopBackend().catch((cleanupError) => cleanupErrors.push(cleanupError));
		if (!(await succeeds("docker", ["rm", "-f", container])))
			cleanupErrors.push(new Error("Failed to remove the public-test webapp container"));
		await rm(traefikFile, { force: true }).catch((cleanupError) =>
			cleanupErrors.push(cleanupError),
		);
		if (cleanupErrors.length) {
			const primary = error instanceof Error ? error : new Error("Public-test startup failed");
			throw new AggregateError(
				[primary, ...cleanupErrors],
				"Public-test startup and rollback failed",
				{
					cause: error,
				},
			);
		}
		throw error;
	}
}

async function backendStatus(): Promise<"running" | "stale" | "stopped"> {
	try {
		await access(pidFile);
	} catch {
		return "stopped";
	}
	const pid = Number((await readFile(pidFile, "utf8")).trim());
	if (!Number.isSafeInteger(pid) || pid <= 1 || !processExists(pid)) return "stale";
	return (await isBackendProcess(pid)) ? "running" : "stale";
}

async function main(): Promise<void> {
	switch (process.argv[2] ?? "start") {
		case "start":
			await start();
			break;
		case "stop":
			await stopBackend();
			await succeeds("docker", ["rm", "-f", container]);
			break;
		case "status":
			console.log(`Public URL: ${origin}\nBackend: ${await backendStatus()}`);
			console.log(
				`Backend health: ${await fetch(
					`http://localhost:${managementPort}/actuator/health/readiness`,
				)
					.then((response) => response.status)
					.catch(() => "unavailable")}`,
			);
			try {
				console.log(
					(
						await output("docker", [
							"ps",
							"--filter",
							`name=^/${container}$`,
							"--format",
							"{{.Names}} {{.Status}}",
						])
					).trim(),
				);
			} catch {
				console.error("Webapp container: Docker unavailable");
			}
			await seedStatus().catch((error) =>
				console.error(error instanceof Error ? error.message : String(error)),
			);
			break;
		case "smoke":
			await smoke();
			break;
		case "seed-status":
			await seedStatus();
			break;
		case "help":
		case "-h":
		case "--help":
			console.log("Usage: node scripts/jean-public-test.ts [start|stop|status|smoke|seed-status]");
			break;
		default:
			console.error(
				"Usage: node scripts/jean-public-test.ts [start|stop|status|smoke|seed-status]",
			);
			process.exitCode = 2;
	}
}

if (import.meta.main) await main();
