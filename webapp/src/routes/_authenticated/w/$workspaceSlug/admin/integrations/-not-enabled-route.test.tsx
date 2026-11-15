import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeAll, describe, expect, it, vi } from "vitest";

import type { IntegrationCatalogEntry, Workspace } from "@/api/types.gen";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

const SLUG = "intro-course";
const OUTLINE_PAGE = `/w/${SLUG}/admin/integrations/outline`;
const SLACK_PAGE = `/w/${SLUG}/admin/integrations/slack`;
const OVERVIEW_PAGE = `/w/${SLUG}/admin/integrations`;

beforeAll(
	async () => loadRouteCode(OUTLINE_PAGE, SLACK_PAGE, OVERVIEW_PAGE),
	ROUTE_RENDER_WAIT.timeout,
);

vi.mock("@/hooks/use-sync-events", () => ({ useSyncEvents: () => false }));

function entry(
	kind: IntegrationCatalogEntry["kind"],
	displayName: string,
	connectable: boolean,
): IntegrationCatalogEntry {
	return { kind, displayName, connected: false, connectable };
}

/** An admin of a workspace with no connection, on an instance whose catalog is `catalog`. */
function serveWorkspace(catalog: IntegrationCatalogEntry[]) {
	const listed = workspaceListItem(SLUG);
	const workspace = {
		...listed,
		gitlabWebhookRegistered: false,
		hasPersonalAccessToken: false,
		hasSlackToken: false,
		publicActivityEnabled: false,
		practiceReviewAutoTriggerEnabled: false,
		practiceReviewManualTriggerEnabled: false,
		updatedAt: listed.createdAt,
	} satisfies Workspace;
	const connects: unknown[] = [];
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([listed])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json(catalog)),
		http.get("*/workspaces/:workspaceSlug/connections", () => HttpResponse.json([])),
		http.post("*/workspaces/:workspaceSlug/connections", async ({ request }) => {
			connects.push(await request.json());
			return HttpResponse.json({}, { status: 400 });
		}),
	);
	return { connects };
}

describe("an integration this instance has not enabled", () => {
	it("offers no Outline connect form, and says the instance has not enabled Outline", async () => {
		const { connects } = serveWorkspace([entry("OUTLINE", "Outline", false)]);
		renderRouteAt(OUTLINE_PAGE);

		await screen.findByText(
			"Outline is not enabled on this instance",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByLabelText(/server url/iu)).toBeNull();
		expect(screen.queryByRole("button", { name: "Connect Outline" })).toBeNull();
		expect(connects).toStrictEqual([]);
	});

	it("offers the Outline connect form when the instance has enabled Outline", async () => {
		serveWorkspace([entry("OUTLINE", "Outline", true)]);
		renderRouteAt(OUTLINE_PAGE);

		await screen.findByLabelText(/server url/iu, undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Outline is not enabled on this instance")).toBeNull();
	});

	it("offers no Slack install, and says the instance has not enabled Slack", async () => {
		serveWorkspace([entry("SLACK", "Slack", false)]);
		renderRouteAt(SLACK_PAGE);

		await screen.findByText("Slack is not enabled on this instance", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: /connect slack workspace/iu })).toBeNull();
	});

	it("says so on the overview card instead of linking to a connect page", async () => {
		serveWorkspace([entry("OUTLINE", "Outline", false), entry("SLACK", "Slack", true)]);
		renderRouteAt(OVERVIEW_PAGE);

		await screen.findByText("Not enabled on this instance.", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("link", { name: /^connect outline/iu })).toBeNull();
		screen.getByRole("link", { name: /^connect slack/iu });
	});
});
