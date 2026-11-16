import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";

import { problemType } from "@/lib/problem-detail";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, loadRouteCode, renderRouteAt } from "@/test/router-harness";

const REQUEST_ID = "8a1f6c3e-2b47-4d55-9f0e-1c2d3e4f5a6b";
const CLIENT_CALLBACK =
	"http://127.0.0.1:53111/callback?code=abc&iss=https%3A%2F%2Fhephaestus.build";

beforeAll(async () => loadRouteCode("/mcp/authorize"), ROUTE_RENDER_WAIT.timeout);

const realLocation = window.location;

beforeEach(() => {
	// The consent page belongs to no workspace, so the account here is a member of none.
	server.use(http.get("*/workspaces", () => HttpResponse.json([])));
});

afterEach(() => {
	Object.defineProperty(window, "location", { configurable: true, value: realLocation });
});

/** Records every URL the page sends the browser to, so the test tab stays. */
function captureNavigation(): string[] {
	const assigned: string[] = [];
	Object.defineProperty(window, "location", {
		configurable: true,
		value: {
			origin: realLocation.origin,
			pathname: "/mcp/authorize",
			search: `?request=${REQUEST_ID}`,
			assign: (url: string) => assigned.push(url),
		},
	});
	return assigned;
}

function serveRequest() {
	server.use(
		http.get(`*/mcp/oauth/requests/${REQUEST_ID}`, () =>
			HttpResponse.json({
				id: REQUEST_ID,
				clientHost: "claude.ai",
				clientName: "Claude Code",
				redirectHost: "127.0.0.1",
				loopbackRedirect: true,
				workspaceSlug: "acme",
				workspaceName: "Acme",
				scopes: ["practices:read"],
			}),
		),
	);
}

describe("the MCP consent route", () => {
	it("sends the browser to the client with the answer once the account allows it", async () => {
		serveRequest();
		server.use(
			http.put(`*/mcp/oauth/requests/${REQUEST_ID}/approval`, () =>
				HttpResponse.json({ redirectUri: CLIENT_CALLBACK }),
			),
		);
		renderRouteAt(`/mcp/authorize?request=${REQUEST_ID}`);
		const allow = await screen.findByRole("button", { name: "Allow" }, ROUTE_RENDER_WAIT);
		const assigned = captureNavigation();

		fireEvent.click(allow);

		await waitFor(() => expect(assigned).toStrictEqual([CLIENT_CALLBACK]));
	});

	it("asks a stale session to confirm access instead of reporting a failure", async () => {
		serveRequest();
		server.use(
			http.put(`*/mcp/oauth/requests/${REQUEST_ID}/approval`, () =>
				HttpResponse.json(
					{
						status: 403,
						title: "Confirm access",
						detail: "This action requires a recent sign-in.",
						type: problemType("step-up-required"),
						maxAgeSeconds: 300,
					},
					{ status: 403 },
				),
			),
		);
		renderRouteAt(`/mcp/authorize?request=${REQUEST_ID}`);
		const allow = await screen.findByRole("button", { name: "Allow" }, ROUTE_RENDER_WAIT);
		const assigned = captureNavigation();

		fireEvent.click(allow);

		const ask = await screen.findByRole("dialog", { name: "Confirm access" }, ROUTE_RENDER_WAIT);
		expect(ask.textContent).toContain("sign-in from the last 5 minutes");
		expect(screen.queryByText("We could not load this request")).toBeNull();
		expect(assigned).toStrictEqual([]);
	});
	it("never sends the browser to an address that is not HTTP or HTTPS", async () => {
		serveRequest();
		server.use(
			http.put(`*/mcp/oauth/requests/${REQUEST_ID}/approval`, () =>
				HttpResponse.json({ redirectUri: "javascript:alert(1)" }),
			),
		);
		renderRouteAt(`/mcp/authorize?request=${REQUEST_ID}`);
		const allow = await screen.findByRole("button", { name: "Allow" }, ROUTE_RENDER_WAIT);
		const assigned = captureNavigation();

		fireEvent.click(allow);

		await screen.findByText("This client cannot connect", undefined, ROUTE_RENDER_WAIT);
		expect(assigned).toStrictEqual([]);
	});
});
