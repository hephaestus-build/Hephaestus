// Replays one composition case through the Node composer loop against a loopback gateway that the replay test serves.
// Usage: node review-composer-replay.ts <gateway URL>. Prints the outcome as JSON on stdout.
import { composeReview } from "../../../main/resources/agent/review-composer.ts";

const url = process.argv[2];
if (url === undefined) {
	throw new Error("Expected the gateway URL");
}
const outcome = await composeReview({
	compositionUrl: `${url}/composition`,
	proxyUrl: `${url}/llm`,
	headers: {},
	retryPauseMs: 0,
});
if (!outcome.leaseLost && outcome.failure !== null) {
	console.error(outcome.failure);
}
process.stdout.write(
	JSON.stringify(outcome.leaseLost ? { leaseLost: true } : { handedOn: outcome.handedOn }),
);
