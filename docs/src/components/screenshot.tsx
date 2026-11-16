import { BASE } from "@/lib/site";

interface ScreenshotProps {
	/** `<page>/<name>` under `static/img/user/`, without the color scheme and the extension. */
	src: string;
	/** What the screen shows, in at most 155 characters. */
	alt: string;
}

const base = BASE;

/** A user-guide screenshot in the reader's color scheme, from `vp run generate:docs-screenshots`. */
export function Screenshot({ src, alt }: ScreenshotProps) {
	return (
		<span className="not-prose border-fd-border my-6 block overflow-hidden rounded-lg border shadow-sm">
			<img
				className="m-0 block h-auto w-full dark:hidden"
				src={`${base}img/user/${src}.light.webp`}
				alt={alt}
				width={1024}
				height={640}
				loading="lazy"
			/>
			<img
				className="m-0 hidden h-auto w-full dark:block"
				src={`${base}img/user/${src}.dark.webp`}
				alt={alt}
				width={1024}
				height={640}
				loading="lazy"
			/>
		</span>
	);
}
