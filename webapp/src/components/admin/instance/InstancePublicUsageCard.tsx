import { Link } from "@tanstack/react-router";
import { ChartColumn } from "lucide-react";
import { useId, useState } from "react";

import type { PublicUsageWithdrawal } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

export type InstancePublicUsageState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			allowed: boolean;
			pending: boolean;
			withdrawals: PublicUsageWithdrawal[];
			/** A stored withdrawal cannot be read: the page hides every figure and `withdrawals` is incomplete. */
			withdrawalsUnreadable: boolean;
			/** Set when the latest read failed, so `allowed` is the last known setting. */
			refreshFailure?: RefreshFailure;
	  };

export interface InstancePublicUsageCardProps {
	state: InstancePublicUsageState;
	onAllowedChange: (allowed: boolean) => void;
}

const FIGURE_LABELS: Record<NonNullable<PublicUsageWithdrawal["figure"]>, string> = {
	WORKSPACES: "Workspaces",
	REVIEWED_PEOPLE: "Developers whose work was reviewed",
	REVIEWED_WORK: "Reviewed work",
	PRACTICE_REVIEWS: "Practice reviews",
	FEEDBACK_DELIVERED: "Practice feedback delivered",
};

function withdrawalLabel({ period, figure }: PublicUsageWithdrawal): string {
	return figure === undefined
		? `${period}: the whole half-year`
		: `${period}: ${FIGURE_LABELS[figure] ?? figure}`;
}

/** Whether this server publishes Hephaestus in numbers, and what an instance admin withdrew from it. */
export function InstancePublicUsageCard({ state, onAllowedChange }: InstancePublicUsageCardProps) {
	const id = useId();
	// Turning the page on publishes every released half-year to anyone, so it asks first. Turning it off does not.
	const [confirming, setConfirming] = useState(false);
	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					<ChartColumn className="size-4 text-muted-foreground" aria-hidden />
					Hephaestus in numbers
				</CardTitle>
				<CardDescription>
					A public page with the totals of this server, released once each half-year, with no
					sign-in. It names no person and no workspace.{" "}
					<Link to="/usage" className="underline underline-offset-4 hover:text-foreground">
						Open the page
					</Link>
				</CardDescription>
			</CardHeader>
			<CardContent>
				{state.status === "loading" && <Skeleton className="h-10 rounded-lg" />}
				{state.status === "error" && (
					<QueryErrorAlert
						error={state.error}
						title="We could not load the Hephaestus in numbers setting"
						onRetry={state.onRetry}
					/>
				)}
				{state.status === "ready" && (
					<div className="flex flex-col gap-3">
						{state.refreshFailure !== undefined && (
							<QueryErrorAlert title="This setting may be out of date" {...state.refreshFailure} />
						)}
						{state.withdrawalsUnreadable && (
							<Alert variant="warning">
								<AlertTitle>The page hides every figure</AlertTitle>
								<AlertDescription>
									A saved withdrawal cannot be read, so the list below may be incomplete. Repair the
									stored withdrawals before you keep a copy of this list.
								</AlertDescription>
							</Alert>
						)}
						<Field orientation="horizontal">
							<FieldContent>
								<FieldLabel htmlFor={id}>Publish Hephaestus in numbers</FieldLabel>
								<FieldDescription>
									{state.allowed
										? "On. Anyone can read the released half-years."
										: "Off. Within 5 minutes, a new load of the page shows no figures."}
								</FieldDescription>
							</FieldContent>
							<Switch
								id={id}
								checked={state.allowed}
								onCheckedChange={(next) => {
									if (next) {
										setConfirming(true);
									} else {
										onAllowedChange(false);
									}
								}}
								pending={state.pending}
							/>
						</Field>
						{state.withdrawals.length > 0 && (
							<div className="flex flex-col gap-1 text-sm">
								<p className="font-medium">Withdrawn for good</p>
								<ul className="list-disc pl-5 text-muted-foreground">
									{state.withdrawals.map((withdrawal) => (
										<li key={`${withdrawal.period}/${withdrawal.figure ?? ""}`}>
											{withdrawalLabel(withdrawal)}
										</li>
									))}
								</ul>
							</div>
						)}
					</div>
				)}
			</CardContent>
			<AlertDialog open={confirming} onOpenChange={setConfirming}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Publish Hephaestus in numbers?</AlertDialogTitle>
						<AlertDialogDescription>
							Anyone can read the released half-years at /usage, without sign-in, and keep a copy.
							You can turn the page off later, but a copy that someone kept stays with them.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Keep it off</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								setConfirming(false);
								onAllowedChange(true);
							}}
						>
							Publish figures
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</Card>
	);
}
