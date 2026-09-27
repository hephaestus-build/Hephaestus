import { ServerIcon } from "lucide-react";
import { useState } from "react";

import { HephMark } from "~/components/brand/HephaestusLogo";
import { Button } from "~/components/common/Button";
import { Spinner } from "~/components/common/Spinner";

export interface InstanceCardProps {
	/** The instance's host, as the reader typed or chose it. */
	host: string;
	/** The hosted service rather than one an organisation runs itself. */
	hosted: boolean;
	disconnecting: boolean;
	onDisconnect: () => void;
}

/**
 * Which Hephaestus the extension talks to, and the way back to choosing another. Disconnecting signs
 * out and gives up the extension's access to that address, so it asks first.
 */
export function InstanceCard({ host, hosted, disconnecting, onDisconnect }: InstanceCardProps) {
	const [confirming, setConfirming] = useState(false);
	return (
		<div className="flex flex-col gap-3">
			<div className="flex flex-wrap items-center gap-x-3 gap-y-2">
				{hosted ? (
					<HephMark className="size-8" />
				) : (
					<span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted">
						<ServerIcon aria-hidden className="size-4 text-muted-foreground" />
					</span>
				)}
				<div className="flex min-w-0 flex-1 flex-col">
					<p className="text-sm font-medium break-all">{host}</p>
					<p className="text-xs text-muted-foreground">
						{hosted ? "Hosted Hephaestus" : "Self-hosted instance"}
					</p>
				</div>
				{confirming ? null : (
					<Button variant="outline" onClick={() => setConfirming(true)}>
						Change instance…
					</Button>
				)}
			</div>
			{confirming ? (
				<div className="flex flex-col gap-3 rounded-lg border border-border bg-muted/40 p-3.5">
					<p className="text-sm">
						Disconnect from <span className="font-medium">{host}</span>? You are signed out, and the
						extension gives up its access to that address. You can then connect to{" "}
						{hosted ? "a self-hosted instance" : "hosted Hephaestus or another instance"}.
					</p>
					<div className="flex flex-wrap gap-2">
						<Button variant="destructive-outline" disabled={disconnecting} onClick={onDisconnect}>
							{disconnecting ? <Spinner /> : null}
							{disconnecting ? "Disconnecting…" : "Disconnect"}
						</Button>
						<Button variant="ghost" disabled={disconnecting} onClick={() => setConfirming(false)}>
							Keep it
						</Button>
					</div>
				</div>
			) : null}
		</div>
	);
}
