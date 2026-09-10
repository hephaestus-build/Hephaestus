import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { unauthenticatedUser } from "@/mocks/handlers";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

const token = "a".repeat(43);
const storageKey = "hephaestus.github-access-approval";

afterEach(() => {
	sessionStorage.removeItem(storageKey);
	window.history.replaceState(null, "", "/");
});

describe("GitHub organization-owner approval", () => {
	it("keeps the capability out of login return URLs and carries it only in tab storage", async () => {
		server.use(unauthenticatedUser);
		window.history.replaceState(null, "", `/github-access-approval#${token}`);
		const { router } = renderRouteAtWithRouter("/github-access-approval");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Sign in to review" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(router.state.location.pathname).toBe("/login"));
		expect({ ...router.state.location.search }).toStrictEqual({
			returnTo: "/github-access-approval",
		});
		expect(router.state.location.href).not.toContain(token);
		expect(window.location.hash).toBe("");
		expect(sessionStorage.getItem(storageKey)).toContain(token);
	});

	it("previews and authorizes explicitly with POST bodies, then clears the saved capability", async () => {
		sessionStorage.setItem(
			storageKey,
			JSON.stringify({ token, expiresAt: Number.MAX_SAFE_INTEGER }),
		);
		let previewed = false;
		let authorized = false;
		server.use(
			http.post("*/user/github-access/approval/preview", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ token });
				expect(request.url).not.toContain(token);
				previewed = true;
				return HttpResponse.json({
					workspaceName: "Engineering",
					organization: "example-org",
					installationId: 500,
					groupIds: ["eligible"],
				});
			}),
			http.post("*/user/github-access/approval", async ({ request }) => {
				expect(await request.json()).toStrictEqual({ token });
				expect(request.url).not.toContain(token);
				authorized = true;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAtWithRouter("/github-access-approval");
		const user = userEvent.setup();
		const review = await screen.findByRole(
			"button",
			{ name: "Review approval request" },
			ROUTE_RENDER_WAIT,
		);
		expect(previewed).toBe(false);
		expect(authorized).toBe(false);
		await user.click(review);
		await user.click(
			await screen.findByRole("button", { name: "Authorize this workspace and scope" }),
		);
		await screen.findByRole("heading", { name: "Organization authorization recorded" });
		expect(authorized).toBe(true);
		expect(sessionStorage.getItem(storageKey)).toBeNull();
	});

	it("does not reuse an expired saved capability", async () => {
		sessionStorage.setItem(storageKey, JSON.stringify({ token, expiresAt: 1 }));
		renderRouteAtWithRouter("/github-access-approval");
		await screen.findByText(/This approval link is missing or expired/, {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Review approval request" })).toBeNull();
	});
});
