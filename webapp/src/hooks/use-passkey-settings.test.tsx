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
			request: async (_name: string, _options: LockOptions, operation: () => Promise<unknown>) => {
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
				assuranceEnforced: true,
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
	it("names a passkey added without a name by the date it was added", async () => {
		let registration: unknown;
		server.use(
			http.post("*/user/passkeys/registration-options", () =>
				HttpResponse.json({ challengeId, optionsJson: JSON.stringify(creation) }),
			),
			http.post("*/user/passkeys", async ({ request }) => {
				registration = await request.json();
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.section.loading).toBe(false));
		act(() => result.current.section.onRegister("  "));
		await waitFor(() => expect(result.current.completed).toBe("register"));
		expect(registration).toHaveProperty("label", expect.stringMatching(/^Passkey added \S/u));
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
	it("reads an expired confirmation again and reports a later verification failure", async () => {
		let fresh = true;
		const credentials = [
			{ id: "one", label: "Laptop" },
			{ id: "two", label: "Phone" },
		];
		server.use(
			http.get("*/user/passkeys", () =>
				HttpResponse.json({
					assuranceEnforced: true,
					protectionEnabled: true,
					recoveryRequired: false,
					instanceAdminRequired: true,
					workspaceAdminRequired: false,
					verified: fresh,
					credentials,
				}),
			),
			http.delete("*/user/passkeys/:credentialId", () => {
				// The confirmation aged out between the page's read and the press.
				fresh = false;
				return HttpResponse.json(
					{
						type: "about:blank",
						status: 403,
						title: "Confirm access with a passkey",
						detail:
							"This action requires passkey verification. Open your passkey settings to confirm access.",
						code: "passkey_required",
					},
					{ status: 403 },
				);
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.section.status?.verified).toBe(true));
		act(() => result.current.section.onRemove("two"));
		await waitFor(() =>
			expect(result.current.section.error).toBe(
				"Your passkey confirmation has expired. Verify your passkey again, then retry.",
			),
		);
		await waitFor(() => expect(result.current.section.status?.verified).toBe(false));
		expect(result.current.section.status?.credentials).toHaveLength(2);
		expect(result.current.confirmation.open).toBe(false);

		// The failed mutation stays recorded while verification options are requested. A newer
		// refusal must be shown in its place, without starting the browser or sending a proof.
		let verifications = 0;
		server.use(
			http.post("*/user/passkeys/verification-options", () =>
				HttpResponse.json(
					{ status: 503, detail: "Passkey verification is temporarily unavailable." },
					{ status: 503 },
				),
			),
			http.post("*/user/passkeys/verification", () => {
				verifications += 1;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		act(() => result.current.section.onVerify());
		await waitFor(() =>
			expect(result.current.section.error).toBe("Passkey verification is temporarily unavailable."),
		);
		expect(get).not.toHaveBeenCalled();
		expect(verifications).toBe(0);
		expect(result.current.section.status?.verified).toBe(false);
	});
	it("does not show invalid recovery codes after removing the last key and enrolling again", async () => {
		const codes = ["synthetic-recovery-code"];
		let status = {
			assuranceEnforced: true,
			protectionEnabled: false,
			recoveryRequired: false,
			instanceAdminRequired: true,
			workspaceAdminRequired: false,
			verified: true,
			credentials: [{ id: "one", label: "Laptop" }],
		};
		let removals = 0;
		server.use(
			http.get("*/user/passkeys", () => HttpResponse.json(status)),
			http.post("*/user/passkeys/recovery-codes", () => HttpResponse.json({ codes })),
			http.delete("*/user/passkeys/one", () => {
				removals += 1;
				status = { ...status, credentials: [], verified: false };
				return new HttpResponse(null, { status: 204 });
			}),
			http.post("*/user/passkeys/registration-options", () =>
				HttpResponse.json({ challengeId, optionsJson: JSON.stringify(creation) }),
			),
			http.post("*/user/passkeys", () => {
				status = {
					...status,
					assuranceEnforced: true,
					protectionEnabled: true,
					credentials: [{ id: "new", label: "Phone" }],
				};
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.section.status?.credentials).toHaveLength(1));
		act(() => result.current.section.onCreateRecoveryCodes());
		await waitFor(() => expect(result.current.section.recoveryCodes).toStrictEqual(codes));
		await waitFor(() => expect(result.current.section.pending).toBe(false));
		act(() => result.current.section.onRemove("one"));
		await waitFor(() => expect(result.current.section.status?.credentials).toHaveLength(0));
		await waitFor(() => expect(result.current.section.pending).toBe(false));
		act(() => result.current.section.onRegister("Phone"));
		await waitFor(() => expect(result.current.completed).toBe("register"));
		expect(result.current.section.status?.credentials).toStrictEqual([
			{ id: "new", label: "Phone" },
		]);
		expect(result.current.section.recoveryCodes).toBeUndefined();
		expect(removals).toBe(1);
	});
	it("keeps the displayed recovery codes when the last-key removal is refused", async () => {
		const codes = ["synthetic-recovery-code"];
		const status = {
			assuranceEnforced: true,
			protectionEnabled: false,
			recoveryRequired: false,
			instanceAdminRequired: true,
			workspaceAdminRequired: false,
			verified: true,
			credentials: [{ id: "one", label: "Laptop" }],
		};
		let removals = 0;
		server.use(
			http.get("*/user/passkeys", () => HttpResponse.json(status)),
			http.post("*/user/passkeys/recovery-codes", () => HttpResponse.json({ codes })),
			http.delete("*/user/passkeys/one", () => {
				removals += 1;
				return HttpResponse.json(
					{ status: 409, detail: "Personal protection is still on." },
					{ status: 409 },
				);
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.section.status?.credentials).toHaveLength(1));
		act(() => result.current.section.onCreateRecoveryCodes());
		await waitFor(() => expect(result.current.section.recoveryCodes).toStrictEqual(codes));
		await waitFor(() => expect(result.current.section.pending).toBe(false));
		act(() => result.current.section.onRemove("one"));
		await waitFor(() =>
			expect(result.current.section.error).toBe("Personal protection is still on."),
		);
		await waitFor(() => expect(result.current.section.pending).toBe(false));
		expect(result.current.section.status?.credentials).toStrictEqual(status.credentials);
		expect(result.current.section.recoveryCodes).toStrictEqual(codes);
		expect(removals).toBe(1);
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
