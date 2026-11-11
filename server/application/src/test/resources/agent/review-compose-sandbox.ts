import { KINDS } from "../../../main/resources/agent/driver-kinds.ts";
// Runs a review sandbox's driver against a test gateway until it ended one run, with the sandbox's own token.
// Usage: node review-compose-sandbox.ts <gateway URL> <sandbox token>. Prints {"ended": <status>} or {"idle": true}.
import { drive } from "../../../main/resources/agent/driver.ts";

const [gatewayUrl, token] = process.argv.slice(2);
if (gatewayUrl === undefined || token === undefined) {
	throw new Error("Expected the gateway URL and the sandbox token");
}
const stop = new AbortController();
// The test gateway has no Kubernetes API, so the policy guard has nothing to probe: the test stands in for it.
const isolation = Promise.resolve({ isolated: true } as const);
let idle = 0;
const fetch: typeof globalThis.fetch = async (input, init) => {
	const response = await globalThis.fetch(input, init);
	const path = new URL(input instanceof Request ? input.url : String(input)).pathname;
	if (path.endsWith("/end")) {
		process.stdout.write(JSON.stringify({ ended: response.status }));
		stop.abort();
	} else if (path.endsWith("/next") && response.status === 204 && (idle += 1) >= 3) {
		process.stdout.write(JSON.stringify({ idle: true }));
		stop.abort();
	}
	return response;
};
await drive({
	gatewayUrl,
	token,
	kinds: KINDS,
	signal: stop.signal,
	isolation,
	fetch,
	idleWaitMs: 50,
});
