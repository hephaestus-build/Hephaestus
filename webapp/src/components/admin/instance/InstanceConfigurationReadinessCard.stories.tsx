import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { ConfigurationFact } from "@/api/types.gen";

import { InstanceConfigurationReadinessCard } from "./InstanceConfigurationReadinessCard";

const onRetry = fn();
const GUIDE = "https://docs.hephaestus.build/admin/configuration-readiness";

function fact(
	id: string,
	subject: string,
	anchor: string,
	overrides: Partial<ConfigurationFact> = {},
): ConfigurationFact {
	return {
		id,
		subject,
		roles: ["SERVER"],
		requirement: "REQUIRED",
		status: "SATISFIED",
		explanation: `${subject} is checked.`,
		documentationUrl: `${GUIDE}#${anchor}`,
		...overrides,
	};
}

const satisfied = [
	fact("database.url", "spring.datasource.url", "database", {
		roles: ["SERVER", "WORKER", "WEBHOOK"],
		explanation: "A PostgreSQL JDBC URL is required.",
	}),
	fact("external.base-url", "hephaestus.host-url", "external-url", {
		explanation: "A root HTTPS origin without credentials, query, or fragment is required.",
	}),
	fact("webhook.shared-secret", "hephaestus.webhook.secret", "webhooks", {
		roles: ["SERVER", "WEBHOOK"],
		explanation: "A webhook secret of at least 32 printable ASCII characters is required.",
	}),
];

const sentryNotConfigured = fact(
	"observability.sentry",
	"hephaestus.sentry.dsn",
	"optional-observability",
	{
		requirement: "OPTIONAL",
		status: "NOT_CONFIGURED",
		explanation: "Sentry is optional, but a configured DSN must be an HTTPS URI.",
	},
);

const emailNotConfigured = fact("notification.email", "spring.mail.host", "email", {
	requirement: "OPTIONAL",
	status: "NOT_CONFIGURED",
	explanation:
		"Email is optional; with a relay host set, hephaestus.email.from must name the sender address.",
});

const workerRuntime = fact(
	"sandbox.isolation-runtime",
	"hephaestus.sandbox.docker.container-runtime",
	"sandbox-isolation",
	{
		roles: ["WORKER"],
		requirement: "RECOMMENDED",
		status: "ACTION_REQUIRED",
		explanation: "gVisor (runsc) is recommended for stronger agent sandbox isolation.",
	},
);

const noLoginProvider = fact("auth.login-provider", "login-provider capability", "login", {
	status: "ACTION_REQUIRED",
	explanation: "At least one enabled sign-in provider is required.",
});

const notApplicable = fact(
	"llm.proxy-egress",
	"hephaestus.llm.egress.allow-loopback",
	"llm-proxy",
	{
		roles: ["WORKER"],
		status: "NOT_APPLICABLE",
		explanation: "Loopback LLM provider egress must be disabled in production.",
	},
);

const meta = {
	component: InstanceConfigurationReadinessCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			facts: [
				...satisfied,
				workerRuntime,
				noLoginProvider,
				sentryNotConfigured,
				emailNotConfigured,
				notApplicable,
			],
		},
	},
} satisfies Meta<typeof InstanceConfigurationReadinessCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** What a first operator sees: the required miss leads, the recommendation follows it. */
export const ActionRequired: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("2 settings need action. 2 settings not configured."),
		).toBeVisible();
		const [first, second] = canvas.getAllByRole("code");
		await expect(first).toHaveTextContent("login-provider capability");
		await expect(second).toHaveTextContent("hephaestus.sandbox.docker.container-runtime");
		await expect(canvas.getByText("Recommended")).toBeVisible();
		await expect(
			canvas.getByRole("link", { name: /Read the guide for login-provider capability/u }),
		).toHaveAttribute("href", `${GUIDE}#login`);
		// The page's `<h1>` sits above this card, so each status group is a level-2 heading.
		await expect(canvas.getAllByRole("heading", { level: 2 })).not.toHaveLength(0);
		await expect(canvas.queryAllByRole("heading", { level: 3 })).toHaveLength(0);
	},
};

export const AllSatisfied: Story = {
	args: { state: { status: "ready", facts: satisfied } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Every check that applies to this instance passes."),
		).toBeVisible();
		await expect(canvas.queryByText("Action required")).toBeNull();
		// Folded, but one press away.
		await expect(canvas.queryByText("spring.datasource.url")).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: /Satisfied/u }));
		await expect(canvas.getByText("spring.datasource.url")).toBeVisible();
	},
};

/** An absent optional setting is optional, never a failure. */
export const OptionalNotConfigured: Story = {
	args: {
		state: { status: "ready", facts: [...satisfied, sentryNotConfigured, emailNotConfigured] },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Every check that applies to this instance passes. 2 settings not configured.",
			),
		).toBeVisible();
		await expect(canvas.queryByText("Action required")).toBeNull();
		await expect(canvas.getAllByText("Optional")).toHaveLength(2);
		await expect(canvas.getByText("hephaestus.sentry.dsn")).toBeVisible();
	},
};

/** A failed refresh keeps the last facts on screen and says they may be out of date. */
export const RefreshFailed: Story = {
	args: {
		state: {
			status: "ready",
			facts: satisfied,
			refreshFailure: { error: new Error("Network Error"), onRetry },
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Could not refresh. Showing the last successful check."),
		).toBeVisible();
		await expect(
			canvas.getByText("Every check that applies to this instance passes."),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Empty: Story = {
	args: { state: { status: "empty" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No configuration checks reported")).toBeVisible();
	},
};

export const Unavailable: Story = {
	args: { state: { status: "error", error: new Error("Network Error"), onRetry } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Configuration readiness is unavailable")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};

export const OnAPhone: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth);
	},
};
