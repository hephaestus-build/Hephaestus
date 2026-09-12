import { MailIcon, SendIcon } from "lucide-react";
import { type SubmitEvent, useId, useState } from "react";
import { useSpinDelay } from "spin-delay";

import type { EmailTestResponse } from "@/api/types.gen";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
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
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";

import { EMAIL_TEST_OUTCOME_DEFS } from "./email-test-outcome-defs";

export interface InstanceEmailCardProps {
	isPending: boolean;
	result?: EmailTestResponse;
	onSendTest: (to: string | undefined) => void;
}

export function InstanceEmailCard({ isPending, result, onSendTest }: InstanceEmailCardProps) {
	const [to, setTo] = useState("");
	const recipientId = useId();
	const showSpinner = useSpinDelay(isPending, { delay: 1000, minDuration: 500 });
	const isBusy = isPending || showSpinner;

	const submit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (isBusy) return;
		const trimmed = to.trim();
		onSendTest(trimmed === "" ? undefined : trimmed);
	};

	const def = result ? EMAIL_TEST_OUTCOME_DEFS[result.outcome] : undefined;

	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					<MailIcon className="size-4 text-muted-foreground" aria-hidden />
					Email
				</CardTitle>
				<CardDescription>
					Hephaestus sends account emails through the relay configured with{" "}
					<code>SPRING_MAIL_HOST</code>. A test email uses the same transport as account
					notifications, including silent mode. A successful test confirms relay acceptance, not
					inbox delivery.
				</CardDescription>
			</CardHeader>
			<form onSubmit={submit} className="flex flex-col gap-4">
				<CardContent className="space-y-4">
					<FieldGroup>
						<Field orientation="responsive">
							<FieldContent>
								<FieldLabel htmlFor={recipientId}>Recipient</FieldLabel>
								<FieldDescription id={`${recipientId}-description`}>
									Leave empty to send to the verified address on your own account.
								</FieldDescription>
							</FieldContent>
							<Input
								className="w-full @md/field-group:w-56"
								id={recipientId}
								aria-describedby={`${recipientId}-description`}
								type="email"
								value={to}
								onChange={(event) => setTo(event.target.value)}
								placeholder="Your verified address"
								autoComplete="off"
								disabled={isBusy}
							/>
						</Field>
					</FieldGroup>
					{result && def ? (
						<div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
							<StatusBadge def={def} />
							<span className="min-w-0 wrap-anywhere">
								{result.outcome === "SENT" && result.to ? (
									<>
										to <span className="font-medium text-foreground">{result.to}</span>
										{result.messageId ? (
											<>
												{" "}
												— message id <code className="text-xs">{result.messageId}</code>
											</>
										) : null}
									</>
								) : (
									def.description
								)}
							</span>
						</div>
					) : null}
				</CardContent>
				<CardFooter>
					<Button type="submit" variant="outline" disabled={isBusy}>
						{showSpinner ? <Spinner aria-hidden /> : <SendIcon aria-hidden />}
						{showSpinner ? "Sending…" : "Send test email"}
					</Button>
				</CardFooter>
			</form>
		</Card>
	);
}
