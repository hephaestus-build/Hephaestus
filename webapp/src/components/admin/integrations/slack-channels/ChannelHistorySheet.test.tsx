import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { SlackMonitoredChannel } from "@/api/types.gen";
import { server } from "@/mocks/server";

import { ChannelHistorySheet } from "./ChannelHistorySheet";

function renderWithClient(node: ReactNode) {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>);
}

const channel: SlackMonitoredChannel = {
	id: 1,
	slackTeamId: "T0000000000",
	slackChannelId: "C02ACTIVE002",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 0,
	createdAt: new Date("2026-01-01T00:00:00Z"),
};

describe("ChannelHistorySheet — failed load offers a retry", () => {
	it("shows a Retry button that re-issues the request after a failed load", async () => {
		let requestCount = 0;
		const CONSENT_EVENTS = "*/slack/channels/:slackChannelId/consent-events";
		server.use(
			http.get(
				CONSENT_EVENTS,
				() => {
					requestCount += 1;
					return new HttpResponse(null, { status: 500 });
				},
				{ once: true },
			),
			http.get(CONSENT_EVENTS, () => {
				requestCount += 1;
				return HttpResponse.json([]);
			}),
		);

		renderWithClient(
			<ChannelHistorySheet
				workspaceSlug="demo"
				channel={channel}
				onOpenChange={vi.fn<() => void>()}
			/>,
		);

		await screen.findByText(/could not load the consent history/iu);
		const retry = screen.getByRole("button", { name: /^retry$/iu });

		fireEvent.click(retry);

		await waitFor(() => screen.getByText(/no consent changes recorded yet/iu));
		expect(requestCount).toBe(2);
	});
});
