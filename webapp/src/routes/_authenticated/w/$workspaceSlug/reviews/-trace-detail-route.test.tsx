import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { artifactTrace } from "@/components/practice-trace/story-mock-data";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

const TRACE_PATH = "*/workspaces/:workspaceSlug/practices/trace/:artifactKind/:artifactId";
const REQUEST_PATH = "*/workspaces/:workspaceSlug/practices/review-requests";
const COOLDOWN_SENTENCE = "This work was reviewed a moment ago, so nothing new was started.";

let traceReads = 0;

/**
 * The states of the page are stories; what a route test owns is the wire — that a refusal is a 200
 * and not an error, that an accepted ask re-reads the trace, and that an HTTP failure never reaches
 * the refusal alert.
 */
beforeEach(() => {
	traceReads = 0;
	server.use(
		// A plain MEMBER: this surface is deliberately not behind the admin layout's role guard.
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		// This surface exists only where practices review the work; the shared fixture has them off.
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
		http.get(TRACE_PATH, () => {
			traceReads += 1;
			return HttpResponse.json(artifactTrace);
		}),
	);
});

const clickAsk = async () =>
	await userEvent.click(
		await screen.findByRole("button", { name: "Review this now" }, ROUTE_RENDER_WAIT),
	);

describe("asking for a review by hand", () => {
	/** A refused ask is a 200 carrying the server's own sentence, not an error. */
	it("shows a refusal in the server's words rather than as a failed request", async () => {
		server.use(
			http.post(REQUEST_PATH, () =>
				HttpResponse.json({
					status: "REFUSED",
					reason: "REQUESTER_QUOTA_EXHAUSTED",
					reasonDescription:
						"You have asked for as many reviews as an hour allows; the allowance refills.",
				}),
			),
		);
		renderRouteAt("/w/acme/reviews/scm.pull_request/1423");

		await clickAsk();

		await screen.findByText("No review was started");
		await screen.findByText(
			"You have asked for as many reviews as an hour allows; the allowance refills.",
		);
		// Nothing was started, so nothing was re-read either.
		expect(traceReads).toBe(1);
	});

	/**
	 * The first attempt has to be refused: a test that only ever submits asserts that a refusal is
	 * absent from a page it was never on, which an implementation clearing nothing satisfies too.
	 */
	it("clears the refusal and re-reads the trace once an ask is accepted", async () => {
		server.use(
			http.post(
				REQUEST_PATH,
				() =>
					HttpResponse.json({
						status: "REFUSED",
						reason: "REQUEST_COOLDOWN_ACTIVE",
						reasonDescription: COOLDOWN_SENTENCE,
					}),
				{ once: true },
			),
			http.post(REQUEST_PATH, () =>
				HttpResponse.json({
					status: "SUBMITTED",
					jobId: "0f2b7c1e-9a3d-4c5b-8e1f-2d6a7b8c9d01",
				}),
			),
		);
		renderRouteAt("/w/acme/reviews/scm.pull_request/1423");

		await clickAsk();
		await screen.findByText(COOLDOWN_SENTENCE);

		await clickAsk();

		await waitFor(
			() => expect(screen.queryByText(COOLDOWN_SENTENCE)).toBeNull(),
			ROUTE_RENDER_WAIT,
		);
		// A review is now running, so the page goes back for the trace that says so.
		await waitFor(() => expect(traceReads).toBeGreaterThan(1), ROUTE_RENDER_WAIT);
		await screen.findByText("Review started");
	});

	/**
	 * A 403 is not a decision the workspace made, so it must not reach the refusal alert, which is
	 * reserved for a `REFUSED` outcome.
	 */
	it("keeps a rejected request out of the refusal alert", async () => {
		server.use(
			http.post(REQUEST_PATH, () =>
				HttpResponse.json(
					{
						status: 403,
						title: "Access denied",
						detail:
							"Only the work's author or assignees, or a workspace admin, can ask for a review of it.",
					},
					{ status: 403, headers: { "Content-Type": "application/problem+json" } },
				),
			),
		);
		renderRouteAt("/w/acme/reviews/scm.pull_request/1423");

		await clickAsk();

		await screen.findByText("Couldn't ask for a review");
		await screen.findByText(
			"Only the work's author or assignees, or a workspace admin, can ask for a review of it.",
		);
		expect(screen.queryByText("No review was started")).toBeNull();
	});
});
