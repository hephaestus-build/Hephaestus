/**
 * Turns the report of the last `vp run` into what a CI job needs to say about it: one workflow
 * notice per task that did not pass, naming the command that reproduces it, and the report
 * itself in the job summary. Reads the report on stdin; exits non-zero when any task did not pass.
 *
 * The runner stops the tasks still running when one fails and reports them exactly like the task
 * that failed. Neither the cross nor report order identifies which task failed.
 */
import { appendFileSync } from "node:fs";
import { text } from "node:stream/consumers";
import { isSet } from "./lib/env.ts";

/**
 * SGR escapes, which the runner emits whenever colour is forced on — `FORCE_COLOR` is set in some
 * terminals and agent harnesses — even though nothing here is a terminal. Stripping them is what
 * keeps this a text parser rather than a guess about how the report was rendered.
 */
// ESC is the character being stripped, so the rule is reporting the thing this line is for.
// oxlint-disable-next-line eslint/no-control-regex -- deliberate: this matches SGR escapes
const ANSI = /\u001B\[[0-9;]*m/gu;

/**
 * Task names the report marks with a cross, from lines shaped `[n] package#task: $ command ✗`.
 *
 * A coloured report used to match nothing, so a failed run produced no annotations and exited 0 —
 * the one failure mode a gate must not have.
 */
export function unpassedTasks(report: string): string[] {
	const plain = report.replaceAll(ANSI, "");
	const names = [...plain.matchAll(/^\s*\[\d+\] [^#\n]+#(?<task>\S+): \$ [^\n]*✗/gmu)].flatMap(
		({ groups }) => (groups?.task === undefined ? [] : [groups.task]),
	);
	return [...new Set(names)];
}

if (import.meta.main) {
	const report = await text(process.stdin);
	const unpassed = unpassedTasks(report);
	const {
		GITHUB_SERVER_URL: server,
		GITHUB_REPOSITORY: repository,
		GITHUB_RUN_ID: runId,
		GITHUB_RUN_ATTEMPT: attempt,
	} = process.env;
	const runAttemptUrl =
		isSet(server) && isSet(repository) && isSet(runId) && isSet(attempt)
			? `${server}/${repository}/actions/runs/${runId}/attempts/${attempt}`
			: undefined;
	for (const task of unpassed) {
		const message =
			`${task}: unpassed, cause unavailable.${runAttemptUrl === undefined ? "" : ` Run attempt: ${runAttemptUrl}`} | Reproduce with: vp run ${task}`
				.replaceAll("%", "%25")
				.replaceAll("\r", "%0D")
				.replaceAll("\n", "%0A");
		console.log(`::notice::${message}`);
	}
	const summary = process.env.GITHUB_STEP_SUMMARY;
	if (isSet(summary)) {
		const explanation =
			unpassed.length === 0
				? ""
				: "Crossed tasks are **unpassed, cause unavailable**. This report does not distinguish failures from tasks the runner stopped.\n\n";
		const source =
			runAttemptUrl === undefined
				? ""
				: `[Run attempt](${runAttemptUrl}). Open the quality job’s **Quality gates** step for task output.\n\n`;
		appendFileSync(summary, `\n${explanation}${source}\`\`\`text\n${report.trim()}\n\`\`\`\n`);
	}
	process.exitCode = unpassed.length === 0 ? 0 : 1;
}
