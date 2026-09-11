package de.tum.cit.aet.hephaestus.agent.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class SecretDiffScanner {

    record SecretHit(String path, int newLine, String addedLine, String ruleId, String matchedToken) {}

    /** {@code @@ -a,b +c,d @@} — capture the new-side start line. */
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /** Known credential prefixes (structural — the prefix is the signal; entropy gate bypassed). */
    private record PrefixRule(String id, Pattern pattern) {}

    private static final List<PrefixRule> PREFIX_RULES = List.of(
            new PrefixRule("openai-key", Pattern.compile("sk-(?:proj-|admin-|svcacct-)?[A-Za-z0-9_-]{16,}")),
            new PrefixRule("github-pat", Pattern.compile("gh[pousr]_[A-Za-z0-9_]{36,}")),
            new PrefixRule("github-fine-grained-pat", Pattern.compile("github_pat_[A-Za-z0-9_]{22,}")),
            new PrefixRule("gitlab-pat", Pattern.compile("glpat-[A-Za-z0-9_-]{20,}")),
            new PrefixRule("aws-access-key", Pattern.compile("(?:AKIA|ASIA)[0-9A-Z]{16}")),
            new PrefixRule("slack-token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,48}")),
            new PrefixRule("google-api-key", Pattern.compile("AIza[0-9A-Za-z_-]{35}")),
            new PrefixRule("stripe-key", Pattern.compile("(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{16,}")),
            new PrefixRule("sendgrid-key", Pattern.compile("SG\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}")),
            new PrefixRule("huggingface-token", Pattern.compile("hf_[A-Za-z0-9]{30,}")));

    private static final Pattern PRIVATE_KEY =
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----");

    private static final Pattern CONNECTION_STRING = Pattern.compile(
            "(?:postgres|postgresql|mysql|mongodb|mongodb\\+srv|redis|amqp|amqps)://[^\\s:@/]+:[^\\s:@/]+@");

    /** {@code (api_key|secret|token|password|...) = "value"} — the generic high-entropy arm. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(?:api[_-]?key|secret|token|passwd|password|auth[_-]?token|access[_-]?key|client[_-]?secret|credential)s?"
                    + "\\s*[:=]\\s*[\"']([^\"'\\s]{12,150})[\"']");

    /** gitleaks community-tuned threshold for a generic high-entropy secret. */
    private static final double ENTROPY_THRESHOLD = 3.5;

    /**
     * Placeholder words — matched against whole {@code [_-]}/non-alphanumeric-delimited segments of a
     * candidate value, never as raw substrings (so a real key like {@code sk-weather-...} is not
     * suppressed just because "weather" contains "the"). "test" is intentionally excluded — Stripe
     * {@code sk_test_} keys are real credentials.
     */
    private static final Set<String> PLACEHOLDER_WORDS = Set.of(
            "your",
            "my",
            "the",
            "some",
            "placeholder",
            "example",
            "sample",
            "dummy",
            "fake",
            "changeme",
            "replace",
            "replaceme",
            "todo",
            "fixme",
            "redacted",
            "none",
            "null",
            "xxx",
            "xxxx");

    /** Env-var read, not a hardcode (the value comes from the environment, not the source). */
    private static final Pattern ENV_REFERENCE = Pattern.compile(
            "(?:process\\.env|import\\.meta\\.env|System\\.getenv|os\\.getenv|os\\.environ|getenv\\(|ENV\\[|"
                    + "Deno\\.env|Bundle\\.main|Keychain|ProcessInfo\\.processInfo\\.environment|Secrets\\.|\\$\\{[^}]+}|\\$[A-Z_]+)");

    /** Documentation example keys that are intentionally public. */
    private static final List<String> DOC_EXAMPLE_KEYS = List.of("AKIAIOSFODNN7EXAMPLE", "sk-test_example");

    /**
     * Paths whose hits are downgraded to non-blocking (test/example/doc fixtures). The singular
     * {@code example}/{@code sample} counts only as the first segment: deeper in a path it names a
     * package rather than a directory of samples, and {@code com/example} is the placeholder group every
     * JVM project generator writes, so a credential in that tree is application code with a credential in
     * it. The plural still matches at any depth, where it is a samples directory even when it sits inside
     * a workspace package ({@code packages/sdk/examples/}).
     */
    private static final Pattern LOW_SIGNAL_PATH =
            Pattern.compile("(?i)(?:^|/)(?:tests?|spec|specs|fixtures?|__tests__|__mocks__|docs?|examples|samples)/"
                    + "|^(?:example|sample)/"
                    + "|\\.(?:example|sample|md)$|(?:^|/)\\.env\\.example$");

    /**
     * Scan a raw or Hephaestus-annotated unified diff for hardcoded secrets on added lines.
     *
     * @param unifiedDiff the output of {@code git diff base head} (may be null/blank)
     * @return one {@link SecretHit} per credential found on a {@code '+'} line
     */
    List<SecretHit> scan(@Nullable String unifiedDiff) {
        List<SecretHit> hits = new ArrayList<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return hits;
        }

        String currentPath = null;
        int newLine = 0;
        for (String storedLine : unifiedDiff.split("\n", -1)) {
            int annotationEnd = storedLine.startsWith("[L") ? storedLine.indexOf("] ") : -1;
            String raw = annotationEnd > 0 ? storedLine.substring(annotationEnd + 2) : storedLine;
            if (raw.startsWith("+++ ")) {
                currentPath = parseNewPath(raw);
                continue;
            }
            if (raw.startsWith("--- ") || raw.startsWith("diff ") || raw.startsWith("index ")) {
                continue;
            }
            Matcher hunk = HUNK_HEADER.matcher(raw);
            if (hunk.find()) {
                newLine = Integer.parseInt(hunk.group(1));
                continue;
            }
            if (raw.startsWith("\\")) {
                // "\ No newline at end of file" marker — diff metadata, not a source line. It must NOT advance
                // the new-side counter, or every subsequent secret hit reports a line number off by one.
                continue;
            }
            if (raw.startsWith("-")) {
                // Removed line — does not advance the new-side counter.
                continue;
            }
            if (raw.startsWith("+") && !raw.startsWith("+++")) {
                String content = raw.substring(1);
                if (currentPath != null) {
                    scanLine(currentPath, newLine, content, hits);
                }
            }
            newLine++;
        }
        return hits;
    }

    private void scanLine(String path, int line, String content, List<SecretHit> hits) {
        String trimmed = content.stripLeading();
        // Skip comment lines (low signal; a commented-out key is not live in source). Note the
        // "-- " SQL-comment guard requires a trailing space so it never eats a "-----BEGIN ... KEY".
        if (trimmed.startsWith("//")
                || trimmed.startsWith("#")
                || trimmed.startsWith("* ")
                || trimmed.startsWith("-- ")) {
            return;
        }
        boolean envRef = ENV_REFERENCE.matcher(content).find();

        // 1) Structural prefix rules (the prefix is the signal — entropy gate bypassed).
        // The line-wide env-reference veto must NOT apply here: a hybrid `process.env.KEY || "sk-live-…"`
        // fallback references an env var AND commits a real structural-prefix literal. Vetoing the whole line
        // because `process.env` appears anywhere on it would suppress that committed secret. A structural prefix
        // match is a literal regardless of any env reference elsewhere on the line — only the doc-example /
        // placeholder guards (the token IS the example) apply.
        for (PrefixRule rule : PREFIX_RULES) {
            Matcher m = rule.pattern().matcher(content);
            // Loop over EVERY occurrence, not just the first: a line that hardcodes two same-type tokens (e.g.
            // two `ghp_` PATs) must report both, or the second leaks. (The caller dedups by path:newLine:ruleId,
            // so genuinely identical same-rule hits on one line still collapse there.)
            while (m.find()) {
                String token = m.group();
                if (isDocExample(token) || isPlaceholder(token)) {
                    continue;
                }
                hits.add(new SecretHit(path, line, content.strip(), rule.id(), token));
            }
        }
        // 2) Private-key block.
        if (PRIVATE_KEY.matcher(content).find()) {
            hits.add(new SecretHit(path, line, content.strip(), "private-key", "-----BEGIN PRIVATE KEY-----"));
        }
        // 3) Connection string with inline credentials — a concrete `scheme://user:pw@host` is a structural
        // literal, so (like the prefix rules) the line-wide env-reference veto does not apply: a
        // `process.env.DB_URL || "postgres://user:pw@host"` fallback still commits real credentials.
        Matcher conn = CONNECTION_STRING.matcher(content);
        if (conn.find()) {
            hits.add(new SecretHit(path, line, content.strip(), "connection-string", conn.group()));
        }
        // 4) Generic secret-named assignment gated by entropy + allowlist.
        Matcher assign = ASSIGNMENT.matcher(content);
        if (assign.find()) {
            String value = assign.group(1);
            boolean alreadyFlagged =
                    hits.stream().anyMatch(h -> h.newLine() == line && h.path().equals(path));
            if (!alreadyFlagged
                    && !envRef
                    && !isPlaceholder(value)
                    && !looksLikePathOrUrl(value)
                    && shannonEntropy(value) >= ENTROPY_THRESHOLD) {
                hits.add(new SecretHit(path, line, content.strip(), "generic-entropy", value));
            }
        }
    }

    /**
     * Heuristic for a value that is a filesystem path or URL rather than a credential — used to avoid flagging
     * paths/URLs as generic-entropy secrets. Deliberately NOT a blanket {@code /}-ban: standard base64/base64url
     * secret material routinely contains a {@code /} from its alphabet, so a single slash must not suppress a
     * real high-entropy token. Skips only when the value starts with a path/URL prefix, embeds a {@code ://}
     * scheme, or carries enough slashes ({@code >= 3}) to be path-structured (a 40-char base64 averages well
     * under one slash). The entropy gate remains the precision backstop.
     */
    private static boolean looksLikePathOrUrl(String value) {
        if (value.startsWith("/")
                || value.startsWith("./")
                || value.startsWith("../")
                || value.startsWith("http")
                || value.contains("://")) {
            return true;
        }
        int slashes = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '/') {
                slashes++;
            }
        }
        return slashes >= 3;
    }

    private boolean isPlaceholder(String value) {
        // template markers: <...>, {{...}}, ${...}
        if (value.contains("<") || value.contains("{{") || value.contains("${")) {
            return true;
        }
        // all-same-char masks like xxxx, ****, 0000
        if (value.chars().distinct().count() <= 2) {
            return true;
        }
        // a whole delimited segment is a placeholder word (YOUR_API_KEY → [your,api,key])
        String low = value.toLowerCase(Locale.ROOT);
        for (String segment : low.split("[^a-z0-9]+")) {
            if (PLACEHOLDER_WORDS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDocExample(String token) {
        for (String doc : DOC_EXAMPLE_KEYS) {
            if (token.contains(doc)) {
                return true;
            }
        }
        return false;
    }

    /** True when a hit's path is a low-signal test/example/doc location (caller downgrades severity). */
    boolean isLowSignalPath(String path) {
        return LOW_SIGNAL_PATH.matcher(path).find();
    }

    private static @Nullable String parseNewPath(String header) {
        // "+++ b/path/to/file"  or  "+++ path/to/file"
        String p = header.substring(4).trim();
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        if (p.startsWith("b/")) {
            p = p.substring(2);
        }
        return p.equals("/dev/null") ? null : p;
    }

    private static double shannonEntropy(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int[] freq = new int[128];
        int counted = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) {
                freq[c]++;
                counted++;
            }
        }
        double entropy = 0;
        for (int f : freq) {
            if (f > 0) {
                double p = (double) f / counted;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }
}
