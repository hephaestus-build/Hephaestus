import { InfoIcon } from "lucide-react";
import { useId, useRef, useState } from "react";

import type { UserInfo } from "@/api/types.gen";
import { MemberAvatar } from "@/components/activity/MemberAvatar";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Section } from "@/components/layout/Section";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { Button } from "@/components/ui/button";
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

export type WorkspacePublicActivityState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			enabled: boolean;
			allowSearchEngines: boolean;
			/** The page is live: the workspace turned it on and the instance allows it. */
			live: boolean;
			/** How many people the page leaves out, never who; undefined where the count did not load. */
			hiddenPeople: number | undefined;
			/** Who an admin hid without a membership: no other screen can show them again. */
			hiddenContributors: readonly Pick<UserInfo, "id" | "login" | "name" | "avatarUrl">[];
			/** The person being shown again, if any. */
			restoring: number | undefined;
			/** The change in flight, if any. */
			pending: "enabled" | "search-engines" | undefined;
	  };

export interface WorkspacePublicActivitySettingsProps {
	workspaceName: string;
	providerType: ProviderType;
	/** Where the workspace is found, which is where a signed-out visitor finds the page. */
	address: string;
	state: WorkspacePublicActivityState;
	onEnabledChange: (enabled: boolean) => void;
	onSearchEnginesChange: (allow: boolean) => void;
	onShowAgain: (userId: number) => void;
}

/**
 * Whether this workspace shows who contributes to its public repositories to anyone. Turning the
 * page on asks first and says what becomes public; every change takes effect at once.
 */
export function WorkspacePublicActivitySettings({
	workspaceName,
	providerType,
	address,
	state,
	onEnabledChange,
	onSearchEnginesChange,
	onShowAgain,
}: WorkspacePublicActivitySettingsProps) {
	const headingId = useId();
	const [confirming, setConfirming] = useState(false);
	return (
		<section aria-labelledby={headingId}>
			<h2 id={headingId} className="text-lg font-semibold">
				Public activity page
			</h2>
			<p className="mb-4 max-w-2xl text-sm text-muted-foreground">
				Show anyone, without sign-in, who contributes to this workspace’s public repositories.
			</p>
			{state.status === "loading" && <Skeleton className="h-24 rounded-xl" />}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load the public activity page settings"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && (
				<div className="space-y-3">
					<ul className="rounded-xl border bg-card">
						<SettingRow
							title="Publish the page"
							description={
								state.enabled
									? `On. Anyone can see it at ${address}.`
									: "Off. Only signed-in members see this workspace’s activity."
							}
							checked={state.enabled}
							pending={state.pending === "enabled"}
							onCheckedChange={(next) => {
								if (next) {
									setConfirming(true);
								} else {
									onEnabledChange(false);
								}
							}}
						/>
						{state.enabled && (
							<SettingRow
								title="Allow search engines"
								description={
									state.allowSearchEngines
										? "On. Search engines can list the page."
										: "Off. The page asks search engines not to list it. This is a request, not a lock: anyone with the address can open the page."
								}
								checked={state.allowSearchEngines}
								pending={state.pending === "search-engines"}
								onCheckedChange={onSearchEnginesChange}
							/>
						)}
						{state.enabled && state.hiddenPeople !== undefined && (
							<Item render={<li />} variant="row">
								<ItemContent>
									<ItemTitle>Hidden people</ItemTitle>
									<ItemDescription className="line-clamp-none">
										The page leaves out and does not count people who hid themselves, and people an
										admin hid. You see how many, never who.
									</ItemDescription>
								</ItemContent>
								<ItemActions>
									<span className="text-sm font-medium tabular-nums">
										{state.hiddenPeople.toLocaleString("en-GB")}
									</span>
								</ItemActions>
							</Item>
						)}
					</ul>
					{state.enabled && !state.live && (
						<Alert variant="warning">
							<InfoIcon />
							<AlertDescription>
								The page is not public yet. An instance administrator has not allowed public
								activity pages.
							</AlertDescription>
						</Alert>
					)}
					{state.enabled && state.live && (
						<p className="max-w-2xl text-xs text-muted-foreground">
							Signed-in members open their workspace home at this address. To preview the page, open
							it in a private window.
						</p>
					)}
					{state.hiddenContributors.length > 0 && (
						<Section
							level={3}
							size="sm"
							title="Hidden by an admin"
							description="These people have no membership, and an admin hid them from activity. A member is shown again under Members."
						>
							<ul className="rounded-xl border bg-card">
								{state.hiddenContributors.map((person) => (
									<Item key={person.id} render={<li />} variant="row">
										<MemberAvatar user={person} />
										<ItemContent>
											<ItemTitle>{person.name}</ItemTitle>
											<ItemDescription>{person.login}</ItemDescription>
										</ItemContent>
										<ItemActions>
											<Button
												variant="outline"
												size="sm"
												disabled={state.restoring !== undefined}
												focusableWhenDisabled
												onClick={() => onShowAgain(person.id)}
											>
												{state.restoring === person.id && <Spinner />}
												Show again
											</Button>
										</ItemActions>
									</Item>
								))}
							</ul>
						</Section>
					)}
				</div>
			)}
			<PublishDialog
				open={confirming}
				onOpenChange={setConfirming}
				workspaceName={workspaceName}
				providerType={providerType}
				onConfirm={() => {
					setConfirming(false);
					onEnabledChange(true);
				}}
			/>
		</section>
	);
}

function SettingRow({
	title,
	description,
	checked,
	pending,
	onCheckedChange,
}: {
	title: string;
	description: string;
	checked: boolean;
	pending: boolean;
	onCheckedChange: (checked: boolean) => void;
}) {
	const id = useId();
	return (
		<Item render={<li />} variant="row">
			<ItemContent>
				<ItemTitle id={`${id}-title`}>{title}</ItemTitle>
				<ItemDescription id={`${id}-description`} className="line-clamp-none">
					{description}
				</ItemDescription>
			</ItemContent>
			<ItemActions>
				{pending && <Spinner />}
				<Switch
					checked={checked}
					onCheckedChange={(next) => onCheckedChange(next)}
					// Read-only, not disabled: a disabled switch leaves the tab order and drops keyboard focus.
					readOnly={pending}
					aria-labelledby={`${id}-title`}
					aria-describedby={`${id}-description`}
				/>
			</ItemActions>
		</Item>
	);
}

function PublishDialog({
	open,
	onOpenChange,
	workspaceName,
	providerType,
	onConfirm,
}: {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	workspaceName: string;
	providerType: ProviderType;
	onConfirm: () => void;
}) {
	const popupRef = useRef<HTMLDivElement>(null);
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	const repositories = getProviderTerms(providerType).repositories.toLowerCase();
	return (
		<AlertDialog open={open} onOpenChange={onOpenChange}>
			{/* Focus starts on the dialog itself, which is announced with its title and text; Cancel is not the default either. */}
			<AlertDialogContent ref={popupRef} initialFocus={popupRef}>
				<AlertDialogHeader className="place-items-start text-left">
					<AlertDialogTitle>Make the activity of {workspaceName} public?</AlertDialogTitle>
					{/* One description, so a screen reader hears all of what becomes public. */}
					<AlertDialogDescription render={<div />}>
						<div className="space-y-3">
							<p>
								Anyone can see the page, without signing in. For the public {repositories} of this
								workspace, it lists:
							</p>
							<ul className="list-disc space-y-1 pl-5">
								<li>
									everyone who contributed, members and outside contributors, by name, picture and a
									link to their profile
								</li>
								<li>
									the {pullRequests} each person opened and reviewed, the issues they opened, and a
									weekly line of their activity
								</li>
							</ul>
							<p>
								The page shows nothing from private {repositories}, practices, feedback, AI content,
								Slack, Outline or automation accounts. People on it can hide themselves by signing
								in. Search engines are asked not to list it until you allow that. Turning the page
								off takes that choice back.
							</p>
						</div>
					</AlertDialogDescription>
				</AlertDialogHeader>
				<AlertDialogFooter>
					<AlertDialogCancel>Cancel</AlertDialogCancel>
					<AlertDialogAction onClick={onConfirm}>Make public</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
