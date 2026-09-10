import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import type { FirstLoginConsent } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

const notice = {
	completed: false,
	noticeText: "Hephaestus analyzes connected activity.\n\nResearch participation is optional.",
	noticeVersion: "2026-08-30",
	participateInResearch: false,
};

function showNotice(onComplete: (body: FirstLoginConsent) => void) {
	server.use(
		http.get("*/user/consent", () => HttpResponse.json(notice)),
		http.put<never, FirstLoginConsent>("*/user/consent", async ({ request }) => {
			onComplete(await request.json());
			return HttpResponse.json({ ...notice, completed: true });
		}),
	);
}

describe("first-login consent route", () => {
	it("records an explicit research refusal and resumes the requested page", async () => {
		let submitted: FirstLoginConsent | undefined;
		showNotice((body) => {
			submitted = body;
		});
		const { router } = renderRouteAtWithRouter("/consent?returnTo=%2Fabout");

		await screen.findByRole(
			"heading",
			{ name: "How Hephaestus uses your data" },
			ROUTE_RENDER_WAIT,
		);

		await userEvent.click(screen.getByRole("checkbox", { name: /terms of use/i }));
		fireEvent.click(screen.getByRole("button", { name: "Continue to the research question" }));
		fireEvent.click(screen.getByRole("button", { name: "Continue without research" }));

		await waitFor(() =>
			expect(submitted).toStrictEqual({
				noticeVersion: notice.noticeVersion,
				participateInResearch: false,
				termsAccepted: true,
			}),
		);
		await waitFor(() => expect(router.state.location.pathname).toBe("/about"));
	});

	it("records an affirmative research choice", async () => {
		let submitted: FirstLoginConsent | undefined;
		showNotice((body) => {
			submitted = body;
		});
		renderRouteAtWithRouter("/consent");

		await screen.findByRole(
			"heading",
			{ name: "How Hephaestus uses your data" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(screen.getByRole("checkbox", { name: /terms of use/i }));
		fireEvent.click(screen.getByRole("button", { name: "Continue to the research question" }));
		fireEvent.click(screen.getByRole("button", { name: "Yes, I'll take part" }));

		await waitFor(() =>
			expect(submitted).toStrictEqual({
				noticeVersion: notice.noticeVersion,
				participateInResearch: true,
				termsAccepted: true,
			}),
		);
	});
});

describe("consent recovery", () => {
	it("offers retry after a failed notice fetch without loading protected data", async () => {
		let protectedReads = 0;
		server.use(
			http.get("*/user/consent", () => new HttpResponse(null, { status: 503 })),
			http.get("*/workspaces", () => {
				protectedReads += 1;
				return HttpResponse.json([]);
			}),
		);
		renderRouteAtWithRouter("/");
		await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		expect(protectedReads).toBe(0);
		server.use(http.get("*/user/consent", () => HttpResponse.json(notice)));
		await userEvent.click(screen.getByRole("button", { name: "Try again" }));
		await screen.findByRole("checkbox", { name: /terms of use/i });
		expect(screen.queryByRole("alert")).toBeNull();
	});

	it("returns to the intended destination when retry discovers onboarding was already completed", async () => {
		server.use(http.get("*/user/consent", () => new HttpResponse(null, { status: 503 })));
		const { router } = renderRouteAtWithRouter("/consent?returnTo=%2Fabout");
		await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		server.use(http.get("*/user/consent", () => HttpResponse.json({ ...notice, completed: true })));
		await userEvent.click(screen.getByRole("button", { name: "Try again" }));
		await waitFor(() => expect(router.state.location.pathname).toBe("/about"));
	});

	it("keeps the research choice available after a failed save", async () => {
		server.use(
			http.get("*/user/consent", () => HttpResponse.json(notice)),
			http.put("*/user/consent", () => new HttpResponse(null, { status: 503 })),
		);
		renderRouteAtWithRouter("/consent");
		await userEvent.click(
			await screen.findByRole("checkbox", { name: /terms of use/i }, ROUTE_RENDER_WAIT),
		);
		fireEvent.click(screen.getByRole("button", { name: "Continue to the research question" }));
		fireEvent.click(screen.getByRole("button", { name: "Continue without research" }));
		await screen.findByRole("alert");
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Yes, I'll take part" }).disabled,
		).toBe(false);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Continue without research" }).disabled,
		).toBe(false);
	});

	it("requires fresh acceptance when a rejected save reveals a new notice version", async () => {
		let version = notice.noticeVersion;
		server.use(
			http.get("*/user/consent", () => HttpResponse.json({ ...notice, noticeVersion: version })),
			http.put("*/user/consent", () => {
				version = "next-notice";
				return new HttpResponse(null, { status: 409 });
			}),
		);
		renderRouteAtWithRouter("/consent");
		await userEvent.click(
			await screen.findByRole("checkbox", { name: /terms of use/i }, ROUTE_RENDER_WAIT),
		);
		fireEvent.click(screen.getByRole("button", { name: "Continue to the research question" }));
		fireEvent.click(screen.getByRole("button", { name: "Yes, I'll take part" }));
		await screen.findByRole("checkbox", { name: /terms of use/i });
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Continue to the research question" })
				.disabled,
		).toBe(true);
		expect(screen.queryByRole("alert")).toBeNull();
	});
});
