import { appendFileSync } from "node:fs";

export function promotionCoverage(frozen: boolean, outcome: string) {
	const observation = frozen
		? "Channel hold recorded; public webapp version was not checked."
		: outcome === "success"
			? "The public webapp reported the requested application version."
			: `Public webapp version verification did not succeed (step outcome: ${outcome}).`;
	return [
		"## Promotion verification scope",
		observation,
		"",
		"**Not verified by this workflow:** server, worker, webhook, database, NATS, and integration runtime health or version convergence.",
		"The signed channel authorizes hosts to apply the deployment; a matching public webapp version is not a full-stack health verdict.",
		"",
		"Use host reconciliation metrics and logs to confirm the Compose apply and each supported service's readiness. Keep management endpoints private.",
		"[Host verification runbook](https://docs.hephaestus.build/admin/pull-based-deployment#watching-it)",
		"",
	].join("\n");
}

if (import.meta.main) {
	const summary = promotionCoverage(
		process.env.FROZEN === "true",
		process.env.WEBAPP_OUTCOME ?? "unknown",
	);
	process.stdout.write(summary);
	if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
}
