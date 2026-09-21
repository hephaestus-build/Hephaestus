import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ObservationDetail, PracticeGroupReviewRun } from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import { formatDay, formatShortDay } from "@/lib/dates";

import { ReviewRunTimeline } from "./ReviewRunTimeline";

const baseObservation = {
	id: "00000000-0000-0000-0000-000000000102",
	feedbackId: "00000000-0000-0000-0000-000000000103",
	feedbackUsefulness: "HELPFUL",
	feedbackResolution: "ADDRESSED",
	feedbackResponseComment: "Applied in the next revision.",
	practiceSlug: "records-decisions",
	practiceName: "Record significant decisions and the reasoning",
	summary: "The workspace trade-off is documented",
	presence: "PRESENT",
	assessment: "GOOD",
	observedAt: daysBefore(2),
	origin: "LIVE",
	claimCurrentness: "CURRENT",
	artifactId: 902,
	artifactKind: "scm.pull_request",
	evidenceRationale: "The description says why the trade-off was made, not only what it is.",
} satisfies ObservationDetail;

const run = {
	reviewId: "00000000-0000-0000-0000-000000000101",
	reviewedAt: daysBefore(2),
	reviewedWork: {
		kind: "scm.pull_request",
		id: "902",
		label: "#902",
		repositoryName: "HephaestusTest/practice-validation",
		url: "https://github.com/HephaestusTest/practice-validation/pull/902",
	},
	observations: [baseObservation],
} satisfies PracticeGroupReviewRun;

const runs = [run];

describe("ReviewRunTimeline", () => {
	it("renders the review-run boundary and its observations, open, each naming its practice", () => {
		render(<ReviewRunTimeline runs={runs} />);

		screen.getByText("#902");
		screen.getByText("The workspace trade-off is documented");
		screen.getByText("Strength shown");
		screen.getByText("Record significant decisions and the reasoning");
		// Open on arrival, with what the feed carries.
		screen.getByText("Why it was noted");
	});

	it("names the day once, on the run's own card, and on none of its rows", () => {
		const denseRun: PracticeGroupReviewRun = {
			...run,
			observations: [1, 2, 3].map((n) => ({
				...baseObservation,
				id: `00000000-0000-0000-0000-00000000020${n}`,
				summary: `Observation ${n}`,
			})),
		};
		render(<ReviewRunTimeline runs={[denseRun]} />);

		expect(screen.getAllByText(formatShortDay(run.reviewedAt))).toHaveLength(1);
		expect(screen.queryByText(formatDay(baseObservation.observedAt))).toBeNull();
	});

	it("opens the newest run's first row alone when the feed is one practice's own", () => {
		const earlier: PracticeGroupReviewRun = {
			...run,
			reviewId: "00000000-0000-0000-0000-000000000301",
			reviewedAt: daysBefore(5),
			observations: [
				{
					...baseObservation,
					id: "00000000-0000-0000-0000-000000000302",
					summary: "An earlier run said the same",
				},
			],
		};
		render(<ReviewRunTimeline runs={[run, earlier]} initiallyOpen="newest" />);

		const newest = screen.getByRole("button", { name: new RegExp(baseObservation.summary) });
		const older = screen.getByRole("button", { name: /An earlier run said the same/ });
		expect(newest.getAttribute("aria-expanded")).toBe("true");
		expect(older.getAttribute("aria-expanded")).toBe("false");
		expect(screen.getAllByText("Why it was noted")).toHaveLength(1);

		// A closed row opens on its own press, leaving the open one alone.
		fireEvent.click(older);
		expect(older.getAttribute("aria-expanded")).toBe("true");
		expect(newest.getAttribute("aria-expanded")).toBe("true");
	});

	it("leaves the practice name off every row on the practice's own level", () => {
		render(<ReviewRunTimeline runs={runs} showPracticeName={false} />);

		screen.getByText("The workspace trade-off is documented");
		expect(screen.queryByText("Record significant decisions and the reasoning")).toBeNull();
	});

	it("sends the whole response when one part of it changes", () => {
		const onRespond = vi.fn();
		render(<ReviewRunTimeline runs={runs} observations={{ onRespond }} />);

		// The observation arrives marked addressed, so pressing it again withdraws that answer and
		// the comment it carried. The usefulness still travels: the endpoint replaces, so omitting
		// it would clear it.
		fireEvent.click(screen.getByRole("button", { name: "Addressed" }));
		expect(onRespond).toHaveBeenCalledWith(baseObservation, {
			usefulness: "HELPFUL",
			resolution: undefined,
			comment: undefined,
		});
	});

	it("sends a new answer and its comment without disturbing the usefulness", () => {
		const onRespond = vi.fn();
		render(<ReviewRunTimeline runs={runs} observations={{ onRespond }} />);

		fireEvent.click(screen.getByRole("button", { name: "Not applicable" }));
		fireEvent.change(screen.getByRole("textbox", { name: "Anything to add?" }), {
			target: { value: "The trade-off was settled in the issue." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Send" }));
		expect(onRespond).toHaveBeenCalledWith(baseObservation, {
			usefulness: "HELPFUL",
			resolution: "NOT_APPLICABLE",
			comment: "The trade-off was settled in the issue.",
		});
	});

	it("keeps a dense review run compact until requested", () => {
		const denseRun: PracticeGroupReviewRun = {
			...run,
			observations: Array.from({ length: 5 }, (_, index) => ({
				...baseObservation,
				id: `00000000-0000-0000-0000-00000000010${index}`,
				practiceSlug: `practice-${index}`,
				practiceName: `Practice ${index + 1}`,
				summary: `Observation ${index + 1}`,
			})),
		};

		render(<ReviewRunTimeline runs={[denseRun]} />);
		expect(screen.queryByText("Observation 4")).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Show more (2)" }));
		screen.getByText("Observation 4");
		screen.getByText("Observation 5");
	});
});
