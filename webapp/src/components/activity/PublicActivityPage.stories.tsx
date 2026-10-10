import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	LARGE_PEOPLE,
	PEOPLE,
	publicActivityOf,
	readyPublicPeople,
	REPOSITORIES,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { PublicActivityPage } from "./PublicActivityPage";

const activity = publicActivityOf(PEOPLE);

const meta = {
	component: PublicActivityPage,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceName: activity.workspaceName,
		providerType: "GITHUB",
		viewer: { status: "signed-out", onSignIn: fn() },
		period: { kind: "preset", preset: "90d" },
		onPeriodChange: fn(),
		order: { sort: "contributions", desc: true },
		onOrderChange: fn(),
		repo: [],
		onRepoChange: fn(),
		repositories: activity.repositories,
		coverage: activity.coverage,
		people: readyPublicPeople(activity),
	},
} satisfies Meta<typeof PublicActivityPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** What a visitor sees: the people with their figures, and the way to hide oneself. */
export const SignedOut: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Hephaestus activity" }),
		).toBeVisible();
		await expect(
			canvas.getByText(
				/This page shows public pull requests, reviews and issues in public repositories\./u,
			),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Sign in" }));
		await expect(
			args.viewer.status === "signed-out" ? args.viewer.onSignIn : undefined,
		).toHaveBeenCalledOnce();
		await expect(canvas.getByRole("table", { name: "People" })).toBeVisible();
	},
};

/** A person on the page signs in, and finds the switch in User settings. */
export const SignedIn: Story = {
	args: { viewer: { status: "signed-in" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "User settings" })).toHaveAttribute(
			"href",
			"/settings",
		);
	},
};

/** Each name leads to the person's page at the provider, apart from this page. */
export const ProfileLinks: Story = {
	play: async ({ canvas }) => {
		const link = within(canvas.getByRole("table", { name: "People" })).getByRole("link", {
			name: "Ada Lovelace, profile (opens in a new tab)",
		});
		await expect(link).toHaveAttribute("href", "https://github.com/ada");
		await expect(link).toHaveAttribute("target", "_blank");
	},
};

/** Only what the public page carries: no team picker, no drawer, no automation. */
export const OnlyWhatIsPublic: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox", { name: /^Team/u })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("region", { name: "Automation" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("region", { name: "Timeline" })).not.toBeInTheDocument();
	},
};

export const Loading: Story = {
	args: { people: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "People" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: {
		workspaceName: undefined,
		coverage: undefined,
		repositories: [],
		people: { status: "error", error: new Error("Network down"), onRetry },
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Public activity" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

/** Nobody contributed to a public repository in the period. */
export const Empty: Story = {
	args: { people: readyPublicPeople(publicActivityOf([])) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No contributions in this range")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Custom range" })).toBeVisible();
	},
};

/** Some repositories' history is complete: the page says since when, and for how many. */
export const PartialHistory: Story = {
	args: {
		coverage: { since: daysBefore(400), completeRepositories: 1, totalRepositories: 2 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/History since .* for 1 of 2 repositories\./u)).toBeVisible();
	},
};

/** 250 people: the table renders 50 rows, and more as its end scrolls into view. */
export const LargeWorkspace: Story = {
	args: { people: readyPublicPeople(publicActivityOf(LARGE_PEOPLE)) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("250 people")).toBeVisible();
	},
};

export const OneRepository: Story = {
	args: { repo: [REPOSITORIES[0]?.key ?? ""], repositories: activity.repositories },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("combobox", { name: "Repository: hephaestus-build/Hephaestus" }),
		).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** GitLab's own words, icons and colours: merge requests, projects, Pajamas icons. */
export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				/This page shows public merge requests, reviews and issues in public projects\./u,
			),
		).toBeVisible();
		await expect(canvas.getByRole("columnheader", { name: /Merge requests/u })).toBeVisible();
	},
};

export const GitLabDark: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	globals: { theme: "dark" },
};

/** Wide screens keep the page to its readable width, so names and figures stay close. */
export const Wide: Story = {
	parameters: { chromatic: { viewports: [1920] } },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "People" });
		await expect(table.getBoundingClientRect().width).toBeLessThanOrEqual(896);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};
