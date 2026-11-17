import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

import type { MyProductFeedback } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/product-feedback"), ROUTE_RENDER_WAIT.timeout);

// The page is not workspace-scoped, so the reader belongs to no workspace here.
beforeEach(() => {
	server.use(http.get("*/workspaces", () => HttpResponse.json([])));
});

const publicIssue = {
	id: "b1b1b1b1-0000-0000-0000-000000000004",
	kind: "IDEA",
	message: "A dark mode for the practice profile.",
	createdAt: "2026-09-11T09:00:00.000Z",
	state: "FILED",
	route: "PUBLIC",
	issueUrl: "https://github.com/hephaestus-build/Hephaestus/issues/1234",
	issueNumber: 1234,
	allowPublic: true,
	canKeepPrivate: false,
	canEdit: false,
	canMakePrivate: true,
	canAllowPublic: false,
	waitingForModel: false,
} satisfies Wire<MyProductFeedback>;

describe("your reports route", () => {
	it("makes a public issue private only after the confirmation", async () => {
		const madePrivate: string[] = [];
		let listed: Wire<MyProductFeedback> = publicIssue;
		const madePrivateIssue = {
			...publicIssue,
			route: "PRIVATE",
			issueUrl: undefined,
			issueNumber: undefined,
			canMakePrivate: false,
		} satisfies Wire<MyProductFeedback>;
		server.use(
			http.get("*/product-feedback/mine", () => HttpResponse.json([listed])),
			http.post("*/product-feedback/:feedbackId/make-private", ({ params }) => {
				madePrivate.push(String(params.feedbackId));
				listed = madePrivateIssue;
				return HttpResponse.json(madePrivateIssue);
			}),
		);
		renderRouteAt("/product-feedback");
		const user = userEvent.setup();

		await screen.findByRole("link", { name: /public issue #1234/u }, ROUTE_RENDER_WAIT);
		expect(screen.getByText("Public")).not.toBeNull();
		await user.click(screen.getByRole("button", { name: "Make private" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		expect(madePrivate).toStrictEqual([]);
		await user.click(dialog.getByRole("button", { name: "Make private" }));

		await waitFor(() => expect(madePrivate).toStrictEqual([publicIssue.id]));
		await screen.findByText(/Filed as a private issue$/u);
		expect(screen.queryByRole("link", { name: /public issue #1234/u })).toBeNull();
		expect(screen.getByText("Private")).not.toBeNull();
		expect(screen.queryByText("Public")).toBeNull();
	});

	it("sends Allow public for a private issue and shows the switch as the server saved it", async () => {
		const privateIssue = {
			...publicIssue,
			route: "PRIVATE",
			reason: "opted out",
			issueUrl: undefined,
			issueNumber: undefined,
			allowPublic: false,
			canMakePrivate: false,
			canAllowPublic: true,
		} satisfies Wire<MyProductFeedback>;
		const sent: unknown[] = [];
		let listed: Wire<MyProductFeedback> = privateIssue;
		server.use(
			http.get("*/product-feedback/mine", () => HttpResponse.json([listed])),
			http.put("*/product-feedback/:feedbackId/allow-public", async ({ request }) => {
				sent.push(await request.json());
				listed = { ...privateIssue, allowPublic: true };
				return HttpResponse.json(listed);
			}),
		);
		renderRouteAt("/product-feedback");
		const user = userEvent.setup();

		const allow = await screen.findByRole("switch", { name: "Allow public" }, ROUTE_RENDER_WAIT);
		expect(allow.getAttribute("aria-checked")).toBe("false");
		await user.click(allow);

		await waitFor(() => expect(sent).toStrictEqual([{ allowPublic: true }]));
		await waitFor(() =>
			expect(
				screen.getByRole("switch", { name: "Allow public" }).getAttribute("aria-checked"),
			).toBe("true"),
		);
		// It stays private until the maintainers publish it.
		expect(screen.getByText("Private")).not.toBeNull();
		expect(screen.getByText("It stays private unless a maintainer publishes it.")).not.toBeNull();
	});

	// A report that moved on must never keep showing Drafting: the list reads again until it settles.
	it("shows a drafting report on GitHub once it is filed, and then stops reading", async () => {
		const drafting = {
			...publicIssue,
			state: "DRAFTING",
			issueUrl: undefined,
			issueNumber: undefined,
			canMakePrivate: false,
			canKeepPrivate: true,
			draftingSince: "2026-09-11T09:00:00.000Z",
			draftDeadline: "2026-09-11T09:10:00.000Z",
		} satisfies Wire<MyProductFeedback>;
		let listed: Wire<MyProductFeedback> = drafting;
		let reads = 0;
		server.use(
			http.get("*/product-feedback/mine", () => {
				reads += 1;
				return HttpResponse.json([listed]);
			}),
		);
		vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
		const queryClient = renderRouteAt("/product-feedback");
		try {
			await screen.findByText("Drafting", undefined, ROUTE_RENDER_WAIT);
			await waitFor(() => expect(queryClient.isFetching()).toBe(0));
			const initialReads = reads;
			listed = publicIssue;
			await act(async () => vi.advanceTimersByTimeAsync(10_000));

			await screen.findByRole("link", { name: /public issue #1234/u });
			expect(screen.queryByText("Drafting")).toBeNull();
			expect(reads).toBe(initialReads + 1);
			await act(async () => vi.advanceTimersByTimeAsync(30_000));
			expect(reads).toBe(initialReads + 1);
		} finally {
			queryClient.clear();
			vi.useRealTimers();
		}
	});
});
