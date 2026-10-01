/**
 * Prepares `docker/self-host/.env` for an unattended boot of the supported installation: it runs
 * the installer an operator runs, then answers the settings `setup.sh` deliberately leaves blank.
 *
 * None of the answers is a credential — nothing in a smoke boot authenticates against a provider —
 * but every one has to be non-empty, because a blank required setting is exactly what the boot is
 * checking the installation refuses to start on.
 */
import { readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";

import { run } from "./lib/process.ts";

export const SELF_HOST = path.join(import.meta.dirname, "..", "docker", "self-host");

/**
 * Traefik routes a boot by `Host(APP_HOSTNAME)`, so the release smoke's ingress check reaches the
 * installation only under the name answered here; `release.yml` resolves this name to the loopback.
 */
export const SMOKE_HOSTNAME = "hephaestus-smoke.invalid";

const ANSWERS: Readonly<Record<string, string>> = {
	APP_HOSTNAME: SMOKE_HOSTNAME,
	ACME_EMAIL: "smoke@example.invalid",
	GH_OAUTH_CLIENT_ID: "smoke",
	GH_OAUTH_CLIENT_SECRET: "smoke-secret",
	HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS: "github:1",
};

/**
 * A key `setup.sh` left blank is answered; a key it did not leave blank is a rename in
 * `.env.example` that this file has not followed, and the boot would otherwise start on a setting
 * the operator is required to supply.
 */
export function answerBlankSettings(environment: string): string {
	let text = environment;
	for (const [key, value] of Object.entries(ANSWERS)) {
		const blank = new RegExp(`^${key}=$`, "mu");
		if (!blank.test(text)) {
			throw new Error(`${key} is not a blank setting of docker/self-host/.env`);
		}
		text = text.replace(blank, () => `${key}=${value}`);
	}
	return text;
}

/**
 * The worker reaches the Docker socket through the group that owns it, which differs between hosts, so
 * a boot that relied on the example's default would start a worker the daemon refuses.
 */
export function withDockerGroup(environment: string, groupId: number): string {
	const line = `DOCKER_GROUP_ID=${groupId}`;
	const assigned = /^DOCKER_GROUP_ID=.*$/mu;
	return assigned.test(environment)
		? environment.replace(assigned, line)
		: `${environment.trimEnd()}\n${line}\n`;
}

if (import.meta.main) {
	await run("./setup.sh", [], { cwd: SELF_HOST });
	const file = path.join(SELF_HOST, ".env");
	const { gid } = await stat("/var/run/docker.sock");
	await writeFile(file, withDockerGroup(answerBlankSettings(await readFile(file, "utf8")), gid));
}
