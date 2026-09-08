import type { Meta, StoryObj } from "@storybook/react";

import {
	LEGAL_PAGE_TITLES,
	type LegalPageId,
	type ResolvedLegalContent,
	type resolveLegalContent,
} from "@/lib/legal";
import { withStandardPage } from "@/stories/decorators";

import { LegalPage } from "./LegalPage";

const TUM_IMPRINT = `Information in accordance with § 5 DDG — German Digital Services Act.

## Publisher

Technical University of Munich  \nArcisstraße 21  \n80333 Munich, Germany

## Responsible for Content

Prof. Dr. Stephan Krusche  \nApplied Education Technologies (AET)  \nBoltzmannstraße 3  \n85748 Garching bei München, Germany
`;

const TUM_PRIVACY = `# Privacy Statement

The Technical University of Munich (TUM), through AET, operates the Hephaestus platform.

## 1. Controller

Technical University of Munich  \nArcisstraße 21, 80333 Munich, Germany

## 7. Third-Country Transfers

Recipients in the U.S. are DPF-certified under Art. 45(3) GDPR; SCCs under Art. 46(2)(c) GDPR serve as a fall-back.
`;

const DISCLAIMER_IMPRINT = `# Imprint not configured

This Hephaestus instance has been deployed without a legal profile.

The operator is legally required under § 5 DDG to identify itself and cannot rely on this fallback.
`;

const DISCLAIMER_PRIVACY = `# Privacy statement not configured

This Hephaestus instance has been deployed without a legal profile. The operator remains the controller under Art. 4(7) GDPR and owes a transparent privacy statement (Art. 13 / 14 GDPR).
`;

type FixtureKey = "tumaet" | "disclaimer";
const FIXTURES: Record<FixtureKey, Record<LegalPageId, string>> = {
	tumaet: {
		imprint: TUM_IMPRINT,
		privacy: TUM_PRIVACY,
	},
	disclaimer: {
		imprint: DISCLAIMER_IMPRINT,
		privacy: DISCLAIMER_PRIVACY,
	},
};

function makeResolver(key: FixtureKey): typeof resolveLegalContent {
	return async (page): Promise<ResolvedLegalContent> => ({
		markdown: FIXTURES[key][page],
		source: key === "tumaet" ? "profile" : "disclaimer",
		profile: key === "tumaet" ? "tumaet" : "",
	});
}

const meta = {
	component: LegalPage,
	tags: ["autodocs"],
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
	argTypes: {
		page: {
			control: { type: "inline-radio" },
			options: ["imprint", "privacy"] satisfies LegalPageId[],
		},
		resolver: {
			control: false,
		},
		profileOverride: {
			control: false,
		},
	},
} satisfies Meta<typeof LegalPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const TumaetImprint: Story = {
	args: {
		page: "imprint",
		title: LEGAL_PAGE_TITLES.imprint,
		resolver: makeResolver("tumaet"),
	},
};

export const TumaetPrivacy: Story = {
	args: {
		page: "privacy",
		title: LEGAL_PAGE_TITLES.privacy,
		resolver: makeResolver("tumaet"),
	},
};

export const DisclaimerImprint: Story = {
	args: {
		page: "imprint",
		title: LEGAL_PAGE_TITLES.imprint,
		resolver: makeResolver("disclaimer"),
	},
};

export const DisclaimerPrivacy: Story = {
	args: {
		page: "privacy",
		title: LEGAL_PAGE_TITLES.privacy,
		resolver: makeResolver("disclaimer"),
	},
};

export const PlainTextCode: Story = {
	args: {
		page: "privacy",
		title: LEGAL_PAGE_TITLES.privacy,
		resolver: async () => ({
			markdown: "The export includes this identifier.\n\n```text\naccount_id: 42\n```",
			source: "profile",
			profile: "example",
		}),
	},
	play: async ({ canvas }) => {
		await canvas.findByText("The export includes this identifier.");
	},
};
