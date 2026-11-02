import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { MyProductFeedback } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the app chrome and its lazy modules.
vi.setConfig({ testTimeout: 30_000 });

const publicIssue = {
	id: "b1b1b1b1-0000-0000-0000-000000000004",
	kind: "IDEA",
	excerpt: "A dark mode for the practice profile.",
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
} satisfies Wire<MyProductFeedback>;

describe("your product feedback route", () => {
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

		await screen.findByRole("link", { name: /Public issue #1234/u }, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("button", { name: "Make private" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		expect(madePrivate).toStrictEqual([]);
		await user.click(dialog.getByRole("button", { name: "Make private" }));

		await waitFor(() => expect(madePrivate).toStrictEqual([publicIssue.id]));
		await screen.findByText("The maintainers have it as a private issue on GitHub.");
		expect(screen.queryByRole("link", { name: /Public issue #1234/u })).toBeNull();
	});
});
