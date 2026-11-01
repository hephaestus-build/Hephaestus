import { describe, expect, it, vi } from "vitest";
import { problemType } from "@/lib/problem-detail";
import { deferred } from "@/test/async";
import { passkeyChallengeHandler, passkeyLeaveTo, passkeyReturnTo } from "./passkey-challenge";

describe("passkeyReturnTo", () => {
	it("returns to the exact local page, with its search and fragment", () => {
		expect(passkeyReturnTo("/w/acme/admin/integrations?tab=slack#channels")).toBe(
			"/w/acme/admin/integrations?tab=slack#channels",
		);
	});
	it.each([
		["another site", "https://evil.example/admin"],
		["a protocol-relative address", "//evil.example/admin"],
		["an encoded protocol-relative address", "%2F%2Fevil.example"],
		["a missing destination", undefined],
	])("falls back to home for %s", (_case, returnTo) => {
		expect(passkeyReturnTo(returnTo)).toBe("/");
	});
	it("never returns to the challenge, which would only ask again", () => {
		expect(passkeyReturnTo("/verify-passkey?returnTo=%2Fadmin")).toBe("/");
	});
});

describe("passkeyLeaveTo", () => {
	it.each([
		["a workspace administration page", "/w/tutors/admin/integrations?tab=slack", "/w/tutors"],
		["an ordinary workspace page", "/w/tutors/teams#members", "/w/tutors"],
		["instance administration", "/admin/users", "/"],
		["another site", "//evil.example/w/tutors", "/"],
		["a missing destination", undefined, "/"],
	])("leaves %s for its workspace home, never the refused page", (_case, returnTo, home) => {
		expect(passkeyLeaveTo(returnTo)).toBe(home);
	});
});

describe("passkeyChallengeHandler", () => {
	it("navigates for a passkey requirement without consuming the response", async () => {
		const handlePasskeyChallenge = passkeyChallengeHandler();
		const navigate = vi.fn().mockResolvedValue(undefined);
		const response = Response.json({ type: problemType("passkey-required") }, { status: 403 });
		await handlePasskeyChallenge(response, navigate);
		expect(navigate).toHaveBeenCalledOnce();
		await expect(response.json()).resolves.toStrictEqual({ type: problemType("passkey-required") });
	});
	it.each([
		{ status: 401, body: JSON.stringify({ type: problemType("passkey-required") }) },
		{ status: 403, body: JSON.stringify({ type: problemType("step-up-required") }) },
		{ status: 403, body: "invalid JSON" },
	])("does not navigate for $status with $body", async ({ status, body }) => {
		const navigate = vi.fn().mockResolvedValue(undefined);
		await passkeyChallengeHandler()(new Response(body, { status }), navigate);
		expect(navigate).not.toHaveBeenCalled();
	});
	it("shares navigation while a draft blocker is pending", async () => {
		const handlePasskeyChallenge = passkeyChallengeHandler();
		const decision = deferred();
		const navigate = vi.fn(async () => decision.promise);
		const response = Response.json({ type: problemType("passkey-required") }, { status: 403 });
		const first = handlePasskeyChallenge(response, navigate);
		const second = handlePasskeyChallenge(response, navigate);
		await vi.waitFor(() => expect(navigate).toHaveBeenCalledOnce());
		decision.resolve();
		await Promise.all([first, second]);
	});
	it("does not hold another installation's refusal behind a navigation that never finishes", async () => {
		const abandoned = passkeyChallengeHandler();
		void abandoned(
			Response.json({ type: problemType("passkey-required") }, { status: 403 }),
			vi.fn(async () => deferred().promise),
		);
		const navigate = vi.fn().mockResolvedValue(undefined);
		await passkeyChallengeHandler()(
			Response.json({ type: problemType("passkey-required") }, { status: 403 }),
			navigate,
		);
		expect(navigate).toHaveBeenCalledOnce();
	});
});
