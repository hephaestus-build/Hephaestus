import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { SlackUserWorkspacePreferences } from "@/api/types.gen";

import {
	SlackPreferencesSection,
	type SlackPreferencesSectionProps,
} from "./SlackPreferencesSection";

const workspace: SlackUserWorkspacePreferences = {
	workspaceSlug: "hephaestustest",
	workspaceName: "Hephaestus Test",
	slackTeamId: "T1",
	slackTeamName: "hephaestus-test",
	slackUserId: "U1",
	slackDisplayName: "Felix",
	channelMessagesAllowed: true,
	activeMonitoredChannelCount: 2,
};

describe("SlackPreferencesSection", () => {
	// The irreversible-deletion guard is safety-critical, so it is asserted here in the fast jsdom
	// lane in addition to the `ConfirmTurningOff` Storybook story (browser lane). Flipping the switch
	// OFF must NOT fire the mutation on its own — only confirming the dialog may, and only with
	// (workspaceSlug, false). A regression that dropped the confirm gate, or passed the wrong args,
	// would fail these assertions.
	it("confirms before turning message use OFF — the switch alone would silently delete collected data", () => {
		const onToggleChannelMessages =
			vi.fn<SlackPreferencesSectionProps["onToggleChannelMessages"]>();
		render(
			<SlackPreferencesSection
				workspaces={[workspace]}
				isSlackLinked
				canConnectSlack
				onConnectSlack={vi.fn()}
				onToggleChannelMessages={onToggleChannelMessages}
			/>,
		);

		const row = screen.getByRole("group", { name: "Hephaestus Test Slack preferences" });
		within(row).getByText("2 active monitored channels");

		fireEvent.click(within(row).getByRole("switch", { name: /use my new channel messages/iu }));

		// The flip alone must NOT delete anything — an irreversible deletion is gated by a confirmation.
		expect(onToggleChannelMessages).not.toHaveBeenCalled();

		fireEvent.click(screen.getByRole("button", { name: /turn off & delete/iu }));

		expect(onToggleChannelMessages).toHaveBeenCalledWith("hephaestustest", false);
	});

	it("cancelling the confirmation leaves message use ON", () => {
		const onToggleChannelMessages =
			vi.fn<SlackPreferencesSectionProps["onToggleChannelMessages"]>();
		render(
			<SlackPreferencesSection
				workspaces={[workspace]}
				isSlackLinked
				canConnectSlack
				onConnectSlack={vi.fn()}
				onToggleChannelMessages={onToggleChannelMessages}
			/>,
		);

		fireEvent.click(screen.getByRole("switch", { name: /use my new channel messages/iu }));
		fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

		expect(onToggleChannelMessages).not.toHaveBeenCalled();
		screen.getByRole("switch", { name: /use my new channel messages/iu });
	});

	it("does not fake controls when Slack is linked but no workspace is available", () => {
		render(
			<SlackPreferencesSection
				workspaces={[]}
				isSlackLinked
				canConnectSlack
				onConnectSlack={vi.fn()}
				onToggleChannelMessages={vi.fn<SlackPreferencesSectionProps["onToggleChannelMessages"]>()}
			/>,
		);

		screen.getByText(/Slack is connected/iu);
		expect(screen.queryByRole("switch")).toBeNull();
	});
});
