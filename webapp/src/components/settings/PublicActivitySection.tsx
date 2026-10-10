import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";

export type PublicActivityChoiceState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			/** Whether public activity pages list this account's work. */
			visible: boolean;
			/** The change in flight, which the switch waits out. */
			pending: boolean;
			onVisibleChange: (visible: boolean) => void;
	  };

export interface PublicActivitySectionProps {
	state: PublicActivityChoiceState;
}

/** The account-wide choice that every public activity page honours. */
export function PublicActivitySection({ state }: PublicActivitySectionProps) {
	return (
		<section
			id="public-activity"
			className="scroll-mt-20 space-y-4"
			aria-labelledby="public-activity-heading"
		>
			<h2 id="public-activity-heading" className="text-xl font-semibold">
				Public activity pages
			</h2>
			{state.status === "loading" && <Skeleton className="h-10 rounded-lg" />}
			{state.status === "error" && (
				<QueryErrorAlert
					title="We could not load your public activity choice"
					error={state.error}
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && (
				<Field orientation="horizontal">
					<FieldContent>
						<FieldLabel htmlFor="public-activity-visible">
							Show me on public activity pages
						</FieldLabel>
						<FieldDescription>
							Public pages list your pull or merge requests, reviews and issues in public
							repositories. Turn this off to leave every public page.
						</FieldDescription>
					</FieldContent>
					<Switch
						id="public-activity-visible"
						checked={state.visible}
						onCheckedChange={(next) => state.onVisibleChange(next)}
						readOnly={state.pending}
					/>
				</Field>
			)}
		</section>
	);
}
