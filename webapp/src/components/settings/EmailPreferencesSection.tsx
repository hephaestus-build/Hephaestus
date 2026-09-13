import { useId } from "react";

import type { UpdateNotificationPreferences } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";

export type EmailNotificationChoices = UpdateNotificationPreferences;

type EmailPreferencesState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			preferences: EmailNotificationChoices & { emailAvailable: boolean };
			isPending: boolean;
			onChange: (choices: EmailNotificationChoices) => void;
	  };

export interface EmailPreferencesSectionProps {
	state: EmailPreferencesState;
	isAppAdmin: boolean;
}

const frequencyItems = [
	{ value: "IMMEDIATE", label: "Immediately" },
	{ value: "DAILY", label: "Daily summary" },
] as const;

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
			"Email invitations to academic research surveys. These also require your current research participation consent. This email choice does not grant or withdraw that consent.",
	},
	{
		key: "productFeedback",
		label: "New product feedback",
		description: "Email notifications when someone submits product feedback to this instance.",
	},
] as const;

export function EmailPreferencesSection({ state, isAppAdmin }: EmailPreferencesSectionProps) {
	const id = useId();
	const visibleChoices = choices.filter(
		(choice) =>
			(choice.key !== "productFeedback" && choice.key !== "surveySummaries") || isAppAdmin,
	);

	const change = (patch: Partial<EmailNotificationChoices>) => {
		if (state.status !== "ready" || state.isPending) return;
		state.onChange({
			productFeedback: state.preferences.productFeedback,
			workspaceAlerts: state.preferences.workspaceAlerts,
			surveySummaries: state.preferences.surveySummaries,
			productFeedbackFrequency: state.preferences.productFeedbackFrequency,
			productSurveys: state.preferences.productSurveys,
			researchSurveys: state.preferences.researchSurveys,
			...patch,
		});
	};

	return (
		<section className="space-y-4" aria-labelledby={`${id}-heading`}>
			<div className="space-y-1">
				<h2 id={`${id}-heading`} className="text-xl font-semibold">
					Email notifications
				</h2>
				<p className="text-sm text-muted-foreground">
					Optional emails are off until you choose to receive them. You can turn each kind off at
					any time. Essential account emails, such as account-deletion confirmations, are separate.
				</p>
			</div>
			{state.status === "loading" ? (
				<div className="space-y-5" aria-hidden>
					{visibleChoices.map((choice) => (
						<div key={choice.key} className="flex items-center gap-4">
							<div className="flex-1 space-y-2">
								<Skeleton className="h-4 w-48" />
								<Skeleton className="h-10 w-full" />
							</div>
							<Skeleton className="h-5 w-8 rounded-full" />
						</div>
					))}
				</div>
			) : state.status === "error" ? (
				<QueryErrorAlert
					title="Could not load your email choices"
					error={state.error}
					onRetry={state.onRetry}
				/>
			) : (
				<>
					{!state.preferences.emailAvailable && (
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
										state.isPending ||
										(!state.preferences.emailAvailable && !state.preferences[choice.key])
									}
									aria-busy={state.isPending}
									onCheckedChange={(checked) => change({ [choice.key]: checked })}
								/>
							</Field>
						))}
						{isAppAdmin && state.preferences.productFeedback && (
							<Field orientation="responsive">
								<FieldContent>
									<FieldLabel id={`${id}-frequency-label`} htmlFor={`${id}-frequency`}>
										Product feedback frequency
									</FieldLabel>
									<FieldDescription id={`${id}-frequency-description`}>
										Daily summaries cover the previous UTC calendar day and are prepared at 08:00
										UTC.
									</FieldDescription>
								</FieldContent>
								<Select
									items={frequencyItems}
									value={state.preferences.productFeedbackFrequency}
									disabled={state.isPending}
									onValueChange={(value) => {
										if (value) change({ productFeedbackFrequency: value });
									}}
								>
									<SelectTrigger
										id={`${id}-frequency`}
										aria-describedby={`${id}-frequency-description`}
										className="w-full @md/field-group:w-56"
									>
										<SelectValue />
									</SelectTrigger>
									<SelectContent aria-labelledby={`${id}-frequency-label`}>
										{frequencyItems.map((item) => (
											<SelectItem key={item.value} value={item.value}>
												{item.label}
											</SelectItem>
										))}
									</SelectContent>
								</Select>
							</Field>
						)}
					</FieldGroup>
				</>
			)}
		</section>
	);
}
