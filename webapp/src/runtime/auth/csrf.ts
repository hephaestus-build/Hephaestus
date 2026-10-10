import { createClient } from "@/api/client/client.gen";
import { getCsrfToken } from "@/api/sdk.gen";
import environment from "@/environment";
import { isRecord } from "@/lib/is-record";

const csrfClient = createClient({ baseUrl: environment.serverUrl, credentials: "include" });
let tokenRequest: Promise<{ token: string; headerName: string }> | undefined;

async function loadToken(): Promise<{ token: string; headerName: string }> {
	const { data } = await getCsrfToken({ client: csrfClient, throwOnError: true });
	if (data.token === "" || data.headerName === "") {
		throw new Error("Could not prepare a secure request.");
	}
	return data;
}

async function settleToken(pending: Promise<{ token: string; headerName: string }>) {
	try {
		return await pending;
	} catch (error) {
		if (tokenRequest === pending) {
			tokenRequest = undefined;
		}
		throw error;
	}
}

export async function refetchCsrfToken(): Promise<{ token: string; headerName: string }> {
	tokenRequest = loadToken();
	return settleToken(tokenRequest);
}

async function isCsrfRefusal(response: Response): Promise<boolean> {
	if (response.status !== 403) {
		return false;
	}
	const body: unknown = await response
		.clone()
		.json()
		.catch(() => undefined);
	return isRecord(body) && body.type === "urn:hephaestus:csrf";
}

/** Keep a replayable body only for a request the CSRF filter can reject before it reaches a controller. */
export const csrfFetch: typeof globalThis.fetch = async (input, init) => {
	const request = new Request(input, init);
	const unsafe = !["GET", "HEAD", "OPTIONS"].includes(request.method);
	const { enabled } = environment.workspaceSubdomains;
	if (!enabled) {
		// oxlint-disable-next-line no-restricted-globals -- Transport for the generated API client.
		return fetch(request);
	}
	if (new URL(request.url).origin !== new URL(environment.serverUrl).origin) {
		throw new Error("Credentialed requests must use the configured API origin.");
	}
	tokenRequest ??= loadToken();
	const pending = tokenRequest;
	const token = await settleToken(pending);
	if (unsafe) {
		request.headers.set(token.headerName, token.token);
	}
	const retry = unsafe ? request.clone() : undefined;
	// oxlint-disable-next-line no-restricted-globals -- Transport for the generated API client.
	const response = await fetch(request);
	if (retry !== undefined && (await isCsrfRefusal(response))) {
		// Concurrent refusals for the same token share its replacement.
		const fresh = await (tokenRequest === pending ? refetchCsrfToken() : settleToken(tokenRequest));
		retry.headers.set(fresh.headerName, fresh.token);
		// oxlint-disable-next-line no-restricted-globals -- Only a definitive CSRF refusal is retried, once.
		return fetch(retry);
	}
	return response;
};
