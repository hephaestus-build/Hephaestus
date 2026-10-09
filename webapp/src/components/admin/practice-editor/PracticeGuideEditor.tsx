import { ImagePlusIcon, Trash2Icon } from "lucide-react";
import { useRef, useState } from "react";

import type { PracticeGuide } from "@/api/types.gen";
import { PracticeGuideMarkdown } from "@/components/practice-guidance/PracticeGuideMarkdown";
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { hasText } from "@/lib/text";

import {
	FIGURE_PLACEHOLDER,
	figureDescription,
	figureLine,
	figureName,
	hasGuide,
	insertBlock,
	MAX_GUIDE_FIGURES,
	MAX_GUIDE_LENGTH,
	NO_GUIDE,
	readSvgFile,
	withoutFigure,
} from "./practice-guidance-draft";

export type PracticeGuideView = "write" | "preview";

export interface PracticeGuideEditorProps {
	/** The guide being written. No text and no figures means the practice has no guide. */
	value: PracticeGuide;
	onChange: (value: PracticeGuide) => void;
	/**
	 * Which tab shows. Controlled, so a refused save can bring the text back into view before it
	 * moves the focus there.
	 */
	view: PracticeGuideView;
	onViewChange: (view: PracticeGuideView) => void;
	/** Why the text cannot be saved, beside the text. */
	error?: string;
}

/**
 * The practice guide, which developers read in the practice's Guide tab: Markdown with up to four
 * SVG figures. Adding a figure writes its Markdown line at the cursor, and removing a figure takes
 * its lines out of the text, so the text and the figures cannot drift apart.
 */
export function PracticeGuideEditor({
	value,
	onChange,
	view,
	onViewChange,
	error,
}: PracticeGuideEditorProps) {
	// Where the author last left the cursor in the text. Unset until they place it, so a figure added
	// to text nobody has clicked into goes to the end rather than the top.
	const cursor = useRef<number>(undefined);
	const fileInput = useRef<HTMLInputElement>(null);
	const [figureError, setFigureError] = useState<string>();
	const names = Object.keys(value.figures);
	const full = names.length >= MAX_GUIDE_FIGURES;

	const addFigure = async (file: File) => {
		const read = await readSvgFile(file);
		if ("problem" in read) {
			setFigureError(read.problem);
			return;
		}
		setFigureError(undefined);
		const name = figureName(file.name, names);
		const line = figureLine(name);
		// Removing a figure or the guide shortens the text under a cursor that stayed where it was.
		const at = Math.min(cursor.current ?? value.markdown.length, value.markdown.length);
		const markdown = insertBlock(value.markdown, line, at);
		// After the new line, so a second figure follows the first instead of landing above it.
		cursor.current = markdown.indexOf(line, at) + line.length;
		onChange({ markdown, figures: { ...value.figures, [name]: read.svg } });
	};

	const removeFigure = (name: string) => {
		onChange({
			markdown: withoutFigure(value.markdown, name),
			figures: Object.fromEntries(Object.entries(value.figures).filter(([key]) => key !== name)),
		});
	};

	return (
		<FieldSet>
			<FieldLegend>Guide</FieldLegend>
			<FieldDescription>
				Optional. A longer explanation that developers read in the practice’s Guide tab. Write it in
				Markdown, with up to {MAX_GUIDE_FIGURES} SVG figures.
			</FieldDescription>

			<Field data-invalid={hasText(error) ? "true" : undefined}>
				<Tabs
					value={view}
					onValueChange={(next) => onViewChange(next === "preview" ? "preview" : "write")}
				>
					<div className="flex flex-wrap items-center justify-between gap-2">
						<FieldLabel htmlFor="practice-guide-markdown">Guide text</FieldLabel>
						<TabsList aria-label="Guide view">
							<TabsTrigger value="write">Write</TabsTrigger>
							<TabsTrigger value="preview">Preview</TabsTrigger>
						</TabsList>
					</div>
					<TabsContent value="write">
						<Textarea
							id="practice-guide-markdown"
							value={value.markdown}
							onChange={(event) => {
								cursor.current = event.target.selectionStart;
								onChange({ ...value, markdown: event.target.value });
							}}
							onSelect={(event) => {
								cursor.current = event.currentTarget.selectionStart;
							}}
							placeholder="## How to do it…"
							className="min-h-56"
							maxLength={MAX_GUIDE_LENGTH}
							aria-invalid={hasText(error)}
							aria-describedby={`practice-guide-markdown-description${
								hasText(error) ? " practice-guide-markdown-error" : ""
							}`}
						/>
					</TabsContent>
					<TabsContent value="preview">
						<div className="rounded-lg border p-4">
							{hasText(value.markdown) ? (
								<PracticeGuideMarkdown guide={value} />
							) : (
								<p className="text-muted-foreground">Nothing to preview yet.</p>
							)}
						</div>
					</TabsContent>
				</Tabs>
				<FieldDescription id="practice-guide-markdown-description">
					The Hephaestus guides use four headings: How to do it, When it does not apply, Common
					mistakes, and Sources. You can use your own.
				</FieldDescription>
				{hasText(error) && <FieldError id="practice-guide-markdown-error">{error}</FieldError>}
			</Field>

			<div className="space-y-3">
				<div className="flex flex-wrap items-center gap-2">
					<Button
						type="button"
						variant="outline"
						size="sm"
						disabled={full}
						aria-describedby={`practice-guide-figures-description${
							hasText(figureError) ? " practice-guide-figures-error" : ""
						}`}
						onClick={() => fileInput.current?.click()}
					>
						<ImagePlusIcon data-icon="inline-start" aria-hidden />
						Add figure
					</Button>
					<input
						ref={fileInput}
						type="file"
						accept=".svg,image/svg+xml"
						aria-label="SVG file for a figure"
						className="hidden"
						onChange={(event) => {
							const file = event.target.files?.[0];
							// Cleared so choosing the same file again still reports a change.
							event.target.value = "";
							if (file) {
								void addFigure(file);
							}
						}}
					/>
				</div>
				<FieldDescription id="practice-guide-figures-description">
					{full
						? `A guide shows at most ${MAX_GUIDE_FIGURES} figures. Remove one to add another.`
						: `Adds an SVG figure and a line that shows it at the cursor. Replace “${FIGURE_PLACEHOLDER}” with what the figure shows.`}
				</FieldDescription>
				{hasText(figureError) && (
					<FieldError id="practice-guide-figures-error">{figureError}</FieldError>
				)}
				{names.length > 0 && (
					<ul aria-label="Figures" className="grid gap-3 sm:grid-cols-2">
						{names.map((name) => (
							<li key={name} className="flex items-start gap-3 rounded-lg border p-2">
								<PracticeVisual
									svg={value.figures[name] ?? ""}
									alt={figureDescription(value.markdown, name) ?? `Figure ${name}`}
									className="w-28 shrink-0"
								/>
								<div className="min-w-0 flex-1 space-y-1">
									<p className="truncate font-mono text-xs">figures/{name}.svg</p>
									<Button
										type="button"
										variant="quiet"
										size="sm"
										aria-label={`Remove figure ${name}`}
										onClick={() => removeFigure(name)}
									>
										<Trash2Icon data-icon="inline-start" aria-hidden />
										Remove
									</Button>
								</div>
							</li>
						))}
					</ul>
				)}
			</div>

			{hasGuide(value) && (
				<div>
					<Button
						type="button"
						variant="quiet"
						size="sm"
						onClick={() => {
							setFigureError(undefined);
							onChange(NO_GUIDE);
						}}
					>
						<Trash2Icon data-icon="inline-start" aria-hidden />
						Remove guide
					</Button>
				</div>
			)}
		</FieldSet>
	);
}
