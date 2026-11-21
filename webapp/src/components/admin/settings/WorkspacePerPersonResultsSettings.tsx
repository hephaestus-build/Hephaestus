import { useId, useRef, useState } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";

export type PerPersonResultsState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			enabled: boolean;
			/** Only a workspace owner turns it on; any admin turns it off. */
			canTurnOn: boolean;
			pending: boolean;
	  };

export interface WorkspacePerPersonResultsSettingsProps {
	workspaceName: string;
	state: PerPersonResultsState;
	onChange: (enabled: boolean) => void;
}

/**
 * Whether the workspace's admins see practice results per person. Off, they see results without
 * names and cannot filter by person. Turning it on asks first and says who then sees what.
 */
export function WorkspacePerPersonResultsSettings({
	workspaceName,
	state,
	onChange,
}: WorkspacePerPersonResultsSettingsProps) {
	const headingId = useId();
	return (
		<section aria-labelledby={headingId}>
			<h2 id={headingId} className="text-lg font-semibold">
				Practice results for admins
			</h2>
			<p className="mb-4 max-w-2xl text-sm text-muted-foreground">
				Choose whether workspace admins see each person’s practice results, or results without
				names.
			</p>
			{state.status === "loading" && <Skeleton className="h-20 rounded-xl" />}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load this setting"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && (
				<ReadyBody workspaceName={workspaceName} state={state} onChange={onChange} />
			)}
		</section>
	);
}

function ReadyBody({
	workspaceName,
	state,
	onChange,
}: {
	workspaceName: string;
	state: Extract<PerPersonResultsState, { status: "ready" }>;
	onChange: (enabled: boolean) => void;
}) {
	const id = useId();
	const [confirming, setConfirming] = useState(false);
	const locked = !state.enabled && !state.canTurnOn;
	const description = state.enabled
		? "Admins see who each result, problem and piece of feedback is about, and can filter by person. Any admin can turn this off."
		: "Admins see no names on results, problems and feedback on practice pages and in conversations, and cannot filter by person. Each result still shows the work it is about. Feedback on the work names its developer, because it is posted to them.";
	return (
		<>
			<ul className="rounded-xl border bg-card">
				<Item render={<li />} variant="row">
					<ItemContent>
						<ItemTitle id={`${id}-title`}>Per-person results for admins</ItemTitle>
						<ItemDescription id={`${id}-description`} className="line-clamp-none">
							{description}
						</ItemDescription>
						{locked && (
							<ItemDescription className="line-clamp-none">
								Only a workspace owner can turn this on.
							</ItemDescription>
						)}
					</ItemContent>
					<ItemActions>
						{state.pending && <Spinner />}
						<Switch
							checked={state.enabled}
							onCheckedChange={(next) => {
								if (next) {
									setConfirming(true);
								} else {
									onChange(false);
								}
							}}
							// Read-only, not disabled: a disabled switch leaves the tab order and drops keyboard focus.
							readOnly={state.pending || locked}
							aria-labelledby={`${id}-title`}
							aria-describedby={`${id}-description`}
						/>
					</ItemActions>
				</Item>
			</ul>
			<TurnOnDialog
				workspaceName={workspaceName}
				open={confirming}
				onOpenChange={setConfirming}
				onConfirm={() => {
					setConfirming(false);
					onChange(true);
				}}
			/>
		</>
	);
}

function TurnOnDialog({
	workspaceName,
	open,
	onOpenChange,
	onConfirm,
}: {
	workspaceName: string;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	onConfirm: () => void;
}) {
	const popupRef = useRef<HTMLDivElement>(null);
	return (
		<AlertDialog open={open} onOpenChange={onOpenChange}>
			{/* Focus starts on the dialog itself, so a screen reader hears who will see what first. */}
			<AlertDialogContent ref={popupRef} initialFocus={popupRef}>
				<AlertDialogHeader className="place-items-start text-left">
					<AlertDialogTitle>
						Show each person’s results to admins of {workspaceName}?
					</AlertDialogTitle>
					<AlertDialogDescription>
						Every workspace admin then sees who each result, problem and piece of feedback is about,
						also for earlier ones. They can filter all of them by person. Members see that this is
						on. The change is recorded with your name, and any admin can turn it off again.
					</AlertDialogDescription>
				</AlertDialogHeader>
				<AlertDialogFooter>
					<AlertDialogCancel>Cancel</AlertDialogCancel>
					<AlertDialogAction onClick={onConfirm}>Show per person</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
