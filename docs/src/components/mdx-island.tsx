import { DocsBody } from "fumadocs-ui/layouts/docs/page";
import defaultMdxComponents from "fumadocs-ui/mdx";

/*
 * The components of the page content island (`components` of the Fumadocs integration in `astro.config.ts`). The island
 * hydrates, so the code-block copy button works. The components of `<Content components>` (`mdx.tsx`) take precedence
 * and render as static HTML into its slots: links, headings, callouts and diagrams need no JavaScript.
 * `wrapper` gives the content its prose styles.
 */
const islandComponents = { ...defaultMdxComponents, wrapper: DocsBody };

export default islandComponents;
