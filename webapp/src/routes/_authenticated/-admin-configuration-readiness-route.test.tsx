import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, beforeAll, describe, expect, it } from "vitest";

import { adminGetConfigurationReadinessQueryKey } from "@/api/@tanstack/react-query.gen";
import type { ConfigurationFact } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT, loadRouteCode } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/admin"), ROUTE_RENDER_WAIT.timeout);

const GUIDE = "https://docs.hephaestus.build/admin/configuration-readiness";
const PLANTED_SECRET = "PLANTED-SECRET-THAT-MUST-NEVER-RENDER";

const facts = [
	{
		id: "database.url",
		subject: "spring.datasource.url",
		roles: ["SERVER"],
		requirement: "REQUIRED",
		status: "SATISFIED",
		explanation: "A PostgreSQL JDBC URL is required.",
		documentationUrl: `${GUIDE}#database`,
	},
	{
		id: "notification.email",
		subject: "spring.mail.host",
		roles: ["SERVER"],
		requirement: "OPTIONAL",
		status: "NOT_CONFIGURED",
		explanation:
			"Email is optional. If you set a relay host, hephaestus.email.from must name the sender address.",
		documentationUrl: `${GUIDE}#email`,
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

type ReadinessRead = { status: 200; body: ConfigurationFact[] } | { status: 403 | 503 };

/** Answers every readiness read with the answer the test last chose. */
function serveReadinessReads() {
	const reads: { count: number; answer: ReadinessRead } = {
		count: 0,
		answer: { status: 200, body: facts },
	};
	server.use(
		http.get("*/admin/configuration-readiness", () => {
			reads.count += 1;
			const { answer } = reads;
			return answer.status === 200
				? HttpResponse.json(answer.body)
				: HttpResponse.json(
						{ status: answer.status, title: "Request failed" },
						{ status: answer.status },
					);
		}),
	);
	return reads;
}

/** The overview's other cards have no API here and show alerts of their own; this is readiness's. */
async function findReadinessAlert(title: string) {
	await screen.findByText(title);
	const alert = screen.getAllByRole("alert").find((node) => node.textContent.includes(title));
	assert(alert);
	return alert;
}

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
		expect(subjects).toStrictEqual(["login-provider capability", "spring.mail.host"]);

		expect(screen.getByText("Optional").closest("li")?.textContent).toContain("spring.mail.host");
		expect(screen.getAllByText("Action required")).toHaveLength(1);

		expect(
			screen
				.getByRole("link", { name: /Read the guide for login-provider capability/u })
				.getAttribute("href"),
		).toBe(`${GUIDE}#login`);
		expect(document.body.textContent).not.toContain(PLANTED_SECRET);
	});

	it("keeps the last facts but says so when a refresh fails, and recovers on retry", async () => {
		server.use(http.get("*/admin/configuration-readiness", () => HttpResponse.json(facts)));
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin");
		await screen.findByText(
			"1 setting needs action. 1 setting not configured.",
			{},
			ROUTE_RENDER_WAIT,
		);

		server.use(
			http.get("*/admin/configuration-readiness", () => HttpResponse.json({}, { status: 503 })),
		);
		await queryClient.invalidateQueries({ queryKey: adminGetConfigurationReadinessQueryKey() });
		await screen.findByText(
			"We could not refresh. This card shows the last successful check.",
			{},
			ROUTE_RENDER_WAIT,
		);
		expect(screen.getByText("login-provider capability")).not.toBeNull();

		server.use(http.get("*/admin/configuration-readiness", () => HttpResponse.json(facts)));
		// Other overview cards have no API here and show alerts of their own; pick this card's.
		const alert = screen
			.getAllByRole("alert")
			.find((candidate) => candidate.textContent.includes("We could not refresh"));
		assert(alert);
		await user.click(within(alert).getByRole("button", { name: "Retry" }));
		await waitFor(() => {
			expect(
				screen.queryByText("We could not refresh. This card shows the last successful check."),
			).toBeNull();
		});
	});

	it("hides refused facts, does not bring them back on an outage, and shows a fresh check", async () => {
		const reads = serveReadinessReads();
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin");
		await screen.findByText("login-provider capability", {}, ROUTE_RENDER_WAIT);
		const reread = async () =>
			act(async () => {
				await queryClient.invalidateQueries({ queryKey: adminGetConfigurationReadinessQueryKey() });
			});

		reads.answer = { status: 403 };
		await reread();
		expect(reads.count).toBe(2);
		const refused = await findReadinessAlert("Configuration readiness is unavailable");
		expect(refused.textContent).toContain("Ask a workspace admin or instance admin for access.");
		expect(within(refused).queryByRole("button", { name: "Retry" })).toBeNull();
		expect(screen.queryByText("login-provider capability")).toBeNull();

		reads.answer = { status: 503 };
		await reread();
		expect(reads.count).toBe(3);
		const outage = await findReadinessAlert("Configuration readiness is unavailable");
		await within(outage).findByText(/Try again in a moment\./u);
		expect(screen.queryByText("login-provider capability")).toBeNull();
		expect(
			screen.queryByText("We could not refresh. This card shows the last successful check."),
		).toBeNull();

		reads.answer = { status: 200, body: facts.slice(0, 1) };
		await user.click(within(outage).getByRole("button", { name: "Retry" }));
		await screen.findByText("Every check that applies to this instance passes.");
		expect(reads.count).toBe(4);
		expect(screen.queryByText("login-provider capability")).toBeNull();
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
