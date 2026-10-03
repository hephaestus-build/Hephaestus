import { screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { ConfigurationFact } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const GUIDE = "https://docs.hephaestus.build/admin/configuration-readiness";
const PLANTED_SECRET = "PLANTED-SECRET-THAT-MUST-NEVER-RENDER";

const facts = [
	{
		id: "database.url",
		subject: "spring.datasource.url",
		roles: ["SERVER", "WORKER", "WEBHOOK"],
		requirement: "REQUIRED",
		status: "SATISFIED",
		explanation: "A PostgreSQL JDBC URL is required.",
		documentationUrl: `${GUIDE}#database`,
	},
	{
		id: "observability.sentry",
		subject: "hephaestus.sentry.dsn",
		roles: ["SERVER"],
		requirement: "OPTIONAL",
		status: "NOT_CONFIGURED",
		explanation: "Sentry is optional, but a configured DSN must be an HTTPS URI.",
		documentationUrl: `${GUIDE}#optional-observability`,
	},
	{
		id: "auth.login-provider",
		subject: "login-provider capability",
		roles: ["SERVER"],
		requirement: "REQUIRED",
		status: "ACTION_REQUIRED",
		explanation: "At least one enabled sign-in provider is required.",
		documentationUrl: `${GUIDE}#login`,
	},
] satisfies ConfigurationFact[];

describe("instance overview configuration readiness", () => {
	it("asks the readiness endpoint and lists what needs action before anything optional", async () => {
		let requests = 0;
		server.use(
			http.get("*/admin/configuration-readiness", () => {
				requests += 1;
				// The server never sends a value; a field it should not send must still not reach the page.
				return HttpResponse.json(facts.map((fact) => ({ ...fact, value: PLANTED_SECRET })));
			}),
		);
		renderRouteAt("/admin");
		await screen.findByText(
			"1 setting needs action. 1 setting not configured.",
			{},
			ROUTE_RENDER_WAIT,
		);

		expect(requests).toBe(1);
		const subjects = screen.getAllByRole("code").map((node) => node.textContent);
		expect(subjects).toStrictEqual(["login-provider capability", "hephaestus.sentry.dsn"]);

		expect(screen.getByText("Optional").closest("li")?.textContent).toContain(
			"hephaestus.sentry.dsn",
		);
		expect(screen.getAllByText("Action required")).toHaveLength(1);

		expect(
			screen
				.getByRole("link", { name: /Read the guide for login-provider capability/u })
				.getAttribute("href"),
		).toBe(`${GUIDE}#login`);
		expect(document.body.textContent).not.toContain(PLANTED_SECRET);
	});

	it("keeps the rest of the overview when readiness is unavailable", async () => {
		server.use(
			http.get("*/admin/configuration-readiness", () => HttpResponse.json({}, { status: 503 })),
		);
		renderRouteAt("/admin");
		await screen.findByText("Configuration readiness is unavailable", {}, ROUTE_RENDER_WAIT);
		expect(screen.getByText("Delivery")).not.toBeNull();
	});
});
