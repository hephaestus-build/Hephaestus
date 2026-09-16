import { useMutation } from "@tanstack/react-query";
import { ExternalLinkIcon, SendIcon } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import {
	initiateMutation,
	sendSlackTestMessageMutation,
	updateConnectionStatusMutation,
	updateLeaderboardDigestMutation,
} from "@/api/@tanstack/react-query.gen";
import type { SlackChannelCandidate } from "@/api/types.gen";
import { SlackIcon } from "@/components/icons/brand";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import { Card, CardContent, CardDescription, CardFooter, CardHeader } from "@/components/ui/card";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { problemDetailOf } from "@/lib/problem-detail";
import { parseSlackChannelReference } from "@/lib/slack-channel-reference";

import { IntegrationCardHeading } from "./IntegrationCardHeading";
import { slackErrorMessage } from "./slack-channels/slack-error-copy";
import { SlackChannelCombobox } from "./slack-channels/SlackChannelCombobox";
import { SlackChannelPasteField } from "./slack-channels/SlackChannelPasteField";

export interface AdminSlackNotificationSettingsProps {
	workspaceSlug: string;
	hasSlackConnection: boolean;
	slackConnectionId?: number;
	channelId?: string;
	teamLabel?: string;
	enabled: boolean;
	scheduleDay?: number;
	scheduleTime?: string;
	channelCandidates?: readonly SlackChannelCandidate[];
	onSaved: () => void;
}

const TIME_24H = /^(?:[01]\d|2[0-3]):[0-5]\d$/u;
const DEFAULT_DAY = 1;
const DEFAULT_TIME = "09:00";

const DAYS = [
	{ value: "1", label: "Monday" },
	{ value: "2", label: "Tuesday" },
	{ value: "3", label: "Wednesday" },
	{ value: "4", label: "Thursday" },
	{ value: "5", label: "Friday" },
	{ value: "6", label: "Saturday" },
	{ value: "7", label: "Sunday" },
];

const NO_CANDIDATES: readonly SlackChannelCandidate[] = [];

export function AdminSlackNotificationSettings({
	workspaceSlug,
	hasSlackConnection,
	slackConnectionId,
	channelId,
	teamLabel,
	enabled,
	scheduleDay,
	scheduleTime,
	channelCandidates = NO_CANDIDATES,
	onSaved,
}: AdminSlackNotificationSettingsProps) {
	const selectableDigestChannels = channelCandidates.filter((candidate) => !candidate.archived);

	// The persisted value is a stable Slack id. Anything that does not parse (legacy garbage, a
	// half-typed value from an older build) is surfaced in the paste escape hatch so an admin can
	// see and repair it, instead of silently becoming the invisible value of a hidden control.
	const persisted = channelId ? parseSlackChannelReference(channelId) : null;
	const persistedIsUnparseable = Boolean(channelId) && persisted == null;

	// The one digest-channel value. Both entry points (combobox, paste) write here; nothing
	// renders it as editable text.
	const [selectedChannelId, setSelectedChannelId] = useState(persisted?.channelId ?? "");
	const [channelReference, setChannelReference] = useState(persistedIsUnparseable ? channelId : "");
	const [pasteOpen, setPasteOpen] = useState(
		persistedIsUnparseable || selectableDigestChannels.length === 0,
	);
	const [teamInput, setTeamInput] = useState(teamLabel ?? "");
	const [enabledInput, setEnabledInput] = useState(enabled);
	const [dayInput, setDayInput] = useState(String(scheduleDay ?? DEFAULT_DAY));
	const [timeInput, setTimeInput] = useState(scheduleTime ?? DEFAULT_TIME);
	const [disconnectOpen, setDisconnectOpen] = useState(false);

	// The OAuth landing route hands the outcome over through sessionStorage. The keys are cleared
	// before the toast fires, so a remount (the parent re-keys this form on every server refetch)
	// cannot replay it.
	useEffect(() => {
		const result = window.sessionStorage.getItem("slack-connect-result");
		if (!result) {
			return;
		}
		const reason = window.sessionStorage.getItem("slack-connect-reason") ?? undefined;
		window.sessionStorage.removeItem("slack-connect-result");
		window.sessionStorage.removeItem("slack-connect-reason");
		if (result === "success") {
			toast.success("Slack workspace connected");
		} else {
			toast.error("Slack connection failed", { description: reason });
		}
	}, []);

	const referenceText = channelReference ?? "";
	const parsedReference = parseSlackChannelReference(referenceText);
	const channelInvalid = referenceText.trim().length > 0 && parsedReference == null;
	const channelRequired = enabledInput && selectedChannelId.length === 0 && !channelInvalid;
	const timeInvalid = !TIME_24H.test(timeInput);

	/** The paste hatch and the combobox write the same single value; the last one used wins. */
	function handlePastedReference(value: string) {
		setChannelReference(value);
		if (value.trim().length === 0) {
			return;
		}
		const parsed = parseSlackChannelReference(value);
		setSelectedChannelId(parsed ? parsed.channelId : "");
	}

	const save = useMutation({
		...updateLeaderboardDigestMutation(),
		onSuccess: () => {
			toast.success("Slack digest settings saved");
			onSaved();
		},
		onError: (e) => {
			toast.error("Failed to save Slack digest settings", {
				description: problemDetailOf(e),
			});
		},
	});

	const test = useMutation({
		...sendSlackTestMessageMutation(),
		onSuccess: (data) => {
			if (data.ok) {
				toast.success("Test message posted to Slack", {
					description: data.channelId ? `Channel ${data.channelId}` : undefined,
				});
				return;
			}
			// Slack's machine codes (channel_not_found, not_in_channel, …) are for us, not for the
			// admin reading the toast.
			toast.error("Slack did not post the test message", {
				description: slackErrorMessage(data.slackError),
			});
		},
		onError: (e) => {
			toast.error("Test message failed", {
				description: problemDetailOf(e),
			});
		},
	});

	const connect = useMutation({
		...initiateMutation(),
		onSuccess: (initiation) => {
			if (initiation.type === "REDIRECT" && initiation.vendorUrl) {
				window.location.assign(initiation.vendorUrl);
				return; // page is unloading
			}
			throw new Error(`Unexpected non-redirect Slack initiation: ${initiation.type}`);
		},
		onError: (e) => {
			toast.error("Could not start Slack OAuth", {
				description: problemDetailOf(e),
			});
		},
	});

	const disconnect = useMutation({
		...updateConnectionStatusMutation(),
		onSuccess: () => {
			setDisconnectOpen(false);
			toast.success("Slack disconnected");
			onSaved();
		},
		onError: (e) => {
			toast.error("Failed to disconnect Slack", {
				description: problemDetailOf(e),
			});
		},
	});

	return (
		<>
			<Card>
				<CardHeader>
					<IntegrationCardHeading className="flex items-center gap-2">
						<SlackIcon className="size-4" aria-hidden />
						Slack integration
					</IntegrationCardHeading>
					<CardDescription>
						Install Hephaestus in Slack for weekly digests, the DM mentor, App Home privacy
						controls, and optional monitored channels. Channel messages are not stored until an
						admin activates a channel.
					</CardDescription>
				</CardHeader>

				{hasSlackConnection && (
					<CardContent className="space-y-4">
						<Item variant="outline" size="sm">
							<ItemMedia variant="icon">
								<SlackIcon />
							</ItemMedia>
							<ItemContent>
								<ItemTitle>Slack workspace</ItemTitle>
								<ItemDescription>Hephaestus is installed and can post as the app.</ItemDescription>
							</ItemContent>
							<ItemActions>
								<Badge variant="success">Connected</Badge>
							</ItemActions>
						</Item>

						<div>
							<h3 className="font-medium">Weekly digest</h3>
							<p className="text-sm text-muted-foreground">
								Optional leaderboard summary posted to one Slack channel on a schedule.
							</p>
						</div>

						<FieldGroup>
							<Field orientation="horizontal">
								<FieldContent>
									<FieldLabel htmlFor="slack-enabled" className="font-medium">
										Send weekly digest
									</FieldLabel>
									<FieldDescription>
										Posts on the schedule below; the leaderboard cycle ends at the same moment.
									</FieldDescription>
								</FieldContent>
								<Switch
									id="slack-enabled"
									checked={enabledInput}
									disabled={save.isPending}
									onCheckedChange={setEnabledInput}
								/>
							</Field>

							<div className="grid grid-cols-2 gap-4">
								<Field>
									<FieldLabel id="slack-day-label" htmlFor="slack-day">
										Day
									</FieldLabel>
									<Select
										items={DAYS}
										value={dayInput}
										disabled={save.isPending}
										onValueChange={(value) => {
											if (value) {
												setDayInput(value);
											}
										}}
									>
										<SelectTrigger id="slack-day">
											<SelectValue />
										</SelectTrigger>
										<SelectContent aria-labelledby="slack-day-label">
											{DAYS.map((d) => (
												<SelectItem key={d.value} value={d.value}>
													{d.label}
												</SelectItem>
											))}
										</SelectContent>
									</Select>
								</Field>

								<Field data-invalid={timeInvalid}>
									<FieldLabel htmlFor="slack-time">Time (24h)</FieldLabel>
									<Input
										id="slack-time"
										type="time"
										value={timeInput}
										disabled={save.isPending}
										onChange={(e) => setTimeInput(e.target.value)}
										aria-invalid={timeInvalid}
									/>
									<FieldDescription>
										When the weekly cycle ends and the digest posts (workspace timezone).
									</FieldDescription>
									{timeInvalid && <FieldError>Time must be in HH:mm format.</FieldError>}
								</Field>
							</div>

							<Field data-invalid={channelRequired}>
								<FieldLabel htmlFor="slack-channel">Digest channel</FieldLabel>
								<SlackChannelCombobox
									id="slack-channel"
									aria-label="Search digest Slack channels"
									candidates={selectableDigestChannels}
									disabled={save.isPending}
									invalid={channelRequired}
									selectedChannelId={selectedChannelId}
									selectedChannelName={parsedReference?.channelName}
									getDisabledReason={(candidate) => (candidate.member ? undefined : "Needs invite")}
									onSelect={(candidate) => {
										setSelectedChannelId(candidate.slackChannelId);
										setChannelReference("");
									}}
								/>
								<FieldDescription>
									Invite Hephaestus to the channel in Slack, then pick it here.
								</FieldDescription>
								{channelRequired && (
									<FieldError>Choose a channel before enabling the weekly digest.</FieldError>
								)}
							</Field>

							{pasteOpen ? (
								<SlackChannelPasteField
									id="slack-channel-reference"
									value={referenceText}
									disabled={save.isPending}
									invalid={channelInvalid}
									onChange={handlePastedReference}
									description="For a channel Slack did not list — its link, mention or C…/G… ID resolves to the same stable channel."
								/>
							) : (
								<Button
									type="button"
									variant="link"
									size="inline"
									className="w-fit text-sm"
									onClick={() => setPasteOpen(true)}
								>
									Paste a channel link or ID instead
								</Button>
							)}

							<Field>
								<FieldLabel htmlFor="slack-team">Team filter (optional)</FieldLabel>
								<Input
									id="slack-team"
									value={teamInput}
									disabled={save.isPending}
									onChange={(e) => setTeamInput(e.target.value)}
									placeholder="e.g. engineering"
									autoComplete="off"
								/>
								<FieldDescription>
									Restrict the leaderboard to a single team. Leave blank to include every
									contributor in the workspace.
								</FieldDescription>
							</Field>
						</FieldGroup>
					</CardContent>
				)}

				<CardFooter className="justify-between gap-2">
					{hasSlackConnection ? (
						<>
							<ButtonGroup>
								<Button
									onClick={() =>
										save.mutate({
											path: { workspaceSlug },
											body: {
												day: Number(dayInput),
												time: timeInput,
												enabled: enabledInput,
												channelId: selectedChannelId.length > 0 ? selectedChannelId : undefined,
												team: teamInput.length > 0 ? teamInput : undefined,
											},
										})
									}
									disabled={save.isPending || channelInvalid || channelRequired || timeInvalid}
								>
									{save.isPending ? "Saving…" : "Save"}
								</Button>
								<Button
									variant="outline"
									onClick={() =>
										test.mutate({
											path: { workspaceSlug },
											body: { channelId: selectedChannelId },
										})
									}
									disabled={test.isPending || selectedChannelId.length === 0}
								>
									<SendIcon className="size-3.5" />
									{test.isPending ? "Sending…" : "Send test message"}
								</Button>
							</ButtonGroup>

							{slackConnectionId != null && (
								<Button
									variant="destructive-outline"
									onClick={() => setDisconnectOpen(true)}
									disabled={disconnect.isPending}
								>
									{disconnect.isPending ? "Disconnecting…" : "Disconnect Slack…"}
								</Button>
							)}
						</>
					) : (
						<Button
							onClick={() => {
								// Persist the originating slug so the OAuth landing route can route back.
								window.sessionStorage.setItem("slack-connect-return-slug", workspaceSlug);
								connect.mutate({
									path: { workspaceSlug },
									body: { kind: "SLACK", userInput: {} },
								});
							}}
							disabled={connect.isPending}
							className="w-full"
						>
							{connect.isPending ? (
								<>
									<Spinner />
									Redirecting to Slack…
								</>
							) : (
								<>
									Connect Slack workspace
									<ExternalLinkIcon className="size-3.5" />
								</>
							)}
						</Button>
					)}
				</CardFooter>
			</Card>

			<AlertDialog open={disconnectOpen} onOpenChange={setDisconnectOpen}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Disconnect Slack?</AlertDialogTitle>
						<AlertDialogDescription>
							The weekly digest stops posting, the bot is uninstalled from this workspace, and every
							ingested Slack message, thread, and per-channel consent record for this workspace is
							erased. Messages in Slack itself are not affected. You can reconnect later, but you
							will need to re-authorize via OAuth and re-activate channels from scratch.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel disabled={disconnect.isPending}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={disconnect.isPending || slackConnectionId == null}
							onClick={() => {
								if (slackConnectionId == null) {
									return;
								}
								disconnect.mutate({
									path: { workspaceSlug, id: slackConnectionId },
									body: { state: "UNINSTALLED" },
								});
							}}
						>
							{disconnect.isPending ? "Disconnecting…" : "Disconnect"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</>
	);
}
