import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it } from "vitest";

import type { UpdateNotificationPreferences, UpdateUserSettings } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const preferences = {
	productFeedback: false,
	workspaceAlerts: false,
	surveySummaries: false,
	productSurveys: false,
	researchSurveys: false,
	emailAvailable: false,
	deliveryConfigured: true,
	weeklyDigest: false,
	weeklyDigestAvailable: true,
	etag: '"0-0-0-0-0-0"',
};

function recordWrites(settings: { practiceFeedbackDeliveryEnabled: boolean; timeZone?: string }) {
	const preferenceWrites: unknown[] = [];
	const settingsWrites: unknown[] = [];
	server.use(
		http.get("*/user/notification-preferences", () => HttpResponse.json(preferences)),
		http.put("*/user/notification-preferences", async ({ request }) => {
			preferenceWrites.push(await request.json());
			return HttpResponse.json({ ...preferences, weeklyDigest: true, etag: '"1"' });
		}),
		http.get("*/user/settings", () => HttpResponse.json(settings)),
		http.post<PathParams, UpdateUserSettings>("*/user/settings", async ({ request }) => {
			const body = await request.json();
			settingsWrites.push(body);
			// The server keeps the saved time zone when the request has none.
			return HttpResponse.json({ ...settings, ...body });
		}),
	);
	return { preferenceWrites, settingsWrites };
}

describe("weekly digest in User settings", () => {
	it("turns the digest on without an email address and takes the browser's time zone for an account without one", async () => {
		const user = userEvent.setup();
		const { preferenceWrites, settingsWrites } = recordWrites({
			practiceFeedbackDeliveryEnabled: true,
		});
		renderRouteAt("/settings");

		const toggle = await screen.findByRole("switch", { name: "Weekly digest" }, ROUTE_RENDER_WAIT);
		expect(toggle.getAttribute("aria-checked")).toBe("false");
		await user.click(toggle);

		await waitFor(() =>
			expect(preferenceWrites).toStrictEqual([
				{
					productFeedback: false,
					productSurveys: false,
					researchSurveys: false,
					workspaceAlerts: false,
					surveySummaries: false,
					weeklyDigest: true,
				},
			]),
		);
		await waitFor(() =>
			expect(settingsWrites).toStrictEqual([
				{
					practiceFeedbackDeliveryEnabled: true,
					timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
				},
			]),
		);
		await waitFor(() => expect(toggle.getAttribute("aria-checked")).toBe("true"));
	});

	it("keeps the account's own time zone when the digest is turned on", async () => {
		const user = userEvent.setup();
		const { preferenceWrites, settingsWrites } = recordWrites({
			practiceFeedbackDeliveryEnabled: true,
			timeZone: "Asia/Tokyo",
		});
		renderRouteAt("/settings");

		await user.click(
			await screen.findByRole("switch", { name: "Weekly digest" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() => expect(preferenceWrites).toHaveLength(1));
		expect(settingsWrites).toStrictEqual([]);
		expect(screen.getByRole("combobox", { name: "Time zone" }).textContent).toContain("Asia/Tokyo");
	});

	it("takes the developer back to UTC after another zone was chosen", async () => {
		const user = userEvent.setup();
		const { settingsWrites } = recordWrites({
			practiceFeedbackDeliveryEnabled: true,
			timeZone: "Asia/Tokyo",
		});
		renderRouteAt("/settings");

		await user.click(await screen.findByRole("combobox", { name: "Time zone" }, ROUTE_RENDER_WAIT));
		await user.type(await screen.findByRole("combobox", { name: "Search time zones" }), "UTC");
		await user.click(await screen.findByRole("option", { name: "UTC" }));

		await waitFor(() =>
			expect(settingsWrites).toStrictEqual([
				{ practiceFeedbackDeliveryEnabled: true, timeZone: "UTC" },
			]),
		);
	});

	it("leaves the saved time zone out of a change to the feedback choice", async () => {
		const user = userEvent.setup();
		const { settingsWrites } = recordWrites({
			practiceFeedbackDeliveryEnabled: true,
			timeZone: "Asia/Tokyo",
		});
		renderRouteAt("/settings");

		await user.click(
			await screen.findByRole("switch", { name: "Comments on your work" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(settingsWrites).toStrictEqual([{ practiceFeedbackDeliveryEnabled: false }]),
		);
		expect(screen.getByRole("combobox", { name: "Time zone" }).textContent).toContain("Asia/Tokyo");
	});

	it("leaves the digest off when the server refuses the browser's time zone", async () => {
		const user = userEvent.setup();
		const { preferenceWrites } = recordWrites({ practiceFeedbackDeliveryEnabled: true });
		server.use(
			http.post("*/user/settings", () => HttpResponse.json({ status: 400 }, { status: 400 })),
		);
		renderRouteAt("/settings");

		const toggle = await screen.findByRole("switch", { name: "Weekly digest" }, ROUTE_RENDER_WAIT);
		await user.click(toggle);

		await screen.findByText("We could not update your settings. Try again.");
		expect(preferenceWrites).toStrictEqual([]);
		expect(toggle.getAttribute("aria-checked")).toBe("false");
	});

	it("writes the digest on top of an email choice made just before, under its new version", async () => {
		const user = userEvent.setup();
		let current = { ...preferences, emailAvailable: true };
		const writes: { ifMatch: string | null; body: unknown }[] = [];
		server.use(
			http.get("*/user/notification-preferences", () => HttpResponse.json(current)),
			http.put<PathParams, UpdateNotificationPreferences>(
				"*/user/notification-preferences",
				async ({ request }) => {
					const body = await request.json();
					writes.push({ ifMatch: request.headers.get("If-Match"), body });
					current = { ...current, ...body, etag: `"${writes.length}"` };
					return HttpResponse.json(current);
				},
			),
			http.get("*/user/settings", () =>
				HttpResponse.json({ practiceFeedbackDeliveryEnabled: true, timeZone: "Asia/Tokyo" }),
			),
		);
		renderRouteAt("/settings");

		const alerts = await screen.findByRole(
			"switch",
			{ name: "Workspace connection alerts" },
			ROUTE_RENDER_WAIT,
		);
		await user.click(alerts);
		await waitFor(() => expect(alerts.getAttribute("aria-checked")).toBe("true"));
		await user.click(screen.getByRole("switch", { name: "Weekly digest" }));

		await waitFor(() => expect(writes).toHaveLength(2));
		expect(writes[1]).toStrictEqual({
			ifMatch: '"1"',
			body: {
				productFeedback: false,
				productSurveys: false,
				researchSurveys: false,
				surveySummaries: false,
				weeklyDigest: true,
				workspaceAlerts: true,
			},
		});
	});

	it("keeps the switch the developer just turned off where no digest is written", async () => {
		const user = userEvent.setup();
		const off = { ...preferences, weeklyDigest: false, weeklyDigestAvailable: false };
		server.use(
			http.get("*/user/notification-preferences", () =>
				HttpResponse.json({ ...off, weeklyDigest: true }),
			),
			http.put("*/user/notification-preferences", () => HttpResponse.json({ ...off, etag: '"1"' })),
			http.get("*/user/settings", () =>
				HttpResponse.json({ practiceFeedbackDeliveryEnabled: true, timeZone: "Asia/Tokyo" }),
			),
		);
		renderRouteAt("/settings");

		const toggle = await screen.findByRole("switch", { name: "Weekly digest" }, ROUTE_RENDER_WAIT);
		await user.click(toggle);

		await waitFor(() => expect(toggle.getAttribute("aria-checked")).toBe("false"));
		expect(screen.getByRole("switch", { name: "Weekly digest" })).toBe(toggle);
		expect(toggle.getAttribute("aria-disabled")).toBe("true");
		expect(document.activeElement).toBe(toggle);
	});

	it("shows nothing about the digest when Heph answers in none of the developer's workspaces", async () => {
		server.use(
			http.get("*/user/notification-preferences", () =>
				HttpResponse.json({ ...preferences, weeklyDigestAvailable: false }),
			),
		);
		renderRouteAt("/settings");

		await screen.findByRole("heading", { name: "Practice feedback" }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("switch", { name: "Comments on your work" })).not.toBeNull();
		expect(screen.queryByRole("switch", { name: "Weekly digest" })).toBeNull();
	});
});
