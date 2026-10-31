import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
	FeedbackReceipt,
	FeedbackRequest,
	MyProductFeedback,
	ProductFeedbackSharing,
} from "@/api/types.gen";
import { zFeedbackRequest } from "@/api/zod.gen";
import type { Wire } from "@/lib/dates";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

describe("product feedback sent from a workspace page", () => {
	let sent: FeedbackRequest[];

	beforeEach(() => {
		sent = [];
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { displayName: "Acme" })]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("MEMBER")),
			),
			http.post("*/workspaces/acme/product-feedback", async ({ request }) => {
				sent.push(zFeedbackRequest.parse(await request.json()));
				return HttpResponse.json({ id: "r1", route: "EMAIL", state: "SENT_TO_ADMINS" });
			}),
		);
	});

	it("attaches the page as its route, without the workspace in the address", async () => {
		const user = userEvent.setup();
		renderRouteAt("/w/acme/activity?period=quarter");

		await user.click(
			await screen.findByRole("button", { name: "Product feedback" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("menuitem", { name: "Report a bug" }));
		await user.type(screen.getByRole("textbox", { name: "What happened?" }), "The list is empty.");
		screen.getByText("/w/$workspaceSlug/activity");
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({
			kind: "BUG",
			message: "The list is empty.",
			pagePath: "/w/$workspaceSlug/activity",
		});
		expect(JSON.stringify(sent[0])).not.toContain("acme");
	});
});

/** Far enough ahead that the hold is still running whenever the suite runs. */
const HOLD_ENDS = "2999-01-01T00:00:00.000Z";

async function composeIdea(user: ReturnType<typeof userEvent.setup>) {
	renderRouteAt("/w/acme/activity");
	await user.click(
		await screen.findByRole("button", { name: "Product feedback" }, ROUTE_RENDER_WAIT),
	);
	await user.click(await screen.findByRole("menuitem", { name: "Share an idea" }));
	await user.type(screen.getByRole("textbox", { name: "Your idea" }), "Pin a practice.");
}

describe("product feedback that can become a public GitHub issue", () => {
	const REPOSITORY = "hephaestus-build/Hephaestus";
	const REPORT_ID = "b1b1b1b1-0000-0000-0000-000000000001";
	let sent: FeedbackRequest[];
	let sharingWrites: unknown[];
	let keptPrivate: string[];

	function heldReport(overrides: Partial<Wire<MyProductFeedback>> = {}) {
		return {
			id: REPORT_ID,
			kind: "IDEA",
			excerpt: "Pin a practice.",
			createdAt: "2026-09-11T09:00:00.000Z",
			state: "HELD",
			route: "PUBLIC",
			filedAfter: HOLD_ENDS,
			draftTitle: "Pin practices to the top of the page",
			draftDescription: "Let people pin the practices they work on.",
			allowPublic: true,
			canKeepPrivate: true,
			canEdit: true,
			canMakePrivate: false,
			canAllowPublic: false,
			...overrides,
		} satisfies Wire<MyProductFeedback>;
	}

	function mockSharing(sharing: Wire<ProductFeedbackSharing>) {
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { displayName: "Acme" })]),
			),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("MEMBER")),
			),
			http.get("*/product-feedback/sharing", () => HttpResponse.json(sharing)),
			http.put("*/product-feedback/sharing", async ({ request }) => {
				sharingWrites.push(await request.json());
				return HttpResponse.json(sharing);
			}),
			http.post("*/workspaces/acme/product-feedback", async ({ request }) => {
				const body = zFeedbackRequest.parse(await request.json());
				sent.push(body);
				return HttpResponse.json({
					id: REPORT_ID,
					route: body.share === true ? "PUBLIC" : "PRIVATE",
					state: "DRAFTING",
					filedAfter: body.share === true ? HOLD_ENDS : undefined,
				} satisfies Wire<FeedbackReceipt>);
			}),
			http.get(`*/product-feedback/${REPORT_ID}`, () =>
				HttpResponse.json(
					sent.at(-1)?.share === true
						? heldReport()
						: heldReport({
								state: "DRAFTING",
								route: "PRIVATE",
								filedAfter: undefined,
								draftTitle: undefined,
								draftDescription: undefined,
								allowPublic: false,
								canKeepPrivate: false,
								canEdit: false,
							}),
				),
			),
			http.post(`*/product-feedback/${REPORT_ID}/keep-private`, () => {
				keptPrivate.push(REPORT_ID);
				return HttpResponse.json(
					heldReport({
						state: "DRAFTING",
						route: "PRIVATE",
						reason: "opted out",
						allowPublic: false,
						canKeepPrivate: false,
						canEdit: false,
					}),
				);
			}),
		);
	}

	beforeEach(() => {
		sent = [];
		sharingWrites = [];
		keptPrivate = [];
	});

	it("asks the first time, sends the choice, and offers Keep private while the report waits", async () => {
		mockSharing({ filesToGitHub: true, publicRepository: REPOSITORY, mention: false });
		const user = userEvent.setup();
		await composeIdea(user);

		const share = await screen.findByRole("radio", { name: "Yes, share it publicly." });
		expect(share.getAttribute("aria-checked")).toBe("false");
		expect(
			screen.getByRole("radio", { name: "No, keep it private." }).getAttribute("aria-checked"),
		).toBe("false");
		expect(screen.getByRole("button", { name: "Send" }).hasAttribute("disabled")).toBe(true);

		await user.click(share);
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({ kind: "IDEA", message: "Pin a practice.", share: true });
		await screen.findByText(/it becomes a public issue in hephaestus-build\/Hephaestus/u);
		await screen.findByText("Pin practices to the top of the page");

		await user.click(screen.getByRole("button", { name: "Keep private" }));
		await waitFor(() => expect(keptPrivate).toStrictEqual([REPORT_ID]));
		await screen.findByText(/You chose to keep it private\./u);
		expect(screen.queryByText(/becomes a public issue/u)).toBeNull();
	});

	it("turns the remembered choice off for this report only", async () => {
		mockSharing({
			filesToGitHub: true,
			publicRepository: REPOSITORY,
			allowPublic: true,
			mention: false,
		});
		const user = userEvent.setup();
		await composeIdea(user);

		const toggle = await screen.findByRole("switch", { name: "Public GitHub issue" });
		expect(toggle.getAttribute("aria-checked")).toBe("true");
		await user.click(toggle);
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({ share: false });
		// The remembered choice is not changed by one report.
		expect(sharingWrites).toStrictEqual([]);
		await screen.findByText(/The maintainers get it as a private issue on GitHub\./u);
		expect(screen.queryByText(/public issue/u)).toBeNull();
	});

	it("asks nothing and sends no choice without a public repository", async () => {
		mockSharing({ filesToGitHub: false, mention: false });
		const user = userEvent.setup();
		await composeIdea(user);
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).not.toHaveProperty("share");
		expect(screen.queryByRole("switch", { name: "Public GitHub issue" })).toBeNull();
	});
});
