/**
 * The build gate's scan of the web app inside a server image (`lib/web-app-scan.ts`, ADR 0100). The image scan cannot
 * see it, so the gate scans it beside the image, from the same digest, under the same policy.
 *
 * usage: scan-web-app <image> <platform> <digest> <repository> <stem>
 */
import { scanWebApp } from "./lib/image-scan.ts";

const [image = "", platform = "", digest = "", repository = "", stem = ""] = process.argv.slice(2);
if ([image, platform, digest, repository, stem].includes("")) {
	throw new Error("usage: scan-web-app <image> <platform> <digest> <repository> <stem>");
}
if (
	!(await scanWebApp({ image, repository }, digest, platform, stem, {
		annotate: true,
		required: true,
	}))
) {
	process.exitCode = 1;
}
