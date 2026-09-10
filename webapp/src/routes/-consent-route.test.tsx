import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import type { FirstLoginConsent } from "@/api/types.gen";
import { NOTICE_VERSION } from "@/components/auth/ConsentPage";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

const notice = {
	completed: false,
	noticeVersion: NOTICE_VERSION,
	participateInResearch: false,
	researchOrganization: "AET",
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

		await screen.findByRole("heading", { name: "Let's get you set up" }, ROUTE_RENDER_WAIT);

		await userEvent.click(screen.getByRole("checkbox", { name: /terms of use/i }));
		await userEvent.click(screen.getByRole("radio", { name: /don't take part/ }));
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));

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

		await screen.findByRole("heading", { name: "Let's get you set up" }, ROUTE_RENDER_WAIT);
		await userEvent.click(screen.getByRole("checkbox", { name: /terms of use/i }));
		await userEvent.click(screen.getByRole("radio", { name: /Yes, take part/ }));
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));

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
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("checkbox", { name: /terms of use/i });
	});

	it("returns to the intended destination when retry discovers onboarding was already completed", async () => {
		server.use(http.get("*/user/consent", () => new HttpResponse(null, { status: 503 })));
		const { router } = renderRouteAtWithRouter("/consent?returnTo=%2Fabout");
		await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		server.use(http.get("*/user/consent", () => HttpResponse.json({ ...notice, completed: true })));
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));
		await waitFor(() => expect(router.state.location.pathname).toBe("/about"));
	});

	it("keeps the answers editable after a failed save", async () => {
		server.use(
			http.get("*/user/consent", () => HttpResponse.json(notice)),
			http.put("*/user/consent", () => new HttpResponse(null, { status: 503 })),
		);
		renderRouteAtWithRouter("/consent");
		await userEvent.click(
			await screen.findByRole("checkbox", { name: /terms of use/i }, ROUTE_RENDER_WAIT),
		);
		await userEvent.click(screen.getByRole("radio", { name: /don't take part/ }));
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));
		await screen.findByRole("alert");
	});

	it("stops offering the form when a rejected save reveals a newer notice version", async () => {
		let version: string = NOTICE_VERSION;
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
		await userEvent.click(screen.getByRole("radio", { name: /Yes, take part/ }));
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));

		// The wording is in this bundle, so once the server has moved on there is nothing here the
		// account could truthfully accept: only a document load can bring the new words in.
		await screen.findByRole("button", { name: "Reload" });
		expect(screen.queryByRole("button", { name: "Continue" })).toBeNull();
		expect(screen.queryByRole("checkbox", { name: /terms of use/i })).toBeNull();
	});

	it("omits the research answer when the instance names no research organisation", async () => {
		let submitted: FirstLoginConsent | undefined;
		const solo = { ...notice, researchOrganization: undefined };
		server.use(
			http.get("*/user/consent", () => HttpResponse.json(solo)),
			http.put<never, FirstLoginConsent>("*/user/consent", async ({ request }) => {
				submitted = await request.json();
				return HttpResponse.json({ ...solo, completed: true });
			}),
		);
		renderRouteAtWithRouter("/consent");
		await userEvent.click(
			await screen.findByRole("checkbox", { name: /terms of use/i }, ROUTE_RENDER_WAIT),
		);
		expect(screen.queryByRole("radiogroup")).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));

		await waitFor(() =>
			expect(submitted).toStrictEqual({ noticeVersion: NOTICE_VERSION, termsAccepted: true }),
		);
	});
});
