import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { FeatureValues } from "./AdminFeaturesSettings";
import { AdminSettingsPage, type AdminSettingsPageProps } from "./AdminSettingsPage";

const features: FeatureValues = {
	mentorEnabled: false,
	leaderboardEnabled: false,
	progressionEnabled: false,
	leaguesEnabled: false,
};

function setup(overrides: Partial<AdminSettingsPageProps> = {}) {
	const props: AdminSettingsPageProps = {
		isResettingLeagues: false,
		onResetLeagues: vi.fn(),
		features,
		isSavingFeatures: false,
		onToggleFeature: vi.fn(),
		...overrides,
	};
	render(<AdminSettingsPage {...props} />);
	return { props };
}

describe("AdminSettingsPage — non-integration content", () => {
	it("offers supported features without an achievements switch", () => {
		setup();
		screen.getByRole("switch", { name: "Leaderboard" });
		expect(screen.queryByRole("switch", { name: "Achievements" })).toBeNull();
	});

	it("hides the league reset card when leagues are disabled", () => {
		setup({ features: { ...features, leaguesEnabled: false } });
		expect(screen.queryByText(/reset and recalculate leagues/i)).toBeNull();
	});

	it("shows the league reset card when leagues are enabled", () => {
		setup({ features: { ...features, leaguesEnabled: true } });
		screen.getByText(/reset and recalculate leagues/i);
	});

	it("leaves the danger zone out until the active workspace has resolved", () => {
		setup({ workspaceSlug: undefined });
		expect(screen.queryByRole("heading", { name: /danger zone/i })).toBeNull();
	});
});
