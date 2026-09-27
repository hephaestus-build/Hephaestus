import { type SubmitEvent, useId, useState } from "react";

import { Button } from "~/components/common/Button";
import { Spinner } from "~/components/common/Spinner";

export type InstanceSetupState =
	| { status: "idle" }
	| { status: "pending" }
	| { status: "error"; message: string };

export interface InstanceSetupFormProps {
	/** Ask for the web app's own address as well — only a development build, for a local server. */
	developmentBuild: boolean;
	state: InstanceSetupState;
	/** While another way of connecting is under way. */
	disabled?: boolean;
	/** Called from the submit event itself, so the handler can still ask Chrome for access. */
	onConnect: (input: { origin: string; webAppOrigin?: string }) => void;
}

const INPUT_CLASSES =
	"h-9 w-full rounded-lg border border-input bg-background px-3 text-sm shadow-xs outline-none placeholder:text-muted-foreground/70 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive";

/**
 * The address of a self-hosted instance. The typed text never leaves the form until it is
 * submitted, and a successful connection replaces the form with the connected instance.
 */
export function InstanceSetupForm({
	developmentBuild,
	state,
	disabled = false,
	onConnect,
}: InstanceSetupFormProps) {
	const [origin, setOrigin] = useState("");
	const [webAppOrigin, setWebAppOrigin] = useState("");
	const originId = useId();
	const originHintId = useId();
	const webAppId = useId();
	const errorId = useId();
	const pending = state.status === "pending";
	const invalid = state.status === "error";
	const submit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		onConnect({
			origin,
			webAppOrigin: developmentBuild && webAppOrigin.trim() !== "" ? webAppOrigin : undefined,
		});
	};
	return (
		<form className="flex flex-col gap-4" onSubmit={submit} noValidate>
			<div className="flex flex-col gap-1.5">
				<label htmlFor={originId} className="text-sm font-medium">
					Hephaestus address
				</label>
				<input
					id={originId}
					type="url"
					inputMode="url"
					autoComplete="url"
					required
					placeholder="https://hephaestus.your-organisation.example"
					value={origin}
					onChange={(event) => setOrigin(event.target.value)}
					aria-invalid={invalid ? true : undefined}
					aria-describedby={invalid ? `${originHintId} ${errorId}` : originHintId}
					className={INPUT_CLASSES}
				/>
				<p id={originHintId} className="text-xs text-muted-foreground">
					The address your organisation runs Hephaestus at. It must use HTTPS.
				</p>
			</div>
			{developmentBuild ? (
				<div className="flex flex-col gap-1.5">
					<label htmlFor={webAppId} className="text-sm font-medium">
						Web app address{" "}
						<span className="font-normal text-muted-foreground">(local server only)</span>
					</label>
					<input
						id={webAppId}
						type="url"
						inputMode="url"
						placeholder="http://localhost:4200"
						value={webAppOrigin}
						onChange={(event) => setWebAppOrigin(event.target.value)}
						className={INPUT_CLASSES}
					/>
				</div>
			) : null}
			{invalid ? (
				<p id={errorId} className="text-sm text-destructive" role="alert">
					{state.message}
				</p>
			) : null}
			<div className="flex flex-wrap items-center gap-3">
				<Button type="submit" variant="outline" disabled={pending || disabled}>
					{pending ? <Spinner /> : null}
					{pending ? "Connecting…" : "Connect"}
				</Button>
				<p className="text-xs text-muted-foreground">
					Chrome asks before the extension may reach this address.
				</p>
			</div>
		</form>
	);
}
