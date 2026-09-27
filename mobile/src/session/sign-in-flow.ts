import type { Pkce } from "./pkce";
import { parseSignInCallback, signInErrorMessage } from "./sign-in-url";

export type SignInResult =
	| { kind: "signedIn" }
	| { kind: "cancelled" }
	| { kind: "failed"; message: string };

/** The platform's part of one sign-in, supplied by `sign-in.ts` so the flow stays testable off-device. */
export interface SignInSteps {
	/** This build's callback address. */
	redirectUri: () => string;
	createPkce: () => Promise<Pkce>;
	/** The sign-in URL for this challenge, state and callback. */
	signInUrl: (pkce: Pkce, redirect: string) => string;
	/** Opens the system's authentication sheet; the address it returned to, or undefined if it did not. */
	authenticate: (url: string, redirect: string) => Promise<string | undefined>;
	/** Redeems the single-use code and starts the session; whether the session began. */
	redeem: (code: string, verifier: string) => Promise<boolean>;
}

const DID_NOT_FINISH: SignInResult = {
	kind: "failed",
	message: "Signing in did not finish. Try again.",
};

/**
 * One sign-in, from challenge to session. It always settles with a result a screen can show: a step
 * that throws — the platform's randomness, the authentication sheet, the network — ends the attempt as
 * failed rather than leaving the screen waiting on it.
 */
export async function runSignIn(steps: SignInSteps): Promise<SignInResult> {
	try {
		const callback = steps.redirectUri();
		const pkce = await steps.createPkce();
		const returned = await steps.authenticate(steps.signInUrl(pkce, callback), callback);
		if (returned === undefined) {
			return { kind: "cancelled" };
		}
		const parsed = parseSignInCallback(returned, callback, pkce.state);
		if (parsed.kind === "foreign") {
			return DID_NOT_FINISH;
		}
		if (parsed.kind === "error") {
			const message = signInErrorMessage(parsed.error);
			return message === null ? { kind: "cancelled" } : { kind: "failed", message };
		}
		return (await steps.redeem(parsed.code, pkce.verifier)) ? { kind: "signedIn" } : DID_NOT_FINISH;
	} catch {
		return DID_NOT_FINISH;
	}
}
