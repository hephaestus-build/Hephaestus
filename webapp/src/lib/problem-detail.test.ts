import { describe, expect, it } from "vitest";

import { problemDetailOf, problemStatusOf, stepUpChallengeOf } from "./problem-detail";

describe("problemDetailOf", () => {
	it("prefers RFC 9457 `detail` over everything else", () => {
		expect(
			problemDetailOf({
				type: "about:blank",
				title: "Bad Request",
				status: 400,
				detail: "Issuer is not reachable",
				error: "legacy error",
				message: "some message",
			}),
		).toBe("Issuer is not reachable");
	});

	it("falls back to `title` when `detail` is absent", () => {
		expect(problemDetailOf({ title: "Bad Request", error: "legacy", message: "msg" })).toBe(
			"Bad Request",
		);
	});

	it("falls back to the legacy `{ error }` shape when title/detail are absent", () => {
		expect(problemDetailOf({ error: "Could not validate", message: "msg" })).toBe(
			"Could not validate",
		);
	});

	it("returns a plain string error as-is", () => {
		expect(problemDetailOf("network down")).toBe("network down");
	});

	it("never shows a thrown Error's own message, however it was produced", () => {
		expect(
			problemDetailOf(
				new TypeError("Cannot read properties of undefined (reading 'id')"),
				"Could not save the model",
			),
		).toBe("Could not save the model");
		expect(problemDetailOf(new TypeError("Failed to fetch"), "Could not save the model")).toBe(
			"Could not save the model",
		);
		expect(problemDetailOf({ message: "boom" })).toBe(
			"An unexpected error occurred. Please try again.",
		);
	});

	it("still prefers `detail` over a `message` sitting beside it", () => {
		expect(problemDetailOf({ detail: "Model still bound to an agent", message: "boom" })).toBe(
			"Model still bound to an agent",
		);
	});

	it("ignores blank/whitespace-only string fields and continues the precedence chain", () => {
		expect(problemDetailOf({ detail: "   ", title: "Real Title" })).toBe("Real Title");
	});

	it("falls back to a generic message for unhandled shapes", () => {
		expect(problemDetailOf(null)).toBe("An unexpected error occurred. Please try again.");
		expect(problemDetailOf(undefined)).toBe("An unexpected error occurred. Please try again.");
		expect(problemDetailOf({ status: 500 })).toBe(
			"An unexpected error occurred. Please try again.",
		);
		expect(problemDetailOf(42)).toBe("An unexpected error occurred. Please try again.");
	});
});

// The challenge decides whether the UI asks for a sign-in at all, so an unrecognised refusal must
// never parse as one: an OAuth round trip that changes nothing is worse than the refusal.
describe("stepUpChallengeOf", () => {
	it("reads the challenge and its window off the refusal body", () => {
		expect(
			stepUpChallengeOf({ status: 403, code: "step_up_required", maxAgeSeconds: 300 }),
		).toStrictEqual({ code: "step_up_required", maxAgeSeconds: 300 });
	});

	it("keeps the challenge when the server names no window", () => {
		expect(stepUpChallengeOf({ status: 403, code: "step_up_required" })).toStrictEqual({
			code: "step_up_required",
		});
	});

	it("ignores a refusal coded as something else", () => {
		expect(stepUpChallengeOf({ status: 409, code: "last_admin" })).toBeUndefined();
		expect(stepUpChallengeOf({ status: 403, detail: "Forbidden" })).toBeUndefined();
	});

	it("drops a window it could not phrase and still asks", () => {
		for (const maxAgeSeconds of ["300", -1, 0, 12.5, Number.NaN, Number.POSITIVE_INFINITY]) {
			const challenge = stepUpChallengeOf({ code: "step_up_required", maxAgeSeconds });
			expect(challenge?.code).toBe("step_up_required");
			expect(challenge?.maxAgeSeconds).toBeUndefined();
		}
	});

	it("is safe on the shapes a failed request can throw", () => {
		expect(stepUpChallengeOf(null)).toBeUndefined();
		expect(stepUpChallengeOf(undefined)).toBeUndefined();
		expect(stepUpChallengeOf("step_up_required")).toBeUndefined();
		expect(stepUpChallengeOf(new TypeError("Failed to fetch"))).toBeUndefined();
	});
});

// `problemStatusOf` decides whether the UI offers a way out at all — a retryable 503 vs a 403 that no
// button can fix. `undefined` is meaningful (no HTTP answer) and must never be coerced to a number.
describe("problemStatusOf", () => {
	it("reads `status` from the RFC 9457 body the client throws", () => {
		expect(problemStatusOf({ type: "about:blank", status: 403, detail: "Forbidden" })).toBe(403);
	});

	it("falls back to `response.status` for shapes carrying the raw Response", () => {
		expect(problemStatusOf({ response: { status: 503 } })).toBe(503);
	});

	it("prefers the body status over the response status", () => {
		expect(problemStatusOf({ status: 409, response: { status: 200 } })).toBe(409);
	});

	it("returns undefined when the request never got an HTTP answer", () => {
		// A network failure is not a status the server chose; conflating it with one would let the UI
		// claim the server said something it never said.
		expect(problemStatusOf(new TypeError("Failed to fetch"))).toBeUndefined();
		expect(problemStatusOf(null)).toBeUndefined();
		expect(problemStatusOf(undefined)).toBeUndefined();
		expect(problemStatusOf("network down")).toBeUndefined();
	});

	it("ignores non-integer status values rather than passing them on", () => {
		expect(problemStatusOf({ status: "403" })).toBeUndefined();
		expect(problemStatusOf({ status: Number.NaN })).toBeUndefined();
		expect(problemStatusOf({ status: 403.5 })).toBeUndefined();
	});
});
