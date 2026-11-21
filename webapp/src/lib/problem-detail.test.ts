import { describe, expect, it } from "vitest";
// oxlint-disable-next-line import/no-relative-parent-imports -- The API description is the contract under test, and no `@/` alias reaches it.
import spec from "../../../server/openapi.yaml?raw";

import { zProblemDetail } from "@/api/zod.gen";

import {
	PROBLEM_TYPE_SLUGS,
	problemDetailOf,
	problemStatusOf,
	stepUpChallengeOf,
	problemType,
} from "./problem-detail";

describe("the generated problem contract", () => {
	it("preserves validation and request extensions at the top level", () => {
		const wire = {
			type: "about:blank",
			title: "Validation failed",
			status: 400,
			instance: "/api/workspaces",
			requestId: "0123456789abcdef0123456789abcdef",
			errors: { slug: ["Choose a different identifier."] },
		};

		expect(zProblemDetail.parse(wire)).toStrictEqual(wire);
		expect(zProblemDetail.safeParse({ ...wire, status: "400" }).success).toBe(false);
	});
});

describe("the problem types the SPA branches on", () => {
	it("are each a type that the API description lists", () => {
		const start = spec.indexOf("\n    ProblemType:\n");
		expect(start).toBeGreaterThan(-1);
		const end = spec.slice(start + 1).search(/\n {4}\S/u);
		const listed = spec
			.slice(start, start + 1 + end)
			.split("\n")
			.map((line) => /^\s*- "?(?<listed>[^"]+)"?$/u.exec(line)?.groups?.listed);
		for (const slug of PROBLEM_TYPE_SLUGS) {
			expect(listed).toContain(problemType(slug));
		}
	});
});

describe("problemDetailOf", () => {
	it("prefers RFC 9457 `detail` over everything else", () => {
		expect(
			problemDetailOf({
				type: "about:blank",
				title: "Bad Request",
				status: 400,
				detail: "Issuer is not reachable",
				message: "some message",
			}),
		).toBe("Issuer is not reachable");
	});

	it("falls back to `title` when `detail` is absent", () => {
		expect(problemDetailOf({ title: "Bad Request", message: "msg" })).toBe("Bad Request");
	});

	it("reads no member outside the RFC 9457 problem", () => {
		expect(problemDetailOf({ error: "We could not validate", message: "msg" }, "Fallback")).toBe(
			"Fallback",
		);
	});

	it("returns a plain string error as-is", () => {
		expect(problemDetailOf("network down")).toBe("network down");
	});

	it("never shows a thrown Error's own message, however it was produced", () => {
		expect(
			problemDetailOf(
				new TypeError("Cannot read properties of undefined (reading 'id')"),
				"We could not save the model",
			),
		).toBe("We could not save the model");
		expect(problemDetailOf(new TypeError("Failed to fetch"), "We could not save the model")).toBe(
			"We could not save the model",
		);
		expect(problemDetailOf({ message: "boom" })).toBe(
			"We could not finish that. Try again. If it keeps failing, contact your instance operator.",
		);
	});

	it("still prefers `detail` over a `message` sitting beside it", () => {
		expect(
			problemDetailOf({ detail: "Model still assigned to a model purpose", message: "boom" }),
		).toBe("Model still assigned to a model purpose");
	});

	it("ignores blank/whitespace-only string fields and continues the precedence chain", () => {
		expect(problemDetailOf({ detail: "   ", title: "Real Title" })).toBe("Real Title");
	});

	it("falls back to a generic message for unhandled shapes", () => {
		expect(problemDetailOf(null)).toBe(
			"We could not finish that. Try again. If it keeps failing, contact your instance operator.",
		);
		expect(problemDetailOf(undefined)).toBe(
			"We could not finish that. Try again. If it keeps failing, contact your instance operator.",
		);
		expect(problemDetailOf({ status: 500 })).toBe(
			"We could not finish that. Try again. If it keeps failing, contact your instance operator.",
		);
		expect(problemDetailOf(42)).toBe(
			"We could not finish that. Try again. If it keeps failing, contact your instance operator.",
		);
	});
});

// The challenge decides whether the UI asks for a sign-in at all, so an unrecognised refusal must
// never parse as one: an OAuth round trip that changes nothing is worse than the refusal.
describe("stepUpChallengeOf", () => {
	it("reads the challenge and its window off the refusal body", () => {
		expect(
			stepUpChallengeOf({ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 }),
		).toStrictEqual({ type: problemType("step-up-required"), maxAgeSeconds: 300 });
	});

	it("keeps the challenge when the server names no window", () => {
		expect(stepUpChallengeOf({ status: 403, type: problemType("step-up-required") })).toStrictEqual(
			{
				type: problemType("step-up-required"),
			},
		);
	});

	it("ignores a refusal coded as something else", () => {
		expect(stepUpChallengeOf({ status: 409, code: "last_admin" })).toBeUndefined();
		expect(stepUpChallengeOf({ status: 403, detail: "Forbidden" })).toBeUndefined();
	});

	it("drops a window it could not phrase and still asks", () => {
		for (const maxAgeSeconds of ["300", -1, 0, 12.5, Number.NaN, Number.POSITIVE_INFINITY]) {
			const challenge = stepUpChallengeOf({ type: problemType("step-up-required"), maxAgeSeconds });
			expect(challenge?.type).toBe(problemType("step-up-required"));
			expect(challenge?.maxAgeSeconds).toBeUndefined();
		}
	});

	it("is safe on the shapes a failed request can throw", () => {
		expect(stepUpChallengeOf(null)).toBeUndefined();
		expect(stepUpChallengeOf(undefined)).toBeUndefined();
		expect(stepUpChallengeOf(problemType("step-up-required"))).toBeUndefined();
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
