import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const preferences = {
	productFeedback: false,
	workspaceAlerts: false,
	surveySummaries: false,
	productFeedbackFrequency: "IMMEDIATE",
	productSurveys: false,
	researchSurveys: false,
	emailAvailable: true,
	etag: '"0-0-0"',
};

describe("account email choices", () => {
	it("sends the confirmed revision and keeps consent off until the server accepts it", async () => {
		const user = userEvent.setup();
		let release: (() => void) | undefined;
		const response = new Promise<void>((resolve) => {
			release = resolve;
		});
		let body: unknown;
		let etag: string | null = null;
		server.use(
			http.get("*/user/notification-preferences", () => HttpResponse.json(preferences)),
			http.put("*/user/notification-preferences", async ({ request }) => {
				body = await request.json();
				etag = request.headers.get("If-Match");
				await response;
				return HttpResponse.json({ ...preferences, productSurveys: true, etag: '"0-1-0"' });
			}),
		);
		renderRouteAt("/settings");
		const product = await screen.findByRole(
			"switch",
			{ name: "Product survey invitations" },
			ROUTE_RENDER_WAIT,
		);
		await user.click(product);
		await waitFor(() => expect(etag).toBe('"0-0-0"'));
		expect(body).toStrictEqual({
			productFeedback: false,
			workspaceAlerts: false,
			surveySummaries: false,
			productFeedbackFrequency: "IMMEDIATE",
			productSurveys: true,
			researchSurveys: false,
		});
		expect(product.getAttribute("aria-checked")).toBe("false");
		expect(product.getAttribute("aria-disabled")).toBe("true");

		release?.();
		await waitFor(() => expect(product.getAttribute("aria-checked")).toBe("true"));
		expect(
			screen
				.getByRole("switch", { name: "Research survey invitations" })
				.getAttribute("aria-checked"),
		).toBe("false");
	});

	it("changes the opted-in admin feedback frequency without enabling another email kind", async () => {
		const user = userEvent.setup();
		let body: unknown;
		server.use(
			http.get("*/user/notification-preferences", () =>
				HttpResponse.json({ ...preferences, productFeedback: true }),
			),
			http.put("*/user/notification-preferences", async ({ request }) => {
				body = await request.json();
				return HttpResponse.json({
					...preferences,
					productFeedback: true,
					productFeedbackFrequency: "DAILY",
					etag: '"1"',
				});
			}),
		);
		renderRouteAt("/settings");
		await user.click(
			await screen.findByRole(
				"combobox",
				{ name: "Product feedback frequency" },
				ROUTE_RENDER_WAIT,
			),
		);
		await user.click(await screen.findByRole("option", { name: "Daily summary" }));
		await waitFor(() =>
			expect(body).toStrictEqual({
				productFeedback: true,
				productFeedbackFrequency: "DAILY",
				workspaceAlerts: false,
				surveySummaries: false,
				productSurveys: false,
				researchSurveys: false,
			}),
		);
		expect(
			screen.getByRole("combobox", { name: "Product feedback frequency" }).textContent,
		).toContain("Daily summary");
	});

	it("reloads a conflicting choice instead of overwriting it or displaying the rejected toggle", async () => {
		const user = userEvent.setup();
		let reads = 0;
		server.use(
			http.get("*/user/notification-preferences", () =>
				HttpResponse.json({ ...preferences, etag: `"${++reads}"` }),
			),
			http.put("*/user/notification-preferences", () =>
				HttpResponse.json({ status: 412 }, { status: 412 }),
			),
		);
		renderRouteAt("/settings");
		await user.click(
			await screen.findByRole("switch", { name: "Product survey invitations" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByText(
			"Email choices changed elsewhere. Review the updated choices and try again.",
		);
		expect(reads).toBe(2);
		expect(
			screen
				.getByRole("switch", { name: "Product survey invitations" })
				.getAttribute("aria-checked"),
		).toBe("false");
	});

	it("allows turning off a retained subscription without verified contact, but blocks new opt-ins", async () => {
		const user = userEvent.setup();
		const writes: unknown[] = [];
		const retained = {
			...preferences,
			emailAvailable: false,
			productSurveys: true,
			researchSurveys: true,
		};
		server.use(
			http.get("*/user/notification-preferences", () => HttpResponse.json(retained)),
			http.put("*/user/notification-preferences", async ({ request }) => {
				writes.push(await request.json());
				return HttpResponse.json({ ...retained, productSurveys: false, etag: '"2"' });
			}),
		);
		renderRouteAt("/settings");
		const product = await screen.findByRole(
			"switch",
			{ name: "Product survey invitations" },
			ROUTE_RENDER_WAIT,
		);
		const workspace = screen.getByRole("switch", { name: "Workspace connection alerts" });
		expect(workspace.getAttribute("aria-disabled")).toBe("true");
		await user.click(workspace);
		expect(writes).toHaveLength(0);
		await user.click(product);
		await waitFor(() =>
			expect(writes).toStrictEqual([
				{
					productFeedback: false,
					productFeedbackFrequency: "IMMEDIATE",
					workspaceAlerts: false,
					surveySummaries: false,
					productSurveys: false,
					researchSurveys: true,
				},
			]),
		);
		await waitFor(() => expect(product.getAttribute("aria-checked")).toBe("false"));
		expect(product.getAttribute("aria-disabled")).toBe("true");
		expect(
			screen
				.getByRole("switch", { name: "Research survey invitations" })
				.getAttribute("aria-checked"),
		).toBe("true");
	});

	it("explains missing verified contact and hides instance-admin subscriptions for a regular account", async () => {
		server.use(
			http.get("*/user", () =>
				HttpResponse.json({ ...currentUser, appRole: "APP_USER", roles: ["ROLE_USER"] }),
			),
			http.get("*/user/notification-preferences", () =>
				HttpResponse.json({ ...preferences, emailAvailable: false }),
			),
		);
		renderRouteAt("/settings");
		await screen.findByRole("switch", { name: "Product survey invitations" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("switch", { name: "New product feedback" })).toBeNull();
		await screen.findByText(/Changing these choices does not add or verify an address/);
	});
});
