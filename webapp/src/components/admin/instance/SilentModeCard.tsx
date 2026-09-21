import { Volume2, VolumeX } from "lucide-react";
import { type SubmitEvent, useState } from "react";

import type { InstanceSettings } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Card,
	CardContent,
	CardDescription,
	CardFooter,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";
import {
	Dialog,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

const RELEASE_CONFIRM_WORD = "release";

export interface SilentModeCardProps {
	settings: InstanceSettings;
	isPending: boolean;
	releaseDisabled?: boolean;
	onEngage: (reason: string | undefined) => void;
	onRelease: () => void;
}

export function SilentModeCard({
	settings,
	isPending,
	releaseDisabled = false,
	onEngage,
	onRelease,
}: SilentModeCardProps) {
	const engaged = settings.silentModeEngaged;
	const [engageOpen, setEngageOpen] = useState(false);
	const [releaseOpen, setReleaseOpen] = useState(false);
	const [reason, setReason] = useState("");
	const [confirmWord, setConfirmWord] = useState("");
	const [mismatch, setMismatch] = useState(false);

	const openEngage = () => {
		setReason("");
		setEngageOpen(true);
	};
	const openRelease = () => {
		setConfirmWord("");
		setMismatch(false);
		setReleaseOpen(true);
	};

	const confirmRelease = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (releaseDisabled) {
			return;
		}
		if (confirmWord.trim() !== RELEASE_CONFIRM_WORD) {
			setMismatch(true);
			return;
		}
		onRelease();
	};

	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					{engaged ? (
						<VolumeX className="size-4 text-destructive" aria-hidden />
					) : (
						<Volume2 className="size-4 text-muted-foreground" aria-hidden />
					)}
					Silent mode
				</CardTitle>
				<CardDescription>
					The instance-wide emergency brake. While engaged, Hephaestus posts no practice feedback on
					pull requests, merge requests or issues, and sends no Slack messages — for any workspace.
					Workspace settings are untouched and apply again the moment silent mode is released.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-2">
				<div className="flex flex-wrap items-center gap-2">
					{engaged ? (
						<Badge variant="destructive">Engaged</Badge>
					) : (
						<Badge variant="success">Released</Badge>
					)}
					{hasText(settings.silentModeChangedBy) || settings.silentModeChangedAt ? (
						<span className="text-sm text-muted-foreground">
							{engaged ? "engaged" : "last changed"}
							{hasText(settings.silentModeChangedBy) ? ` by ${settings.silentModeChangedBy}` : ""}
							{settings.silentModeChangedAt ? (
								<>
									{" "}
									<RelativeTime value={settings.silentModeChangedAt} />
								</>
							) : null}
						</span>
					) : null}
				</div>
				{engaged && hasText(settings.silentModeReason) ? (
					<p className="text-sm text-muted-foreground">Reason: “{settings.silentModeReason}”</p>
				) : null}
			</CardContent>
			<CardFooter>
				{engaged ? (
					<Button variant="outline" onClick={openRelease} disabled={isPending || releaseDisabled}>
						{isPending ? <Spinner aria-hidden /> : <Volume2 aria-hidden />}
						Release silent mode…
					</Button>
				) : (
					<Button variant="destructive-outline" onClick={openEngage} disabled={isPending}>
						{isPending ? <Spinner aria-hidden /> : <VolumeX aria-hidden />}
						Engage silent mode…
					</Button>
				)}
			</CardFooter>

			<Dialog open={engageOpen} onOpenChange={setEngageOpen}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>Engage silent mode</DialogTitle>
						<DialogDescription>
							Nothing will be posted to GitHub, GitLab or Slack from any workspace — no feedback on
							pull requests, merge requests or issues, no Slack messages, not even the
							acknowledgement reaction. Reviews keep running and keep costing AI budget; their
							observations are saved and marked withheld, and anything withheld while silent mode is
							on is never posted, not even after you release it.
						</DialogDescription>
					</DialogHeader>
					<Field>
						<FieldLabel htmlFor="silent-mode-reason">
							Why are you silencing the instance?
						</FieldLabel>
						<Textarea
							id="silent-mode-reason"
							value={reason}
							onChange={(event) => setReason(event.target.value)}
							placeholder="e.g. Investigating incident #42 — bad feedback going out"
							maxLength={500}
							rows={3}
						/>
						<FieldDescription>
							Shown on the banner to every admin, and recorded in the audit log.
						</FieldDescription>
					</Field>
					<DialogFooter>
						<DialogClose render={<Button variant="outline" disabled={isPending} />}>
							Cancel
						</DialogClose>
						<Button
							variant="destructive"
							disabled={isPending}
							onClick={() => onEngage(reason.trim() === "" ? undefined : reason.trim())}
						>
							{isPending ? <Spinner aria-hidden /> : <VolumeX aria-hidden />}
							Engage silent mode
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<AlertDialog open={releaseOpen} onOpenChange={setReleaseOpen}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Release silent mode?</AlertDialogTitle>
						<AlertDialogDescription>
							Feedback starts landing on pull requests and merge requests again, and Slack messages
							go out — for every workspace, immediately. Anything withheld while silent mode was on
							stays withheld; releasing does not post it. If a bad review is what prompted this,
							check that it is fixed first: the next completed review posts for real.
						</AlertDialogDescription>
					</AlertDialogHeader>
					{releaseDisabled ? (
						<Alert variant="destructive">
							<AlertDescription>
								The current settings could not be verified. Keep silent mode on and retry after
								reloading.
							</AlertDescription>
						</Alert>
					) : null}
					<form onSubmit={confirmRelease} className="grid gap-4">
						<Field data-invalid={mismatch}>
							<FieldLabel htmlFor="silent-mode-release-confirm">
								Type <span className="font-mono font-medium">{RELEASE_CONFIRM_WORD}</span> to
								confirm
							</FieldLabel>
							<Input
								id="silent-mode-release-confirm"
								value={confirmWord}
								disabled={isPending || releaseDisabled}
								onChange={(event) => {
									setConfirmWord(event.target.value);
									setMismatch(false);
								}}
								autoComplete="off"
								autoCapitalize="off"
								spellCheck={false}
								aria-invalid={mismatch}
								aria-describedby={mismatch ? "silent-mode-release-error" : undefined}
							/>
							{mismatch && (
								<FieldError id="silent-mode-release-error">
									That does not match. Type “{RELEASE_CONFIRM_WORD}” exactly.
								</FieldError>
							)}
						</Field>
						<AlertDialogFooter>
							<AlertDialogCancel>Keep silent mode on</AlertDialogCancel>
							<AlertDialogAction
								type="submit"
								variant="destructive"
								disabled={isPending || releaseDisabled}
							>
								{isPending && <Spinner aria-hidden />}
								Release silent mode
							</AlertDialogAction>
						</AlertDialogFooter>
					</form>
				</AlertDialogContent>
			</AlertDialog>
		</Card>
	);
}
