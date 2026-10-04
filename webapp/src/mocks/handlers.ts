// URL patterns use the `*/path` wildcard so they match regardless of the API
// client's configured base URL: in the app the client points at
// `environment.serverUrl` (e.g. http://localhost:8080), while in Storybook the
// client is unconfigured and issues same-origin relative requests (`/user`).
// The leading `*` matches the optional `<scheme>://<host>` prefix.

import { HttpResponse, http, type PathParams } from "msw";

import type { AccountAiChoice, AccountAiChoiceRequest } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";

import { workspaceOnboarding } from "./fixtures/onboarding";

import { WORDING_VERSION } from "@/components/auth/consent-wording";
import {
	adminUsers,
	currentUser,
	exportPending,
	exportReady,
	identityProviders,
	linkedIdentities,
	sessions,
} from "./fixtures/auth";

// Per-export poll counter: the first status read returns PENDING, subsequent reads
// return READY — mirroring the async export job completing server-side.
const exportPolls = new Map<string, number>();

/** Body of `PATCH /admin/users/:id` — the only field the admin table sends. */
interface AdminUserPatch {
	appRole?: string;
}

export const handlers = [
	// Opening Heph prepares the member's sandbox; the server accepts and answers with no body.
	http.post(
		"*/workspaces/:workspaceSlug/mentor/sandbox",
		() => new HttpResponse(null, { status: 202 }),
	),
	http.get("*/workspaces/:workspaceSlug/onboarding/me", () =>
		HttpResponse.json(workspaceOnboarding()),
	),
	// The account's AI choice: unanswered by default, so no story or test inherits an answer.
	http.get("*/user/ai-choice", () => HttpResponse.json({})),
	http.get("*/user/settings", () => HttpResponse.json({ practiceFeedbackDeliveryEnabled: true })),
	http.put<PathParams, AccountAiChoiceRequest>("*/user/ai-choice", async ({ request }) => {
		const body = await request.json();
		return HttpResponse.json({
			choice: body.choice,
			updatedAt: new Date().toISOString(),
		} satisfies Wire<AccountAiChoice>);
	}),
	http.get("*/user", () => HttpResponse.json(currentUser)),
	http.get("*/user/notification-preferences", () =>
		HttpResponse.json({
			productFeedback: false,
			workspaceAlerts: false,
			surveySummaries: false,
			productSurveys: false,
			researchSurveys: false,
			emailAvailable: true,
			deliveryConfigured: true,
			etag: '"0-0-0"',
		}),
	),
	http.get("*/user/consent", () =>
		HttpResponse.json({
			completed: true,
			noticeVersion: WORDING_VERSION,
			participateInResearch: false,
		}),
	),
	// Mirror the server contract: the confirmation header MUST equal the caller's
	// account id, else 400. A `"true"`-style placeholder must NOT pass — that mismatch
	// is exactly the regression a unconditional-204 mock would hide.
	http.delete("*/user", ({ request }) =>
		request.headers.get("X-Confirm-Delete") === String(currentUser.id)
			? new HttpResponse(null, { status: 204 })
			: new HttpResponse(null, { status: 400 }),
	),

	http.get("*/workspaces/:workspaceSlug/product-feedback/surveys", () => HttpResponse.json([])),
	http.get("*/identity-providers", () => HttpResponse.json(identityProviders)),
	http.get("*/user/identities", () => HttpResponse.json(linkedIdentities)),

	http.get("*/user/sessions", () => HttpResponse.json(sessions)),
	http.delete("*/user/sessions/:jti", () => new HttpResponse(null, { status: 204 })),
	// Revoke-all-others (no path param) — registered after the `:jti` route so the
	// more specific match wins for single-session revocation.
	http.delete("*/user/sessions", () => new HttpResponse(null, { status: 204 })),

	http.post("*/user/exports", () => {
		exportPolls.delete(String(exportPending.id));
		return HttpResponse.json({ id: exportPending.id, status: "PENDING" }, { status: 202 });
	}),
	http.get("*/user/exports/:id/download", () =>
		HttpResponse.json(
			{ account: currentUser, exportedAt: "2026-05-29T10:00:05Z" },
			{ headers: { "Content-Disposition": 'attachment; filename="hephaestus-export.json"' } },
		),
	),
	http.get("*/user/exports/:id", ({ params }) => {
		const key = String(params.id);
		const count = (exportPolls.get(key) ?? 0) + 1;
		exportPolls.set(key, count);
		return HttpResponse.json(count <= 1 ? exportPending : exportReady);
	}),

	http.get("*/admin/users", () => HttpResponse.json(adminUsers)),
	http.patch<PathParams, AdminUserPatch>("*/admin/users/:id", async ({ request, params }) => {
		const body = await request.json().catch((): AdminUserPatch => ({}));
		// Any id echoes a user back, so a story can PATCH a row the fixture list does not carry.
		const [fallback] = adminUsers;
		const existing = adminUsers.find((u) => String(u.id) === String(params.id)) ?? fallback;
		if (!existing) {
			return new HttpResponse(null, { status: 404 });
		}
		return HttpResponse.json({ ...existing, appRole: body.appRole ?? existing.appRole });
	}),
];

// Spread one override into a story's `parameters.msw.handlers`, or pass it to `server.use(...)` in a
// test, to flip a single endpoint without redefining the whole default set.

/** `GET /user` -> 401, for logged-out / session-expired states. */
export const unauthenticatedUser = http.get(
	"*/user",
	() => new HttpResponse(null, { status: 401 }),
);
