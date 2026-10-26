import type { Meta, StoryObj } from "@storybook/react";
import { expect, spyOn } from "storybook/test";

import {
	LEGAL_PAGE_TITLES,
	type LegalPageId,
	type ResolvedLegalContent,
	type resolveLegalContent,
} from "@/lib/legal";
import { withStandardPage } from "@/stories/decorators";

import { LegalPage } from "./LegalPage";

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

function makeResolver(key: FixtureKey): typeof resolveLegalContent {
	return async (page): Promise<ResolvedLegalContent> => ({
		markdown: FIXTURES[key][page],
		source: key,
	});
}

// LegalPage.test.tsx requires each page's warning exactly once across mounts. Docs and reruns
// share that production state, so a story asserts any new diagnostics without resetting it.
function captureDisclaimerWarning(page: LegalPageId) {
	const expected = `[legal] Disclaimer fallback served for page=${page}. Mount /legal-overrides/. See docs/admin/legal-pages.`;
	// oxlint-disable-next-line no-console -- Unexpected diagnostics must remain visible while the intentional fallback warning is asserted.
	const original = console.warn;
	const warning = spyOn(console, "warn").mockImplementation((...args: unknown[]) => {
		if (args.length !== 1 || args[0] !== expected) {
			original(...args);
		}
	});
	return async () => {
		try {
			for (const args of warning.mock.calls) {
				await expect(args).toEqual([expected]);
			}
		} finally {
			warning.mockRestore();
		}
	};
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
	},
} satisfies Meta<typeof LegalPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const OperatorImprint: Story = {
	args: {
		page: "imprint",
		title: LEGAL_PAGE_TITLES.imprint,
		resolver: makeResolver("override"),
	},
};

export const OperatorPrivacy: Story = {
	args: {
		page: "privacy",
		title: LEGAL_PAGE_TITLES.privacy,
		resolver: makeResolver("override"),
	},
};

export const DisclaimerImprint: Story = {
	beforeEach: () => captureDisclaimerWarning("imprint"),
	play: async ({ canvas }) => {
		await expect(canvas.findByRole("alert")).resolves.toHaveTextContent(
			"has not published its own legal pages",
		);
	},
	args: {
		page: "imprint",
		title: LEGAL_PAGE_TITLES.imprint,
		resolver: makeResolver("disclaimer"),
	},
};

export const DisclaimerPrivacy: Story = {
	beforeEach: () => captureDisclaimerWarning("privacy"),
	play: async ({ canvas }) => {
		await expect(canvas.findByRole("alert")).resolves.toHaveTextContent(
			"has not published its own legal pages",
		);
	},
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
			source: "override",
		}),
	},
	play: async ({ canvas }) => {
		await canvas.findByText("The export includes this identifier.");
	},
};
