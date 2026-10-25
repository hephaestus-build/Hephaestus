import type { QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import {
	adminGetInstanceSettingsQueryKey,
	getPublicActivityPolicyQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const settings = (revision: number) => ({
	silentModeEngaged: true,
	etag: `"${revision}"`,
	silentModeReason: "incident",
	silentModeChangedAt: "2026-08-03T08:00:00Z",
	silentModeChangedByAccountId: 42,
});

type PolicyAnswer = { status: 200; body: { allowed: boolean } } | { status: 403 | 503 };

/** Answers the instance policy GET with the answer the test last chose, counting each read. */
function servePolicy(initial: PolicyAnswer) {
	const read = { count: 0, answer: initial };
	server.use(
		http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
		http.get("*/admin/settings/public-activity", () => {
			read.count += 1;
			const { answer } = read;
			return answer.status === 200
				? HttpResponse.json(answer.body)
				: HttpResponse.json(
						{ status: answer.status, title: "Request failed" },
						{ status: answer.status },
					);
		}),
	);
	return read;
}

/** Reads the instance policy again through its generated key, as a returning admin does. */
async function rereadPolicy(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey: getPublicActivityPolicyQueryKey() });
	});
}

describe("instance settings route", () => {
	it("sends If-Match and reloads current state after a stale release", async () => {
		const user = userEvent.setup();
		let reads = 0;
		let writes = 0;
		let ifMatch: string | null = null;
		server.use(
			http.get("*/admin/settings", () => {
				reads += 1;
				return HttpResponse.json(settings(reads));
			}),
			http.patch("*/admin/settings/silent-mode", ({ request }) => {
				writes += 1;
				ifMatch = request.headers.get("If-Match");
				return HttpResponse.json(
					{ status: 412, title: "Instance settings changed" },
					{ status: 412 },
				);
			}),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("button", { name: "Turn off silent mode…" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		await user.type(within(dialog).getByLabelText(/Enter turn off to confirm/u), "turn off");
		await user.click(within(dialog).getByRole("button", { name: "Turn off silent mode" }));

		await waitFor(() => expect(reads).toBe(2));
		expect(ifMatch).toBe('"1"');
		expect(writes).toBe(1);
		await screen.findByText(/Check the current state, then try again/u);
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	it("keeps release disabled when stale settings cannot be reloaded", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1)), { once: true }),
			// The reload the 412 triggers is the one that has to fail.
			http.get("*/admin/settings", () =>
				HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
			),
			http.patch("*/admin/settings/silent-mode", () =>
				HttpResponse.json({ status: 412, title: "Instance settings changed" }, { status: 412 }),
			),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("button", { name: "Turn off silent mode…" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		await user.type(within(dialog).getByLabelText(/Enter turn off to confirm/u), "turn off");
		await user.click(within(dialog).getByRole("button", { name: "Turn off silent mode" }));

		await screen.findByText("We could not verify the current instance settings");
		await within(dialog).findByText(/The current settings could not be verified/u);
		expect(
			within(dialog).getByRole<HTMLButtonElement>("button", { name: "Turn off silent mode" })
				.disabled,
		).toBe(true);
	});

	it.each([
		{ recipient: "  ops@example.org ", expectedBody: { to: "ops@example.org" } },
		{ recipient: " ", expectedBody: {} },
	])(
		"posts $expectedBody to the test-email endpoint and shows that the mail server accepted the email",
		async ({ recipient, expectedBody }) => {
			const user = userEvent.setup();
			let body: unknown;
			server.use(
				http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
				http.post("*/admin/email/test", async ({ request }) => {
					body = await request.json();
					return HttpResponse.json({
						outcome: "SENT",
						to: "ops@example.org",
						messageId: "<abc@hephaestus.example>",
					});
				}),
			);
			renderRouteAt("/admin/settings");

			const input = await screen.findByRole("textbox", { name: "Recipient" }, ROUTE_RENDER_WAIT);
			await user.type(input, recipient);
			await user.click(screen.getByRole("button", { name: "Send test email" }));

			await screen.findByText("Handed to mail server");
			expect(body).toStrictEqual(expectedBody);
			await screen.findByText("<abc@hephaestus.example>");
		},
	);

	it("names the stop a withheld test email hit", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
			http.post("*/admin/email/test", () => HttpResponse.json({ outcome: "SILENT_MODE" })),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("button", { name: "Send test email" }, ROUTE_RENDER_WAIT),
		);

		await screen.findAllByText("Withheld by silent mode");
		expect(screen.queryByText("Handed to mail server")).toBeNull();
	});

	it("does not leave a previous success visible after the next request fails", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
			http.post(
				"*/admin/email/test",
				() => HttpResponse.json({ outcome: "SENT", to: "ops@example.org" }),
				{ once: true },
			),
			http.post("*/admin/email/test", () =>
				HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
			),
		);
		renderRouteAt("/admin/settings");

		const send = await screen.findByRole("button", { name: "Send test email" }, ROUTE_RENDER_WAIT);
		await user.click(send);
		await screen.findByText("Handed to mail server");
		await user.click(send);

		await screen.findByText("Unavailable");
		expect(screen.queryByText("Handed to mail server")).toBeNull();
	});

	it("can test email when the instance settings cannot be loaded", async () => {
		const user = userEvent.setup();
		server.use(
			http.get("*/admin/settings", () =>
				HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
			),
			http.post("*/admin/email/test", () =>
				HttpResponse.json({ outcome: "SENT", to: "ops@example.org" }),
			),
		);
		renderRouteAt("/admin/settings");

		await screen.findByText("We could not load instance settings", undefined, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("button", { name: "Send test email" }));

		await screen.findByText("Handed to mail server");
		expect(screen.queryByRole("button", { name: "Turn off silent mode…" })).toBeNull();
	});

	it("allows public activity pages for the instance, and asks for a fresh sign-in when refused", async () => {
		const user = userEvent.setup();
		const puts: unknown[] = [];
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
			http.get("*/admin/settings/public-activity", () => HttpResponse.json({ allowed: false })),
			http.put("*/admin/settings/public-activity", async ({ request }) => {
				puts.push(await request.json());
				return HttpResponse.json(
					{ status: 403, code: "step_up_required", maxAgeSeconds: 300 },
					{ status: 403 },
				);
			}),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("switch", { name: "Allow public activity pages" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() => expect(puts).toStrictEqual([{ allowed: true }]));
		await screen.findByRole("dialog", { name: "Confirm access" });
	});

	it("shows the instance's choice after it is allowed", async () => {
		const user = userEvent.setup();
		let allowed = false;
		server.use(
			http.get("*/admin/settings", () => HttpResponse.json(settings(1))),
			http.get("*/admin/settings/public-activity", () => HttpResponse.json({ allowed })),
			http.put("*/admin/settings/public-activity", () => {
				allowed = true;
				return HttpResponse.json({ allowed });
			}),
		);
		renderRouteAt("/admin/settings");

		await user.click(
			await screen.findByRole("switch", { name: "Allow public activity pages" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(
				screen
					.getByRole("switch", { name: "Allow public activity pages" })
					.getAttribute("aria-checked"),
			).toBe("true"),
		);
	});

	it("withdraws a refused public activity setting, also when an earlier change is accepted, and shows only a fresh read", async () => {
		const policy = servePolicy({ status: 200, body: { allowed: false } });
		const accepted = deferred();
		const puts: unknown[] = [];
		server.use(
			http.put("*/admin/settings/public-activity", async ({ request }) => {
				puts.push(await request.json());
				await accepted.promise;
				return HttpResponse.json({ allowed: true });
			}),
		);
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin/settings");
		try {
			await user.click(
				await screen.findByRole(
					"switch",
					{ name: "Allow public activity pages" },
					ROUTE_RENDER_WAIT,
				),
			);
			await waitFor(() => expect(puts).toStrictEqual([{ allowed: true }]));

			policy.answer = { status: 403 };
			await rereadPolicy(queryClient);
			expect(policy.count).toBe(2);
			await screen.findByText("You do not have access to the public activity setting");
			expect(screen.queryByRole("switch", { name: "Allow public activity pages" })).toBeNull();
			expect(screen.queryByText("Off. No workspace can publish a page.")).toBeNull();
			expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

			accepted.resolve();
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(screen.queryByRole("switch", { name: "Allow public activity pages" })).toBeNull();
			screen.getByText("You do not have access to the public activity setting");

			policy.answer = { status: 503 };
			await rereadPolicy(queryClient);
			await screen.findByText("We could not load the public activity setting");
			expect(screen.queryByRole("switch", { name: "Allow public activity pages" })).toBeNull();
			expect(screen.queryByText("This setting may be out of date")).toBeNull();

			const readsBeforeRetry = policy.count;
			policy.answer = { status: 200, body: { allowed: true } };
			await user.click(screen.getByRole("button", { name: "Retry" }));
			const fresh = await screen.findByRole("switch", { name: "Allow public activity pages" });
			expect(fresh.getAttribute("aria-checked")).toBe("true");
			screen.getByText("On. Workspaces can publish their page.");
			expect(policy.count).toBe(readsBeforeRetry + 1);
			expect(puts).toHaveLength(1);
		} finally {
			accepted.resolve();
		}
	});

	it("keeps the last public activity setting beside a failed refresh, and shows the next read", async () => {
		const policy = servePolicy({ status: 200, body: { allowed: true } });
		const puts: unknown[] = [];
		server.use(
			http.put("*/admin/settings/public-activity", async ({ request }) => {
				puts.push(await request.json());
				return HttpResponse.json({ allowed: false });
			}),
		);
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin/settings");
		const loaded = await screen.findByRole(
			"switch",
			{ name: "Allow public activity pages" },
			ROUTE_RENDER_WAIT,
		);
		expect(loaded.getAttribute("aria-checked")).toBe("true");

		policy.answer = { status: 503 };
		await rereadPolicy(queryClient);
		expect(policy.count).toBe(2);
		await screen.findByText("This setting may be out of date");
		expect(
			screen
				.getByRole("switch", { name: "Allow public activity pages" })
				.getAttribute("aria-checked"),
		).toBe("true");
		screen.getByText("On. Workspaces can publish their page.");

		policy.answer = { status: 200, body: { allowed: false } };
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByText("Off. No workspace can publish a page.");
		expect(screen.queryByText("This setting may be out of date")).toBeNull();
		expect(policy.count).toBe(3);
		expect(puts).toStrictEqual([]);
	});

	it.each([
		{
			status: 403 as const,
			title: "You do not have access to the public activity setting",
			retry: false,
		},
		{ status: 503 as const, title: "We could not load the public activity setting", retry: true },
	])(
		"offers no public activity switch when the first read answers $status",
		async ({ status, title, retry }) => {
			servePolicy({ status });
			renderRouteAt("/admin/settings");

			await screen.findByText(title, undefined, ROUTE_RENDER_WAIT);
			expect(screen.queryByRole("switch", { name: "Allow public activity pages" })).toBeNull();
			expect(screen.queryByRole("button", { name: "Retry" }) !== null).toBe(retry);
		},
	);
});

const off = (revision: number, accountId: number) => ({
	silentModeEngaged: false,
	etag: `"${revision}"`,
	silentModeChangedAt: "2026-08-04T09:00:00Z",
	silentModeChangedByAccountId: accountId,
});

type SettingsAnswer =
	| { status: 200; body: ReturnType<typeof settings> | ReturnType<typeof off> }
	| { status: 403 | 503 };

/** Answers the instance settings GET with the answer the test last chose, counting each read. */
function serveSettings(initial: SettingsAnswer) {
	const read = { count: 0, answer: initial };
	server.use(
		http.get("*/admin/settings", () => {
			read.count += 1;
			const { answer } = read;
			return answer.status === 200
				? HttpResponse.json(answer.body)
				: HttpResponse.json(
						{ status: answer.status, title: "Request failed" },
						{ status: answer.status },
					);
		}),
		http.get("*/admin/settings/public-activity", () => HttpResponse.json({ allowed: false })),
	);
	return read;
}

/** Reads the instance settings again through their generated key, as a returning admin does. */
async function rereadSettings(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey: adminGetInstanceSettingsQueryKey() });
	});
}

/** Nothing the refused `settings(…)` read said, and no control that acts on it, stays on screen. */
function expectSettingsWithdrawn() {
	expect(screen.queryByText("Silent mode is on: workspace delivery is blocked")).toBeNull();
	expect(screen.queryAllByText(/incident/u)).toStrictEqual([]);
	expect(screen.queryAllByText(/account #42/u)).toStrictEqual([]);
	expect(screen.queryByRole("button", { name: /silent mode…/u })).toBeNull();
	expect(screen.queryByRole("alertdialog")).toBeNull();
	expect(screen.queryByRole("dialog")).toBeNull();
}

describe("silent mode settings read", () => {
	it("withdraws refused settings from the page and the banner, also through a later outage, and shows only a fresh read", async () => {
		const read = serveSettings({ status: 200, body: settings(1) });
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin/settings");
		await user.click(
			await screen.findByRole("button", { name: "Turn off silent mode…" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByRole("alertdialog");
		screen.getByText("Silent mode is on: workspace delivery is blocked");

		read.answer = { status: 403 };
		await rereadSettings(queryClient);
		expect(read.count).toBe(2);
		await screen.findByText("You do not have access to instance settings");
		expectSettingsWithdrawn();
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
		screen.getByRole("button", { name: "Send test email" });
		await screen.findByRole("switch", { name: "Allow public activity pages" });

		read.answer = { status: 503 };
		await rereadSettings(queryClient);
		expect(read.count).toBe(3);
		await screen.findByText("We could not load instance settings");
		expectSettingsWithdrawn();
		expect(screen.queryByText("We could not verify the current instance settings")).toBeNull();

		read.answer = { status: 200, body: off(2, 7) };
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("button", { name: "Turn on silent mode…" });
		screen.getByText(/last changed by account #7/u);
		expect(screen.queryByText("Silent mode is on: workspace delivery is blocked")).toBeNull();
		expect(read.count).toBe(4);
	});

	it("withdraws a refused banner on another admin page, also through a later outage", async () => {
		const read = serveSettings({ status: 200, body: settings(1) });
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin/users");
		await screen.findByText(
			"Silent mode is on: workspace delivery is blocked",
			undefined,
			ROUTE_RENDER_WAIT,
		);

		read.answer = { status: 403 };
		await rereadSettings(queryClient);
		await screen.findByText("You do not have access to the instance delivery state");
		expectSettingsWithdrawn();
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

		read.answer = { status: 503 };
		await rereadSettings(queryClient);
		await screen.findByText("We could not load the instance delivery state");
		expectSettingsWithdrawn();

		read.answer = { status: 200, body: settings(2) };
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByText("Silent mode is on: workspace delivery is blocked");
		expect(read.count).toBe(4);
	});

	it("keeps refused settings refused when an earlier change is accepted, and shows only a fresh read", async () => {
		const read = serveSettings({ status: 200, body: off(1, 7) });
		const accepted = deferred();
		const patches: unknown[] = [];
		server.use(
			http.patch("*/admin/settings/silent-mode", async ({ request }) => {
				patches.push(await request.json());
				await accepted.promise;
				return HttpResponse.json(settings(2));
			}),
		);
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin/settings");
		try {
			await user.click(
				await screen.findByRole("button", { name: "Turn on silent mode…" }, ROUTE_RENDER_WAIT),
			);
			const dialog = await screen.findByRole("dialog", { name: "Turn on silent mode" });
			await user.type(
				within(dialog).getByLabelText("Why are you silencing the instance?"),
				"incident",
			);
			await user.click(within(dialog).getByRole("button", { name: "Turn on silent mode" }));
			await waitFor(() => expect(patches).toStrictEqual([{ engaged: true, reason: "incident" }]));

			read.answer = { status: 403 };
			await rereadSettings(queryClient);
			await screen.findByText("You do not have access to instance settings");
			expectSettingsWithdrawn();

			accepted.resolve();
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expectSettingsWithdrawn();
			screen.getByText("You do not have access to instance settings");

			read.answer = { status: 200, body: settings(3) };
			await rereadSettings(queryClient);
			await screen.findByText("Silent mode is on: workspace delivery is blocked");
			await screen.findByRole("button", { name: "Turn off silent mode…" });
			expect(patches).toHaveLength(1);
		} finally {
			accepted.resolve();
		}
	});

	it("keeps the last settings, a drafted reason and Turn on beside a failed refresh", async () => {
		const read = serveSettings({ status: 200, body: off(1, 7) });
		const patches: unknown[] = [];
		server.use(
			http.patch("*/admin/settings/silent-mode", async ({ request }) => {
				patches.push(await request.json());
				read.answer = { status: 200, body: settings(2) };
				return HttpResponse.json(settings(2));
			}),
		);
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin/settings");
		await user.click(
			await screen.findByRole("button", { name: "Turn on silent mode…" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("dialog", { name: "Turn on silent mode" });
		const reason = within(dialog).getByLabelText<HTMLTextAreaElement>(
			"Why are you silencing the instance?",
		);
		await user.type(reason, "outage");

		read.answer = { status: 503 };
		await rereadSettings(queryClient);
		expect(read.count).toBe(2);
		await screen.findByText("We could not verify the current instance settings");
		// The open modal dialog sets aria-hidden on the page behind it, which includes its Retry.
		screen.getByRole("button", { name: "Retry", hidden: true });
		screen.getByText(/last changed by account #7/u);
		expect(reason.value).toBe("outage");

		await user.click(within(dialog).getByRole("button", { name: "Turn on silent mode" }));
		await screen.findByText("Silent mode is on: workspace delivery is blocked");
		expect(patches).toStrictEqual([{ engaged: true, reason: "outage" }]);
	});
});
