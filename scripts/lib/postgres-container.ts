import { spawnSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

/**
 * Whether the final PostgreSQL server in a container of the bundled image accepts connections.
 *
 * On an empty data directory, the entrypoint runs the init scripts on a temporary server that listens only on the
 * socket, then stops it and starts the final server. A probe over the socket can answer for the temporary server,
 * and the next command then meets "the database system is shutting down". Only the final server listens on TCP.
 */
export function finalServerAnswers(container: string): boolean {
	return (
		spawnSync("docker", ["exec", container, "pg_isready", "--quiet", "--host=127.0.0.1"], {
			stdio: "ignore",
			timeout: 15_000,
			killSignal: "SIGKILL",
		}).status === 0
	);
}

/** Waits up to about a minute for {@link finalServerAnswers}. */
export async function waitForFinalServer(container: string): Promise<void> {
	for (let attempt = 1; !finalServerAnswers(container); attempt += 1) {
		if (attempt === 60) {
			throw new Error(`PostgreSQL in ${container} did not become ready`);
		}
		await sleep(1000);
	}
}
