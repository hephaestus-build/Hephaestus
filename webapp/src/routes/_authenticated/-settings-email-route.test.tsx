import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it } from "vitest";

import { getNotificationPreferencesQueryKey } from "@/api/@tanstack/react-query.gen";
import { WORDING_VERSION } from "@/components/auth/consent-wording";
import { currentUser } from "@/mocks/fixtures/auth";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const preferences = {
	productFeedback: false,
	productFeedbackUpdates: false,
	workspaceAlerts: false,
	emailAvailable: true,
	deliveryConfigured: true,
	weeklyDigest: false,
	weeklyDigestAvailable: false,
	etag: '"0-0-0"',
};

describe("account email choices", () => {
	beforeEach(() => {
		server.use(
			http.get("*/user/consent", () =>
				HttpResponse.json({
					completed: true,
					noticeVersion: WORDING_VERSION,
					participateInResearch: false,
					researchOrganization: "AET",
				}),
			),
		);
	});

	it("sends the confirmed revision and keeps consent off until the server accepts it", async () => {
		const user = userEvent.setup();
		const response = deferred();
		let body: unknown;
		let etag: string | null = null;
		server.use(
			http.get("*/user/notification-preferences", () => HttpResponse.json(preferences)),
			http.put("*/user/notification-preferences", async ({ request }) => {
				body = await request.json();
				etag = request.headers.get("If-Match");
				await response.promise;
				return HttpResponse.json({ ...preferences, workspaceAlerts: true, etag: '"0-1-0"' });
			}),
		);
		renderRouteAt("/settings");
		const product = await screen.findByRole(
			"switch",
			{ name: "Workspace connection alerts" },
			ROUTE_RENDER_WAIT,
		);
		await user.click(product);
		await waitFor(() => expect(etag).toBe('"0-0-0"'));
		expect(body).toStrictEqual({
			productFeedback: false,
			productFeedbackUpdates: false,
			workspaceAlerts: true,
			weeklyDigest: false,
		});
		expect(product.getAttribute("aria-checked")).toBe("false");
		expect(product.getAttribute("aria-disabled")).toBe("true");

		expect(screen.queryByText("Saving email choices…")).toBeNull();
		const saving = await screen.findByText("Saving email choices…", {}, { timeout: 2000 });
		expect(saving.getAttribute("role")).toBe("status");
		expect(saving.querySelector("svg")).not.toBeNull();

		response.resolve();
		await waitFor(() => expect(product.getAttribute("aria-checked")).toBe("true"));
		await waitFor(() => expect(screen.queryByText("Saving email choices…")).toBeNull());
		expect(product.getAttribute("aria-disabled")).not.toBe("true");
	});

	it("cancels stale reads before and during a save so confirmed consent and its revision stay current", async () => {
		const user = userEvent.setup();
		const heldRead = deferred();
		const heldWrite = deferred();
		let reads = 0;
		const revisions: (string | null)[] = [];
		server.use(
			http.get("*/user/notification-preferences", () => HttpResponse.json(preferences), {
				once: true,
			}),
			http.get("*/user/notification-preferences", async () => {
				reads += 1;
				await heldRead.promise;
				return HttpResponse.json(preferences);
			}),
			http.put("*/user/notification-preferences", async ({ request }) => {
				revisions.push(request.headers.get("If-Match"));
				await heldWrite.promise;
				return HttpResponse.json({ ...preferences, workspaceAlerts: true, etag: '"2"' });
			}),
		);
		const queryClient = renderRouteAt("/settings");
		const product = await screen.findByRole(
			"switch",
			{ name: "Workspace connection alerts" },
			ROUTE_RENDER_WAIT,
		);
		try {
			const beforeSave = queryClient.refetchQueries({
				queryKey: getNotificationPreferencesQueryKey(),
			});
			await waitFor(() => expect(reads).toBe(1));
			await user.click(product);
			await waitFor(() => expect(revisions).toHaveLength(1));
			await beforeSave;
			const duringSave = queryClient.refetchQueries({
				queryKey: getNotificationPreferencesQueryKey(),
			});
			await waitFor(() => expect(reads).toBe(2));
			heldWrite.resolve();
			await waitFor(() => expect(product.getAttribute("aria-checked")).toBe("true"));
			await duringSave;
			await act(async () => {
				heldRead.resolve();
				await Promise.all([beforeSave, duringSave]);
			});
			expect(product.getAttribute("aria-checked")).toBe("true");
			server.use(
				http.put("*/user/notification-preferences", ({ request }) => {
					revisions.push(request.headers.get("If-Match"));
					return HttpResponse.json({ ...preferences, etag: '"3"' });
				}),
			);
			await user.click(product);
			await waitFor(() => expect(revisions).toStrictEqual([preferences.etag, '"2"']));
			await waitFor(() => expect(product.getAttribute("aria-checked")).toBe("false"));
		} finally {
			heldRead.resolve();
			heldWrite.resolve();
		}
	});

	it.each([
		{ appRole: "APP_USER", roles: ["ROLE_USER"] },
		{ appRole: "APP_ADMIN", roles: ["ROLE_ADMIN"] },
	])(
		"hides unconfigured email choices without retained subscriptions for $appRole",
		async ({ appRole, roles }) => {
			server.use(
				http.get("*/user", () =>
					HttpResponse.json({
						...currentUser,
						appRole,
						roles,
					}),
				),
				http.get("*/user/notification-preferences", () =>
					HttpResponse.json({ ...preferences, deliveryConfigured: false }),
				),
			);
			const queryClient = renderRouteAt("/settings");
			await waitFor(
				() =>
					expect(queryClient.getQueryData(getNotificationPreferencesQueryKey())).toMatchObject({
						deliveryConfigured: false,
					}),
				ROUTE_RENDER_WAIT,
			);
			expect(screen.queryByRole("region", { name: "Email notifications" })).toBeNull();
			expect(screen.queryByText(/Email delivery is not configured/u)).toBeNull();
		},
	);

	it.each([
		{ appRole: "APP_USER", roles: ["ROLE_USER"] },
		{ appRole: "APP_ADMIN", roles: ["ROLE_ADMIN"] },
	])(
		"allows only retained opt-outs without SMTP for %s and removes the section after the last",
		async ({ appRole, roles }) => {
			const user = userEvent.setup();
			const retained = {
				...preferences,
				deliveryConfigured: false,
				emailAvailable: false,
				productFeedback: true,
			};
			const writes: unknown[] = [];
			server.use(
				http.get("*/user", () =>
					HttpResponse.json({
						...currentUser,
						appRole,
						roles,
					}),
				),
				http.get("*/user/notification-preferences", () => HttpResponse.json(retained)),
				http.put("*/user/notification-preferences", async ({ request }) => {
					writes.push(await request.json());
					return HttpResponse.json({
						...retained,
						productFeedback: false,
						productFeedbackUpdates: false,
						etag: '"2"',
					});
				}),
			);
			renderRouteAt("/settings");
			const feedback = await screen.findByRole(
				"switch",
				{ name: "New product feedback" },
				ROUTE_RENDER_WAIT,
			);
			expect(screen.queryByRole("switch", { name: "Workspace connection alerts" })).toBeNull();
			expect(screen.queryByText(/Email delivery is not configured/u)).toBeNull();
			await user.click(feedback);
			await waitFor(() =>
				expect(screen.queryByRole("region", { name: "Email notifications" })).toBeNull(),
			);
			expect(writes).toStrictEqual([
				{
					productFeedback: false,
					productFeedbackUpdates: false,
					workspaceAlerts: false,
					weeklyDigest: false,
				},
			]);
		},
	);

	it("lets a former instance administrator remove retained administrator opt-ins without re-enabling them", async () => {
		const user = userEvent.setup();
		const writes: unknown[] = [];
		const retained = {
			...preferences,
			emailAvailable: false,
			productFeedback: true,
		};
		server.use(
			http.get("*/user", () =>
				HttpResponse.json({ ...currentUser, appRole: "APP_USER", roles: ["ROLE_USER"] }),
			),
			http.get("*/user/notification-preferences", () => HttpResponse.json(retained)),
			http.put("*/user/notification-preferences", async ({ request }) => {
				writes.push(await request.json());
				return HttpResponse.json({
					...retained,
					productFeedback: false,
					productFeedbackUpdates: false,
					etag: '"2"',
				});
			}),
		);
		renderRouteAt("/settings");
		const feedback = await screen.findByRole(
			"switch",
			{ name: "New product feedback" },
			ROUTE_RENDER_WAIT,
		);
		expect(feedback.getAttribute("aria-checked")).toBe("true");
		await user.click(feedback);
		await waitFor(() =>
			expect(screen.queryByRole("switch", { name: "New product feedback" })).toBeNull(),
		);
		expect(writes).toStrictEqual([
			{
				productFeedback: false,
				productFeedbackUpdates: false,
				workspaceAlerts: false,
				weeklyDigest: false,
			},
		]);
	});

	it("lets a reporter unsubscribe and subscribe again while preserving other choices", async () => {
		const user = userEvent.setup();
		let current = { ...preferences, productFeedbackUpdates: true };
		const writes: unknown[] = [];
		server.use(
			http.get("*/user/notification-preferences", () => HttpResponse.json(current)),
			http.put("*/user/notification-preferences", async ({ request }) => {
				writes.push(await request.json());
				current = { ...current, productFeedbackUpdates: !current.productFeedbackUpdates };
				return HttpResponse.json(current);
			}),
		);
		renderRouteAt("/settings");
		const updates = await screen.findByRole(
			"switch",
			{ name: "Your product feedback updates" },
			ROUTE_RENDER_WAIT,
		);
		expect(updates.getAttribute("aria-checked")).toBe("true");
		await user.click(updates);
		await waitFor(() => expect(updates.getAttribute("aria-checked")).toBe("false"));
		await waitFor(() => expect(updates.getAttribute("aria-disabled")).not.toBe("true"));
		await user.click(updates);
		await waitFor(() => expect(updates.getAttribute("aria-checked")).toBe("true"));
		expect(writes).toStrictEqual([
			{
				productFeedback: false,
				workspaceAlerts: false,
				weeklyDigest: false,
				productFeedbackUpdates: false,
			},
			{
				productFeedback: false,
				workspaceAlerts: false,
				weeklyDigest: false,
				productFeedbackUpdates: true,
			},
		]);
	});

	it("reloads a conflicting choice instead of overwriting it or displaying the rejected toggle", async () => {
		const user = userEvent.setup();
		let reads = 0;
		server.use(
			http.get("*/user/notification-preferences", () => {
				reads += 1;
				return HttpResponse.json({ ...preferences, etag: `"${reads}"` });
			}),
			http.put("*/user/notification-preferences", () =>
				HttpResponse.json({ status: 412 }, { status: 412 }),
			),
		);
		renderRouteAt("/settings");
		await user.click(
			await screen.findByRole("switch", { name: "Workspace connection alerts" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByText(
			"Your choices changed elsewhere. Review the updated choices and try again.",
		);
		expect(reads).toBe(2);
		expect(
			screen
				.getByRole("switch", { name: "Workspace connection alerts" })
				.getAttribute("aria-checked"),
		).toBe("false");
	});

	it.each(["MEMBER", "ADMIN"] as const)(
		"does not treat workspace %s access as instance administration",
		async (role) => {
			server.use(
				http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
				http.get("*/workspaces/:workspaceSlug/members/me", () =>
					HttpResponse.json(ownMembership(role)),
				),
			);
			server.use(
				http.get("*/user", () =>
					HttpResponse.json({ ...currentUser, appRole: "APP_USER", roles: ["ROLE_USER"] }),
				),
				http.get("*/user/notification-preferences", () =>
					HttpResponse.json({ ...preferences, emailAvailable: false }),
				),
			);
			renderRouteAt("/settings");
			await screen.findByRole("switch", { name: "Workspace connection alerts" }, ROUTE_RENDER_WAIT);
			expect(screen.queryByRole("switch", { name: "New product feedback" })).toBeNull();
			expect(
				screen
					.getByRole("switch", { name: "Workspace connection alerts" })
					.getAttribute("aria-disabled"),
			).toBe("true");
			await screen.findByText(/Changing these choices does not add or verify an address/u);
		},
	);
});
