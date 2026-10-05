import { asRecord, asString } from "./json.ts";

export function researchConsentFields(researchOrganization: unknown) {
	return typeof researchOrganization === "string"
		? { participateInResearch: false, researchOrganization }
		: {};
}

/** A request to the running server as the signed-in account; resolves to the parsed body. */
export type AccountRequest = (
	method: "GET" | "PUT",
	path: string,
	body?: unknown,
) => Promise<unknown>;

/**
 * Completes the current transparency notice for a dev account that has not yet, through the endpoint
 * the interstitial uses. Until then the server refuses the account every other request.
 */
export async function completeTransparencyNotice(request: AccountRequest): Promise<void> {
	const status = asRecord(await request("GET", "/user/consent"), "consent status");
	if (status.completed === true) {
		return;
	}
	await request("PUT", "/user/consent", {
		noticeVersion: asString(status.noticeVersion, "consent status noticeVersion"),
		termsAccepted: true,
		...researchConsentFields(status.researchOrganization),
	});
}
