import { describe, expect, it } from "vitest";

import type { ReviewQueue } from "@/api/types.gen";

import { reviewItem } from "./fixtures";
import {
	oldestWaitSeconds,
	queueActive,
	queueItemState,
	queueTotal,
	waitsLong,
} from "./review-queue";

const AS_OF = new Date("2026-10-07T12:00:00Z");

const queue = (patch: Partial<ReviewQueue> = {}): ReviewQueue => ({
	asOf: AS_OF,
	running: 0,
	activeWorkers: 0,
	waiting: 0,
	heldForBudget: 0,
	oldestWaitingSince: null,
	head: [],
	finishedLastHour: 0,
	finishedLastDay: 0,
	finishedPerHourLastDay: 0,
	...patch,
});

describe("review queue", () => {
	it("counts a budget hold in the queue but polls only for what waits or runs", () => {
		expect(queueTotal(queue({ running: 2, waiting: 5, heldForBudget: 1 }))).toBe(8);
		expect(queueActive(queue())).toBe(false);
		// A budget hold alone waits for the monthly cap: nothing moves, so nothing is re-read.
		expect(queueActive(queue({ heldForBudget: 1 }))).toBe(false);
		expect(queueActive(queue({ waiting: 1 }))).toBe(true);
	});

	it("flags a wait from half an hour, the overview's long-wait mark", () => {
		const since = (minutes: number) => new Date(AS_OF.getTime() - minutes * 60_000);
		expect(oldestWaitSeconds(queue())).toBeUndefined();
		expect(waitsLong(queue({ waiting: 1, oldestWaitingSince: since(29) }))).toBe(false);
		expect(waitsLong(queue({ waiting: 1, oldestWaitingSince: since(30) }))).toBe(true);
		// A wait read on a clock behind the server's is no wait, never a negative one.
		expect(oldestWaitSeconds(queue({ oldestWaitingSince: new Date(AS_OF.getTime() + 5000) }))).toBe(
			0,
		);
	});

	it("tells a hold from a wait", () => {
		const item = {
			reviewId: "r",
			jobId: "j",
			item: reviewItem,
			developer: null,
			since: AS_OF,
			progress: null,
		} as const;
		expect(queueItemState({ ...item, status: "RUNNING", heldForBudget: false })).toBe("running");
		expect(queueItemState({ ...item, status: "QUEUED", heldForBudget: false })).toBe("waiting");
		expect(queueItemState({ ...item, status: "QUEUED", heldForBudget: true })).toBe("held");
	});
});
