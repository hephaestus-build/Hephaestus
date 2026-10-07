import { afterEach, describe, expect, it, vi } from "vitest";
import { deferred } from "@/test/async";
import { handlePasskeyChallenge } from "./passkey-challenge";

const realLocation = window.location;
afterEach(() => {
	Object.defineProperty(window, "location", { configurable: true, value: realLocation });
});
function location(pathname: string) {
	Object.defineProperty(window, "location", { configurable: true, value: { pathname } });
}

describe("handlePasskeyChallenge", () => {
	it("navigates for a passkey requirement without consuming the response", async () => {
		location("/w/acme/admin/settings");
		const navigate = vi.fn().mockResolvedValue(undefined);
		const response = Response.json({ code: "passkey_required" }, { status: 403 });
		await handlePasskeyChallenge(response, navigate);
		expect(navigate).toHaveBeenCalledOnce();
		await expect(response.json()).resolves.toStrictEqual({ code: "passkey_required" });
	});
	it.each([
		{ status: 401, body: '{"code":"passkey_required"}', pathname: "/w/acme/admin/settings" },
		{ status: 403, body: '{"code":"access_denied"}', pathname: "/w/acme/admin/settings" },
		{ status: 403, body: "invalid JSON", pathname: "/w/acme/admin/settings" },
		{ status: 403, body: '{"code":"passkey_required"}', pathname: "/settings" },
	])(
		"does not navigate for $status at $pathname with $body",
		async ({ status, body, pathname }) => {
			location(pathname);
			const navigate = vi.fn().mockResolvedValue(undefined);
			await handlePasskeyChallenge(new Response(body, { status }), navigate);
			expect(navigate).not.toHaveBeenCalled();
		},
	);
	it("shares navigation while a draft blocker is pending", async () => {
		location("/w/acme/admin/settings");
		const decision = deferred();
		const navigate = vi.fn(async () => decision.promise);
		const response = Response.json({ code: "passkey_required" }, { status: 403 });
		const first = handlePasskeyChallenge(response, navigate);
		const second = handlePasskeyChallenge(response, navigate);
		await vi.waitFor(() => expect(navigate).toHaveBeenCalledOnce());
		decision.resolve();
		await Promise.all([first, second]);
	});
});
