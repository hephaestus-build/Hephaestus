import { once } from "node:events";
import { writeFileSync } from "node:fs";
import { connect } from "node:net";
import { setTimeout as sleep } from "node:timers/promises";

// Records when the Kubernetes API stopped answering, in the file that the first argument names.
const firstApi = process.argv[1];
const start = performance.timeOrigin;
async function reach() {
	const socket = connect({
		host: process.env.KUBERNETES_SERVICE_HOST,
		port: Number(process.env.KUBERNETES_SERVICE_PORT),
		timeout: 300,
	});
	try {
		await Promise.race([
			once(socket, "connect"),
			once(socket, "timeout").then(() => {
				throw new Error("timed out");
			}),
		]);
		return true;
	} catch {
		return false;
	} finally {
		socket.destroy();
	}
}
process.on("SIGTERM", () => {
	process.exit(0);
});
setInterval(() => undefined, 2 ** 30);

let result = "open";
while (Date.now() - start < 10_000) {
	if (!(await reach())) {
		result = `closed after ${String(Math.round(Date.now() - start))} ms`;
		break;
	}
	await sleep(50);
}
try {
	if (firstApi !== undefined) {
		writeFileSync(firstApi, result);
	}
} catch {
	// The probe reads a missing file as `null`.
}
