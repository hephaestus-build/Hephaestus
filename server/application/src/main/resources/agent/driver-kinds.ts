// driver-kinds.ts — the runner of each run kind that a sandbox drives, by the server's AgentRunKind name. A kind joins
// here together with its server side (`SandboxRunKind`), so the gateway never offers a kind that no runner takes.

import type { KindRunner } from "./driver.ts";
import { composeReview } from "./review-composer.ts";

/** The feedback composer of a sealed review: it composes, then the driver ends or hands on the run. */
const reviewCompose: KindRunner = async ({ assignment, gateway, signal }) => {
	const outcome = await composeReview({
		compositionUrl: `${gateway.url}/internal/agent/runs/${assignment.runId}/composition`,
		proxyUrl: `${gateway.url}/internal/llm`,
		headers: gateway.headers,
		signal,
	});
	if (outcome.leaseLost) {
		return { leaseLost: true };
	}
	return { leaseLost: false, outcome: outcome.handedOn === null ? "completed" : "handed_on" };
};

export const KINDS: Readonly<Record<string, KindRunner>> = { REVIEW_COMPOSE: reviewCompose };
