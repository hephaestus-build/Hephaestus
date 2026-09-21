import { CircleAlert } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

export interface FormError {
	/** The `id` of the field this is about. Activating the entry moves focus there. */
	fieldId: string;
	message: string;
	/**
	 * Called before focusing, for a field that is not in the document yet. A collapsed section
	 * unmounts its contents, so without this the entry links to an id that does not exist and focus
	 * stays on the link.
	 */
	reveal?: () => void;
}

export interface FormErrorSummaryProps {
	errors: FormError[];
	className?: string;
}

/**
 * Every reason a submit was refused, linking to the field each one is about.
 *
 * Announced but never focused. [GOV.UK](https://design-system.service.gov.uk/components/error-summary/)
 * moves focus to the summary; this repo focuses the first invalid field instead, so there is one
 * focus target and it is the one that fixes the error.
 */
export function FormErrorSummary({ errors, className }: FormErrorSummaryProps) {
	if (errors.length === 0) {
		return null;
	}

	return (
		<Alert variant="destructive" className={className}>
			<CircleAlert />
			<AlertTitle>
				<h2>
					{errors.length === 1 ? "There is a problem" : `There are ${errors.length} problems`}
				</h2>
			</AlertTitle>
			<AlertDescription>
				<ul className="list-inside list-disc space-y-1">
					{errors.map((error) => (
						<li key={error.fieldId}>
							<a
								href={`#${error.fieldId}`}
								className="underline underline-offset-4"
								onClick={(event) => {
									// A fragment jump cannot expand a collapsed section or wait for the field to mount.
									event.preventDefault();
									error.reveal?.();
									requestAnimationFrame(() => document.getElementById(error.fieldId)?.focus());
								}}
							>
								{error.message}
							</a>
						</li>
					))}
				</ul>
			</AlertDescription>
		</Alert>
	);
}
