import { CircleAlertIcon, CircleCheckIcon, CircleSlashIcon } from "lucide-react";
import type { ReactNode } from "react";

import { HephMark } from "~/components/brand/HephaestusLogo";
import { Button } from "~/components/common/Button";
import { Skeleton } from "~/components/common/Skeleton";
import { Spinner } from "~/components/common/Spinner";
import type { ActionOutcome, ActionPreview } from "~/shared/review-actions";
import { workNoun } from "~/shared/work-noun";

export type ActionConfirmationState =
	| { status: "loading" }
	| { status: "invalid"; message: string }
	| { status: "ready"; preview: ActionPreview }
	| { status: "sending"; preview: ActionPreview }
	| { status: "done"; preview: ActionPreview; outcome: ActionOutcome }
	/** The server refused, and said so: nothing happened. */
	| { status: "refused"; preview: ActionPreview; message: string }
	/** No answer came back: the request may or may not have arrived. */
	| { status: "unknown"; preview: ActionPreview };

export interface ActionConfirmationProps {
	state: ActionConfirmationState;
	onConfirm: () => void;
	/** Closes the window; before sending, it also gives up the pending change. */
	onClose: () => void;
}

function WorkCard({ preview }: { preview: ActionPreview }) {
	const { work, workspace, instanceHost } = preview;
	return (
		<dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5 rounded-lg border border-border bg-card p-3.5 text-sm">
			<dt className="text-muted-foreground">Work</dt>
			<dd className="min-w-0 break-words">
				<span className="font-medium">
					{work.repositoryName === undefined ? "" : `${work.repositoryName} `}
					{work.label}
				</span>
				{work.title === undefined ? null : (
					<span className="block text-muted-foreground">{work.title}</span>
				)}
			</dd>
			<dt className="text-muted-foreground">Workspace</dt>
			<dd className="min-w-0 break-words">
				{workspace.displayName} <span className="text-muted-foreground">on {instanceHost}</span>
			</dd>
		</dl>
	);
}

function explanation(preview: ActionPreview): string {
	return `Hephaestus reviews this ${workNoun(preview.work)} against the practices ${preview.workspace.displayName} follows. What it records, and whether feedback is posted where the developer sees it, follows the workspace's settings.`;
}

function outcomeText(outcome: ActionOutcome): { title: string; body: string; done: boolean } {
	return outcome.status === "SUBMITTED"
		? {
				title: "Review requested",
				body: "It runs in the background. The practice review on the page shows it once it is queued or running.",
				done: true,
			}
		: {
				title: "No review started",
				body: outcome.reasonDescription ?? "Hephaestus did not start a review.",
				done: false,
			};
}

function Frame({ title, children }: { title: string; children: ReactNode }) {
	return (
		<main className="mx-auto flex min-h-dvh max-w-md flex-col gap-5 p-6">
			<header className="flex items-center gap-2 text-sm font-semibold">
				<HephMark className="size-5" />
				Hephaestus
			</header>
			<h1 className="text-xl font-semibold tracking-tight">{title}</h1>
			{children}
		</main>
	);
}

function Result({
	icon,
	tone,
	title,
	children,
}: {
	icon: typeof CircleCheckIcon;
	tone: string;
	title: string;
	children?: ReactNode;
}) {
	const Icon = icon;
	return (
		<section
			role="status"
			aria-label={title}
			className="flex gap-3 rounded-lg border border-border p-3.5"
		>
			<Icon aria-hidden className={`mt-0.5 size-4 shrink-0 ${tone}`} />
			<div className="flex min-w-0 flex-col gap-1">
				<h2 className="text-sm font-semibold">{title}</h2>
				{children === undefined ? null : (
					<div className="text-sm text-muted-foreground">{children}</div>
				)}
			</div>
		</section>
	);
}

/**
 * The extension's own confirmation window for a change started from a provider page. It is a top-level
 * extension page no site can frame or cover, it shows exactly what will change, read afresh from
 * Hephaestus, and it sends the change once. An answer that never came back is said as such, and there
 * is no automatic second try: the reader checks the page first.
 */
export function ActionConfirmation({ state, onConfirm, onClose }: ActionConfirmationProps) {
	if (state.status === "loading") {
		return (
			<Frame title="Confirm">
				<div className="flex flex-col gap-2" aria-hidden>
					<Skeleton className="h-4 w-3/4" />
					<Skeleton className="h-20 w-full" />
				</div>
			</Frame>
		);
	}
	if (state.status === "invalid") {
		return (
			<Frame title="Nothing to confirm">
				<Result icon={CircleSlashIcon} tone="text-muted-foreground" title="Nothing was changed">
					{state.message}
				</Result>
				<div className="flex justify-end">
					<Button variant="outline" onClick={onClose}>
						Close
					</Button>
				</div>
			</Frame>
		);
	}
	const { preview } = state;
	let result: ReactNode = null;
	if (state.status === "done") {
		const text = outcomeText(state.outcome);
		result = (
			<Result
				icon={text.done ? CircleCheckIcon : CircleSlashIcon}
				tone={text.done ? "text-success" : "text-muted-foreground"}
				title={text.title}
			>
				{text.body}
			</Result>
		);
	} else if (state.status === "refused") {
		result = (
			<Result icon={CircleSlashIcon} tone="text-muted-foreground" title="Nothing was changed">
				{state.message}
			</Result>
		);
	} else if (state.status === "unknown") {
		result = (
			<Result icon={CircleAlertIcon} tone="text-warning" title="No answer from Hephaestus">
				The change may or may not have gone through. Check the practice review on the page before
				trying again.
			</Result>
		);
	}
	const finished =
		state.status === "done" || state.status === "refused" || state.status === "unknown";
	return (
		<Frame title="Request a practice review">
			<WorkCard preview={preview} />
			{finished ? result : <p className="text-sm text-muted-foreground">{explanation(preview)}</p>}
			<div className="flex flex-wrap justify-end gap-2">
				{finished ? (
					<Button variant="outline" onClick={onClose}>
						Close
					</Button>
				) : (
					<>
						<Button variant="ghost" disabled={state.status === "sending"} onClick={onClose}>
							Cancel
						</Button>
						<Button variant="mentor" disabled={state.status === "sending"} onClick={onConfirm}>
							{state.status === "sending" ? <Spinner /> : null}
							Request review
						</Button>
					</>
				)}
			</div>
		</Frame>
	);
}
