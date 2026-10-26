import { errorText } from "./pi-error-text.ts";
import {
	answerText,
	causeText,
	isRetryableStatus,
	isTimeoutAbort,
	retrying,
} from "./pi-runner-retry.ts";
import { OUTCOMES } from "./work/practice-scripts/lib/core/contract.ts";
import { z } from "./work/practice-scripts/lib/core/practice-script.ts";

/** Validate admission identity and outcome. */
export const admittedResultSchema = z
	.looseObject({
		resultId: z.uuid(),
		practiceSlug: z.string().min(1),
		outcome: z.enum(OUTCOMES),
		evidence: z.looseObject({ citations: z.array(z.looseObject({})) }),
		problems: z.array(
			z.looseObject({ id: z.string().min(1), citations: z.array(z.looseObject({})) }),
		),
	})
	.refine((result) => (result.outcome === "NOT_MET") === result.problems.length > 0, {
		message: "Only NOT_MET results have problems",
	});

export const admissionResponseSchema = z.looseObject({
	contractVersion: z.literal(4),
	admissionDigest: z.string().regex(/^[0-9a-f]{64}$/u),
	admitted: z.array(admittedResultSchema),
	withheld: z.array(z.object({ practiceSlug: z.string(), reason: z.string() })),
});

export function parseAdmission(value: unknown) {
	const response = admissionResponseSchema.parse(value);
	for (const withheld of response.withheld) {
		console.error(`[pi-runner] admission withheld ${withheld.practiceSlug}: ${withheld.reason}`);
	}
	return { results: response.admitted, digest: response.admissionDigest };
}
type Admission = ReturnType<typeof parseAdmission>;

// A timeout can leave verification active; retry only interrupted transport or retryable HTTP responses.
const ADMISSION_ATTEMPT_TIMEOUT_MS = 10 * 60_000;
const ADMISSION_ATTEMPTS = 4;
export class AdmissionUnreachableError extends Error {
	name = "AdmissionUnreachableError";
}
export type AdmissionFetch = (body: string, signal: AbortSignal) => Promise<Response>;
async function postAdmission(body: string, send: AdmissionFetch): Promise<unknown> {
	let response: Response;
	try {
		response = await send(body, AbortSignal.timeout(ADMISSION_ATTEMPT_TIMEOUT_MS));
	} catch (error) {
		if (isTimeoutAbort(error)) {
			// The server is still verifying; a second attempt would only queue the same work behind it.
			throw new Error(`result admission did not answer within ${ADMISSION_ATTEMPT_TIMEOUT_MS}ms`, {
				cause: error,
			});
		}
		// Node reports every transport failure as `TypeError: fetch failed`; only the cause says which
		// one it was, and a report that has just the message cannot tell a restart from a wrong URL.
		throw new AdmissionUnreachableError(
			`result admission could not be sent: ${errorText(error)}${causeText(error)}`,
		);
	}
	if (isRetryableStatus(response.status)) {
		throw new AdmissionUnreachableError(`result admission failed: HTTP ${response.status}`);
	}
	if (!response.ok) {
		// The server has decided, and it said why. Asking again puts the same question, so the run ends
		// here — with the reason in the transcript, which is the only place a reader can find it.
		throw new Error(
			`result admission was refused: HTTP ${response.status} — ${await answerText(response)}`,
		);
	}
	try {
		return await response.json();
	} catch (error) {
		// A server that dies while writing its answer ends the body mid-stream. The admission may well
		// have been recorded; asking again replays it rather than admitting it twice.
		throw new AdmissionUnreachableError(
			`result admission answer was cut short: ${errorText(error)}${causeText(error)}`,
		);
	}
}

export async function admit(request: unknown, send: AdmissionFetch): Promise<Admission> {
	const body = JSON.stringify(request);
	const response: unknown = await retrying(
		async () => postAdmission(body, send),
		(error) => error instanceof AdmissionUnreachableError,
		{ attempts: ADMISSION_ATTEMPTS },
		(attempt, error, delayMs) =>
			console.error(
				`[pi-runner] admission attempt ${attempt}/${ADMISSION_ATTEMPTS} did not arrive (${errorText(error)}); retrying in ${delayMs}ms`,
			),
	);
	return parseAdmission(response);
}
