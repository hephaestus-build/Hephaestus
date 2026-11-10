import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { SlackMonitoredChannel } from "@/api/types.gen";
import { ChannelHistorySheet } from "@/components/admin/integrations/slack-channels/ChannelHistorySheet";
import { server } from "@/mocks/server";

import { useSlackConsentHistory } from "./use-slack-consent-history";

const CONSENT_EVENTS = "*/slack/channels/:slackChannelId/consent-events";

const channel: SlackMonitoredChannel = {
	id: "1",
	slackTeamId: "T0000000000",
	slackChannelId: "C02ACTIVE002",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 0,
	consentAnnouncedAt: null,
	createdAt: new Date("2026-01-01T00:00:00Z"),
};

/** The settings page's wiring of the sheet: the hook reads, the sheet shows. */
function History() {
	const { consentHistory, onHistoryOpenChange } = useSlackConsentHistory("demo");
	const [open, setOpen] = useState(false);
	return (
		<>
			<button
				type="button"
				onClick={() => {
					setOpen(true);
					onHistoryOpenChange(channel.slackChannelId, true);
				}}
			>
				Open history
			</button>
			<ChannelHistorySheet
				channel={open ? channel : null}
				history={consentHistory}
				onOpenChange={vi.fn()}
			/>
		</>
	);
}

function renderHistory() {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	render(
		<QueryClientProvider client={queryClient}>
			<History />
		</QueryClientProvider>,
	);
}

describe("useSlackConsentHistory", () => {
	it("reads nothing until the sheet opens, and Retry reads again after a failed load", async () => {
		let requestCount = 0;
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
		renderHistory();
		expect(requestCount).toBe(0);

		fireEvent.click(screen.getByRole("button", { name: "Open history" }));
		await screen.findByText(/could not load the consent history/iu);
		fireEvent.click(screen.getByRole("button", { name: /^retry$/iu }));

		await waitFor(() => screen.getByText(/no consent changes recorded yet/iu));
		expect(requestCount).toBe(2);
	});
});
