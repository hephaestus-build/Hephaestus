import { Link } from "@tanstack/react-router";
import { useId, useState } from "react";

import type { WorkspaceOnboardingLink, WorkspaceOnboardingSettings } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageLayout } from "@/components/core/PageLayout";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";

type State =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; settings: WorkspaceOnboardingSettings; links: WorkspaceOnboardingLink[] };
export interface WorkspaceOnboardingSettingsPageProps {
	workspaceSlug: string;
	state: State;
	saving?: boolean;
	saveError?: string;
	onSave: (settings: WorkspaceOnboardingSettings) => void;
}

export function WorkspaceOnboardingSettingsPage(props: WorkspaceOnboardingSettingsPageProps) {
	return (
		<PageLayout className="max-w-3xl space-y-6">
			<header className="space-y-2">
				<h1 className="text-2xl font-semibold">Member onboarding</h1>
				<p className="text-sm text-muted-foreground">
					Welcome developers after they join this workspace. This does not grant membership or
					change how people join.
				</p>
			</header>
			{props.state.status === "loading" ? (
				<div
					role="region"
					aria-label="Loading onboarding settings"
					aria-busy="true"
					className="space-y-6"
				>
					<Skeleton className="h-20 w-full" />
					<Skeleton className="h-48 w-full" />
					<Skeleton className="h-32 w-full" />
				</div>
			) : props.state.status === "error" ? (
				<QueryErrorAlert
					title="Couldn't load onboarding settings"
					error={props.state.error}
					onRetry={props.state.onRetry}
				/>
			) : (
				<SettingsForm
					key={`${props.workspaceSlug}:${props.state.settings.revision}`}
					{...props}
					settings={props.state.settings}
					links={props.state.links}
				/>
			)}
		</PageLayout>
	);
}

function SettingsForm({
	workspaceSlug,
	settings,
	links,
	saving,
	saveError,
	onSave,
}: WorkspaceOnboardingSettingsPageProps & {
	settings: WorkspaceOnboardingSettings;
	links: WorkspaceOnboardingLink[];
}) {
	const [draft, setDraft] = useState(settings);
	const id = useId();
	const changed =
		draft.enabled !== settings.enabled ||
		draft.welcomeMarkdown !== settings.welcomeMarkdown ||
		draft.requiredConnectionIds.length !== settings.requiredConnectionIds.length ||
		draft.requiredConnectionIds.some((value) => !settings.requiredConnectionIds.includes(value));
	return (
		<form
			className="space-y-6"
			onSubmit={(event) => {
				event.preventDefault();
				onSave(draft);
			}}
		>
			<FieldGroup>
				<Field orientation="horizontal">
					<Switch
						id={`${id}-enabled`}
						checked={draft.enabled}
						onCheckedChange={(enabled) => setDraft({ ...draft, enabled })}
						disabled={saving}
					/>
					<div>
						<FieldLabel htmlFor={`${id}-enabled`}>Show a welcome on first visit</FieldLabel>
						<FieldDescription>
							Members can finish now or return later. Enabling this requires an explicit AI choice
							before new personal AI activity. Hiding the welcome again never restores AI permission
							or clears saved choices.
						</FieldDescription>
					</div>
				</Field>
				<Field>
					<FieldLabel htmlFor={`${id}-welcome`}>Welcome from your team</FieldLabel>
					<Textarea
						id={`${id}-welcome`}
						value={draft.welcomeMarkdown}
						maxLength={20000}
						rows={7}
						disabled={saving}
						onChange={(event) => setDraft({ ...draft, welcomeMarkdown: event.target.value })}
						placeholder="What should new members know about working with your team?"
					/>
					<FieldDescription>
						Optional Markdown. Explain your team's practices and where to find help. Leave empty to
						use only the standard introduction.
					</FieldDescription>
				</Field>
			</FieldGroup>
			<section className="space-y-4 rounded-xl border p-5" aria-labelledby={`${id}-links`}>
				<div>
					<h2 id={`${id}-links`} className="font-semibold">
						Required account links
					</h2>
					<p className="mt-1 text-sm text-muted-foreground">
						Members must connect these accounts to finish setup, but can always save No AI or
						continue to the workspace. Clear a requirement to make that link optional again.
					</p>
				</div>
				{links.length === 0 ? (
					<p className="text-sm text-muted-foreground">
						No Slack or Outline integrations are configured. Connect a service and enable its
						sign-in provider before requiring it here.
					</p>
				) : (
					<FieldGroup>
						{links.map((link) => (
							<Field key={link.connectionId} orientation="horizontal">
								<Checkbox
									id={`${id}-link-${link.connectionId}`}
									checked={draft.requiredConnectionIds.includes(link.connectionId)}
									disabled={
										saving === true ||
										(!link.available && !draft.requiredConnectionIds.includes(link.connectionId))
									}
									onCheckedChange={(checked) =>
										setDraft({
											...draft,
											requiredConnectionIds: checked
												? [...draft.requiredConnectionIds, link.connectionId]
												: draft.requiredConnectionIds.filter(
														(value) => value !== link.connectionId,
													),
										})
									}
								/>
								<div>
									<FieldLabel htmlFor={`${id}-link-${link.connectionId}`}>
										{link.displayName}
									</FieldLabel>
									<FieldDescription>
										{link.teamName ? `${link.teamName}. ` : ""}
										{link.available
											? "Require this workspace account to finish setup."
											: "Unavailable. Repair the integration or remove this requirement."}
									</FieldDescription>
								</div>
							</Field>
						))}
					</FieldGroup>
				)}
			</section>
			<section className="space-y-2 rounded-xl border p-5" aria-labelledby={`${id}-models`}>
				<h2 id={`${id}-models`} className="font-semibold">
					AI processing locations
				</h2>
				<p className="text-sm leading-relaxed text-muted-foreground">
					Classify each model as On-premises or Private cloud, then assign models separately to
					practice reviews and Heph for each location. Only ready locations appear as available
					choices. Unclassified models and workspace-default assignments are never fallbacks for a
					member's chosen location. No AI is always available.
				</p>
				<Button
					variant="outline"
					nativeButton={false}
					render={<Link to="/w/$workspaceSlug/admin/models" params={{ workspaceSlug }} />}
				>
					Configure AI models
				</Button>
			</section>
			{saveError && (
				<Alert variant="destructive">
					<AlertTitle>Couldn't save onboarding settings</AlertTitle>
					<AlertDescription>{saveError}</AlertDescription>
				</Alert>
			)}
			<div className="flex flex-wrap gap-3">
				<Button type="submit" disabled={saving === true || !changed}>
					{saving && <Spinner />}
					{saving ? "Saving…" : "Save onboarding settings"}
				</Button>
				<Button
					variant="outline"
					type="button"
					disabled={saving === true || !changed}
					onClick={() => setDraft(settings)}
				>
					Discard changes
				</Button>
			</div>
		</form>
	);
}
