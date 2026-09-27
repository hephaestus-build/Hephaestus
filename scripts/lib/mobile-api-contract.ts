import { asArray, isRecord } from "./json.ts";

/** oasdiff's level for a change that breaks a client; 2 is a warning and 1 is information. */
const ERR = 3;

/**
 * The breaking changes, from oasdiff's JSON report, to operations a released app calls. `operations`
 * holds that release's entries as `METHOD /path`, the form the mobile client's generator filter takes.
 */
export function breaksForRelease(report: unknown, operations: readonly string[]): string[] {
	const called = new Set(operations);
	return asArray(report ?? [], "oasdiff report")
		.filter(isRecord)
		.filter(
			(change) =>
				typeof change.level === "number" &&
				change.level >= ERR &&
				called.has(`${String(change.operation)} ${String(change.path)}`),
		)
		.map((change) => `${String(change.operation)} ${String(change.path)}: ${String(change.text)}`);
}
