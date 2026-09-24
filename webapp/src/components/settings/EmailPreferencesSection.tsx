import { useId } from "react";
import { useSpinDelay } from "spin-delay";

import type { NotificationPreferences, UpdateNotificationPreferences } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";

export type EmailNotificationChoices = UpdateNotificationPreferences;

type EmailPreferencesState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			preferences: Omit<NotificationPreferences, "etag">;
			isPending: boolean;
			onChange: (choices: EmailNotificationChoices) => void;
	  };

export interface EmailPreferencesSectionProps {
	state: EmailPreferencesState;
	isAppAdmin: boolean;
	researchAvailable: boolean;
}

const choices = [
	{
		key: "surveySummaries",
		label: "Survey summaries",
		description:
			"Email aggregate participation summaries when a survey ends. Individual answers are never included.",
	},
	{
		key: "workspaceAlerts",
		label: "Workspace connection alerts",
		description:
			"Email alerts about revoked Slack access, suspended GitHub access, and recovery for workspaces you currently administer.",
	},
	{
		key: "productSurveys",
		label: "Product survey invitations",
		description: "Email invitations to help improve Hephaestus through product surveys.",
	},
	{
		key: "researchSurveys",
		label: "Research survey invitations",
		description:
			"Receive academic research survey invitations by email when you also consent to research participation. Turning this off stops email invitations, not your research participation.",
	},
	{
		key: "productFeedback",
		label: "New product feedback",
		description:
			"Get an email when new product feedback arrives. Review it in the private instance-admin inbox; resolving it does not notify the sender.",
	},
] as const;

export function hasEmailPreferences(state: EmailPreferencesState) {
	return (
		state.status !== "ready" ||
		state.preferences.deliveryConfigured ||
		choices.some((choice) => state.preferences[choice.key])
	);
}

export function EmailPreferencesSection({
	state,
	isAppAdmin,
	researchAvailable,
}: EmailPreferencesSectionProps) {
	const id = useId();
	const showSpinner = useSpinDelay(state.status === "ready" && state.isPending, {
		delay: 1000,
		minDuration: 500,
	});
	const busy = state.status === "ready" && (state.isPending || showSpinner);
	const optOutOnly = state.status === "ready" && !state.preferences.deliveryConfigured;
	const visibleChoices = choices.filter((choice) => {
		if (optOutOnly) {
			return state.preferences[choice.key];
		}
		if (choice.key === "researchSurveys") {
			return researchAvailable || (state.status === "ready" && state.preferences.researchSurveys);
		}
		return (
			(choice.key !== "productFeedback" && choice.key !== "surveySummaries") ||
			isAppAdmin ||
			(state.status === "ready" && state.preferences[choice.key])
		);
	});

	if (!hasEmailPreferences(state)) {
		return null;
	}

	const change = (patch: Partial<EmailNotificationChoices>) => {
		if (state.status !== "ready" || busy) {
			return;
		}
		state.onChange({
			productFeedback: state.preferences.productFeedback,
			workspaceAlerts: state.preferences.workspaceAlerts,
			surveySummaries: state.preferences.surveySummaries,
			productSurveys: state.preferences.productSurveys,
			researchSurveys: state.preferences.researchSurveys,
			...patch,
		});
	};

	return (
		<section className="space-y-4" aria-labelledby={`${id}-heading`}>
			<div className="space-y-1">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1">
					<h2 id={`${id}-heading`} className="text-xl font-semibold">
						Email notifications
					</h2>
					<p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
						{showSpinner && state.status === "ready" && (
							<>
								<Spinner />
								Saving email choices…
							</>
						)}
					</p>
				</div>
				<p className="text-sm text-muted-foreground">
					{optOutOnly
						? "You can turn off your existing email subscriptions below."
						: "Optional emails are off until you choose to receive them. You can turn each kind off at any time. Essential account emails, such as account-deletion confirmations, are separate."}
				</p>
			</div>
			{state.status === "loading" && (
				<div className="space-y-5" aria-hidden>
					{visibleChoices.map((choice) => (
						<div key={choice.key} className="flex items-center gap-4">
							<div className="flex-1 space-y-2">
								<Skeleton className="h-4 w-48" />
								<Skeleton className="h-10 w-full" />
							</div>
							<Skeleton className="h-5 w-8" />
						</div>
					))}
				</div>
			)}
			{state.status === "error" && (
				<QueryErrorAlert
					title="Could not load your email choices"
					error={state.error}
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && (
				<>
					{!optOutOnly &&
						!isAppAdmin &&
						(state.preferences.productFeedback || state.preferences.surveySummaries) && (
							<p className="text-sm text-muted-foreground">
								You can turn off your previous administrator subscriptions here. They do not send
								while you lack instance-admin access.
							</p>
						)}
					{!optOutOnly && !state.preferences.emailAvailable && (
						<p className="text-sm text-muted-foreground">
							No verified email address is available for your account. A linked sign-in provider
							must supply a verified address before you can turn on another email kind. You can
							still turn off existing subscriptions. Changing these choices does not add or verify
							an address.
						</p>
					)}
					<FieldGroup>
						{visibleChoices.map((choice) => (
							<Field key={choice.key} orientation="horizontal">
								<FieldContent>
									<FieldLabel htmlFor={`${id}-${choice.key}`}>{choice.label}</FieldLabel>
									<FieldDescription id={`${id}-${choice.key}-description`}>
										{choice.description}
									</FieldDescription>
								</FieldContent>
								<Switch
									id={`${id}-${choice.key}`}
									aria-describedby={`${id}-${choice.key}-description`}
									checked={state.preferences[choice.key]}
									disabled={
										busy || (!state.preferences.emailAvailable && !state.preferences[choice.key])
									}
									aria-busy={state.isPending}
									onCheckedChange={(checked) => change({ [choice.key]: checked })}
								/>
							</Field>
						))}
					</FieldGroup>
				</>
			)}
		</section>
	);
}
