import * as Application from "expo-application";
import { Platform } from "react-native";

import { type Client, createClient, createConfig } from "@/api/client";
import { getNativeClientConfiguration } from "@/api/sdk.gen";
import type { ClientOptions } from "@/api/types.gen";

import { type Instance, instanceUrlCandidates, isOlderThan, webAddress } from "./instance-url";

export const APP_VERSION = Application.nativeApplicationVersion ?? "0.0.0";

/**
 * Identifies the app on every request. The session list shows it, so a person can tell this phone
 * from their browser when they revoke one.
 */
export const USER_AGENT = `Hephaestus/${APP_VERSION} (${Platform.OS === "ios" ? "iOS" : "Android"} ${String(Platform.Version)})`;

const publicClients = new Map<string, Client>();

/**
 * A client for the endpoints that authenticate with a body secret or not at all. Never carries the
 * access token, and never cookies: the native session must not mix with a browser one.
 */
export function publicClient(apiBaseUrl: string): Client {
	let existing = publicClients.get(apiBaseUrl);
	if (existing === undefined) {
		existing = createClient(
			createConfig<ClientOptions>({
				baseUrl: apiBaseUrl,
				credentials: "omit",
				headers: { "User-Agent": USER_AGENT },
			}),
		);
		publicClients.set(apiBaseUrl, existing);
	}
	return existing;
}

export type InstanceResolution =
	| { ok: true; instance: Instance }
	| {
			ok: false;
			reason: "empty" | "invalid" | "insecure" | "unreachable" | "unsupported" | "update-required";
	  };

/**
 * Finds the API behind what a person typed, and whether it serves this app. A server without native
 * sign-in answers 404 for its configuration; one that needs a newer app says which version.
 */
export async function resolveInstance(
	input: string,
	allowInsecure: boolean,
): Promise<InstanceResolution> {
	const url = instanceUrlCandidates(input, allowInsecure);
	if (!url.ok) {
		return url;
	}
	let reachedAny = false;
	for (const apiBaseUrl of url.candidates) {
		try {
			const { data, response } = await getNativeClientConfiguration({
				client: publicClient(apiBaseUrl),
				signal: AbortSignal.timeout(10_000),
			});
			reachedAny = true;
			if (data === undefined || response?.status !== 200) {
				continue;
			}
			if (isOlderThan(APP_VERSION, data.minimumAppVersion)) {
				return { ok: false, reason: "update-required" };
			}
			// The web app is where the privacy notice and settings live; a server that cannot say where it
			// is, or names an address the app will not open, does not serve this app.
			const web = webAddress(data.webappUrl, apiBaseUrl, allowInsecure);
			if (web === undefined) {
				return { ok: false, reason: "unsupported" };
			}
			return { ok: true, instance: { apiBaseUrl, label: url.label, webUrl: web } };
		} catch {
			// Not reachable at this candidate; the next one may be.
		}
	}
	return { ok: false, reason: reachedAny ? "unsupported" : "unreachable" };
}

const PROBLEMS: Record<Exclude<InstanceResolution, { ok: true }>["reason"], string> = {
	empty: "Enter the address of your team's Hephaestus.",
	invalid: "That is not a web address. It looks like hephaestus.example.org.",
	insecure: "Hephaestus only connects over HTTPS.",
	unreachable: "Nothing answered at that address. Check it, and your connection.",
	unsupported: "That address does not serve the Hephaestus app. It may run an older Hephaestus.",
	"update-required": "This Hephaestus needs a newer version of the app. Update it from the store.",
};

export function instanceProblem(
	reason: Exclude<InstanceResolution, { ok: true }>["reason"],
): string {
	return PROBLEMS[reason];
}

let pending: Instance | undefined;

/**
 * The instance a person just confirmed on the welcome screen, handed to the sign-in screen in memory.
 * Never a route parameter: a link must not be able to choose which server this app signs in to.
 */
export function setPendingInstance(instance: Instance): void {
	pending = instance;
}

export function pendingInstance(): Instance | undefined {
	return pending;
}

/** The web app of an instance: its API's origin, without the standard `/api` mount. */
export function webUrl(instance: Instance): string {
	return instance.webUrl;
}
