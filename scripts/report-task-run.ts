/**
 * Turns the report of the last `vp run` into what a CI job needs to say about it: one workflow error
 * annotation per task that did not pass, naming the command that reproduces it, and the report
 * itself in the job summary. Reads the report on stdin; exits non-zero when any task did not pass.
 *
 * The runner stops the tasks still running when one fails and reports them exactly like the task
 * that failed, so an annotation says a task did not pass rather than claiming it failed on its own.
 * The report in the summary keeps the order, which is where the first failure is.
 */
import { appendFileSync } from "node:fs";
import { text } from "node:stream/consumers";

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
	const names = [...plain.matchAll(/^\s*\[\d+\] [^#\n]+#(\S+): \$ [^\n]*✗/gmu)].flatMap(
		([, task]) => (task === undefined ? [] : [task]),
	);
	return [...new Set(names)];
}

if (import.meta.main) {
	const report = await text(process.stdin);
	const unpassed = unpassedTasks(report);
	for (const task of unpassed)
		console.log(`::error::${task} did not pass. Reproduce with: vp run ${task}`);
	const summary = process.env.GITHUB_STEP_SUMMARY;
	if (summary) appendFileSync(summary, `\n\`\`\`text\n${report.trim()}\n\`\`\`\n`);
	process.exitCode = unpassed.length === 0 ? 0 : 1;
}
