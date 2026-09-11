import { Link } from "@tanstack/react-router";
import { HandshakeIcon, Link2Icon } from "lucide-react";
import { useId, useRef, useState } from "react";

import type { WorkspaceOnboardingLink, WorkspaceOnboardingSettings } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import { Section } from "@/components/core/Section";
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
// A plain `Link` in button clothes rather than `Button render={<Link/>}`: with `nativeButton={false}`
// Base UI stamps `role="button"` on the anchor, and both of these are navigations.
import { Button, buttonVariants } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";

export type SettingsSubmission =
	| { status: "idle" }
	| { status: "saving" }
	| { status: "error"; message: string };

export interface WorkspaceOnboardingSettingsPageProps {
	workspaceSlug: string;
	state:
		| { status: "loading" }
		| { status: "error"; error: unknown; onRetry: () => void }
		| {
				status: "ready";
				settings: WorkspaceOnboardingSettings;
				links: WorkspaceOnboardingLink[];
				submission: SettingsSubmission;
				/**
				 * Resolves once the server holds the draft, which is when it folds into `settings`; a
				 * rejection keeps the draft for a retry and is reported through `submission`.
				 */
				onSave: (settings: WorkspaceOnboardingSettings) => Promise<unknown>;
		  };
}

type SettingsFormProps = Pick<WorkspaceOnboardingSettingsPageProps, "workspaceSlug"> &
	Omit<Extract<WorkspaceOnboardingSettingsPageProps["state"], { status: "ready" }>, "status">;

export function WorkspaceOnboardingSettingsPage({
	workspaceSlug,
	state,
}: WorkspaceOnboardingSettingsPageProps) {
	return (
		<PageLayout className="max-w-3xl">
			<PageHeader
				icon={<HandshakeIcon />}
				title="Member onboarding"
				description="Welcome developers after they join this workspace. This does not grant membership or change how people join."
			/>
			{state.status === "loading" ? (
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
			) : state.status === "error" ? (
				<QueryErrorAlert
					title="Couldn't load onboarding settings"
					error={state.error}
					onRetry={state.onRetry}
				/>
			) : (
				<SettingsForm key={workspaceSlug} workspaceSlug={workspaceSlug} {...state} />
			)}
		</PageLayout>
	);
}

interface Edits {
	/** The revision the reader started from; the save carries it so the server can refuse a stale one. */
	baseRevision: number;
	patch: Partial<WorkspaceOnboardingSettings>;
	/**
	 * Set when a save of this draft was refused. `submission` outlives the draft — the route keeps
	 * the last mutation's error until the next one — so the alert is shown for the draft it is about
	 * and not for one started after Discard.
	 */
	refused?: true;
}

/** The server treats the ids as a set; unchecking and re-checking one must not read as a change. */
function sameIds(a: readonly number[], b: readonly number[]) {
	return a.length === b.length && a.every((id) => b.includes(id));
}

function sameSettings(a: WorkspaceOnboardingSettings, b: WorkspaceOnboardingSettings) {
	return (
		a.enabled === b.enabled &&
		a.welcomeMarkdown === b.welcomeMarkdown &&
		sameIds(a.requiredConnectionIds, b.requiredConnectionIds)
	);
}

/**
 * Keyed on the workspace only, so a save or another owner's change reaches the form as new
 * `settings` while it stays mounted. Nothing is copied into state: a pristine form renders
 * `settings` as they arrive, and an edited one renders `settings` under its patch.
 */
function SettingsForm({ workspaceSlug, settings, links, submission, onSave }: SettingsFormProps) {
	const id = useId();
	const [edits, setEdits] = useState<Edits>();
	const draft = { ...settings, ...edits?.patch };
	const changed = edits !== undefined && !sameSettings(draft, settings);
	// A revision that moved under a draft that now equals it has nothing to lose, so it is not
	// announced: the post-save frame, before the draft folds, would otherwise read as a conflict.
	const conflicted = edits !== undefined && changed && edits.baseRevision !== settings.revision;
	const saving = submission.status === "saving";
	// Not `track`ed: the hook's latch stays down after a resolved save, which is right for a form that
	// navigates away and wrong for one that stays. Here success folds the draft, so the guard drops
	// because there is nothing dirty left.
	const guard = useUnsavedChanges({ isDirty: changed, disabled: saving });
	const enabledRef = useRef<HTMLElement>(null);

	const edit = (patch: Partial<WorkspaceOnboardingSettings>) =>
		setEdits((current) => ({
			...current,
			baseRevision: current?.baseRevision ?? settings.revision,
			patch: { ...current?.patch, ...patch },
		}));
	const save = async () => {
		if (!edits || !changed || conflicted) return;
		try {
			await onSave({ ...draft, revision: edits.baseRevision });
			setEdits(undefined);
		} catch {
			setEdits((current) => current && { ...current, refused: true });
		}
	};
	// The button that loads them unmounts with its alert, so focus is handed to the first control
	// rather than dropped on the body.
	const loadCurrent = () => {
		setEdits(undefined);
		enabledRef.current?.focus();
	};

	return (
		<form
			className="space-y-8"
			onSubmit={(event) => {
				event.preventDefault();
				void save();
			}}
		>
			{guard.dialog}
			<Section
				title="Welcome"
				actions={
					<Link
						to="/w/$workspaceSlug/admin/models"
						params={{ workspaceSlug }}
						className={buttonVariants({ variant: "outline" })}
					>
						Configure AI models
					</Link>
				}
			>
				<FieldGroup>
					<Field orientation="horizontal">
						<Switch
							ref={enabledRef}
							id={`${id}-enabled`}
							checked={draft.enabled}
							onCheckedChange={(enabled) => edit({ enabled })}
							disabled={saving}
							aria-describedby={`${id}-enabled-description`}
						/>
						<FieldContent>
							<FieldLabel htmlFor={`${id}-enabled`}>Show a welcome on first visit</FieldLabel>
							<FieldDescription id={`${id}-enabled-description`}>
								Members see the welcome once, on their next visit. They can finish it later.
							</FieldDescription>
						</FieldContent>
					</Field>
					<Field>
						<FieldLabel htmlFor={`${id}-welcome`}>Welcome from your team</FieldLabel>
						<Textarea
							id={`${id}-welcome`}
							value={draft.welcomeMarkdown}
							maxLength={20000}
							rows={7}
							disabled={saving}
							aria-describedby={`${id}-welcome-description`}
							onChange={(event) => edit({ welcomeMarkdown: event.target.value })}
							placeholder="What should new members know about working with your team?"
						/>
						<FieldDescription id={`${id}-welcome-description`}>
							Optional Markdown, shown open the first time a member arrives.
						</FieldDescription>
					</Field>
				</FieldGroup>
			</Section>
			<Section
				title="Required account links"
				description={
					links.length > 0
						? "Members connect these to finish setup. A link that is unavailable never holds a member up; clear a requirement to make the link optional."
						: undefined
				}
			>
				{links.length === 0 ? (
					<Empty className="border">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<Link2Icon />
							</EmptyMedia>
							<EmptyTitle>No integrations to require</EmptyTitle>
							<EmptyDescription>
								Connect Slack or Outline under Integrations. If an integration is active but still
								unavailable here, ask your instance admin to enable account linking for it.
							</EmptyDescription>
						</EmptyHeader>
						<EmptyContent>
							<Link
								to="/w/$workspaceSlug/admin/integrations"
								params={{ workspaceSlug }}
								className={buttonVariants({ variant: "outline" })}
							>
								Integrations
							</Link>
						</EmptyContent>
					</Empty>
				) : (
					<div className="grid gap-3">
						{links.map((link) => {
							const required = draft.requiredConnectionIds.includes(link.connectionId);
							// Clearing a broken link's requirement must stay possible, so only an unavailable
							// link that is not yet required is out of reach.
							const unavailable = !link.available && !required;
							const detail = link.available
								? link.teamName
								: required
									? "Unavailable — repair it under Integrations or clear this requirement."
									: "Unavailable — repair it under Integrations before requiring it.";
							const controlId = `${id}-link-${link.connectionId}`;
							return (
								<FieldLabel key={link.connectionId} htmlFor={controlId}>
									<Field orientation="horizontal" data-disabled={unavailable || undefined}>
										<Checkbox
											id={controlId}
											checked={required}
											disabled={saving || unavailable}
											aria-labelledby={`${controlId}-title`}
											aria-describedby={detail ? `${controlId}-detail` : undefined}
											onCheckedChange={(checked) =>
												edit({
													requiredConnectionIds: checked
														? [...draft.requiredConnectionIds, link.connectionId]
														: draft.requiredConnectionIds.filter(
																(value) => value !== link.connectionId,
															),
												})
											}
										/>
										<FieldContent>
											<FieldTitle id={`${controlId}-title`}>{link.displayName}</FieldTitle>
											{detail && (
												<FieldDescription id={`${controlId}-detail`}>{detail}</FieldDescription>
											)}
										</FieldContent>
									</Field>
								</FieldLabel>
							);
						})}
					</div>
				)}
			</Section>
			{conflicted && (
				<Alert variant="warning">
					<AlertTitle id={`${id}-conflict`}>
						Someone changed these settings while you were editing
					</AlertTitle>
					<AlertDescription>
						Load the current settings to keep editing. Your draft will be replaced.
					</AlertDescription>
					<AlertAction>
						<Button type="button" variant="outline" size="sm" onClick={loadCurrent}>
							Load current settings
						</Button>
					</AlertAction>
				</Alert>
			)}
			{submission.status === "error" && edits?.refused && (
				<Alert variant="destructive">
					<AlertTitle>Couldn't save onboarding settings</AlertTitle>
					<AlertDescription>{submission.message}</AlertDescription>
				</Alert>
			)}
			<div className="flex flex-wrap gap-3">
				<Button
					type="submit"
					disabled={saving || !changed || conflicted}
					aria-describedby={conflicted ? `${id}-conflict` : undefined}
				>
					{saving && <Spinner />}
					{saving ? "Saving…" : "Save onboarding settings"}
				</Button>
				<Button
					type="button"
					variant="outline"
					disabled={saving || !changed}
					onClick={() => setEdits(undefined)}
				>
					Discard changes
				</Button>
			</div>
		</form>
	);
}
