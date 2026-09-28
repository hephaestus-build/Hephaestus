import type { QueryClient } from "@tanstack/react-query";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, describe, expect, it, vi } from "vitest";

import {
	getPracticeReviewOverviewQueryKey,
	listPracticeReviewObservationsQueryKey,
	listPracticeReviewsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import {
	awaitingApprovalFeedback,
	reviewFeedbackDetail,
} from "@/components/admin/practice-reviews/fixtures";
import { reviewHandlers } from "@/components/admin/practice-reviews/story-mock-server";
import { server } from "@/mocks/server";
import { deferred, sleep } from "@/test/async";
import { levelsOpenedBy } from "@/test/detail-stack";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter, testQueryClient } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const FEEDBACK_ID = reviewFeedbackDetail.id;

/** The level opened by its URL over the list it belongs to, as a shared link opens it. */
const FEEDBACK_LEVEL = `/w/acme/admin/practices/reviews/feedback?detail=feedback:${FEEDBACK_ID}`;

function proposalWire(deliveryState: "AWAITING_APPROVAL" | "PREPARED") {
	const body = "Please keep the cache scoped to one workspace so membership changes cannot leak.";
	return {
		...reviewFeedbackDetail,
		deliveryState,
		body,
		proposedPlacements:
			reviewFeedbackDetail.proposedPlacements.length === 0
				? [{ type: "SUMMARY" as const, body }]
				: reviewFeedbackDetail.proposedPlacements.map((placement) =>
						placement.type === "SUMMARY" ? { ...placement, body } : placement,
					),
		createdAt: reviewFeedbackDetail.createdAt.toISOString(),
		observations: reviewFeedbackDetail.observations.map((observation) => ({
			...observation,
			observedAt: observation.observedAt.toISOString(),
		})),
	};
}

/**
 * Cached reads that count feedback by delivery state, under filters the level cannot know and the
 * page under it does not mount, so each stays in the cache for the assertion to read.
 */
function seedCountingReads(queryClient: QueryClient, workspaceSlug: string) {
	const path = { workspaceSlug };
	const keys = [
		getPracticeReviewOverviewQueryKey({
			path,
			query: { from: new Date("2026-01-01T00:00:00Z"), zone: "UTC" },
		}),
		listPracticeReviewsQueryKey({ path, query: { status: ["COMPLETED"] } }),
		listPracticeReviewObservationsQueryKey({ path, query: { agentJobId: "cached-review" } }),
	];
	for (const key of keys) {
		queryClient.setQueryData(key, {});
	}
	return keys;
}

/**
 * The approval queue's steps once they read `position`. Read within them: the overview under the
 * level has figures that read "1 of 3" too.
 */
async function queueSteps(position: string) {
	return waitFor(() => {
		const steps = within(screen.getByRole("navigation", { name: "Feedback awaiting approval" }));
		steps.getByText(position);
		return steps;
	}, ROUTE_RENDER_WAIT);
}

describe("feedback approval level", () => {
	it("shows the exact proposal, approves it, and refreshes this workspace's counts of it", async () => {
		let deliveryState: "AWAITING_APPROVAL" | "PREPARED" = "AWAITING_APPROVAL";
		let requestBody: unknown;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", () =>
				HttpResponse.json(proposalWire(deliveryState)),
			),
			http.put(
				"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/approval",
				async ({ request }) => {
					requestBody = await request.json();
					deliveryState = "PREPARED";
					return HttpResponse.json({ feedbackId: FEEDBACK_ID, decision: "APPROVED" });
				},
			),
			...reviewHandlers(),
		);

		const queryClient = testQueryClient();
		const ours = seedCountingReads(queryClient, "acme");
		const theirs = seedCountingReads(queryClient, "other");
		renderRouteAtWithRouter(FEEDBACK_LEVEL, queryClient);

		await screen.findByRole("heading", { name: /Feedback for/u }, ROUTE_RENDER_WAIT);
		expect(screen.getByText(/Please keep the cache scoped/u)).not.toBeNull();
		const [firstObservation] = reviewFeedbackDetail.observations;
		assert(firstObservation);
		expect(screen.getByRole("link", { name: firstObservation.summary })).not.toBeNull();

		await userEvent.click(screen.getByRole("button", { name: "Approve for delivery" }));
		await screen.findByText("Feedback approved. Delivery is being checked.");
		expect(requestBody).toStrictEqual({ decision: "APPROVED" });
		// The level reads the decision back rather than assuming it: once prepared, there is nothing
		// left to approve.
		await waitFor(() =>
			expect(screen.queryByRole("button", { name: "Approve for delivery" })).toBeNull(),
		);
		// Every count of it moved: the overview's "awaiting approval", a review's tally, an
		// observation's feedback — in this workspace only.
		const invalidated = (keys: typeof ours) =>
			keys.map((key) => queryClient.getQueryState(key)?.isInvalidated);
		expect(invalidated(ours)).toStrictEqual([true, true, true]);
		expect(invalidated(theirs)).toStrictEqual([false, false, false]);
	});

	it("sends the selected rejection category and reviewer context", async () => {
		let requestBody: unknown;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", () =>
				HttpResponse.json(proposalWire("AWAITING_APPROVAL")),
			),
			http.put(
				"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/approval",
				async ({ request }) => {
					requestBody = await request.json();
					return HttpResponse.json({ feedbackId: FEEDBACK_ID, decision: "REJECTED" });
				},
			),
			...reviewHandlers(),
		);

		renderRouteAtWithRouter(FEEDBACK_LEVEL);
		await screen.findByRole("heading", { name: /Feedback for/u }, ROUTE_RENDER_WAIT);
		await userEvent.click(screen.getByRole("button", { name: "Reject feedback" }));
		const dialog = await screen.findByRole("dialog", { name: "Reject this feedback" });
		await userEvent.click(within(dialog).getByText("Missing important context"));
		await userEvent.type(
			within(dialog).getByLabelText("Note"),
			"The fallback path was not considered.",
		);
		await userEvent.click(within(dialog).getByRole("button", { name: "Reject feedback" }));

		await screen.findByText("Feedback rejected");
		expect(requestBody).toStrictEqual({
			decision: "REJECTED",
			rejectionReason: "MISSING_CONTEXT",
			rejectionNote: "The fallback path was not considered.",
		});
	});

	it("reloads the winning decision after a concurrent approval conflict", async () => {
		let deliveryState: "AWAITING_APPROVAL" | "PREPARED" = "AWAITING_APPROVAL";
		let detailReads = 0;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", () => {
				detailReads += 1;
				return HttpResponse.json(proposalWire(deliveryState));
			}),
			http.put(
				"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/approval",
				() => {
					deliveryState = "PREPARED";
					return HttpResponse.json(
						{ status: 409, title: "This review was already decided" },
						{ status: 409 },
					);
				},
			),
			...reviewHandlers(),
		);

		renderRouteAtWithRouter(FEEDBACK_LEVEL);
		await screen.findByRole("button", { name: "Approve for delivery" });
		fireEvent.click(screen.getByRole("button", { name: "Approve for delivery" }));

		await waitFor(() =>
			expect(screen.queryByRole("button", { name: "Approve for delivery" })).toBeNull(),
		);
		expect(detailReads).toBeGreaterThan(1);
	});

	/**
	 * The approval queue is worked oldest first, one level deep: a decision swaps the level for the
	 * next proposal — the one it showed as next when the reader decided, or else the oldest still
	 * waiting — over the same history entry, and closes it once nothing else waits. Only a level
	 * opened as the queue walks it.
	 */
	describe("the approval queue", () => {
		const queue = [...awaitingApprovalFeedback].reverse();
		const [oldest, second, newest] = queue;
		assert(oldest && second && newest, "The fixtures hold three pieces of feedback to approve");

		const APPROVAL = "*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/approval";

		/**
		 * Serves the queue as the server would: a decided proposal leaves it. A `refusal` answers every
		 * decision with a conflict — one that says someone else decided first, and so has taken the
		 * proposal out of the queue, or one that leaves it waiting.
		 */
		function serveQueue(
			approved: string[],
			refusal?: { title: string; decidedElsewhere: boolean },
		) {
			const rows = awaitingApprovalFeedback.map((item) =>
				approved.includes(item.id) ? { ...item, deliveryState: "PREPARED" as const } : item,
			);
			server.use(
				http.get("*/workspaces/:workspaceSlug/members/me", () =>
					HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
				),
				http.get(
					"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId",
					({ params }) => {
						const id = String(params.feedbackId);
						return HttpResponse.json({
							...proposalWire(approved.includes(id) ? "PREPARED" : "AWAITING_APPROVAL"),
							id,
						});
					},
				),
				http.put(APPROVAL, ({ params }) => {
					const conflict = () =>
						HttpResponse.json({ status: 409, title: refusal?.title }, { status: 409 });
					if (refusal?.decidedElsewhere === false) {
						return conflict();
					}
					const id = String(params.feedbackId);
					approved.push(id);
					const index = rows.findIndex((item) => item.id === id);
					const row = rows[index];
					if (row) {
						rows[index] = { ...row, deliveryState: "PREPARED" };
					}
					return refusal ? conflict() : HttpResponse.json({ feedbackId: id, decision: "APPROVED" });
				}),
				...reviewHandlers({ feedback: rows }),
			);
		}

		it("approves and swaps the level for the next proposal", async () => {
			const approved: string[] = [];
			serveQueue(approved);
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${oldest.id}&queue=approvals`,
			);

			await queueSteps("1 of 3");
			const { length: entries } = router.history;
			await userEvent.click(screen.getByRole("button", { name: "Approve and next" }));

			await waitFor(
				() => expect(router.state.location.search.detail).toStrictEqual([`feedback:${second.id}`]),
				ROUTE_RENDER_WAIT,
			);
			expect(approved).toStrictEqual([oldest.id]);
			// In place of the level just decided, not over it, and not as another step back.
			expect(router.history).toHaveLength(entries);
			await waitFor(() => expect(screen.getAllByRole("dialog")).toHaveLength(1));
		});

		/**
		 * Stepping past proposals leaves them waiting: deciding the last one goes back to the oldest
		 * skipped, not out of the level.
		 */
		it("returns to the proposals skipped past after deciding the last", async () => {
			const approved: string[] = [];
			serveQueue(approved);
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${oldest.id}&queue=approvals`,
			);

			const atOldest = await queueSteps("1 of 3");
			await userEvent.click(atOldest.getByRole("link", { name: "Next" }));
			const atSecond = await queueSteps("2 of 3");
			await userEvent.click(atSecond.getByRole("link", { name: "Next" }));
			await queueSteps("3 of 3");
			await userEvent.click(screen.getByRole("button", { name: "Approve and next" }));

			await waitFor(
				() => expect(router.state.location.search.detail).toStrictEqual([`feedback:${oldest.id}`]),
				ROUTE_RENDER_WAIT,
			);
			expect(approved).toStrictEqual([newest.id]);
			expect(router.state.location.search).toMatchObject({ queue: "approvals" });
			await queueSteps("1 of 2");
		});

		it("approves the only proposal left and closes the level", async () => {
			const approved = [oldest.id, second.id];
			serveQueue(approved);
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${newest.id}&queue=approvals`,
			);

			await userEvent.click(
				await screen.findByRole("button", { name: "Approve and close" }, ROUTE_RENDER_WAIT),
			);

			await waitFor(
				() => expect(router.state.location.search).not.toHaveProperty("detail"),
				ROUTE_RENDER_WAIT,
			);
			expect(approved).toStrictEqual([oldest.id, second.id, newest.id]);
			// The queue closes with its level, so the next feedback opened from a list is not one.
			expect(router.state.location.search).not.toHaveProperty("queue");
		});

		/**
		 * Someone else deciding first is a refusal that still moved the queue on: the level follows it
		 * rather than leaving the admin on a proposal with nothing left to decide.
		 */
		it("moves on after a conflict from a decision taken elsewhere", async () => {
			const approved: string[] = [];
			serveQueue(approved, {
				title: "This proposal has already been decided",
				decidedElsewhere: true,
			});
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${oldest.id}&queue=approvals`,
			);

			await queueSteps("1 of 3");
			await userEvent.click(screen.getByRole("button", { name: "Approve and next" }));

			await screen.findByText("This proposal has already been decided");
			await waitFor(
				() => expect(router.state.location.search.detail).toStrictEqual([`feedback:${second.id}`]),
				ROUTE_RENDER_WAIT,
			);
		});

		/** A conflict that leaves the proposal waiting — sending paused — keeps the admin on it. */
		it("stays on a proposal a conflict left waiting", async () => {
			serveQueue([], {
				title: "Sending is paused for this workspace, so approving would not send anything",
				decidedElsewhere: false,
			});
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${oldest.id}&queue=approvals`,
			);

			await queueSteps("1 of 3");
			await userEvent.click(screen.getByRole("button", { name: "Approve and next" }));

			await screen.findByText(/Sending is paused/u);
			// Enabled again once the decision settles, which is when a move on would have run.
			await waitFor(() =>
				expect(
					screen.getByRole("button", { name: "Approve and next" }).hasAttribute("disabled"),
				).toBe(false),
			);
			expect(router.state.location.search.detail).toStrictEqual([`feedback:${oldest.id}`]);
			await queueSteps("1 of 3");
		});

		/**
		 * The admin is not held to a decision in flight: dismissing the level while it is on its way
		 * leaves the level dismissed once it lands, and the page under it still opens the next.
		 */
		it("stays dismissed when dismissed while a decision is in flight", async () => {
			const approved: string[] = [];
			serveQueue(approved);
			const decision = deferred();
			server.use(
				// Held, then answered by the queue's own handler.
				http.put(APPROVAL, async () => {
					await decision.promise;
				}),
			);
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews/feedback?detail=feedback:${oldest.id}&queue=approvals`,
			);

			await queueSteps("1 of 3");
			await userEvent.click(screen.getByRole("button", { name: "Approve and next" }));
			fireEvent.keyDown(document.body, { key: "Escape" });
			await waitFor(
				() => expect(router.state.location.search).not.toHaveProperty("detail"),
				ROUTE_RENDER_WAIT,
			);
			decision.resolve();

			await waitFor(() => expect(approved).toStrictEqual([oldest.id]));
			await sleep(100);
			expect(router.state.location.search).not.toHaveProperty("detail");
			await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

			const row = await waitFor(() => {
				const found = screen
					.getAllByRole("link")
					.find((link) => String(levelsOpenedBy(link)) === `feedback:${second.id}`);
				assert(found, "The list shows the feedback as a row");
				return found;
			}, ROUTE_RENDER_WAIT);
			await userEvent.click(row);
			await screen.findByRole("dialog", { name: /^Feedback for/u }, ROUTE_RENDER_WAIT);
			expect(router.state.location.search.detail).toStrictEqual([`feedback:${second.id}`]);
		});

		/** A record opened over the queue while a decision is in flight is still there once it lands. */
		it("keeps a record opened over the queue while a decision is in flight", async () => {
			const [source] = reviewFeedbackDetail.observations;
			assert(source);
			const approved: string[] = [];
			serveQueue(approved);
			const decision = deferred();
			server.use(
				http.put(APPROVAL, async () => {
					await decision.promise;
				}),
			);
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${oldest.id}&queue=approvals`,
			);

			await queueSteps("1 of 3");
			await userEvent.click(screen.getByRole("button", { name: "Approve and next" }));
			const feedbackLevel = screen.getByRole("dialog", { name: /^Feedback for/u });
			await userEvent.click(within(feedbackLevel).getByRole("link", { name: source.summary }));
			const opened = [`feedback:${oldest.id}`, `observation:${source.observationId}`];
			await waitFor(
				() => expect(router.state.location.search.detail).toStrictEqual(opened),
				ROUTE_RENDER_WAIT,
			);
			decision.resolve();

			await screen.findByText("Feedback approved. Delivery is being checked.");
			// Once decided, the level reads it back as prepared and drops its footer; the move on would
			// run right after.
			await waitFor(() =>
				expect(screen.queryByRole("button", { name: "Approve and next", hidden: true })).toBeNull(),
			);
			await sleep(100);
			expect(router.state.location.search.detail).toStrictEqual(opened);
			screen.getByRole("dialog", { name: source.summary });
		});

		/** A decision taken before the queue has been read still moves on once it has. */
		it("moves on from a decision taken before the queue was read", async () => {
			const approved: string[] = [];
			serveQueue(approved);
			const queueRead = deferred();
			server.use(
				// Held, then answered by the queue's own handler: a resolver that returns nothing falls
				// through to the next one that matches.
				http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", async () => {
					await queueRead.promise;
				}),
			);
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${oldest.id}&queue=approvals`,
			);

			await userEvent.click(
				await screen.findByRole("button", { name: "Approve for delivery" }, ROUTE_RENDER_WAIT),
			);
			await waitFor(() => expect(approved).toStrictEqual([oldest.id]));
			queueRead.resolve();

			await waitFor(
				() => expect(router.state.location.search.detail).toStrictEqual([`feedback:${second.id}`]),
				ROUTE_RENDER_WAIT,
			);
			expect(router.state.location.search).toMatchObject({ queue: "approvals" });
		});

		/**
		 * The last of the page the queue was read in is not the last in the queue: a decision there
		 * moves on to the oldest still waiting once the queue is read again, not out of the level.
		 */
		it("moves past the page the queue was read in", async () => {
			const approved: string[] = [];
			serveQueue(approved);
			server.use(
				http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", () => {
					const waiting = queue.filter((item) => !approved.includes(item.id));
					return HttpResponse.json({
						content: waiting,
						// One more waits beyond this page.
						page: { number: 0, size: 100, totalElements: waiting.length + 1, totalPages: 2 },
					});
				}),
			);
			const { router } = renderRouteAtWithRouter(
				`/w/acme/admin/practices/reviews?detail=feedback:${newest.id}&queue=approvals`,
			);

			await queueSteps("3 of 4");
			await userEvent.click(screen.getByRole("button", { name: "Approve and next" }));

			await waitFor(
				() => expect(router.state.location.search.detail).toStrictEqual([`feedback:${oldest.id}`]),
				ROUTE_RENDER_WAIT,
			);
			expect(approved).toStrictEqual([newest.id]);
			expect(router.state.location.search).toMatchObject({ queue: "approvals" });
		});

		it("walks the queue when it is opened from Needs you", async () => {
			serveQueue([]);
			const { router } = renderRouteAtWithRouter("/w/acme/admin/practices/reviews");

			await userEvent.click(
				await screen.findByRole("link", { name: "Review 3 pieces of feedback" }, ROUTE_RENDER_WAIT),
			);

			// Within the level: the overview under it has figures of its own that read "1 of 3".
			const level = await screen.findByRole("dialog", {}, ROUTE_RENDER_WAIT);
			await within(level).findByText("1 of 3", undefined, ROUTE_RENDER_WAIT);
			within(level).getByRole("button", { name: "Approve and next" });
			expect(router.state.location.search).toMatchObject({
				detail: [`feedback:${oldest.id}`],
				queue: "approvals",
			});
		});

		/** The same feedback opened from a row of the Feedback list is that one piece, alone. */
		it("does not walk the queue when the feedback is opened from the Feedback list", async () => {
			serveQueue([]);
			const { router } = renderRouteAtWithRouter("/w/acme/admin/practices/reviews/feedback");

			const row = await waitFor(() => {
				const found = screen
					.getAllByRole("link")
					.find((link) => String(levelsOpenedBy(link)) === `feedback:${second.id}`);
				assert(found, "The list shows the feedback as a row");
				return found;
			}, ROUTE_RENDER_WAIT);
			await userEvent.click(row);

			const level = await screen.findByRole("dialog", {}, ROUTE_RENDER_WAIT);
			await within(level).findByRole("button", { name: "Approve for delivery" }, ROUTE_RENDER_WAIT);
			expect(router.state.location.search).not.toHaveProperty("queue");
			expect(within(level).queryByText(/ of 3$/u)).toBeNull();
			expect(within(level).queryByRole("button", { name: "Approve and next" })).toBeNull();
		});
	});
});
