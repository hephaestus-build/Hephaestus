import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { SlackChannelCandidate } from "@/api/types.gen";

import { WorkspaceSlackNotificationSettings } from "./WorkspaceSlackNotificationSettings";

function renderWithClient(node: ReactNode) {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>);
}

const general: SlackChannelCandidate = {
	slackChannelId: "C01GENERAL01",
	channelName: "general",
	privateChannel: false,
	member: true,
	archived: false,
};

const privateTeam: SlackChannelCandidate = {
	slackChannelId: "C02PRIVATE02",
	channelName: "private-team",
	privateChannel: true,
	member: false,
	archived: false,
};

function setup(candidates: SlackChannelCandidate[] = [], enabled = false) {
	renderWithClient(
		<WorkspaceSlackNotificationSettings
			workspaceSlug="demo"
			hasSlackConnection
			slackConnectionId={1}
			enabled={enabled}
			channelCandidates={candidates}
			onSaved={vi.fn()}
		/>,
	);
}

/** The combobox keeps its options in a popover — open it before querying them. */
function openChannelCombobox() {
	fireEvent.click(screen.getByRole("combobox", { name: /digest channel/iu }));
}

describe("WorkspaceSlackNotificationSettings — digest channel combobox", () => {
	it("selects a Slack-discovered channel and never exposes the raw id as an editable value", () => {
		setup([general]);
		openChannelCombobox();

		fireEvent.click(screen.getByRole("option", { name: /#general/iu }));

		expect(screen.getByRole("combobox", { name: /digest channel/iu }).textContent).toContain(
			"#general",
		);
		expect(screen.queryByDisplayValue("C01GENERAL01")).toBeNull();
	});

	it("requires a channel before enabling the digest", () => {
		setup([], true);

		screen.getByText(/choose a channel before enabling/iu);
		expect(screen.getByRole<HTMLButtonElement>("button", { name: /^save$/iu }).disabled).toBe(true);
	});

	it("does not let admins pick a digest channel before the bot is a member", () => {
		setup([privateTeam]);
		openChannelCombobox();

		expect(
			screen.getByRole("option", { name: /#private-team/iu }).getAttribute("aria-disabled"),
		).toBe("true");
		screen.getByText(/needs invite/iu);
	});

	it("resolves a pasted channel link through the escape hatch into the same single value", () => {
		setup([general]);

		// The paste escape hatch writes the same value the combobox does — the one Send-test reads.
		fireEvent.click(screen.getByRole("button", { name: /paste a channel link or id instead/iu }));
		fireEvent.change(screen.getByLabelText(/paste a channel link or id/iu), {
			target: { value: "https://acme.slack.com/archives/C0974LJBPBK" },
		});

		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: /send test message/iu }).disabled,
		).toBe(false);
	});
});
