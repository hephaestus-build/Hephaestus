import { InfoIcon } from "lucide-react";
import { useId, useRef, useState } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
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
import { Item, ItemActions, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { ITEM_KIND, itemKindNoun } from "@/lib/item-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

export type WorkspacePublicActivityState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			enabled: boolean;
			allowSearchEngines: boolean;
			/** Set when the latest read failed, so these settings are the last known ones. */
			refreshFailure?: RefreshFailure;
			/** Server-computed publication status from the workspace list. */
			live: boolean;
			/** How many people the page leaves out, never who; undefined where the count did not load. */
			hiddenPeople: number | undefined;
			/** The change in flight, if any. */
			pending: "enabled" | "search-engines" | undefined;
	  };

type ReadySettings = Extract<WorkspacePublicActivityState, { status: "ready" }>;

export interface WorkspacePublicActivitySettingsProps {
	workspaceName: string;
	providerType: ProviderType;
	/** Where the workspace is found, which is where a signed-out visitor finds the page. */
	address: string;
	state: WorkspacePublicActivityState;
	onEnabledChange: (enabled: boolean) => void;
	onSearchEnginesChange: (allow: boolean) => void;
}

/**
 * Whether this workspace shows who contributes to its public repositories to anyone. Turning the
 * page on asks first and says what a public page shows.
 */
export function WorkspacePublicActivitySettings({
	workspaceName,
	providerType,
	address,
	state,
	onEnabledChange,
	onSearchEnginesChange,
}: WorkspacePublicActivitySettingsProps) {
	const headingId = useId();
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
				<ReadySettingsBody
					workspaceName={workspaceName}
					providerType={providerType}
					address={address}
					state={state}
					onEnabledChange={onEnabledChange}
					onSearchEnginesChange={onSearchEnginesChange}
				/>
			)}
		</section>
	);
}

/**
 * The settings while they are readable. It owns the publishing confirmation, so a read that stops
 * being readable takes an open confirmation with it, and a later read does not bring it back.
 */
function ReadySettingsBody({
	workspaceName,
	providerType,
	address,
	state,
	onEnabledChange,
	onSearchEnginesChange,
}: Omit<WorkspacePublicActivitySettingsProps, "state"> & { state: ReadySettings }) {
	const [confirming, setConfirming] = useState(false);
	return (
		<>
			<div className="space-y-3">
				{state.refreshFailure !== undefined && (
					<QueryErrorAlert title="These settings may be out of date" {...state.refreshFailure} />
				)}
				<ul className="rounded-xl border bg-card">
					<SettingRow
						title="Publish the page"
						description={publishDescription(state.enabled, state.live, address)}
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
									? "On. Search engines are allowed to list the page when it is public."
									: "Off. A public page asks search engines not to list it. This is a request, not an access restriction."
							}
							checked={state.allowSearchEngines}
							pending={state.pending === "search-engines"}
							onCheckedChange={onSearchEnginesChange}
						/>
					)}
					{state.enabled && state.hiddenPeople !== undefined && (
						<Item render={<li />} variant="row">
							<ItemContent>
								<ItemTitle>People left out of the page</ItemTitle>
								<ItemDescription className="line-clamp-none">
									The page leaves out and does not count people who hid themselves, and people an
									admin hid. People who hid themselves are counted here, never named.
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
							Public availability is not confirmed. Public pages also require instance permission
							and an active workspace.
						</AlertDescription>
					</Alert>
				)}
				{state.enabled && state.live && (
					<p className="max-w-2xl text-xs text-muted-foreground">
						Signed-in members open their workspace home at this address. To preview the page, open
						it in a private window.
					</p>
				)}
			</div>
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
		</>
	);
}

/**
 * The saved choice, and the address only where the workspace list confirms the page is public. A
 * saved Off stays Off even while that status still reads from before the change.
 */
function publishDescription(enabled: boolean, live: boolean, address: string): string {
	if (!enabled) {
		return "Off. This workspace is not set to publish a public activity page.";
	}
	return live ? `On. Anyone can see it at ${address}.` : "On for this workspace.";
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
	const pullRequests = itemKindNoun(ITEM_KIND.pullRequest, 2, providerType);
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
								When the page is public, anyone can see it without signing in. For the public{" "}
								{repositories} of this workspace, it lists:
							</p>
							<ul className="list-disc space-y-1 pl-5">
								<li>
									everyone who contributed, members and outside contributors, by name, picture and a
									link to their profile
								</li>
								<li>
									counts of the {pullRequests} each person opened and reviewed and the issues they
									opened
								</li>
								<li>
									their public work timeline, with titles and links, and weekly activity counts
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
