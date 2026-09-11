import type { Meta, StoryObj } from "@storybook/react-vite";

import { ASSESSMENT_DEFS } from "./assessment-defs";
import { DASHBOARD_VISIBILITY_DEFS } from "./dashboard-visibility-defs";
import { DELIVERY_STATE_DEFS } from "./delivery-outcome-defs";
import { DELIVERY_PLACE_DEFS } from "./delivery-place-defs";
import { OUTCOME_DEFS } from "./outcome-defs";
import { PRESENCE_DEFS } from "./presence-defs";
import { REVIEW_STATUS_DEFS, SUMMARY_POST_DEFS } from "./review-status-defs";
import { SEVERITY_DEFS } from "./severity-defs";
import { type StatusDef, type StatusDefs, statusValues } from "./status-def";
import { StatusBadge } from "./StatusBadge";
import { WITHHOLDING_FAMILY_DEFS } from "./withholding-defs";

/**
 * Every status on the practice-review screens renders through this one badge, reading an entry from
 * the registry for its enum. Nothing else may hold words, a colour or an icon for an enum value.
 *
 * The galleries below are where the two registry rules are checkable by eye: badge variants
 * collapse, so the icon is the channel left when colour is unavailable, and within one enum no two
 * entries may share an icon.
 */
const meta = {
	title: "Shared/Practice vocabulary/Status badge",
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
			<Gallery heading="Delivery outcome" defs={DELIVERY_STATE_DEFS} />
			<Gallery heading="Delivery place" defs={DELIVERY_PLACE_DEFS} />
			<Gallery heading="Dashboard visibility" defs={DASHBOARD_VISIBILITY_DEFS} />
			<Gallery heading="Why withheld" defs={WITHHOLDING_FAMILY_DEFS} />
			<Gallery heading="Outcome" defs={OUTCOME_DEFS} />
			<Gallery heading="Target assessment" defs={ASSESSMENT_DEFS} />
			<Gallery heading="Severity" defs={SEVERITY_DEFS} />
			<Gallery heading="Practice status" defs={PRESENCE_DEFS} />
			<Gallery heading="Review status" defs={REVIEW_STATUS_DEFS} />
			<Gallery heading="Summary comment" defs={SUMMARY_POST_DEFS} />
			<section className="space-y-2">
				<h3 className="text-sm font-semibold">Selected in Controls</h3>
				<StatusBadge def={args.def} />
			</section>
		</div>
	),
	play: async ({ canvas }) => {
		// The bare stored state, which is what this gallery shows: the per-lane refinements of it are
		// `deliveryOutcome`'s job and appear on a row, never in the registry.
		canvas.getByText("Prepared");
		canvas.getByText("Withheld");
		canvas.getByText("On the work");
	},
};

/** A label is prose, so it can be long: the badge truncates rather than opening a table column. */
export const LongLabelTruncates: Story = {
	args: { def: DELIVERY_STATE_DEFS.PREPARED },
	render: (args: { def: StatusDef }) => (
		<div className="w-40 border p-2">
			<StatusBadge def={args.def} />
		</div>
	),
};
