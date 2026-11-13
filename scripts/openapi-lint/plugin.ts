/**
 * The Hephaestus rules of the OpenAPI lint, which `server/redocly.yaml` loads. ADR 0103 rule 7: from
 * the release that deprecates an operation on, each success response carries `Deprecation`.
 * `@redocly/cli` ships no types, so the few shapes that the rule reads are declared here.
 */

interface Context {
	readonly key: string | number;
	/** Redocly's pointer to the visited node, which the rule only passes back. */
	readonly location: unknown;
	readonly report: (problem: { message: string; location: unknown }) => void;
}

interface Operation {
	readonly operationId?: string;
	readonly deprecated?: boolean;
}

interface Response {
	readonly headers?: Readonly<Record<string, unknown>>;
}

const DEPRECATION_HEADER = "Deprecation";

/** A success status: `200`, or the `2XX` range. */
const SUCCESS = /^2(?:\d\d|XX)$/u;

function deprecationHeader() {
	return {
		Operation: {
			Response(
				response: Response,
				{ key, location, report }: Context,
				parents: { readonly Operation: Operation },
			) {
				const operation = parents.Operation;
				if (operation.deprecated !== true || !SUCCESS.test(String(key))) {
					return;
				}
				const headers = Object.keys(response.headers ?? {});
				if (!headers.some((name) => name.toLowerCase() === DEPRECATION_HEADER.toLowerCase())) {
					report({
						message: `Deprecated operation ${operation.operationId ?? ""} must declare the ${DEPRECATION_HEADER} header on its ${String(key)} response (ADR 0103 rule 7).`,
						location,
					});
				}
			},
		},
	};
}

export default function plugin() {
	return {
		id: "hephaestus",
		rules: {
			oas3: { "deprecation-header": deprecationHeader },
		},
	};
}
