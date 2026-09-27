import { describe, expect, it } from "vitest";

import { feedbackCount, speaks, feedbackNote } from "./mentor-voice";

describe("speaks", () => {
	it("lets Heph speak only where it can talk with the person", () => {
		expect(speaks("available")).toBe(true);
		for (const access of ["checking", "unreachable", "no-access", "workspace-off"] as const) {
			expect(speaks(access)).toBe(false);
		}
	});
});

describe("feedbackNote", () => {
	it("counts the feedback the list holds, in words", () => {
		expect(feedbackNote(1)).toContain("one piece of feedback");
		expect(feedbackNote(3)).toContain("3 pieces of feedback");
	});

	it("never presents Heph as the author of the feedback, nor guesses where it came from", () => {
		for (const count of [0, 1, 2]) {
			expect(feedbackNote(count)).not.toMatch(
				/\bI (?:wrote|found|left|noticed|saw)\b|a review|reviews/iu,
			);
		}
	});

	it("never reads an empty list as work reviewed or going well", () => {
		const empty = feedbackNote(0);
		expect(empty).toContain("no feedback for you here yet");
		expect(empty).not.toMatch(
			/well|great|good job|nothing to (?:improve|fix)|all clear|reviewed/iu,
		);
	});

	it("claims nothing the list cannot say: no novelty, no unread state", () => {
		for (const count of [0, 1, 5]) {
			expect(feedbackNote(count)).not.toMatch(/\bnew\b|unread|latest|recent|today/iu);
		}
	});
});

describe("feedbackCount", () => {
	it("keeps the system's count when Heph cannot speak", () => {
		expect(feedbackCount(1)).toBe("1 piece of feedback");
		expect(feedbackCount(4)).toBe("4 pieces of feedback");
	});
});
