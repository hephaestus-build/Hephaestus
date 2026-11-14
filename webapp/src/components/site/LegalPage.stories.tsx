import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { LEGAL_PAGE_TITLES, type LegalPageId } from "@/lib/legal";
import { withStandardPage } from "@/stories/decorators";

import { LegalPage, type LegalContentState } from "./LegalPage";

const OPERATOR_IMPRINT = `Information in accordance with § 5 DDG — German Digital Services Act.

## Publisher

Example Software GmbH  \nMusterstraße 1  \n12345 Musterstadt, Germany

## Responsible for Content

Erika Mustermann  \nMusterstraße 1  \n12345 Musterstadt, Germany
`;

const OPERATOR_PRIVACY = `# Privacy Statement

Example Software GmbH operates this Hephaestus deployment.

## 1. Controller

Example Software GmbH  \nMusterstraße 1, 12345 Musterstadt, Germany

## 7. Third-Country Transfers

Recipients in the U.S. are DPF-certified under Art. 45(3) GDPR; SCCs under Art. 46(2)(c) GDPR serve as a fall-back.
`;

const DISCLAIMER_IMPRINT = `# Imprint not configured

This Hephaestus instance has been deployed without its own legal pages.

The operator is legally required under § 5 DDG to identify itself and cannot rely on this fallback.
`;

const DISCLAIMER_PRIVACY = `# Privacy statement not configured

This Hephaestus instance has been deployed without its own legal pages. The operator remains the controller under Art. 4(7) GDPR and owes a transparent privacy statement (Art. 13 / 14 GDPR).
`;

type FixtureKey = "override" | "disclaimer";
const FIXTURES: Record<FixtureKey, Record<LegalPageId, string>> = {
	override: {
		imprint: OPERATOR_IMPRINT,
		privacy: OPERATOR_PRIVACY,
	},
	disclaimer: {
		imprint: DISCLAIMER_IMPRINT,
		privacy: DISCLAIMER_PRIVACY,
	},
};

const ready = (key: FixtureKey, page: LegalPageId): LegalContentState => ({
	status: "ready",
	content: { markdown: FIXTURES[key][page], source: key },
});

const meta = {
	component: LegalPage,
	tags: ["autodocs"],
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
	argTypes: {
		state: {
			control: false,
		},
	},
} satisfies Meta<typeof LegalPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const OperatorImprint: Story = {
	args: { title: LEGAL_PAGE_TITLES.imprint, state: ready("override", "imprint") },
};

export const OperatorPrivacy: Story = {
	args: { title: LEGAL_PAGE_TITLES.privacy, state: ready("override", "privacy") },
};

export const DisclaimerImprint: Story = {
	args: { title: LEGAL_PAGE_TITLES.imprint, state: ready("disclaimer", "imprint") },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"has not published its own legal pages",
		);
	},
};

export const DisclaimerPrivacy: Story = {
	args: { title: LEGAL_PAGE_TITLES.privacy, state: ready("disclaimer", "privacy") },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"has not published its own legal pages",
		);
	},
};

export const Loading: Story = {
	args: { title: LEGAL_PAGE_TITLES.privacy, state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveAttribute("aria-busy", "true");
	},
};

export const Error: Story = {
	args: { title: LEGAL_PAGE_TITLES.privacy, state: { status: "error" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("We could not load this page");
	},
};

export const PlainTextCode: Story = {
	args: {
		title: LEGAL_PAGE_TITLES.privacy,
		state: {
			status: "ready",
			content: {
				markdown: "The export includes this identifier.\n\n```text\naccount_id: 42\n```",
				source: "override",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The export includes this identifier.")).toBeVisible();
	},
};
