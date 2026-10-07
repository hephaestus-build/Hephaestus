import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { usePasskeySettings } from "./use-passkey-settings";

const challengeId = "ab265a5b-b502-4a23-b20b-c77e304a58c5";
const creation = {
	challenge: "Y2hhbGxlbmdl",
	rp: { id: "localhost", name: "Hephaestus" },
	user: { id: "dXNlcg", name: "account", displayName: "Account" },
	pubKeyCredParams: [{ type: "public-key", alg: -7 }],
	authenticatorSelection: { residentKey: "required", userVerification: "required" },
};
const assertion = {
	challenge: "Y2hhbGxlbmdl",
	rpId: "localhost",
	allowCredentials: [{ id: "Y3JlZGVudGlhbA", type: "public-key" }],
	userVerification: "required",
};
const signedCredential = {
	id: "Y3JlZGVudGlhbA",
	type: "public-key",
	response: { signature: "signed" },
};
class BrowserCredential {
	readonly payload = signedCredential;
	static parseCreationOptionsFromJSON(options: unknown) {
		return options;
	}
	static parseRequestOptionsFromJSON(options: unknown) {
		return options;
	}
	toJSON() {
		return this.payload;
	}
}
let sessionLocked = false;
let ceremonyLocked: boolean | undefined;
const create = vi.fn(async () => {
	ceremonyLocked = sessionLocked;
	return new BrowserCredential();
});
const get = vi.fn(async () => {
	ceremonyLocked = sessionLocked;
	return new BrowserCredential();
});

beforeEach(() => {
	sessionLocked = false;
	ceremonyLocked = undefined;
	create.mockClear();
	get.mockClear();
	vi.stubGlobal("PublicKeyCredential", BrowserCredential);
	vi.stubGlobal("navigator", {
		credentials: { create, get },
		locks: {
			request: async (_name: string, operation: () => Promise<unknown>) => {
				sessionLocked = true;
				try {
					return await operation();
				} finally {
					sessionLocked = false;
				}
			},
		},
	});
	server.use(
		http.get("*/user/passkeys", () =>
			HttpResponse.json({
				protectionEnabled: false,
				recoveryRequired: false,
				instanceAdminRequired: true,
				workspaceAdminRequired: false,
				verified: false,
				credentials: [],
			}),
		),
	);
});
afterEach(() => {
	vi.unstubAllGlobals();
});

function setup() {
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	function Wrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
	}
	return renderHook(() => usePasskeySettings(), { wrapper: Wrapper });
}

describe("usePasskeySettings", () => {
	it("registers a credential without starting a second browser ceremony", async () => {
		let registrations = 0;
		server.use(
			http.post("*/user/passkeys/registration-options", () => {
				expect(sessionLocked).toBe(false);
				return HttpResponse.json({ challengeId, optionsJson: JSON.stringify(creation) });
			}),
			http.post("*/user/passkeys", async ({ request }) => {
				expect(sessionLocked).toBe(true);
				await expect(request.json()).resolves.toStrictEqual({
					challengeId,
					label: "Security key",
					credentialJson: JSON.stringify(signedCredential),
				});
				registrations += 1;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.section.loading).toBe(false));
		act(() => result.current.section.onRegister("Security key"));
		await waitFor(() => expect(registrations).toBe(1));
		await waitFor(() => expect(result.current.section.pending).toBe(false));
		expect(create).toHaveBeenCalledOnce();
		expect(ceremonyLocked).toBe(false);
		expect(get).not.toHaveBeenCalled();
		expect(result.current.section.error).toBeUndefined();
	});
	it("verifies only on an explicit action and locks the cookie-changing completion", async () => {
		let verifications = 0;
		server.use(
			http.post("*/user/passkeys/verification-options", () => {
				expect(sessionLocked).toBe(false);
				return HttpResponse.json({ challengeId, optionsJson: JSON.stringify(assertion) });
			}),
			http.post("*/user/passkeys/verification", async ({ request }) => {
				expect(sessionLocked).toBe(true);
				await expect(request.json()).resolves.toStrictEqual({
					challengeId,
					credentialJson: JSON.stringify(signedCredential),
				});
				verifications += 1;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.section.loading).toBe(false));
		expect(get).not.toHaveBeenCalled();
		act(() => result.current.section.onVerify());
		await waitFor(() => expect(verifications).toBe(1));
		await waitFor(() => expect(result.current.section.pending).toBe(false));
		expect(get).toHaveBeenCalledOnce();
		expect(ceremonyLocked).toBe(false);
		expect(create).not.toHaveBeenCalled();
		expect(result.current.section.error).toBeUndefined();
	});
	it("ends a canceled browser ceremony without sending a verification", async () => {
		let verifications = 0;
		get.mockRejectedValueOnce(new DOMException("Canceled", "NotAllowedError"));
		server.use(
			http.post("*/user/passkeys/verification-options", () =>
				HttpResponse.json({ challengeId, optionsJson: JSON.stringify(assertion) }),
			),
			http.post("*/user/passkeys/verification", () => {
				verifications += 1;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.section.loading).toBe(false));
		act(() => result.current.section.onVerify());
		await waitFor(() => expect(result.current.section.error).toBeDefined());
		expect(result.current.section.pending).toBe(false);
		expect(verifications).toBe(0);
	});
});
