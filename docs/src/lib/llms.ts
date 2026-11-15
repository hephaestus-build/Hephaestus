import { llms } from "fumadocs-core/source";

import { DESCRIPTION, SITE_TITLE } from "@/lib/site";
import { source } from "@/lib/source";

// Server only: the Markdown twins, `llms.txt` and `llms-full.txt` are prerendered to static files.
export const docsLlms = llms(source, {
	renderPage: async (page) => `# ${page.data.title ?? page.url}

${page.data.description ?? ""}

${await page.data.getText("processed")}`,
});

export const llmsHeader = `# ${SITE_TITLE}

> ${DESCRIPTION}
`;
