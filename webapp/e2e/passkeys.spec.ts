import { randomUUID } from "node:crypto";

import type { BrowserContext, Locator, Page } from "@playwright/test";
import { z } from "zod";

import { problemType } from "@/lib/problem-detail";

import { expect, loginAsDevAdmin, test } from "./fixtures";
import { E2E_BASE_URL } from "./urls";

const serverUrl = process.env.E2E_SERVER_URL ?? "http://localhost:8080";
const statusSchema = z.object({
	protectionEnabled: z.boolean(),
	recoveryRequired: z.boolean(),
	verified: z.boolean(),
	credentials: z.array(z.object({ id: z.string(), label: z.string() })),
});

async function passkeyStatus(context: BrowserContext) {
	const response = await context.request.get(`${serverUrl}/user/passkeys`);
	expect(response.status()).toBe(200);
	const body: unknown = await response.json();
	return statusSchema.parse(body);
}

async function csrfHeaders(context: BrowserContext) {
	const cookies = await context.cookies(serverUrl);
	const csrf = cookies.find((cookie) => cookie.name === "XSRF-TOKEN");
	if (!csrf) {
		throw new Error("The real sign-in must issue a CSRF cookie");
	}
	return { "X-XSRF-TOKEN": decodeURIComponent(csrf.value) };
}

async function expectAdminAccess(context: BrowserContext, allowed: boolean) {
	const response = await context.request.get(`${serverUrl}/admin/users?page=0&size=1`);
	expect(response.status()).toBe(allowed ? 200 : 403);
	if (!allowed) {
		const body: unknown = await response.json();
		z.object({ type: z.literal(problemType("passkey-required")) }).parse(body);
	}
}

async function submitFromSettings(
	page: Page,
	path: string,
	button: string,
	status = 200,
	scope: Page | Locator = page,
) {
	const [response] = await Promise.all([
		page.waitForResponse(
			(value) =>
				value.request().method() === "POST" && value.url() === `${serverUrl}/user/passkeys${path}`,
		),
		scope.getByRole("button", { name: button, exact: true }).click(),
	]);
	expect(response.status()).toBe(status);
	// The mutation must settle before starting another browser ceremony.
	await expect(page.getByRole("region", { name: "Passkeys", exact: true })).toHaveAttribute(
		"aria-busy",
		"false",
	);
}

// Use a new account: personal protection must not change the shared E2E administrator's session.
test("real user verification grants admin access and recovery requires re-enrollment", async ({
	page,
	context,
}) => {
	for (const url of [serverUrl, E2E_BASE_URL]) {
		expect(new URL(url).hostname, "Use only the disposable localhost E2E server").toBe("localhost");
	}
	const username = `passkey-${randomUUID()}`;
	const cdp = await context.newCDPSession(page);
	await cdp.send("WebAuthn.enable", { enableUI: false });
	const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", {
		options: {
			protocol: "ctap2",
			transport: "internal",
			hasResidentKey: true,
			hasUserVerification: true,
			isUserVerified: true,
			automaticPresenceSimulation: true,
		},
	});
	try {
		await loginAsDevAdmin(page, username);
		await page.goto("/settings");
		const initial = await passkeyStatus(context);
		expect(initial.verified).toBe(false);
		expect(initial.credentials).toHaveLength(0);

		await page.getByLabel("Passkey name", { exact: true }).fill("Browser proof key");
		await submitFromSettings(page, "", "Register passkey");
		const enrolled = await passkeyStatus(context);
		expect(enrolled).toMatchObject({ protectionEnabled: true, verified: false });
		expect(enrolled.credentials).toHaveLength(1);
		await expectAdminAccess(context, false);
		const unverifiedProtection = await context.request.patch(
			`${serverUrl}/user/passkeys/protection`,
			{
				headers: await csrfHeaders(context),
				data: { enabled: true },
			},
		);
		expect(unverifiedProtection.status()).toBe(403);
		const unverifiedBody: unknown = await unverifiedProtection.json();
		z.object({ type: z.literal(problemType("passkey-required")) }).parse(unverifiedBody);

		// Chrome signs a response whose UV bit is absent; the real server must reject it.
		await cdp.send("WebAuthn.setResponseOverrideBits", { authenticatorId, isBadUV: true });
		await submitFromSettings(page, "/verification", "Verify passkey", 400);
		const rejectedStatus = await passkeyStatus(context);
		expect(rejectedStatus.verified).toBe(false);
		await expectAdminAccess(context, false);
		await cdp.send("WebAuthn.setResponseOverrideBits", { authenticatorId });

		await submitFromSettings(page, "/verification", "Verify passkey");
		expect(await passkeyStatus(context)).toMatchObject({ verified: true, recoveryRequired: false });
		const protection = await context.request.patch(`${serverUrl}/user/passkeys/protection`, {
			headers: await csrfHeaders(context),
			data: { enabled: true },
		});
		expect(protection.status()).toBe(200);
		await expectAdminAccess(context, true);
		const codesResponse = await context.request.post(`${serverUrl}/user/passkeys/recovery-codes`, {
			headers: await csrfHeaders(context),
		});
		expect(codesResponse.status()).toBe(200);
		const codesBody: unknown = await codesResponse.json();
		const { codes } = z.object({ codes: z.tuple([z.string()]).rest(z.string()) }).parse(codesBody);
		const [recoveryCode] = codes;

		await page.getByRole("button", { name: "Account", exact: true }).click();
		await page.getByRole("menuitem", { name: "Sign out", exact: true }).click();
		await expect(page).toHaveURL((url) => url.pathname === "/");
		await loginAsDevAdmin(page, username);
		await page.goto("/settings");
		const newSession = await passkeyStatus(context);
		expect(newSession).toMatchObject({ protectionEnabled: true, verified: false });
		expect(newSession.credentials).toHaveLength(1);
		await expectAdminAccess(context, false);
		await submitFromSettings(page, "/verification", "Verify passkey");
		const verifiedAgain = await passkeyStatus(context);
		expect(verifiedAgain.verified).toBe(true);
		await expectAdminAccess(context, true);

		await page.getByText("Recover passkeys", { exact: true }).click();
		await page.getByLabel("Recovery code", { exact: true }).fill(recoveryCode);
		await page.getByRole("button", { name: "Start recovery", exact: true }).click();
		await expect(page.getByRole("alertdialog", { name: "Start passkey recovery?" })).toBeVisible();
		await submitFromSettings(
			page,
			"/recovery",
			"Start recovery",
			200,
			page.getByRole("alertdialog", { name: "Start passkey recovery?" }),
		);
		const recovered = await passkeyStatus(context);
		expect(recovered).toMatchObject({ recoveryRequired: true, verified: false });
		expect(recovered.credentials).toHaveLength(0);
		await expectAdminAccess(context, false);
		const replay = await context.request.post(`${serverUrl}/user/passkeys/recovery`, {
			headers: await csrfHeaders(context),
			data: { code: recoveryCode },
		});
		expect(replay.status()).toBe(400);

		await page.getByLabel("Passkey name", { exact: true }).fill("Replacement browser key");
		await submitFromSettings(page, "", "Register passkey");
		const replacementStatus = await passkeyStatus(context);
		expect(replacementStatus.verified).toBe(false);
		await expectAdminAccess(context, false);
		await submitFromSettings(page, "/verification", "Verify passkey");
		const restored = await passkeyStatus(context);
		expect(restored).toMatchObject({ verified: true, recoveryRequired: false });
		expect(restored.credentials).toHaveLength(1);
		await expectAdminAccess(context, true);
	} finally {
		try {
			await cdp.send("WebAuthn.removeVirtualAuthenticator", { authenticatorId });
			await cdp.send("WebAuthn.disable");
		} finally {
			await cdp.detach();
		}
	}
});
