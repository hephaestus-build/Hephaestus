import type { ChatStatus } from "ai";
import { ArrowDownIcon, CircleAlertIcon, InfoIcon, PlusIcon, SettingsIcon } from "lucide-react";
import type { ReactNode } from "react";
import { StickToBottom, useStickToBottomContext } from "use-stick-to-bottom";

import { memberAiChoiceTitle } from "@/components/practice-vocabulary/data-handling-defs";
import { MENTOR_PREFERENCE_COPY, type MentorNotice } from "@/lib/mentor-preference";
import type { ChatMessage } from "@/lib/types";
import { HephMark } from "~/components/brand/HephaestusLogo";
import { Button } from "~/components/common/Button";
import { ExternalLink } from "~/components/common/ExternalLink";
import { Notice } from "~/components/common/Notice";
import { Skeleton } from "~/components/common/Skeleton";
import { MentorComposer } from "~/components/mentor/MentorComposer";
import { MentorTranscript } from "~/components/mentor/MentorTranscript";
import type { MentorPanel as MentorPanelAnswer } from "~/shared/mentor";

type ReadyPanel = Extract<MentorPanelAnswer, { status: "ready" }>;

export type MentorPanelState =
	| { status: "loading" }
	/** The worker could not answer at all. */
	| { status: "failed"; message: string }
	| MentorPanelAnswer;

export interface MentorConversation {
	messages: ChatMessage[];
	status: ChatStatus;
	/** Why the last turn failed, as the server or the extension worded it. */
	error?: string;
	/** The stored conversation is being read back; nothing can be sent until it is. */
	restoring: boolean;
	/** Reading the stored conversation back failed. */
	restoreError?: string;
}

export interface MentorPanelProps {
	state: MentorPanelState;
	conversation: MentorConversation;
	onSend: (text: string) => void;
	onStop: () => void;
	/** Answers the latest message again, replacing the reply that failed. */
	onRetry: () => void;
	/** Lets go of this conversation and starts an empty one about the work the tab shows. */
	onNewConversation: () => void;
	/** Asks the worker again: the tab, the session or the server may have moved on. */
	onReload: () => void;
	onOpenSettings: () => void;
	newConversation?: { status: "pending" } | { status: "error"; message: string };
}

function originOf(url: string): string {
	try {
		return new URL(url).origin;
	} catch {
		return "";
	}
}

function noticeText(notice: MentorNotice): { title: string; description: string; cta?: string } {
	if (notice.reason === "unavailable") {
		const { description, ...copy } = MENTOR_PREFERENCE_COPY.unavailable;
		return {
			...copy,
			description: `${description.before}${memberAiChoiceTitle(notice.choice)}${description.after}`,
		};
	}
	return MENTOR_PREFERENCE_COPY[notice.reason];
}

function Frame({
	header,
	children,
	onOpenSettings,
}: {
	header?: ReactNode;
	children: ReactNode;
	onOpenSettings: () => void;
}) {
	return (
		<main className="flex h-dvh min-h-0 flex-col bg-background text-foreground">
			<header className="flex flex-col gap-1 border-b border-border px-4 py-3">
				<div className="flex items-center gap-2 text-sm font-semibold">
					<HephMark className="size-5" />
					<h1 className="flex-1">Heph</h1>
					<Button
						variant="ghost"
						size="icon"
						aria-label="Extension settings"
						onClick={onOpenSettings}
					>
						<SettingsIcon aria-hidden />
					</Button>
				</div>
				{header}
			</header>
			{children}
		</main>
	);
}

function StateNotice({
	title,
	children,
	action,
	tone = "neutral",
}: {
	title: string;
	children?: ReactNode;
	action?: ReactNode;
	tone?: "neutral" | "destructive";
}) {
	return (
		<div className="p-4">
			<Notice
				icon={tone === "destructive" ? CircleAlertIcon : InfoIcon}
				title={title}
				tone={tone}
				action={action}
			>
				{children}
			</Notice>
		</div>
	);
}

/** Every state that is not a conversation: what is true, and the one thing to do about it. */
function Unready({ props }: { props: MentorPanelProps }) {
	const { state } = props;
	switch (state.status) {
		case "loading": {
			return (
				<div className="flex flex-col gap-3 p-4" aria-hidden>
					<Skeleton className="h-4 w-2/3" />
					<Skeleton className="h-16 w-full" />
				</div>
			);
		}
		case "failed":
		case "error": {
			return (
				<StateNotice
					title="Heph could not be reached"
					tone="destructive"
					action={
						<Button variant="outline" size="sm" onClick={props.onReload}>
							Try again
						</Button>
					}
				>
					{state.message}
				</StateNotice>
			);
		}
		case "not-configured": {
			return (
				<StateNotice
					title="Connect to Hephaestus"
					action={
						<Button variant="outline" size="sm" onClick={props.onOpenSettings}>
							Set up
						</Button>
					}
				>
					Connect the extension to Hephaestus and sign in to talk with Heph here.
				</StateNotice>
			);
		}
		case "signed-out": {
			return (
				<StateNotice
					title="Sign in"
					action={
						<Button variant="outline" size="sm" onClick={props.onOpenSettings}>
							Sign in
						</Button>
					}
				>
					Sign in to {state.instanceHost} to talk with Heph about this work.
				</StateNotice>
			);
		}
		case "consent-required": {
			return (
				<StateNotice
					title="Read the current notice"
					action={
						<ExternalLink
							href={state.webAppUrl}
							allowedOrigin={originOf(state.webAppUrl)}
							button={{ variant: "outline", size: "sm" }}
						>
							Open Hephaestus
						</ExternalLink>
					}
				>
					Read and accept the current notice in the Hephaestus web app, then come back.
				</StateNotice>
			);
		}
		case "unsupported-page": {
			return (
				<StateNotice title="Nothing to talk about here">
					Heph follows this tab. Open a pull request, merge request or issue to talk about it.
				</StateNotice>
			);
		}
		case "no-workspace": {
			return (
				<StateNotice title="No workspace here">
					None of your workspaces is connected to {state.siteOrigin}.
				</StateNotice>
			);
		}
		case "not-found": {
			return (
				<StateNotice title="Not work Hephaestus follows">
					{state.workLabel} is not work any of your workspaces follows, or not work you can see.
					Hephaestus does not say which.
				</StateNotice>
			);
		}
		case "choose-workspace": {
			return (
				<StateNotice
					title="Choose a workspace first"
					action={
						<Button variant="outline" size="sm" onClick={props.onReload}>
							Check again
						</Button>
					}
				>
					More than one of your workspaces follows {state.workLabel}. Choose one in its practice
					review on the page, then check again.
				</StateNotice>
			);
		}
		case "ready": {
			return null;
		}
	}
}

function WorkHeader({ panel, props }: { panel: ReadyPanel; props: MentorPanelProps }) {
	const { work } = panel;
	const busy =
		props.conversation.status === "submitted" || props.conversation.status === "streaming";
	const started = panel.threadId !== undefined || props.conversation.messages.length > 0;
	return (
		<>
			<p className="min-w-0 text-sm break-words">
				<span className="text-muted-foreground">About </span>
				{work.repository} {work.label}
			</p>
			{work.title === undefined ? null : (
				<p className="truncate text-xs text-muted-foreground" title={work.title}>
					{work.title}
				</p>
			)}
			{started ? (
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1 pt-1 text-xs">
					<Button
						variant="ghost"
						size="sm"
						onClick={props.onNewConversation}
						disabled={busy || props.newConversation?.status === "pending"}
					>
						<PlusIcon aria-hidden />
						New conversation
					</Button>
					{panel.threadUrl === undefined ? null : (
						<ExternalLink
							href={panel.threadUrl}
							allowedOrigin={originOf(panel.threadUrl)}
							tone="link"
						>
							Continue in Hephaestus
						</ExternalLink>
					)}
				</div>
			) : null}
		</>
	);
}

/** Before the first message: what Heph knows, and what it does not. Nothing has been sent. */
function Greeting({ panel }: { panel: ReadyPanel }) {
	return (
		<div className="flex flex-col gap-2 p-4 text-sm">
			<p className="font-medium">
				Ask Heph about {panel.work.noun} {panel.work.label}.
			</p>
			<p className="text-muted-foreground">
				Heph answers from what Hephaestus recorded in {panel.workspace.displayName}: your own work,
				its practice reviews and your feedback. It does not read this page, and it may know little
				about work other people did.
			</p>
		</div>
	);
}

function PreferenceNotice({ notice, href }: { notice: MentorNotice; href: string }) {
	const copy = noticeText(notice);
	return (
		<StateNotice
			title={copy.title}
			action={
				copy.cta === undefined ? undefined : (
					<ExternalLink href={href} allowedOrigin={originOf(href)} button={{ size: "sm" }}>
						{copy.cta}
					</ExternalLink>
				)
			}
		>
			{copy.description}
		</StateNotice>
	);
}

function LatestReply() {
	const { isAtBottom, scrollToBottom } = useStickToBottomContext();
	if (isAtBottom) {
		return null;
	}
	return (
		<div className="pointer-events-none absolute inset-x-0 bottom-2 flex justify-center">
			<Button
				variant="outline"
				size="sm"
				className="pointer-events-auto shadow-sm"
				onClick={() => {
					void scrollToBottom({ animation: "instant" });
				}}
			>
				<ArrowDownIcon aria-hidden />
				Latest message
			</Button>
		</div>
	);
}

function Conversation({ panel, props }: { panel: ReadyPanel; props: MentorPanelProps }) {
	const { conversation } = props;
	const replying = conversation.status === "submitted" || conversation.status === "streaming";
	let disabledReason: string | undefined;
	if (panel.notice !== undefined) {
		disabledReason = "Heph is not answering you here; the notice above says why.";
	} else if (panel.heldAbout !== undefined) {
		disabledReason = `This conversation is about ${panel.heldAbout}.`;
	} else if (conversation.restoring) {
		disabledReason = "Loading this conversation…";
	} else if (conversation.restoreError !== undefined) {
		disabledReason = "This conversation could not be loaded.";
	} else if (props.newConversation?.status === "pending") {
		disabledReason = "Starting a new conversation…";
	}
	const empty = conversation.messages.length === 0;
	let announcement = "";
	if (conversation.status === "submitted") {
		announcement = "Heph is thinking…";
	} else if (conversation.status === "error") {
		announcement = "Heph's reply failed.";
	}
	return (
		<>
			{/* Mounted with the conversation, before anything it announces. */}
			<p className="sr-only" aria-live="polite">
				{announcement}
			</p>
			<StickToBottom className="relative min-h-0 flex-1" initial="instant" resize="instant">
				<StickToBottom.Content className="flex flex-col pb-10" scrollClassName="overflow-y-auto">
					{props.newConversation?.status === "error" ? (
						<p role="alert" className="px-4 py-3 text-sm text-destructive">
							{props.newConversation.message}
						</p>
					) : null}
					{panel.notice === undefined ? null : (
						<PreferenceNotice notice={panel.notice} href={panel.onboardingUrl} />
					)}
					{panel.heldAbout === undefined ? null : (
						<StateNotice
							title="This tab moved on"
							action={
								<Button
									variant="outline"
									size="sm"
									onClick={props.onNewConversation}
									disabled={props.newConversation?.status === "pending"}
								>
									Start one about {panel.work.label}
								</Button>
							}
						>
							This panel holds a conversation about {panel.heldAbout}. Go back to it to continue, or
							start a new conversation about {panel.work.repository} {panel.work.label}.
						</StateNotice>
					)}
					{conversation.restoreError === undefined ? null : (
						<StateNotice
							title="This conversation could not be loaded"
							tone="destructive"
							action={
								<>
									<Button variant="outline" size="sm" onClick={props.onReload}>
										Try again
									</Button>
									<Button variant="ghost" size="sm" onClick={props.onNewConversation}>
										New conversation
									</Button>
								</>
							}
						>
							{conversation.restoreError}
						</StateNotice>
					)}
					{empty && panel.notice === undefined && panel.heldAbout === undefined ? (
						<Greeting panel={panel} />
					) : (
						<div className="p-4">
							<MentorTranscript messages={conversation.messages} status={conversation.status} />
						</div>
					)}
					{conversation.error === undefined || replying ? null : (
						<div role="alert" className="mx-4 mb-4 flex flex-col gap-2 text-sm">
							<p className="text-destructive">{conversation.error}</p>
							<div>
								<Button
									variant="outline"
									size="sm"
									onClick={props.onRetry}
									disabled={disabledReason !== undefined}
								>
									Try again
								</Button>
							</div>
						</div>
					)}
				</StickToBottom.Content>
				<LatestReply />
			</StickToBottom>
			<MentorComposer
				reference={empty ? panel.reference : undefined}
				replying={replying}
				disabledReason={disabledReason}
				onSend={props.onSend}
				onStop={props.onStop}
			/>
		</>
	);
}

/**
 * The Heph panel: Chrome's side panel beside one tab, holding one conversation with Heph about the
 * work the tab shows. It shows the conversation and nothing else — the practice review stays in the
 * page — and it sends nothing until the reader does.
 */
export function MentorPanel(props: MentorPanelProps) {
	const { state } = props;
	if (state.status !== "ready") {
		return (
			<Frame onOpenSettings={props.onOpenSettings}>
				<Unready props={props} />
			</Frame>
		);
	}
	return (
		<Frame
			header={<WorkHeader panel={state} props={props} />}
			onOpenSettings={props.onOpenSettings}
		>
			<Conversation panel={state} props={props} />
		</Frame>
	);
}
