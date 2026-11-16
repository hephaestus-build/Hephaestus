import { type ReactNode, useId } from "react";
import { BASE } from "@/lib/site";

interface SystemDiagramProps {
	/** The source name in `docs/diagrams/`, without `.hyl`. */
	name: string;
	/** The view and its scope, such as "Deployment view: Hephaestus on one host with k3s". */
	title: string;
	/** What the diagram shows, in at most 155 characters. The text around it gives its facts. */
	alt: string;
	/** Every connection of the view, as text. The SVG text is paths, so this is what a screen reader and a search find. */
	children: ReactNode;
}

const base = BASE;

/** A HyLiMo structural view in the reader's color scheme, from `vp run generate:diagrams`. */
export function SystemDiagram({ name, title, alt, children }: SystemDiagramProps) {
	const connections = useId();
	const light = `${base}img/diagrams/${name}.light.svg`;
	const dark = `${base}img/diagrams/${name}.dark.svg`;
	return (
		<figure className="not-prose my-6">
			<img
				className="block h-auto w-full dark:hidden"
				src={light}
				alt={alt}
				aria-describedby={connections}
				loading="lazy"
			/>
			<img
				className="hidden h-auto w-full dark:block"
				src={dark}
				alt={alt}
				aria-describedby={connections}
				loading="lazy"
			/>
			<figcaption className="text-fd-muted-foreground mt-2 text-sm">
				{title} ·{" "}
				<a className="underline dark:hidden" href={light}>
					Open full size
				</a>
				<a className="hidden underline dark:inline" href={dark}>
					Open full size
				</a>
			</figcaption>
			<details className="mt-2 text-sm">
				<summary className="cursor-pointer">Connections as text</summary>
				<div id={connections} className="prose mt-2">
					{children}
				</div>
			</details>
		</figure>
	);
}
