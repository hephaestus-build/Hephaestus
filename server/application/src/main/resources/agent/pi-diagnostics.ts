/**
 * The review runtime's diagnostics on stderr. Each line names one event from a closed set, with
 * fields whose values are numbers, booleans, reason codes, identifiers or labels. A value that a
 * tool, a model, a provider or reviewed code wrote never appears: such text can hold reviewed code,
 * a credential or a person's words, and the worker copies these lines into its own log. An error is
 * named by its class, and a text by its length.
 *
 * The worker keeps only lines that match `DIAGNOSTIC_LINE` (`SandboxExits.java`), so a line that
 * breaks this rule is dropped there too.
 */

export type DiagnosticEvent =
	| "admission.retrying"
	| "admission.withheld"
	| "assistant.message"
	| "compaction.failed"
	| "context.compacted"
	| "coverage.measured"
	| "extension.failed"
	| "gateway.refused"
	| "measurement.ended"
	| "measurement.finished"
	| "measurement.stopped"
	| "models.unparsable"
	| "notes.failed"
	| "output.failed"
	| "practice.failed"
	| "practice.inputs_invalid"
	| "practice.not_reached"
	| "practice.oversized"
	| "practice.program_finished"
	| "practice.program_log"
	| "practice.result_refused"
	| "practice.unsubmitted"
	| "practice_scripts.truncated"
	| "provider.failed"
	| "provider.parse_failed"
	| "provider.registered"
	| "provider.retrying"
	| "review.concurrency"
	| "review.failed"
	| "review.fatal"
	| "review.finishing"
	| "review.partial"
	| "review.planned"
	| "review.started"
	| "review.succeeded"
	| "review.unreachable"
	| "review.unhandled_rejection"
	| "session.abort_failed"
	| "session.idle_wait"
	| "session.steer_failed"
	| "task.invalid"
	| "task.loaded"
	| "tool.called"
	| "tool.failed"
	| "turn.finished"
	| "turn.near_ceiling"
	| "turn.nudged"
	| "turn.output_limit"
	| "turn.repeated_call"
	| "turn.started"
	| "turn.stopped"
	| "watchdog.exited";

export type DiagnosticValue = string | number | boolean | undefined;

/** A whole diagnostic line. The worker's copy of the same grammar is `SandboxExits.DIAGNOSTIC_LINE`. */
export const DIAGNOSTIC_LINE =
	/^\[pi-(?:runner|provider)\] [a-z][a-z0-9_]*(?:\.[a-z0-9_]+)*(?: [a-z][a-z0-9_.]{0,31}=[\w.,:/+-]{1,128})*$/u;

const VALUE = /^[\w.,:/+-]{1,128}$/u;

/** The line for `event`, with each field that has a value. A text that a value cannot hold becomes `invalid`. */
export function formatDiagnostic(
	event: DiagnosticEvent,
	fields: Record<string, DiagnosticValue> = {},
	source: "runner" | "provider" = "runner",
): string {
	const parts = [`[pi-${source}] ${event}`];
	for (const [key, value] of Object.entries(fields)) {
		const text = value === undefined ? "" : String(value);
		if (text === "") {
			continue;
		}
		parts.push(`${key}=${VALUE.test(text) ? text : "invalid"}`);
	}
	return parts.join(" ");
}

export function diagnose(
	event: DiagnosticEvent,
	fields: Record<string, DiagnosticValue> = {},
	source: "runner" | "provider" = "runner",
): void {
	console.error(formatDiagnostic(event, fields, source));
}

/** An error's class and closed reason code, never its message: a message can quote the input that failed. */
export function errorKind(error: unknown): string {
	if (!(error instanceof Error)) {
		return typeof error;
	}
	const name = /^[A-Za-z]{1,64}$/u.test(error.name) ? error.name : "Error";
	// An error that carries a closed reason code, such as `UnratedError`, names it too.
	const reason: unknown = "reason" in error ? error.reason : undefined;
	return typeof reason === "string" && /^[a-z_-]{1,32}$/u.test(reason) ? `${name}.${reason}` : name;
}

/**
 * Where an error was thrown, as the runtime's own `file:line` frames, at most four so that they fit
 * one value. A frame names our code, not the input; the message stays out.
 */
export function stackOf(error: unknown): string | undefined {
	if (!(error instanceof Error) || error.stack === undefined) {
		return undefined;
	}
	const frames = [...error.stack.matchAll(/(?<file>[\w.-]+\.[cm]?[jt]s):(?<line>\d+):\d+/gu)]
		.flatMap(({ groups }) =>
			groups?.file === undefined || groups.line === undefined
				? []
				: [`${groups.file}:${groups.line}`],
		)
		.slice(0, 4);
	return frames.length === 0 ? undefined : frames.join(",");
}

/** A label that the runtime composed from its own ids and slugs, as one value: spaces become `_`. */
export function token(label: string): string {
	return label
		.trim()
		.replaceAll(/\s+/gu, "_")
		.replaceAll(/[^\w.,:/+-]/gu, "");
}

/** Milliseconds as whole numbers, so a duration is a count and never a formatted text. */
export function ms(value: number): number {
	return Math.round(value);
}
