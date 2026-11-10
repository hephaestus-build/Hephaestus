/** The neutral author API. A domain kit validates item and supplies domain reads. */
import type {
	Candidate,
	Citation,
	Reason,
	ReviewOptions,
	SessionCandidate,
	PracticeInputs,
} from "./contract.ts";
import { isJsonObject } from "./json.ts";

export type {
	Candidate,
	Citation,
	ClaimBasis,
	Coverage,
	EvidenceLocation,
	Example,
	Facts,
	InapplicabilityWarrant,
	NonEmpty,
	Omission,
	OpenQuestion,
	Outcome,
	PreparedContext,
	Previous,
	Problem,
	Reason,
	ReviewLead,
	ReviewOptions,
	SearchWarrant,
	SessionCandidate,
	Severity,
	AboutRole,
	Origin,
	ItemRef,
	ReviewScope,
	PracticeInputs,
	ReadReceipt,
} from "./contract.ts";

export type UnratedReason = Reason | "cancelled";
export interface Evidence {
	record: (name: string) => Promise<{ status: "FOUND"; text: string } | { status: "ABSENT" }>;
	citeRecord: (name: string, startLine: number, endLine?: number) => Promise<Citation>;
}
export type PracticeScriptContext = PracticeInputs & {
	evidence: Evidence;
	now: string;
	abortSignal: AbortSignal;
	log: (text: string) => void;
	review: (options?: ReviewOptions) => Promise<Candidate>;
};

/**
 * What a script reads, as the public API promises it. `review` ends the script: it never returns to
 * the script, so a script returns its call and never reads its result. The toolkit's own context has
 * more; a script cannot rely on it. An allowlist, so a new toolkit or wire field stays private.
 */
export type ScriptContext = Pick<
	PracticeScriptContext,
	| "contractVersion"
	| "kind"
	| "item"
	| "about"
	| "practice"
	| "scope"
	| "now"
	| "abortSignal"
	| "log"
> & {
	evidence: Pick<Evidence, "record" | "citeRecord">;
	review: ((options?: ReviewOptions) => Promise<never>) & PracticeInputs["review"];
};

/** A script as the public API defines it. */
export interface ScriptDefinition {
	meta: Record<string, never>;
	/** Return `ctx.review(...)`, or a result that code decided, without coverage: the host measures it. */
	run: (ctx: ScriptContext) => Promise<SessionCandidate>;
}

export interface PracticeScriptDefinition {
	meta: Record<string, never>;
	/**
	 * Return what `ctx.review` returned, or a result that code decided: without coverage, which the host measures.
	 * The program ends at `ctx.review`: code after it does not run, and the session's result is the program's result.
	 */
	run: (ctx: PracticeScriptContext) => Promise<Candidate | SessionCandidate>;
}

export function isPracticeScriptDefinition(value: unknown): value is PracticeScriptDefinition {
	return isJsonObject(value) && typeof value.run === "function" && isJsonObject(value.meta);
}

export function definePracticeScript(
	definition: PracticeScriptDefinition,
): PracticeScriptDefinition {
	return definition;
}

/** A read or review that the host refused or could not finish. It is never a statement about the work. */
export class UnratedError extends Error {
	readonly reason: UnratedReason;

	constructor(reason: UnratedReason, message?: string) {
		super(message ?? `not rated: ${reason}`);
		this.name = "UnratedError";
		this.reason = reason;
	}
}

const captureFailureMessages: string[] = [];

/**
 * A failure of a kit to trust what the capture holds. The host counts it as a failed read of this
 * program, so that code which catches it decides nothing from it.
 */
export function captureFailure(reason: UnratedReason, message: string): UnratedError {
	captureFailureMessages.push(message);
	return new UnratedError(reason, message);
}

export const captureFailures = (): readonly string[] => captureFailureMessages;

/**
 * The text of a captured record parsed as JSON, or null when the record was not captured.
 * Malformed JSON and failed reads throw `UnratedError("unavailable")`.
 */
export async function readRecordJson(
	evidence: Pick<Evidence, "record">,
	name: string,
): Promise<unknown> {
	const record = await evidence.record(name);
	if (record.status === "ABSENT") {
		return null;
	}
	try {
		return JSON.parse(record.text);
	} catch {
		throw captureFailure("unavailable", `${name} is not valid JSON`);
	}
}

export function reasonOf(error: unknown): UnratedReason {
	if (error instanceof UnratedError) {
		return error.reason;
	}
	if (error instanceof Error && (error.name === "AbortError" || error.name === "TimeoutError")) {
		return "deadline";
	}
	return "error";
}

export { z } from "zod";
