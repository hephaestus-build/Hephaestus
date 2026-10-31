import { z } from "zod";

import { isRecord } from "@/lib/is-record";

/**
 * A human-readable message from a thrown request error: the generated client (with `throwOnError`)
 * throws the parsed RFC 9457 body on a non-2xx, so its `detail` and `title` are the wording worth
 * reading.
 *
 * **`message` is deliberately not among them**: a network rejection and a null-deref in our own code
 * are both a thrown `TypeError`, so only wording the server chose is ever shown.
 */
export function problemDetailOf(
	err: unknown,
	fallback = "We could not finish that. Try again. If it keeps failing, contact your instance operator.",
): string {
	if (typeof err === "string") {
		return err;
	}
	if (isRecord(err)) {
		for (const key of ["detail", "title"] as const) {
			const value = err[key];
			if (typeof value === "string" && value.trim().length > 0) {
				return value;
			}
		}
	}
	return fallback;
}

/** The kinds of problem the SPA branches on. The server's `ProblemType` is the registry. */
export type ProblemTypeSlug = "feedback-withheld" | "passkey-required" | "step-up-required";

/** The RFC 9457 `type` the server sends for a problem kind. */
export function problemType(slug: ProblemTypeSlug | "csrf-token-rejected"): string {
	return `https://docs.hephaestus.build/contributor/api-error-handling#${slug}`;
}

/** Whether a thrown request error, or a parsed response body, is a problem of this kind. */
export function isProblemType(err: unknown, slug: ProblemTypeSlug): boolean {
	return isRecord(err) && err.type === problemType(slug);
}

/**
 * The refusal that asks for a fresh sign-in, as the server states it in the RFC 9457 body. The
 * thrown value is untyped, so it is validated rather than read: a problem of another type is not a
 * challenge it can answer.
 *
 * A window it cannot phrase is dropped rather than failing the parse — the ask is still true
 * without a number, and refusing the whole challenge would leave the reader with a wall and no
 * explanation.
 */
const stepUpChallengeSchema = z.object({
	type: z.literal(problemType("step-up-required")),
	maxAgeSeconds: z.int().positive().optional().catch(undefined),
});

export type StepUpChallenge = z.infer<typeof stepUpChallengeSchema>;

/** The challenge a refusal carries, or `undefined` when the refusal is any other kind. */
export function stepUpChallengeOf(err: unknown): StepUpChallenge | undefined {
	const parsed = stepUpChallengeSchema.safeParse(err);
	return parsed.success ? parsed.data : undefined;
}

/**
 * Whether asking again can answer differently: no HTTP answer at all, too many requests, or a
 * server failure. Every other status is the server's answer about this request, and a refusal
 * stays refused. The client's retries and the error guidance both read this one answer.
 */
export function problemIsRetryable(err: unknown): boolean {
	const kind = problemKindOf(problemStatusOf(err));
	return kind === "no-answer" || kind === "rate-limited" || kind === "server";
}

/**
 * `undefined` is meaningful here and must not be collapsed to a number: it means the request never
 * got an HTTP answer (offline, DNS failure, CORS, an aborted fetch), which is a different situation
 * from any status the server could have returned.
 */
export function problemStatusOf(err: unknown): number | undefined {
	if (!isRecord(err)) {
		return undefined;
	}
	const direct = err.status;
	if (typeof direct === "number" && Number.isInteger(direct)) {
		return direct;
	}
	const { response } = err;
	if (isRecord(response)) {
		const nested = response.status;
		if (typeof nested === "number" && Number.isInteger(nested)) {
			return nested;
		}
	}
	return undefined;
}

/** What a failed request means for the reader, by its status. */
export type ProblemKind =
	| "no-answer"
	| "signed-out"
	| "no-access"
	| "not-found"
	| "conflict"
	| "rate-limited"
	| "server"
	| "rejected";

export function problemKindOf(status: number | undefined): ProblemKind {
	if (status === undefined) {
		return "no-answer";
	}
	if (status === 401) {
		return "signed-out";
	}
	if (status === 403) {
		return "no-access";
	}
	if (status === 404) {
		return "not-found";
	}
	if (status === 409) {
		return "conflict";
	}
	if (status === 429) {
		return "rate-limited";
	}
	return status >= 500 ? "server" : "rejected";
}

export interface ProblemCopy {
	/** What happened, used when the server gave no wording of its own. */
	cause?: string;
	/** What the reader does next. */
	next: string;
	/** Re-words a "We could not load X" title when the failure is not a load failure. */
	retitle?: (what: string) => string;
}

/** The one home for these words, whether a region's alert or a whole page shows the failure. */
export const PROBLEM_COPY = {
	"no-answer": { next: "Check your connection, then try again." },
	"signed-out": { next: "Your session has expired. Sign in again to continue." },
	"no-access": {
		next: "Ask a workspace admin or instance admin for access.",
		retitle: (what: string) => `You do not have access to ${what}`,
	},
	"not-found": {
		next: "It may have been deleted or moved. Go back to continue.",
		retitle: (what: string) => `We could not find ${what}`,
	},
	conflict: {
		next: "Something else changed this first. Reload the page to see the current state.",
	},
	"rate-limited": { cause: "Too many requests.", next: "Wait a moment, then try again." },
	server: { cause: "The server had a problem.", next: "Try again in a moment." },
	rejected: { cause: "The request was not accepted.", next: "Reload the page and try again." },
} satisfies Record<ProblemKind, ProblemCopy>;
