import { type ReactNode, type Ref, useId } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { rendersContent } from "@/lib/react-node";

export interface ConsentBannerProps {
	editing: boolean;
	onAllow: () => void;
	onDecline: () => void;
	onCancel: () => void;
	privacyPolicy?: ReactNode;
	ref?: Ref<HTMLDivElement>;
}

export function ConsentBanner({
	editing,
	onAllow,
	onDecline,
	onCancel,
	privacyPolicy,
	ref,
}: ConsentBannerProps) {
	const titleId = useId();
	const descriptionId = useId();
	return (
		<div className="fixed inset-x-0 bottom-0 z-50 flex justify-center p-4">
			<Card
				ref={ref}
				tabIndex={-1}
				role="region"
				aria-live="polite"
				aria-labelledby={titleId}
				aria-describedby={descriptionId}
				className="w-full max-w-2xl shadow-lg outline-none"
			>
				<CardHeader>
					<CardTitle id={titleId}>Your privacy</CardTitle>
					<CardDescription id={descriptionId}>
						Hephaestus uses essential cookies to keep you signed in and secure. With your
						permission, we’d also like to send error reports so we can fix problems faster. You can
						change this anytime.
						{rendersContent(privacyPolicy) ? <> {privacyPolicy}</> : null}
					</CardDescription>
				</CardHeader>
				<CardContent>
					<div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
						{editing ? (
							<Button variant="ghost" className="sm:mr-auto" onClick={onCancel}>
								Cancel
							</Button>
						) : null}
						<Button onClick={onDecline}>Decline</Button>
						<Button onClick={onAllow}>Allow</Button>
					</div>
				</CardContent>
			</Card>
		</div>
	);
}
