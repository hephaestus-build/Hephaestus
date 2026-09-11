import type { APIRequestContext, BrowserContext, Page } from "@playwright/test";
import { z } from "zod";

import { expect, loginAsDevAdmin, test } from "./fixtures";

const serverUrl = process.env.E2E_SERVER_URL ?? "http://localhost:8080";
const userSchema = z.looseObject({ accessTokenExpiresAt: z.number() });

async function accessCookie(context: BrowserContext, name = "HEPHAESTUS_AT") {
	const cookie = (await context.cookies(serverUrl)).find((value) => value.name === name);
	expect(cookie, "dev login must issue an HttpOnly access cookie").toBeDefined();
	if (!cookie) throw new Error("Missing session cookie");
	return cookie;
}

function waitForRefresh(page: Page, status: number) {
	return page.waitForResponse(
		(response) => response.url().endsWith("/auth/refresh") && response.status() === status,
	);
}

// Advance only the SPA schedule; JWT expiry and revocation use the server clock.
async function scheduleRenewal(page: Page) {
	await page.clock.install();
	await page.route(
		"**/user",
		async (route) => {
			const response = await route.fetch();
			const user = userSchema.parse(await response.json());
			await route.fulfill({
				response,
				json: { ...user, accessTokenExpiresAt: Math.floor(Date.now() / 1000) + 120 },
			});
		},
		{ times: 1 },
	);
	await page.goto("/settings");
	await expect(page.getByRole("heading", { name: "User settings", exact: true })).toBeVisible();
}

async function holdRenewal(page: Page, request: APIRequestContext) {
	let markProcessed: (() => void) | undefined;
	let releaseResponse: (() => void) | undefined;
	const processed = new Promise<void>((resolve) => {
		markProcessed = resolve;
	});
	const release = new Promise<void>((resolve) => {
		releaseResponse = resolve;
	});
	await page.route(
		"**/auth/refresh",
		async (route) => {
			// The isolated API context holds Set-Cookie back from the browser until fulfillment.
			const response = await request.fetch(route.request());
			expect(response.status()).toBe(204);
			markProcessed?.();
			await release;
			await route.fulfill({ response });
		},
		{ times: 1 },
	);
	return { processed, release: () => releaseResponse?.() };
}

test("a session persists across browser contexts with a full-day cookie lifetime", async ({
	page,
	browser,
	context,
}) => {
	await loginAsDevAdmin(page, "session-lifecycle");
	const cookie = await accessCookie(context);
	expect(cookie.httpOnly).toBe(true);
	expect(cookie.sameSite).toBe("Lax");
	expect(cookie.path).toBe("/");
	expect(cookie.expires - Date.now() / 1000).toBeGreaterThan(23 * 60 * 60);
	const restored = await browser.newContext({ storageState: await context.storageState() });
	try {
		const response = await restored.request.get(`${serverUrl}/user`);
		expect(response.status()).toBe(200);
	} finally {
		await restored.close();
	}
});

test("two tabs renew without losing their shared session and old tokens stay revoked", async ({
	page,
	context,
}) => {
	await loginAsDevAdmin(page, "session-tabs");
	const oldCookie = await accessCookie(context);
	const other = await context.newPage();
	await Promise.all([scheduleRenewal(page), scheduleRenewal(other)]);
	const refreshed = [waitForRefresh(page, 204), waitForRefresh(other, 204)];
	await Promise.all([page.clock.fastForward(61_000), other.clock.fastForward(61_000)]);
	await Promise.all(refreshed);
	expect((await context.request.get(`${serverUrl}/user`)).status()).toBe(200);
	expect(
		(
			await context.request.get(`${serverUrl}/user`, {
				headers: { Cookie: `${oldCookie.name}=${oldCookie.value}` },
			})
		).status(),
	).toBe(401);
	await Promise.all([page.reload(), other.reload()]);
	await expect(page).toHaveURL(/\/settings$/);
	await expect(other).toHaveURL(/\/settings$/);
});

test("a temporary renewal outage preserves the page and later activity recovers", async ({
	page,
	context,
}) => {
	await loginAsDevAdmin(page, "session-outage");
	await page.route("**/auth/refresh", (route) => route.fulfill({ status: 503 }), { times: 1 });
	await scheduleRenewal(page);
	const failed = waitForRefresh(page, 503);
	await page.clock.fastForward(61_000);
	await failed;
	await expect(page).toHaveURL(/\/settings$/);
	expect((await context.request.get(`${serverUrl}/user`)).status()).toBe(200);
	const renewed = waitForRefresh(page, 204);
	await page.clock.fastForward(11_000);
	await page.mouse.click(5, 5);
	await renewed;
	await expect(page).toHaveURL(/\/settings$/);
	expect((await context.request.get(`${serverUrl}/user`)).status()).toBe(200);
});

test("logout requires CSRF protection and revoked sessions cannot renew", async ({
	page,
	context,
}) => {
	await loginAsDevAdmin(page, "session-logout");
	const signedInCookie = await accessCookie(context);
	await scheduleRenewal(page);
	expect((await context.request.post(`${serverUrl}/auth/logout`)).status()).toBe(403);
	expect((await context.request.get(`${serverUrl}/user`)).status()).toBe(200);
	const csrf = await accessCookie(context, "XSRF-TOKEN");
	const headers = { "X-XSRF-TOKEN": decodeURIComponent(csrf.value) };
	expect((await context.request.post(`${serverUrl}/auth/logout`, { headers })).status()).toBe(204);
	expect(
		(
			await context.request.get(`${serverUrl}/user`, {
				headers: { Cookie: `${signedInCookie.name}=${signedInCookie.value}` },
			})
		).status(),
	).toBe(401);
	expect(
		(
			await context.request.get(`${serverUrl}/identity-providers`, {
				headers: { Cookie: `${signedInCookie.name}=${signedInCookie.value}` },
			})
		).status(),
	).toBe(200);
	const renewalCsrf = await accessCookie(context, "XSRF-TOKEN");
	expect(
		(
			await context.request.post(`${serverUrl}/auth/refresh`, {
				headers: { "X-XSRF-TOKEN": decodeURIComponent(renewalCsrf.value) },
			})
		).status(),
	).toBe(401);
	await page.clock.fastForward(61_000);
	await expect(page).toHaveURL(/\/login/);
});

test("signing out in another tab cannot be undone by a delayed renewal response", async ({
	page,
	context,
	request,
}) => {
	await loginAsDevAdmin(page, "session-logout-race");
	const held = await holdRenewal(page, request);
	await scheduleRenewal(page);
	const other = await context.newPage();
	await other.goto("/settings");
	await expect(other.getByRole("heading", { name: "User settings", exact: true })).toBeVisible();
	await page.clock.fastForward(61_000);
	await held.processed;
	try {
		await other.getByRole("button", { name: "Account", exact: true }).click();
		await other.getByRole("menuitem", { name: "Sign Out", exact: true }).click();
	} finally {
		held.release();
	}
	await expect(other).toHaveURL((url) => url.pathname === "/");
	expect((await context.request.get(`${serverUrl}/user`)).status()).toBe(401);
});

test("failed sign-out reports the failure and allows a successful retry", async ({
	page,
	context,
}) => {
	await loginAsDevAdmin(page, "session-logout-retry");
	await page.goto("/settings");
	await page.route("**/auth/logout", (route) => route.fulfill({ status: 503 }), { times: 1 });
	await page.getByRole("button", { name: "Account", exact: true }).click();
	await page.getByRole("menuitem", { name: "Sign Out", exact: true }).click();
	await expect(page.getByText("Could not confirm sign-out. Please try again.")).toBeVisible();
	await expect(page).toHaveURL(/\/settings$/);
	expect((await context.request.get(`${serverUrl}/user`)).status()).toBe(200);
	await page.getByRole("button", { name: "Account", exact: true }).click();
	await page.getByRole("menuitem", { name: "Sign Out", exact: true }).click();
	await expect(page).toHaveURL((url) => url.pathname === "/");
	expect((await context.request.get(`${serverUrl}/user`)).status()).toBe(401);
});

test("a cold identity outage shows the existing retry screen instead of signing out", async ({
	page,
}) => {
	await loginAsDevAdmin(page, "session-cold-outage");
	await page.route("**/user", (route) => route.fulfill({ status: 503 }));
	await page.goto("/settings");
	await expect(
		page.getByRole("heading", { name: "Something went wrong", exact: true }),
	).toBeVisible();
	await expect(page).toHaveURL(/\/settings$/);
	await page.unroute("**/user");
	await page.getByRole("button", { name: "Try again", exact: true }).click();
	await expect(page.getByRole("heading", { name: "User settings", exact: true })).toBeVisible();
});

test("exiting impersonation waits for a delayed renewal and restores the operator", async ({
	page,
	context,
	browser,
	request,
}) => {
	const target = await browser.newContext();
	let targetId: number;
	try {
		expect(
			(
				await target.request.post(`${serverUrl}/auth/dev-login`, {
					data: { username: "session-impersonated", admin: false },
				})
			).status(),
		).toBe(204);
		targetId = z
			.object({ id: z.number() })
			.parse(await (await target.request.get(`${serverUrl}/user`)).json()).id;
		const notice = z
			.object({ noticeVersion: z.string(), researchOrganization: z.string().optional() })
			.parse(await (await target.request.get(`${serverUrl}/user/consent`)).json());
		// This instance names no research organisation, so setup is the terms alone and a research
		// answer would be an answer to a question it never put.
		expect(notice.researchOrganization).toBeUndefined();
		const targetCsrf = await accessCookie(target, "XSRF-TOKEN");
		expect(
			(
				await target.request.put(`${serverUrl}/user/consent`, {
					headers: { "X-XSRF-TOKEN": decodeURIComponent(targetCsrf.value) },
					data: { noticeVersion: notice.noticeVersion, termsAccepted: true },
				})
			).status(),
		).toBe(200);
	} finally {
		await target.close();
	}
	await loginAsDevAdmin(page, "session-operator");
	const operator = z
		.object({ id: z.number() })
		.parse(await (await context.request.get(`${serverUrl}/user`)).json());
	const csrf = await accessCookie(context, "XSRF-TOKEN");
	expect(
		(
			await context.request.post(`${serverUrl}/auth/impersonate`, {
				headers: { "X-XSRF-TOKEN": decodeURIComponent(csrf.value) },
				data: { targetAccountId: targetId, reason: "Verify session transition isolation" },
			})
		).status(),
	).toBe(204);
	const held = await holdRenewal(page, request);
	await scheduleRenewal(page);
	await page.clock.fastForward(61_000);
	await held.processed;
	try {
		await page
			.getByRole("button", { name: "Stop impersonating and restore your account", exact: true })
			.click();
	} finally {
		held.release();
	}
	await expect(
		page.getByRole("button", { name: "Stop impersonating and restore your account", exact: true }),
	).toBeHidden();
	const restored = z
		.object({ id: z.number(), impersonating: z.boolean() })
		.parse(await (await context.request.get(`${serverUrl}/user`)).json());
	expect(restored).toMatchObject({ id: operator.id, impersonating: false });
});
