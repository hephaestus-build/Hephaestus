import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, beforeAll, beforeEach, expect, it } from "vitest";

import type { ConfigAuditEntryView } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/admin/audit"), ROUTE_RENDER_WAIT.timeout);

// The page is not workspace-scoped, so the reader belongs to no workspace here.
beforeEach(() => {
	server.use(http.get("*/workspaces", () => HttpResponse.json([])));
});

function change(id: number, operator: string) {
	return {
		id,
		occurredAt: "2026-10-01T09:00:00Z",
		action: "UPDATED",
		entityType: "WORKSPACE_STATUS",
		entityId: "1",
		actorKind: "USER",
		actorAccountId: id,
		actor: { id, displayName: operator },
		workspaceId: 1,
		elevatedViaInstanceAdmin: false,
		changedKeys: ["status"],
	} satisfies Wire<ConfigAuditEntryView>;
}

function page(number: number, operator: string) {
	return {
		content: [change(51 + number, operator)],
		...(number === 0 ? { nextCursor: "after-first" } : {}),
	};
}

/** Answers the instance settings log in order, recording which cursor each read named. */
function serveSettingsChanges(answers: readonly (ReturnType<typeof page> | { status: number })[]) {
	const asked: (string | null)[] = [];
	server.use(
		http.get("*/admin/config-audit/count", () => HttpResponse.json({ total: 2 })),
		http.get("*/admin/config-audit", ({ request }) => {
			const answer = answers[asked.length];
			assert(answer, "Settings audit read exceeded the prepared responses");
			asked.push(new URL(request.url).searchParams.get("cursor"));
			return "status" in answer
				? HttpResponse.json({ status: answer.status }, { status: answer.status })
				: HttpResponse.json(answer);
		}),
	);
	return asked;
}

it("keeps instance settings changes when the next page fails, and asks for that page again", async () => {
	const user = userEvent.setup();
	let accessReads = 0;
	server.use(
		http.get("*/admin/settings", () =>
			HttpResponse.json({ silentModeEngaged: false, etag: '"0"' }),
		),
		http.get("*/admin/workspaces", () => HttpResponse.json([])),
		http.get("*/admin/audit", () => {
			accessReads += 1;
			return HttpResponse.json({ content: [] });
		}),
	);
	const asked = serveSettingsChanges([
		page(0, "First page operator"),
		{ status: 503 },
		page(1, "Second page operator"),
	]);
	renderRouteAtWithRouter("/admin/audit?tab=settings");

	await screen.findByText("First page operator", undefined, ROUTE_RENDER_WAIT);
	await user.click(screen.getByRole("button", { name: "Load more" }));
	await screen.findByText("We could not load more changes.");
	const table = screen.getByRole("table");
	within(table).getByText("First page operator");
	screen.getByText("2 changes.");

	await user.click(screen.getByRole("button", { name: "Load more" }));
	await screen.findByText("Second page operator");
	expect(within(table).getAllByRole("button", { name: /^View details for/u })).toHaveLength(2);
	expect(screen.queryByText("We could not load more changes.")).toBeNull();
	expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
	expect(asked).toStrictEqual([null, "after-first", "after-first"]);
	// The Access tab is not open, so its log is never read.
	expect(accessReads).toBe(0);
});
