import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { FeatureValues } from "./WorkspaceFeaturesSettings";
import { WorkspaceSettingsPage, type WorkspaceSettingsPageProps } from "./WorkspaceSettingsPage";

const features: FeatureValues = {
	mentorEnabled: false,
	leaderboardEnabled: false,
	progressionEnabled: false,
	leaguesEnabled: false,
};

function setup(overrides: Partial<WorkspaceSettingsPageProps> = {}) {
	const props: WorkspaceSettingsPageProps = {
		isResettingLeagues: false,
		onResetLeagues: vi.fn(),
		features,
		isSavingFeatures: false,
		onToggleFeature: vi.fn(),
		...overrides,
	};
	render(<WorkspaceSettingsPage {...props} />);
	return { props };
}

describe("WorkspaceSettingsPage — non-integration content", () => {
	it("offers supported features without an achievements switch", () => {
		setup();
		screen.getByRole("switch", { name: "Leaderboard" });
		expect(screen.queryByRole("switch", { name: "Achievements" })).toBeNull();
	});

	it("hides the league reset card when leagues are disabled", () => {
		setup({ features: { ...features, leaguesEnabled: false } });
		expect(screen.queryByText(/reset and recalculate leagues/iu)).toBeNull();
	});

	it("shows the league reset card when leagues are enabled", () => {
		setup({ features: { ...features, leaguesEnabled: true } });
		screen.getByText(/reset and recalculate leagues/iu);
	});

	it("leaves the danger zone out until the active workspace has resolved", () => {
		setup({ workspaceSlug: undefined });
		expect(screen.queryByRole("heading", { name: /danger zone/iu })).toBeNull();
	});
});
