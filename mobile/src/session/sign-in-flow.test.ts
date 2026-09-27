import { describe, expect, it, vi } from "vitest";

import { runSignIn, type SignInSteps } from "./sign-in-flow";

const CALLBACK = "build.hephaestus.app:/auth/callback";
const PKCE = { verifier: "v".repeat(43), challenge: "c", state: "s" };

function steps(overrides: Partial<SignInSteps> = {}): SignInSteps {
	return {
		redirectUri: () => CALLBACK,
		createPkce: async () => PKCE,
		signInUrl: () => "https://hephaestus.example/auth/native",
		authenticate: async () => `${CALLBACK}?code=abc&state=s`,
		redeem: async () => true,
		...overrides,
	};
}

describe("runSignIn", () => {
	it("redeems the returned code with this sign-in's verifier", async () => {
		const redeem = vi.fn<SignInSteps["redeem"]>(async () => true);
		await expect(runSignIn(steps({ redeem }))).resolves.toStrictEqual({ kind: "signedIn" });
		expect(redeem).toHaveBeenCalledWith("abc", PKCE.verifier);
	});

	it.each([
		[
			"the callback address cannot be built",
			{
				redirectUri: () => {
					throw new Error("no scheme");
				},
			},
		],
		[
			"the platform's randomness fails",
			{
				createPkce: async () => {
					throw new Error("crypto");
				},
			},
		],
		[
			"the authentication sheet cannot open",
			{
				authenticate: async () => {
					throw new Error("no presenting window");
				},
			},
		],
		[
			"the code cannot be redeemed",
			{
				redeem: async () => {
					throw new Error("offline");
				},
			},
		],
	] satisfies [string, Partial<SignInSteps>][])(
		"settles as failed when %s, so the screen stops waiting",
		async (_case, override) => {
			await expect(runSignIn(steps(override))).resolves.toStrictEqual({
				kind: "failed",
				message: "Signing in did not finish. Try again.",
			});
		},
	);

	it("is cancelled when the person closes the sheet or declines at the provider", async () => {
		await expect(runSignIn(steps({ authenticate: async () => undefined }))).resolves.toStrictEqual({
			kind: "cancelled",
		});
		await expect(
			runSignIn(steps({ authenticate: async () => `${CALLBACK}?error=access_denied&state=s` })),
		).resolves.toStrictEqual({ kind: "cancelled" });
	});

	it("acts on no callback carrying another sign-in's state", async () => {
		const redeem = vi.fn<SignInSteps["redeem"]>(async () => true);
		await expect(
			runSignIn(steps({ authenticate: async () => `${CALLBACK}?code=abc&state=other`, redeem })),
		).resolves.toMatchObject({ kind: "failed" });
		expect(redeem).not.toHaveBeenCalled();
	});
});
