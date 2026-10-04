import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { detailRun } from "@/stories/practice-detail-story-mock-data";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole app shell and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

const RUNS_PATH =
	"*/workspaces/:workspaceSlug/practices/reviewed-work/:artifactKind/:artifactId/review-runs";
const RESPONSE_PATH = "*/workspaces/:workspaceSlug/practices/feedback/:feedbackId/response";

let reads: { artifactKind: string; artifactId: string }[] = [];

beforeEach(() => {
	reads = [];
	server.use(
		http.get(RUNS_PATH, ({ params }) => {
			reads.push({
				artifactKind: String(params.artifactKind),
				artifactId: String(params.artifactId),
			});
			return HttpResponse.json({ content: [detailRun], page: 0, size: 10, hasNext: false });
		}),
	);
});

/** The page is the address comments on the work link to; its states are stories, its wire is here. */
describe("the page a comment on the work links to", () => {
	it("reads the reader's reviews of exactly the linked work", async () => {
		renderRouteAt("/w/acme/feedback/scm.pull_request/1423");

		await screen.findByRole("heading", { name: "Your feedback on this work" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("list", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		expect(reads[0]).toStrictEqual({ artifactKind: "scm.pull_request", artifactId: "1423" });
	});

	it("writes a dispute and reads the work's reviews again", async () => {
		const written: unknown[] = [];
		server.use(
			http.put(RESPONSE_PATH, async ({ request, params }) => {
				written.push(await request.json());
				return HttpResponse.json({ feedbackId: String(params.feedbackId), resolution: "DISPUTED" });
			}),
		);
		renderRouteAt("/w/acme/feedback/scm.pull_request/1423");

		// One observation of the linked review carries feedback, so one can be disputed.
		await userEvent.click(
			await screen.findByRole("button", { name: "Disputed" }, ROUTE_RENDER_WAIT),
		);
		await userEvent.type(
			screen.getByRole("textbox", { name: "Why do you dispute this?" }),
			"The loader never ran on this path.",
		);
		await userEvent.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => {
			expect(written).toHaveLength(1);
		});
		expect(written[0]).toMatchObject({
			resolution: "DISPUTED",
			comment: "The loader never ran on this path.",
		});
		await waitFor(() => {
			expect(reads.length).toBeGreaterThan(1);
		});
	});

	it("does not ask for anything when the address names no work", async () => {
		renderRouteAt("/w/acme/feedback/scm.pull_request/not-a-number");

		await screen.findByText(/not found/iu, undefined, ROUTE_RENDER_WAIT);
		expect(reads).toHaveLength(0);
	});
});
