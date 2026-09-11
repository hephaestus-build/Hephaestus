import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { SlackMonitoredChannel } from "@/api/types.gen";

import { AdminSlackChannelsSettings } from "./AdminSlackChannelsSettings";

function renderWithClient(node: ReactNode) {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>);
}

const base = {
	slackTeamId: "T0000000000",
	createdAt: new Date("2026-01-01T00:00:00Z"),
} satisfies Pick<SlackMonitoredChannel, "slackTeamId" | "createdAt">;

const pending: SlackMonitoredChannel = {
	...base,
	id: 1,
	slackChannelId: "C01PENDING01",
	channelName: "team-intro",
	consentState: "PENDING",
	optedOutMemberCount: 0,
};

const active: SlackMonitoredChannel = {
	...base,
	id: 2,
	slackChannelId: "C02ACTIVE002",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 2,
	consentAnnouncedAt: new Date("2026-02-01T00:00:00Z"),
};

const revoked: SlackMonitoredChannel = {
	...base,
	id: 3,
	slackChannelId: "C03REVOKED03",
	channelName: "team-legacy",
	consentState: "REVOKED",
	optedOutMemberCount: 0,
};

function setup(overrides: Partial<Parameters<typeof AdminSlackChannelsSettings>[0]> = {}) {
	const props = {
		workspaceSlug: "demo",
		hasSlackConnection: true,
		isLoading: false,
		channels: [pending, active],
		onRegisterChannel: vi.fn(),
		onUpdateConsent: vi.fn(),
		onRemoveChannel: vi.fn(),
		...overrides,
	};
	renderWithClient(<AdminSlackChannelsSettings {...props} />);
	return { props };
}

function openRowMenu(label: string) {
	fireEvent.click(screen.getByRole("button", { name: new RegExp(`actions for ${label}`, "i") }));
}

describe("AdminSlackChannelsSettings — reversible row actions swallow rejections", () => {
	it("does not leak an unhandled rejection when Pause's consent update fails", async () => {
		const onRejection = vi.fn();
		process.on("unhandledRejection", onRejection);
		try {
			const onUpdateConsent = vi.fn().mockRejectedValue(new Error("boom"));
			setup({ channels: [active], onUpdateConsent });
			openRowMenu("team-standup");
			fireEvent.click(await screen.findByRole("menuitem", { name: /^pause$/i }));

			await waitFor(() => expect(onUpdateConsent).toHaveBeenCalledTimes(1));
			// Let unhandled rejections surface on the next event-loop turn while React finishes closing the menu.
			await act(async () => {
				await new Promise((resolve) => {
					setTimeout(resolve, 0);
				});
			});
			expect(onRejection).not.toHaveBeenCalled();
		} finally {
			process.off("unhandledRejection", onRejection);
		}
	});

	it("does not leak an unhandled rejection when Set up again's re-register fails", async () => {
		const onRejection = vi.fn();
		process.on("unhandledRejection", onRejection);
		try {
			const onRegisterChannel = vi.fn().mockRejectedValue(new Error("boom"));
			setup({ channels: [revoked], onRegisterChannel });
			openRowMenu("team-legacy");
			fireEvent.click(await screen.findByRole("menuitem", { name: /set up again/i }));

			await waitFor(() => expect(onRegisterChannel).toHaveBeenCalledTimes(1));
			await act(async () => {
				await new Promise((resolve) => {
					setTimeout(resolve, 0);
				});
			});
			expect(onRejection).not.toHaveBeenCalled();
		} finally {
			process.off("unhandledRejection", onRejection);
		}
	});
});

describe("AdminSlackChannelsSettings — Slack channel picker", () => {
	it("lets admins set up a revoked channel again", async () => {
		const { props } = setup({ channels: [revoked] });
		openRowMenu("team-legacy");

		fireEvent.click(await screen.findByRole("menuitem", { name: /set up again/i }));

		expect(props.onRegisterChannel).toHaveBeenCalledWith({
			slackChannelId: revoked.slackChannelId,
			channelName: revoked.channelName,
		});
	});
});
