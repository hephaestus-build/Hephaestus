// Scrape the supplied CI artifact, or build a local JAR when HEPHAESTUS_APPLICATION_JAR is unset.
import { spawn } from "node:child_process";
import { once } from "node:events";
import { glob, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { join } from "node:path";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

import { run } from "./lib/process.ts";

const serverDirectory = join(import.meta.dirname, "..", "server");
const specification = join(serverDirectory, "openapi.yaml");
const wrapper = join(import.meta.dirname, "run-gradlew.ts");
const startupBudgetMs = 180_000;

async function executableJar(): Promise<string> {
	const configured = process.env.HEPHAESTUS_APPLICATION_JAR;
	if (configured) return configured;
	await run(process.execPath, [wrapper, ":application:bootJar", ...process.argv.slice(2)], {
		cwd: serverDirectory,
	});
	const jars = (
		await Array.fromAsync(
			glob("application/build/libs/hephaestus-application-*.jar", { cwd: serverDirectory }),
		)
	).filter((jar) => !/-(?:sources|javadoc)\.jar$/.test(jar));
	if (jars.length !== 1) throw new Error(`Expected one executable JAR, found ${jars.length}`);
	return join(serverDirectory, jars[0] ?? "");
}

async function freePort(): Promise<number> {
	const probe = createServer().listen(0, "127.0.0.1");
	await once(probe, "listening");
	const address = probe.address();
	probe.close();
	await once(probe, "close");
	if (!address || typeof address === "string") throw new Error("Could not allocate a port");
	return address.port;
}

async function fetchSpecification(url: string, child: ReturnType<typeof spawn>): Promise<string> {
	const deadline = Date.now() + startupBudgetMs;
	while (Date.now() < deadline) {
		if (child.exitCode !== null) throw new Error(`The server exited with code ${child.exitCode}`);
		try {
			const response = await fetch(url, { signal: AbortSignal.timeout(deadline - Date.now()) });
			if (response.ok) return await response.text();
		} catch {
			// Retry connection failures until the startup deadline.
		}
		await sleep(1000);
	}
	throw new Error(`The server did not serve ${url} within ${startupBudgetMs / 1000}s`);
}

const jar = await executableJar();
// Both HTTP connectors must be isolated from other worktrees, including the sandbox gateway.
const port = await freePort();
let sandboxPort = await freePort();
while (sandboxPort === port) sandboxPort = await freePort();
const child = spawn(
	"java",
	[
		"-jar",
		jar,
		"--spring.profiles.active=specs",
		`--server.port=${port}`,
		`--hephaestus.sandbox.gateway.port=${sandboxPort}`,
		"--management.server.port=0",
	],
	{ cwd: serverDirectory, stdio: ["ignore", "inherit", "inherit"] },
);
const exited = once(child, "exit");
try {
	const yaml = await fetchSpecification(`http://127.0.0.1:${port}/v3/api-docs.yaml`, child);
	if (!yaml.trim()) throw new Error("The server returned an empty specification");
	await rm(specification, { force: true });
	await writeFile(specification, yaml);
	console.log(`Wrote ${specification} (${(await readFile(specification)).length} bytes)`);
} finally {
	child.kill("SIGTERM");
	await Promise.race([exited, sleep(15_000).then(() => child.kill("SIGKILL"))]);
}
