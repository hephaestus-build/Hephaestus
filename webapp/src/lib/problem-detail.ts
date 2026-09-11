import { z } from "zod";

import { isRecord } from "@/lib/is-record";

/**
 * A human-readable message from a thrown request error: the generated client (with `throwOnError`)
 * throws the parsed response body on a non-2xx, so RFC 9457's `detail` and `title` and the
 * controllers' legacy `{ error }` are the shapes worth reading.
 *
 * **`message` is deliberately not among them**: a network rejection and a null-deref in our own code
 * are both a thrown `TypeError`, so only wording the server chose is ever shown.
 */
export function problemDetailOf(
	err: unknown,
	fallback = "An unexpected error occurred. Please try again.",
): string {
	if (typeof err === "string") {
		return err;
	}
	if (isRecord(err)) {
		for (const key of ["detail", "title", "error"] as const) {
			const value = err[key];
			if (typeof value === "string" && value.trim().length > 0) {
				return value;
			}
		}
	}
	return fallback;
}

/**
 * The refusal that asks for a fresh sign-in, as the server states it in the RFC 9457 body. The
 * thrown value is untyped, so it is validated rather than read: a `code` the SPA does not know is
 * not a challenge it can answer.
 *
 * A window it cannot phrase is dropped rather than failing the parse — the ask is still true
 * without a number, and refusing the whole challenge would leave the reader with a wall and no
 * explanation.
 */
const stepUpChallengeSchema = z.object({
	code: z.literal("step_up_required"),
	maxAgeSeconds: z.int().positive().optional().catch(undefined),
});

export type StepUpChallenge = z.infer<typeof stepUpChallengeSchema>;

/** The challenge a refusal carries, or `undefined` when the refusal is any other kind. */
export function stepUpChallengeOf(err: unknown): StepUpChallenge | undefined {
	const parsed = stepUpChallengeSchema.safeParse(err);
	return parsed.success ? parsed.data : undefined;
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
	const response = err.response;
	if (isRecord(response)) {
		const nested = response.status;
		if (typeof nested === "number" && Number.isInteger(nested)) {
			return nested;
		}
	}
	return undefined;
}
