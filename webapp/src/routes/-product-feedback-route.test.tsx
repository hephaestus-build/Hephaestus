import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

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
import { deferred } from "@/test/async";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

beforeAll(() => loadRouteCode("/product-feedback", "/w/acme/activity"), ROUTE_RENDER_WAIT.timeout);

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
				return HttpResponse.json({ id: "r1", route: "ADMINS", state: "SAVED_FOR_ADMINS" });
			}),
		);
	});

	it("attaches the page as its route, without the workspace in the address", async () => {
		const user = userEvent.setup();
		renderRouteAt("/w/acme/activity?period=quarter");

		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Send feedback about Hephaestus" },
				ROUTE_RENDER_WAIT,
			),
		);
		await user.type(
			await screen.findByRole("textbox", { name: "Your feedback" }, ROUTE_RENDER_WAIT),
			"The list is empty.",
		);
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({
			kind: "FEEDBACK",
			message: "The list is empty.",
			pagePath: "/w/$workspaceSlug/activity",
		});
		expect(JSON.stringify(sent[0])).not.toContain("acme");
	});

	it("keeps a draft and its original page context after visiting reports from the account menu", async () => {
		const user = userEvent.setup();
		renderRouteAt("/w/acme/activity");
		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Send feedback about Hephaestus" },
				ROUTE_RENDER_WAIT,
			),
		);
		await user.type(
			await screen.findByRole("textbox", { name: "Your feedback" }),
			"The list jumps.",
		);
		await user.click(screen.getByRole("button", { name: "Close" }));
		await user.click(screen.getByRole("button", { name: /^Account/u }));
		const reports = await screen.findByRole("menuitem", { name: "Your reports" });
		expect(reports.getAttribute("href")).toBe("/product-feedback?chromeWorkspace=acme");
		await user.click(reports);
		await screen.findByRole("heading", { name: "Your reports" }, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("button", { name: "Send feedback about Hephaestus" }));
		const message = await screen.findByRole("textbox", { name: "Your feedback" });
		expect(message).toHaveProperty("value", "The list jumps.");
		await user.click(message);
		await user.keyboard("{Control>}{Enter}{/Control}");
		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({
			kind: "FEEDBACK",
			message: "The list jumps.",
			pagePath: "/w/$workspaceSlug/activity",
		});
		await screen.findByText("Report sent");
	});

	it("opens the form from anywhere with the shortcut", async () => {
		const user = userEvent.setup();
		renderRouteAt("/w/acme/activity");
		await screen.findByRole(
			"button",
			{ name: "Send feedback about Hephaestus" },
			ROUTE_RENDER_WAIT,
		);

		await user.keyboard("{Control>}{Shift>}F{/Shift}{/Control}");

		await screen.findByRole("textbox", { name: "Your feedback" });
	});

	it("keeps a failed report recoverable when the form closes during sending", async () => {
		const response = deferred<Response>();
		server.use(http.post("*/workspaces/acme/product-feedback", async () => response.promise));
		const user = userEvent.setup();
		renderRouteAt("/w/acme/activity");
		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Send feedback about Hephaestus" },
				ROUTE_RENDER_WAIT,
			),
		);
		await user.type(
			await screen.findByRole("textbox", { name: "Your feedback" }),
			"Keep this draft",
		);
		await user.click(screen.getByRole("button", { name: "Send" }));
		await screen.findByRole("button", { name: "Sending…" });
		await user.click(screen.getByRole("button", { name: "Close" }));
		response.resolve(HttpResponse.json({ status: 503 }, { status: 503 }));
		await screen.findByText("We could not confirm receipt");
		await user.click(screen.getByRole("button", { name: "Open draft" }));
		const draft = await screen.findByRole("textbox", { name: "Your feedback" });
		expect(draft).toHaveProperty("value", "Keep this draft");
	});
});

/** Far enough ahead that the hold is still running whenever the suite runs. */
const HOLD_ENDS = "2999-01-01T00:00:00.000Z";

async function composeFeedback(user: ReturnType<typeof userEvent.setup>) {
	renderRouteAt("/w/acme/activity");
	await user.click(
		await screen.findByRole(
			"button",
			{ name: "Send feedback about Hephaestus" },
			ROUTE_RENDER_WAIT,
		),
	);
	await user.type(
		await screen.findByRole("textbox", { name: "Your feedback" }, ROUTE_RENDER_WAIT),
		"Pin a practice.",
	);
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
			kind: "FEEDBACK",
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

	function privateReport() {
		return heldReport({
			state: "DRAFTING",
			route: "PRIVATE",
			reason: "opted out",
			filedAfter: undefined,
			draftTitle: undefined,
			draftDescription: undefined,
			allowPublic: false,
			canKeepPrivate: false,
			canEdit: false,
		});
	}

	function mockSharing(sharing: Wire<ProductFeedbackSharing>) {
		let currentReport = heldReport();
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
				currentReport = body.share === true ? heldReport() : privateReport();
				return HttpResponse.json({
					id: REPORT_ID,
					route: body.share === true ? "PUBLIC" : "PRIVATE",
					state: "DRAFTING",
					filedAfter: body.share === true ? HOLD_ENDS : undefined,
				} satisfies Wire<FeedbackReceipt>);
			}),
			http.get("*/product-feedback/mine", () => HttpResponse.json([currentReport])),
			http.post(`*/product-feedback/${REPORT_ID}/keep-private`, () => {
				keptPrivate.push(REPORT_ID);
				currentReport = privateReport();
				return HttpResponse.json(currentReport);
			}),
		);
	}

	beforeEach(() => {
		sent = [];
		sharingWrites = [];
		keptPrivate = [];
	});

	it("sends a first report at once, as a private one, and says where it went", async () => {
		mockSharing({ filesToGitHub: true, publicRepository: REPOSITORY, mention: false });
		const user = userEvent.setup();
		await composeFeedback(user);

		const box = await screen.findByRole("checkbox", { name: "Post as a public GitHub issue" });
		expect(box.getAttribute("aria-checked")).toBe("false");
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({ kind: "FEEDBACK", message: "Pin a practice.", share: false });
		await screen.findByText("Report sent");
		await screen.findByText(
			"Your report is saved. It becomes a private GitHub issue for the Hephaestus maintainers. To ask for a public issue, open Your reports.",
		);
		expect(screen.queryByRole("dialog")).toBeNull();
	});

	it("files a ticked report publicly, and its receipt leads to the report's actions", async () => {
		mockSharing({ filesToGitHub: true, publicRepository: REPOSITORY, mention: false });
		const user = userEvent.setup();
		await composeFeedback(user);

		await user.click(
			await screen.findByRole("checkbox", { name: "Post as a public GitHub issue" }),
		);
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({ share: true });
		await screen.findByText(
			"Your report is saved. It can become a public GitHub issue after the checks pass.",
		);
		await user.click(screen.getByRole("button", { name: "View" }));
		await screen.findByRole("heading", { name: "Your reports" }, ROUTE_RENDER_WAIT);
		await user.click(await screen.findByRole("button", { name: "Keep private" }));
		await screen.findByText("Report kept private");
		await screen.findByText("It is not marked public, so it stays private.");
		expect(keptPrivate).toStrictEqual([REPORT_ID]);
	});

	it("turns the remembered choice off for this report only", async () => {
		mockSharing({
			filesToGitHub: true,
			publicRepository: REPOSITORY,
			allowPublic: true,
			mention: false,
		});
		const user = userEvent.setup();
		await composeFeedback(user);

		const toggle = await screen.findByRole("checkbox", { name: "Post as a public GitHub issue" });
		expect(toggle.getAttribute("aria-checked")).toBe("true");
		await user.click(toggle);
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({ share: false });
		// The remembered choice is not changed by one report.
		expect(sharingWrites).toStrictEqual([]);
		await screen.findByText(
			"Your report is saved. It becomes a private GitHub issue for the Hephaestus maintainers. To ask for a public issue, open Your reports.",
		);
	});

	it("offers no box and keeps the report private without a public repository", async () => {
		mockSharing({ filesToGitHub: false, mention: false });
		const user = userEvent.setup();
		await composeFeedback(user);
		await user.click(screen.getByRole("button", { name: "Send" }));

		await waitFor(() => expect(sent).toHaveLength(1));
		expect(sent[0]).toMatchObject({ share: false });
		expect(screen.queryByRole("checkbox")).toBeNull();
	});
});
