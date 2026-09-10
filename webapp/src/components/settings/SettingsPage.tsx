import type { ReactNode } from "react";

import { UserRoundCog } from "lucide-react";

import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { optionalIntegrationsAvailable } from "@/integrations/consent";

import { CookiePreferencesSection } from "./CookiePreferencesSection";
import { DangerZoneSection } from "./DangerZoneSection";
import { LinkedAccountsSection, type LinkedAccountsSectionProps } from "./LinkedAccountsSection";
import {
	PracticeFeedbackSection,
	type PracticeFeedbackSectionProps,
} from "./PracticeFeedbackSection";
import {
	ResearchParticipationSection,
	type ResearchParticipationSectionProps,
} from "./ResearchParticipationSection";
import { SessionsSection } from "./SessionsSection";
import {
	SlackPreferencesSection,
	type SlackPreferencesSectionProps,
} from "./SlackPreferencesSection";

export interface SettingsPageProps {
	accountId?: string;
	workspaceAccessSection?: ReactNode;
	practiceFeedbackProps: PracticeFeedbackSectionProps;
	researchProps: ResearchParticipationSectionProps;
	showResearchSection: boolean;
	linkedAccountsProps: LinkedAccountsSectionProps;
	slackPreferencesProps: SlackPreferencesSectionProps;
	showSlackPreferencesSection?: boolean;
	onAccountDeleted: () => void | Promise<void>;
	isLoading?: boolean;
	settingsError?: boolean;
	needsScmIdentity?: boolean;
	onRetrySettings?: () => void;
}

export function SettingsPage({
	accountId,
	workspaceAccessSection,
	practiceFeedbackProps,
	researchProps,
	showResearchSection,
	linkedAccountsProps,
	slackPreferencesProps,
	showSlackPreferencesSection = true,
	onAccountDeleted,
	isLoading = false,
	settingsError = false,
	needsScmIdentity = false,
	onRetrySettings,
}: SettingsPageProps) {
	const { isLoading: practiceFeedbackLoading = false, ...practiceFeedbackRest } =
		practiceFeedbackProps;
	const { isLoading: researchLoading = false, ...researchRest } = researchProps;
	const { isLoading: linkedLoading = false, ...linkedRest } = linkedAccountsProps;
	const { isLoading: slackLoading = false, ...slackRest } = slackPreferencesProps;

	const practiceFeedbackPending = isLoading || practiceFeedbackLoading;
	const researchPending = researchLoading;

	return (
		<PageLayout>
			<PageHeader
				icon={<UserRoundCog />}
				title="User settings"
				description="Manage your preferences, connected accounts, and sessions."
			/>

			<div className="max-w-3xl space-y-8">
				{accountId && (
					<section className="space-y-1" aria-label="Account identity">
						<p className="text-sm">
							Your account ID: <code className="font-mono">{accountId}</code>
						</p>
						<p className="text-sm text-muted-foreground">
							Share this ID with a workspace owner who wants to add you. It stays the same when you
							connect another sign-in method.
						</p>
					</section>
				)}
				{workspaceAccessSection}
				{needsScmIdentity ? (
					<section className="space-y-2" aria-labelledby="preferences-identity-heading">
						<h2 id="preferences-identity-heading" className="text-xl font-semibold">
							Practice feedback preferences
						</h2>
						<p className="text-sm text-muted-foreground">
							Connect a GitHub or GitLab account below to configure practice feedback about your
							work.
						</p>
					</section>
				) : settingsError ? (
					<>
						<Separator />
						<section className="space-y-2" aria-labelledby="settings-error-heading">
							<h2 id="settings-error-heading" className="text-xl font-semibold">
								Preferences
							</h2>
							<p className="text-sm text-destructive" role="alert">
								We couldn't load your practice feedback preferences.
							</p>
							{onRetrySettings && (
								<Button variant="outline" size="sm" onClick={onRetrySettings}>
									Retry
								</Button>
							)}
						</section>
					</>
				) : (
					<>
						<Separator />
						<PracticeFeedbackSection
							{...practiceFeedbackRest}
							isLoading={practiceFeedbackPending}
						/>
					</>
				)}
				{showResearchSection && (
					<>
						<Separator />
						<ResearchParticipationSection {...researchRest} isLoading={researchPending} />
					</>
				)}

				<Separator />
				<LinkedAccountsSection {...linkedRest} isLoading={linkedLoading} />

				{showSlackPreferencesSection && (
					<>
						<Separator />
						<SlackPreferencesSection {...slackRest} isLoading={slackLoading} />
					</>
				)}

				<Separator />
				<SessionsSection />

				{optionalIntegrationsAvailable && (
					<>
						<Separator />
						<CookiePreferencesSection />
					</>
				)}

				<Separator />
				<DangerZoneSection onAccountDeleted={onAccountDeleted} />
			</div>
		</PageLayout>
	);
}
