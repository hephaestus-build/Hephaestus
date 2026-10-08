import { Trash2Icon, UploadIcon } from "lucide-react";
import { useId, useRef, useState } from "react";

import { cn } from "cn";
import type { PracticeVisual as PracticeVisualValue } from "@/api/types.gen";
import { PracticeVisual } from "@/components/practice-guidance/PracticeVisual";
import { Button } from "@/components/ui/button";
import {
	Field,
	FieldDescription,
	FieldError,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

import {
	hasVisual,
	MAX_SVG_SIZE,
	MAX_VISUAL_ALT_LENGTH,
	NO_VISUAL,
	readSvgFile,
} from "./practice-guidance-draft";

export interface PracticeVisualEditorProps {
	/** The visual being written. An empty `svg` means the practice has no visual. */
	value: PracticeVisualValue;
	onChange: (value: PracticeVisualValue) => void;
	/** Why the markup cannot be saved, beside the markup. */
	svgError?: string;
	/** Why the description cannot be saved, beside the description. */
	altError?: string;
}

/**
 * The practice's one picture: SVG markup, uploaded or pasted, and its description. Once there is
 * markup, the picture shows in the light and the dark theme side by side, because a picture that
 * reads in one theme can lose its lines in the other.
 */
export function PracticeVisualEditor({
	value,
	onChange,
	svgError,
	altError,
}: PracticeVisualEditorProps) {
	const fileInput = useRef<HTMLInputElement>(null);
	const [uploadError, setUploadError] = useState<string>();
	const present = hasVisual(value);

	const upload = async (file: File) => {
		const read = await readSvgFile(file);
		if ("problem" in read) {
			setUploadError(read.problem);
			return;
		}
		setUploadError(undefined);
		onChange({ ...value, svg: read.svg });
	};

	return (
		<FieldSet>
			<FieldLegend>Visual</FieldLegend>
			<FieldDescription>
				Optional. One SVG picture that shows the idea of the practice. Developers see it between Why
				it matters and What good looks like.
			</FieldDescription>

			<div className="flex flex-wrap items-center gap-2">
				<Button
					type="button"
					variant="outline"
					size="sm"
					aria-describedby={hasText(uploadError) ? "practice-visual-upload-error" : undefined}
					onClick={() => fileInput.current?.click()}
				>
					<UploadIcon data-icon="inline-start" aria-hidden />
					Upload SVG
				</Button>
				{present && (
					<Button
						type="button"
						variant="quiet"
						size="sm"
						aria-label="Remove visual"
						onClick={() => {
							setUploadError(undefined);
							onChange(NO_VISUAL);
						}}
					>
						<Trash2Icon data-icon="inline-start" aria-hidden />
						Remove
					</Button>
				)}
				<input
					ref={fileInput}
					type="file"
					accept=".svg,image/svg+xml"
					aria-label="SVG file for the visual"
					className="hidden"
					onChange={(event) => {
						const file = event.target.files?.[0];
						// Cleared so choosing the same file again still reports a change.
						event.target.value = "";
						if (file) {
							void upload(file);
						}
					}}
				/>
			</div>
			{hasText(uploadError) && (
				<FieldError id="practice-visual-upload-error">{uploadError}</FieldError>
			)}

			<Field data-invalid={hasText(svgError) ? "true" : undefined}>
				<FieldLabel htmlFor="practice-visual-svg">SVG markup</FieldLabel>
				<Textarea
					id="practice-visual-svg"
					value={value.svg}
					onChange={(event) => onChange({ ...value, svg: event.target.value })}
					placeholder='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 320">…</svg>'
					className={cn("max-h-48", present ? "min-h-24" : "min-h-12")}
					spellCheck={false}
					aria-invalid={hasText(svgError)}
					aria-describedby={`practice-visual-svg-description${
						hasText(svgError) ? " practice-visual-svg-error" : ""
					}`}
				/>
				<FieldDescription id="practice-visual-svg-description">
					Upload a file or paste its markup. Use shapes and text only, in {MAX_SVG_SIZE} or less.
				</FieldDescription>
				{hasText(svgError) && <FieldError id="practice-visual-svg-error">{svgError}</FieldError>}
			</Field>

			{present && (
				<>
					<Field data-invalid={hasText(altError) ? "true" : undefined}>
						<FieldLabel htmlFor="practice-visual-alt">Description *</FieldLabel>
						<Textarea
							id="practice-visual-alt"
							value={value.alt}
							onChange={(event) => onChange({ ...value, alt: event.target.value })}
							placeholder="Say what the picture shows and what it means…"
							className="min-h-16"
							required
							maxLength={MAX_VISUAL_ALT_LENGTH}
							aria-invalid={hasText(altError)}
							aria-describedby={`practice-visual-alt-description${
								hasText(altError) ? " practice-visual-alt-error" : ""
							}`}
						/>
						<FieldDescription id="practice-visual-alt-description">
							Say what the picture shows, for people who cannot see it. Up to{" "}
							{MAX_VISUAL_ALT_LENGTH} characters.
						</FieldDescription>
						{hasText(altError) && (
							<FieldError id="practice-visual-alt-error">{altError}</FieldError>
						)}
					</Field>
					<PracticeVisualThemePreview visual={value} />
				</>
			)}
		</FieldSet>
	);
}

const PREVIEW_THEMES = [
	{ theme: "light", label: "Light theme" },
	{ theme: "dark", label: "Dark theme" },
] as const;

/**
 * The picture on each theme's own ground. A `light` or `dark` class re-scopes the theme tokens for
 * its subtree, so both previews are true whichever theme the page is in.
 */
function PracticeVisualThemePreview({ visual }: { visual: PracticeVisualValue }) {
	const id = useId();
	return (
		<section aria-label="Preview" className="grid gap-3 sm:grid-cols-2">
			{PREVIEW_THEMES.map(({ theme, label }) => (
				<figure
					key={theme}
					aria-labelledby={`${id}-${theme}`}
					className={cn(
						theme,
						"flex flex-col gap-2 rounded-lg border bg-background p-3 text-foreground",
					)}
				>
					<figcaption id={`${id}-${theme}`} className="text-xs text-muted-foreground">
						{label}
					</figcaption>
					<PracticeVisual
						svg={visual.svg}
						alt={hasText(visual.alt) ? visual.alt : "The visual, with no description yet"}
					/>
				</figure>
			))}
		</section>
	);
}
