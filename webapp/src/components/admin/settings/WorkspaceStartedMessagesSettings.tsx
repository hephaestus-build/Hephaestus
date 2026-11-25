import { useId } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";

export type StartedMessagesState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; enabled: boolean; pending: boolean };

export interface WorkspaceStartedMessagesSettingsProps {
	state: StartedMessagesState;
	onChange: (enabled: boolean) => void;
}

/**
 * Whether Hephaestus may start check-ins and updates for people in the workspace. Either change
 * applies at once and can be undone the same way, so neither asks first.
 */
export function WorkspaceStartedMessagesSettings({
	state,
	onChange,
}: WorkspaceStartedMessagesSettingsProps) {
	const headingId = useId();
	return (
		<section aria-labelledby={headingId}>
			<h2 id={headingId} className="text-lg font-semibold">
				Messages from Hephaestus
			</h2>
			<p className="mb-4 max-w-2xl text-sm text-muted-foreground">
				Choose whether Hephaestus may contact people in this workspace on its own.
			</p>
			{state.status === "loading" && <Skeleton className="h-20 rounded-xl" />}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load this setting"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && <ReadyBody state={state} onChange={onChange} />}
		</section>
	);
}

function ReadyBody({
	state,
	onChange,
}: {
	state: Extract<StartedMessagesState, { status: "ready" }>;
	onChange: (enabled: boolean) => void;
}) {
	const id = useId();
	const description = state.enabled
		? "Hephaestus may send the check-ins that people agree to, and updates when it finds something new for them."
		: "Hephaestus sends no check-ins or updates here. People still get answers, the results they ask for, and the weekly digest if they chose it.";
	return (
		<ul className="rounded-xl border bg-card">
			<Item render={<li />} variant="row">
				<ItemContent>
					<ItemTitle id={`${id}-title`}>Check-ins and updates</ItemTitle>
					<ItemDescription id={`${id}-description`} className="line-clamp-none">
						{description}
					</ItemDescription>
				</ItemContent>
				<ItemActions>
					{state.pending && <Spinner />}
					<Switch
						checked={state.enabled}
						onCheckedChange={(next) => onChange(next)}
						// Read-only, not disabled: a disabled switch leaves the tab order and drops keyboard focus.
						readOnly={state.pending}
						aria-labelledby={`${id}-title`}
						aria-describedby={`${id}-description`}
					/>
				</ItemActions>
			</Item>
		</ul>
	);
}
