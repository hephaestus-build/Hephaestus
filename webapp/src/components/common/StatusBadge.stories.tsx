import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { PROFILE_VISIBILITY_DEFS } from "@/components/practice-vocabulary/profile-visibility-defs";
import {
	REVIEW_STATUS_DEFS,
	REVIEW_JOB_PROCESSING_DEFS,
} from "@/components/practice-vocabulary/review-status-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { STANDING_BASIS_DEFS } from "@/components/practice-vocabulary/standing-basis-defs";
import { WITHHOLDING_FAMILY_DEFS } from "@/components/practice-vocabulary/withholding-defs";
import { type StatusDef, type StatusDefs, statusValues } from "./status-def";
import { StatusBadge } from "./StatusBadge";

/**
 * Every status on the practice-review screens renders through this one badge, reading an entry from
 * the registry for its enum. Nothing else may hold words, a colour or an icon for an enum value.
 *
 * The galleries below are where the two registry rules are checkable by eye: badge variants
 * collapse, so the icon is the channel left when colour is unavailable, and within one enum no two
 * entries may share an icon.
 */
const meta = {
	component: StatusBadge,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { def: DELIVERY_STATE_DEFS.DELIVERED },
} satisfies Meta<typeof StatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

function Gallery<TValue extends string>({
	heading,
	defs,
}: {
	heading: string;
	defs: StatusDefs<TValue>;
}) {
	return (
		<section className="space-y-2">
			<h3 className="text-sm font-semibold">{heading}</h3>
			<dl className="space-y-1.5">
				{statusValues(defs).map((value) => (
					<div key={value} className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
						<dt className="shrink-0">
							<StatusBadge def={defs[value]} />
						</dt>
						<dd className="text-sm text-muted-foreground">{defs[value].description}</dd>
					</div>
				))}
			</dl>
		</section>
	);
}

/** Every registry at once — where a new entry reusing another's icon becomes visible. */
export const EveryRegistry: Story = {
	render: (args: { def: StatusDef }) => (
		<div className="space-y-6">
			<Gallery heading="Delivery" defs={DELIVERY_STATE_DEFS} />
			<Gallery heading="Destination" defs={FEEDBACK_DESTINATION_DEFS} />
			<Gallery heading="Practice profile visibility" defs={PROFILE_VISIBILITY_DEFS} />
			<Gallery heading="Why withheld" defs={WITHHOLDING_FAMILY_DEFS} />
			<Gallery heading="Outcome" defs={OUTCOME_DEFS} />
			<Gallery heading="Severity" defs={SEVERITY_DEFS} />
			<Gallery heading="Review status" defs={REVIEW_STATUS_DEFS} />
			<Gallery heading="Job processing" defs={REVIEW_JOB_PROCESSING_DEFS} />
			<section className="space-y-2">
				<h3 className="text-sm font-semibold">Selected in Controls</h3>
				<StatusBadge def={args.def} />
			</section>
		</div>
	),
	play: async ({ canvas }) => {
		// The bare stored state, which is what this gallery shows: the per-lane refinements of it are
		// `deliveryOutcome`'s job and appear on a row, never in the registry.
		canvas.getByText("Ready");
		canvas.getByText("Withheld");
		canvas.getByText("On the work");
	},
};

/** A label is prose, so it can be long: the badge truncates rather than opening a table column. */
export const LongLabelTruncates: Story = {
	args: { def: DELIVERY_STATE_DEFS.READY },
	render: (args: { def: StatusDef }) => (
		<div className="w-40 border p-2">
			<StatusBadge def={args.def} />
		</div>
	),
};

/**
 * `wrap`, for a label whose words are the whole message: in a narrow column it wraps onto a second
 * line instead of being cut.
 */
export const LongLabelWraps: Story = {
	args: { def: STANDING_BASIS_DEFS.EARLIER_REVIEWS },
	render: (args: { def: StatusDef }) => (
		<div className="w-40 border p-2">
			<StatusBadge wrap def={args.def} />
		</div>
	),
	play: async ({ canvas }) => {
		const words = canvas.getByText(STANDING_BASIS_DEFS.EARLIER_REVIEWS.label);
		// Nothing is cut: the whole label is laid out, not ended in an ellipsis.
		await expect(words.scrollWidth).toBeLessThanOrEqual(words.clientWidth);
		await expect(getComputedStyle(words).textOverflow).not.toBe("ellipsis");
	},
};
