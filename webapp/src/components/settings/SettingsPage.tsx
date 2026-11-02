import { UserRoundCog } from "lucide-react";
import { useState, type ReactNode } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import { AiChoiceSection, type AiChoiceSectionProps } from "./AiChoiceSection";
import { DangerZoneSection } from "./DangerZoneSection";
import {
	EmailPreferencesSection,
	hasEmailPreferences,
	type EmailPreferencesSectionProps,
} from "./EmailPreferencesSection";
import { LinkedAccountsSection, type LinkedAccountsSectionProps } from "./LinkedAccountsSection";
import {
	PracticeFeedbackSection,
	type PracticeFeedbackSectionProps,
} from "./PracticeFeedbackSection";
import {
	hasProductFeedbackSharing,
	ProductFeedbackSharingSection,
	type ProductFeedbackSharingSectionProps,
} from "./ProductFeedbackSharingSection";
import { PublicActivitySection, type PublicActivitySectionProps } from "./PublicActivitySection";
import {
	ResearchParticipationSection,
	type ResearchParticipationSectionProps,
} from "./ResearchParticipationSection";
import { SessionsSection, type SessionsSectionProps } from "./SessionsSection";
import {
	SlackPreferencesSection,
	type SlackPreferencesSectionProps,
} from "./SlackPreferencesSection";
import {
	hasWeeklyDigest,
	WeeklyDigestSection,
	type WeeklyDigestSectionProps,
} from "./WeeklyDigestSection";

export interface SettingsPageProps {
	passkeys?: ReactNode;
	emailPreferencesProps: EmailPreferencesSectionProps;
	weeklyDigestProps: WeeklyDigestSectionProps;
	practiceFeedbackProps: PracticeFeedbackSectionProps;
	researchProps: ResearchParticipationSectionProps;
	showResearchSection: boolean;
	aiChoiceProps: AiChoiceSectionProps;
	publicActivityProps: PublicActivitySectionProps;
	/** Absent, or with no public repository on the instance, the section is not shown. */
	productFeedbackSharingProps?: ProductFeedbackSharingSectionProps;
	linkedAccountsProps: LinkedAccountsSectionProps;
	slackPreferencesProps: SlackPreferencesSectionProps;
	showSlackPreferencesSection?: boolean;
	sessionsProps: SessionsSectionProps;
	onAccountDeleted: () => void | Promise<void>;
	isLoading?: boolean;
	settingsError?: boolean;
	onRetrySettings?: () => void;
}

export function SettingsPage({
	passkeys,
	emailPreferencesProps,
	weeklyDigestProps,
	practiceFeedbackProps,
	researchProps,
	showResearchSection,
	aiChoiceProps,
	publicActivityProps,
	productFeedbackSharingProps,
	linkedAccountsProps,
	slackPreferencesProps,
	showSlackPreferencesSection = true,
	sessionsProps,
	onAccountDeleted,
	isLoading = false,
	settingsError = false,
	onRetrySettings,
}: SettingsPageProps) {
	const { isLoading: practiceFeedbackLoading = false, ...practiceFeedbackRest } =
		practiceFeedbackProps;
	const { isLoading: researchLoading = false, ...researchRest } = researchProps;
	const { isLoading: aiChoiceLoading = false, ...aiChoiceRest } = aiChoiceProps;
	const { isLoading: linkedLoading = false, ...linkedRest } = linkedAccountsProps;
	const { isLoading: slackLoading = false, ...slackRest } = slackPreferencesProps;

	const practiceFeedbackPending = isLoading || practiceFeedbackLoading;

	// Once shown with its switch, the weekly digest stays for this visit: turning it off where none
	// is written would otherwise remove the switch the reader just used.
	const digestState = weeklyDigestProps.state;
	const [keepWeeklyDigest, setKeepWeeklyDigest] = useState(false);
	if (digestState.status === "ready" && hasWeeklyDigest(digestState) && !keepWeeklyDigest) {
		setKeepWeeklyDigest(true);
	}
	const showWeeklyDigest = keepWeeklyDigest || hasWeeklyDigest(digestState);

	const destinations = [
		{ id: "connected-accounts", label: "Connected accounts" },
		...(passkeys === undefined ? [] : [{ id: "passkeys", label: "Passkeys" }]),
		{ id: "preferences", label: "Preferences" },
		...(showResearchSection ? [{ id: "research", label: "Research participation" }] : []),
		{ id: "danger-zone", label: "Danger zone" },
	];

	return (
		<PageLayout>
			<PageHeader
				icon={<UserRoundCog />}
				title="User settings"
				description="Manage your preferences, connected accounts, passkeys, and sessions."
			/>

			<div className="max-w-3xl space-y-8">
				{/* Links to the groups below, named as they render: a section that is not shown is not listed. */}
				<nav aria-label="Settings sections">
					<ul className="flex flex-wrap gap-x-4 gap-y-2 text-sm">
						{destinations.map(({ id, label }) => (
							<li key={id}>
								<InlineLink href={`#${id}`}>{label}</InlineLink>
							</li>
						))}
					</ul>
				</nav>

				{/* The account group, under the link to its first heading: how you sign in, your passkeys,
				    and where you are signed in. */}
				<Separator />
				<div id="connected-accounts" className="scroll-mt-20 space-y-8">
					<LinkedAccountsSection {...linkedRest} isLoading={linkedLoading} />
					{passkeys !== undefined && (
						<>
							<Separator />
							<div id="passkeys" className="scroll-mt-20">
								{passkeys}
							</div>
						</>
					)}
					<Separator />
					<SessionsSection {...sessionsProps} />
				</div>

				<Separator />
				<div id="preferences" className="scroll-mt-20 space-y-8">
					{/* Only the setting the preferences read holds waits on it; every other section has its own read. */}
					{settingsError ? (
						<section className="space-y-2" aria-labelledby="settings-error-heading">
							<h2 id="settings-error-heading" className="text-xl font-semibold">
								Preferences
							</h2>
							<p className="text-sm text-destructive" role="alert">
								We could not load your preferences, so your practice feedback setting is not shown.
							</p>
							{onRetrySettings && (
								<Button variant="outline" size="sm" onClick={onRetrySettings}>
									Retry
								</Button>
							)}
						</section>
					) : (
						<PracticeFeedbackSection
							{...practiceFeedbackRest}
							isLoading={practiceFeedbackPending}
						/>
					)}

					<Separator />
					<AiChoiceSection {...aiChoiceRest} isLoading={aiChoiceLoading} />

					<Separator />
					<PublicActivitySection {...publicActivityProps} />

					{productFeedbackSharingProps !== undefined &&
						hasProductFeedbackSharing(productFeedbackSharingProps.state) && (
							<>
								<Separator />
								<ProductFeedbackSharingSection {...productFeedbackSharingProps} />
							</>
						)}

					{showWeeklyDigest && (
						<>
							<Separator />
							<WeeklyDigestSection {...weeklyDigestProps} />
						</>
					)}

					{hasEmailPreferences(emailPreferencesProps.state) && (
						<>
							<Separator />
							<EmailPreferencesSection {...emailPreferencesProps} />
						</>
					)}

					{showSlackPreferencesSection && (
						<>
							<Separator />
							<SlackPreferencesSection {...slackRest} isLoading={slackLoading} />
						</>
					)}
				</div>

				{showResearchSection && (
					<>
						<Separator />
						<div id="research" className="scroll-mt-20">
							<ResearchParticipationSection {...researchRest} isLoading={researchLoading} />
						</div>
					</>
				)}

				<Separator />
				<div id="danger-zone" className="scroll-mt-20">
					<DangerZoneSection onAccountDeleted={onAccountDeleted} />
				</div>
			</div>
		</PageLayout>
	);
}
