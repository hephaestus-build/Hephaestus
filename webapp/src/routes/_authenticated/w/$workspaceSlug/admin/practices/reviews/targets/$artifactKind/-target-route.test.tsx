import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
	reviewArtifact,
	reviewFeedback,
	reviewObservations,
	workspacePractices,
} from "@/components/admin/practice-reviews/story-mock-data";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

const URL_PATH = "/w/acme/admin/practices/reviews/targets/pull-request/42";

const forArtifact = <T extends { artifact?: { id: string } }>(rows: T[]) =>
	rows.filter((row) => row.artifact?.id === reviewArtifact.reviewedWork.id);

/** The work as every row names it, and as the page links to it: the repository and the provider's own label. */
const WORK_LINK = /ls1intum\/Hephaestus · #1423/;

const pageOf = <T,>(rows: T[]) => ({
	content: rows.slice(0, 5),
	page: { number: 0, size: 5, totalElements: rows.length, totalPages: 1 },
});

beforeEach(() => {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		// The page also joins each observation's practice slug to the record its hover card shows.
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json(workspacePractices)),
	);
});

describe("reviewed work route", () => {
	/**
	 * The wire contract the page cannot state for itself, because it takes its rows as props: both
	 * endpoints are asked for *this* work, and for exactly the number of rows the section previews —
	 * a page that asked for the default page size would silently claim a "see all" count it had
	 * already fetched.
	 */
	it("asks both endpoints for this work's preview page", async () => {
		const asked: URL[] = [];
		const record =
			(rows: unknown[]) =>
			({ request }: { request: Request }) => {
				asked.push(new URL(request.url));
				return HttpResponse.json(pageOf(rows));
			};
		server.use(
			http.get(
				"*/workspaces/:workspaceSlug/practices/reviews/feedback",
				record(forArtifact(reviewFeedback)),
			),
			http.get(
				"*/workspaces/:workspaceSlug/practices/reviews/observations",
				record(forArtifact(reviewObservations)),
			),
		);

		renderRouteAt(URL_PATH);

		await screen.findByRole("link", { name: WORK_LINK }, ROUTE_RENDER_WAIT);
		expect(asked).toHaveLength(2);
		for (const url of asked) {
			expect(url.searchParams.get("artifactKind")).toBe("scm.pull_request");
			expect(url.searchParams.get("artifactId")).toBe("42");
			expect(url.searchParams.get("size")).toBe("5");
		}
	});

	/** Two reads, two fates: a failed observations call must not empty the feedback section. */
	it("keeps the section that answered when the other fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", () =>
				HttpResponse.json(pageOf(forArtifact(reviewFeedback))),
			),
			http.get(
				"*/workspaces/:workspaceSlug/practices/reviews/observations",
				() => new HttpResponse(null, { status: 500 }),
			),
		);

		renderRouteAt(URL_PATH);

		await screen.findByText("Couldn't load observations", undefined, ROUTE_RENDER_WAIT);
		screen.getByRole("link", { name: WORK_LINK });
		expect(screen.queryByText("Nothing has been reviewed on this work")).toBeNull();
	});
});
