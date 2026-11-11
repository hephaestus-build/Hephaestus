package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.candidate.Candidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Words that say the repository, beyond the lines a result cites, lacks something: that nothing elsewhere handles a
 * failure, or that the code base defines no such thing. Only a search of the whole checkout can show that (ADR 0064 §
 * Absence). This is a short list of markers, not a reading of the prose. It catches the usual wording of such a claim
 * and leaves claims bounded to the change or the cited lines alone. A clause that states what the review did not
 * establish is a limit of the review, not a claim. Clauses end at a full stop, a semicolon, a colon, a dash, a
 * parenthesis or a comma before a conjunction, so such a limit cannot hide the claim beside it. {@code pi-session-reads.ts} holds the same markers, so the runner
 * asks the session before admission withholds the result.
 *
 * <p>A search of the whole checkout backs a claim only when it names what the claim says is absent, or a model could
 * search for nonsense and claim anything. The server does not run a model's pattern: it reads the words of a plain one,
 * words joined by {@code |}, and any other pattern names nothing. Each claim must hold one of those words. A search
 * that ignored case must be able to find the claim's words: {@code auth} finds {@code authentication}, and
 * {@code retryzzz} finds nothing. One that did not must match as written, because {@code RETRY} does not find
 * {@code retry}. A match does not refute "no other file references X", so a search with matches can back a claim, but
 * the result must then say what the matches are. This stops a lazy or nonsense search, and it puts on the record what
 * a steered one ignored. It does not judge whether that account is true.
 */
final class AbsenceClaims {

    static final List<String> MARKERS = List.of(
            "(?:\\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|"
                    + "absent)\\b[^.]{0,80}\\b(?:in|across|throughout|under)|\\b(?:missing|absent) from) "
                    + "(?:the |this |our )?(?:whole |entire |"
                    + "reviewed |pinned |checked-out |rest of the )?(?:repository|repo|codebase|code base|"
                    + "checkout|project|application|source tree|src)\\b(?!\\.\\w|(?:'s)?[ -](?:code|manifest|"
                    + "inventory|policy|board|tracker|implementation|service|config|method|call|class|interface|layer|bean|query|lookup|result|field|object|instance|step|action|settings?)s?\\b)",
            "\\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|"
                    + "absent)\\b[^.]{0,80}\\b(?:anywhere|elsewhere) (?:in|across|throughout|"
                    + "under) (?:the )?(?:whole |entire |reviewed |pinned |checked-out |"
                    + "rest of the )?(?:code|app|source|sources)\\b(?![ -](?:file|files|change|diff)\\b)",
            "\\bno other files?\\b(?! (?:in|of) (?:the|this) (?:change|diff|commit|"
                    + "pull request)\\b)[^.;]{0,30}\\b(?:references?|imports?|calls?|uses?|instantiates?|"
                    + "consumes?|wires?|depends? on|handles?|catches?|validates?)\\b|\\b(?:referenced|"
                    + "imported|called|used|instantiated|consumed|handled) (?:by|from|in) no other files?\\b|"
                    + "\\breferences from other files\\b",
            "(?<!from )\\b(?:the|this) (?:whole |entire |reviewed |pinned )?(?:repository|repo|codebase|"
                    + "code base|checkout)\\b(?! (?:method|call|class|interface|layer|bean|query|lookup|"
                    + "result|field|object|instance|step|action|policy|"
                    + "settings?)s?\\b)[^.]{0,40}\\b(?:contains no|has no|holds no|defines no|declares no|"
                    + "registers no|lacks?|never|does not|doesn't)\\b|\\b(?:the|this) (?:project|"
                    + "application)\\b(?![ -](?:code|manifest|inventory|policy|board|"
                    + "tracker)\\b)[^.]{0,40}\\b(?:defines no|declares no|registers no|never)\\b",
            "\\b(?:nothing else|nothing in the (?:project|application|code ?base|repository|repo|"
                    + "checkout)|no (?:other )?(?:code|callers?)(?! path| (?:in|of)\\b)|none of (?:the|"
                    + "its) callers)\\b[^.]{0,60}\\b(?:catch(?:es)?|handles?)\\b(?!\\()",
            "\\b(?:the|its) only (?:unit |integration |end-to-end )?tests? (?:of|for|that "
                    + "(?:covers?|exercises?|checks?))\\b(?![^.;]{0,60}\\b(?:in|of) (?:the|this) "
                    + "(?:change|diff|commit|pull request)\\b)|\\bno other tests?\\b(?! (?:in|of) "
                    + "(?:the|this) (?:change|diff|commit|pull request)\\b)[^.;]{0,30}\\b(?:covers?|"
                    + "exercises?|tests?|checks?|asserts?|verif(?:y|ies)|reach(?:es)?)\\b|"
                    + "\\b(?:covered|tested|exercised) by no other tests?\\b");

    static final String DISCLAIMER =
            "\\b(?:did|does|do|could|can|would)(?: not|n't) (?:\\w+ ){0,1}?(?:establish|inspect|"
                    + "examine|search|judge)\\w*\\b|\\bnot (?:established|inspected|examined|searched|"
                    + "judged)\\b|\\bwithout (?:establishing|inspecting|examining|searching|judging)\\b|"
                    + "\\bnot that\\b|\\b(?:was|were) not (?:examined|inspected|judged)\\b|"
                    + "\\bno (?:[\\w-]+ ){0,8}?(?:was|were) (?:examined|inspected|judged)\\b";

    static final String CLAUSE_END =
            "(?<=[.;:])[ \\t\\r\\n\\u00A0]+|,[ \\t\\r\\n\\u00A0]+(?=(?:but|and|so|yet|while|though|"
                    + "although)[ \\t\\r\\n\\u00A0])|[ \\t\\r\\n\\u00A0]+[—–-][ \\t\\r\\n\\u00A0]+|[ \\t\\r\\n\\u00A0]*[()][ \\t\\r\\n\\u00A0]*";

    static final String PLAIN_SEARCH =
            "(?:\\(\\?i\\))?(?:\\\\b)?[\\p{L}\\p{N}_-]+(?:\\\\b)?(?:\\|(?:\\\\b)?[\\p{L}\\p{N}_-]+(?:\\\\b)?)*";

    /** Words that name no subject: a clause of only these says what is absent in another sentence. */
    static final List<String> GENERIC_WORDS = List.of(
            "a",
            "across",
            "an",
            "and",
            "any",
            "anywhere",
            "app",
            "application",
            "are",
            "as",
            "at",
            "base",
            "be",
            "by",
            "call",
            "caller",
            "catch",
            "check",
            "checked",
            "checkout",
            "class",
            "code",
            "codebase",
            "commit",
            "consume",
            "contain",
            "declare",
            "define",
            "depend",
            "diff",
            "do",
            "does",
            "doesn",
            "else",
            "elsewhere",
            "entire",
            "exist",
            "file",
            "find",
            "for",
            "found",
            "from",
            "function",
            "handle",
            "has",
            "hasn",
            "have",
            "helper",
            "hold",
            "import",
            "in",
            "instantiate",
            "into",
            "is",
            "isn",
            "it",
            "its",
            "lack",
            "logic",
            "mechanism",
            "method",
            "missing",
            "never",
            "no",
            "none",
            "not",
            "nothing",
            "nowhere",
            "of",
            "on",
            "one",
            "only",
            "or",
            "other",
            "out",
            "path",
            "pinned",
            "project",
            "pull",
            "reference",
            "register",
            "repo",
            "repository",
            "request",
            "rest",
            "reviewed",
            "search",
            "source",
            "sources",
            "src",
            "such",
            "that",
            "the",
            "there",
            "thing",
            "this",
            "throughout",
            "to",
            "tree",
            "under",
            "use",
            "used",
            "using",
            "validate",
            "value",
            "was",
            "were",
            "whole",
            "wire",
            "with",
            "without");

    private static final Pattern PLAIN = Pattern.compile(PLAIN_SEARCH);
    private static final Pattern WORD_BREAK = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})|[^\\p{L}\\p{N}]+");
    private static final Pattern WORD_EDGE = Pattern.compile("^[_-]|[_-]$");
    private static final Pattern TOKEN_BREAK = Pattern.compile("[^\\p{L}\\p{N}_-]+");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> GENERIC =
            GENERIC_WORDS.stream().map(AbsenceClaims::stem).collect(Collectors.toSet());

    private static final List<Pattern> PATTERNS =
            MARKERS.stream().map(AbsenceClaims::compile).toList();
    private static final Pattern DISCLAIMS = compile(DISCLAIMER);
    private static final Pattern CLAUSES = Pattern.compile(CLAUSE_END);

    private AbsenceClaims() {}

    private static Pattern compile(String marker) {
        return Pattern.compile(marker, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Whether any text the result shows a reader says the repository lacks something. */
    static boolean claimed(Candidate candidate) {
        return texts(candidate).stream().anyMatch(AbsenceClaims::claimed);
    }

    /**
     * Whether the patterns of a result's searches name what it says is absent: each claim, and when the result names
     * the checkout as consulted, what it looked for.
     */
    static boolean searchedFor(Candidate candidate, List<String> patterns, boolean consulted) {
        return searchedFor(subject(candidate), texts(candidate), patterns, consulted);
    }

    private static String subject(Candidate candidate) {
        var search = candidate.search();
        var inapplicability = candidate.inapplicability();
        return search != null
                ? search.lookedFor()
                : inapplicability != null ? inapplicability.prerequisite() : candidate.summary();
    }

    static boolean searchedFor(String subject, List<String> texts, List<String> patterns, boolean consulted) {
        var terms = terms(patterns);
        return claims(subject, texts, consulted).stream().allMatch(claim -> named(terms, claim));
    }

    /** Whether a search that found matches names any claim of the result: the result must then say what they are. */
    static boolean hitsOwed(Candidate candidate, List<String> patternsWithMatches, boolean consulted) {
        return hitsOwed(subject(candidate), texts(candidate), patternsWithMatches, consulted);
    }

    static boolean hitsOwed(String subject, List<String> texts, List<String> patternsWithMatches, boolean consulted) {
        var terms = terms(patternsWithMatches);
        return claims(subject, texts, consulted).stream().anyMatch(claim -> named(terms, claim));
    }

    /** The text of each claim: a clause that names its own subject, or else what the result looked for. */
    private static List<String> claims(String subject, List<String> texts, boolean consulted) {
        var clauses = texts.stream()
                .flatMap(text -> Arrays.stream(CLAUSES.split(text)))
                .filter(AbsenceClaims::claimed)
                .map(clause -> content(clause).isEmpty() ? subject : clause)
                .toList();
        return clauses.isEmpty() && consulted ? List.of(subject) : clauses;
    }

    private static List<Term> terms(List<String> patterns) {
        return patterns.stream().flatMap(pattern -> terms(pattern).stream()).toList();
    }

    private record Term(String word, boolean ignoresCase, boolean leadingBoundary, boolean bounded) {}

    private static List<Term> terms(String pattern) {
        if (!PLAIN.matcher(pattern).matches()) return List.of();
        boolean ignoresCase = pattern.startsWith("(?i)");
        return Arrays.stream(pattern.replace("(?i)", "").split("\\|"))
                .map(word ->
                        new Term(word.replace("\\b", ""), ignoresCase, word.startsWith("\\b"), word.endsWith("\\b")))
                .filter(term -> !content(term.word()).isEmpty()
                        && !WORD_EDGE.matcher(term.word()).find())
                .toList();
    }

    private static boolean named(List<Term> terms, String text) {
        var words = words(text);
        var tokens = TOKEN_BREAK.splitAsStream(text).collect(Collectors.toSet());
        var folded =
                tokens.stream().map(token -> token.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return terms.stream().anyMatch(term -> {
            boolean names = term.ignoresCase()
                    ? folded.contains(term.word().toLowerCase(Locale.ROOT)) || finds(term, words)
                    : tokens.contains(term.word());
            return names && (!(term.leadingBoundary() || term.bounded()) || boundedFinds(term, text));
        });
    }

    /** Ripgrep's Unicode word boundary applies before words splits an identifier. */
    private static boolean boundedFinds(Term term, String text) {
        int length = term.word().codePointCount(0, term.word().length());
        int points = text.codePointCount(0, text.length());
        String word = term.ignoresCase() ? term.word().toLowerCase(Locale.ROOT) : term.word();
        for (int first = 0, at = 0;
                first <= points - length;
                first++, at += Character.charCount(text.codePointAt(at))) {
            if (term.leadingBoundary() && !boundaryAt(text, at)) continue;
            // Keep the original count: lowercasing İ must not join extra pattern positions.
            int end = text.offsetByCodePoints(at, length);
            String original = text.substring(at, end);
            String selected = term.ignoresCase() ? original.toLowerCase(Locale.ROOT) : original;
            if (selected.equals(word) && (!term.bounded() || boundaryAt(text, end))) return true;
        }
        return false;
    }

    private static boolean boundaryAt(String text, int at) {
        return WORD_BOUNDARY
                .matcher(text)
                .useTransparentBounds(true)
                .region(at, text.length())
                .lookingAt();
    }

    /**
     * Whether a search for {@code term} finds these words. It runs over them in order, past a separator only where one
     * word ends, and ends inside the last. It starts and ends at a word that names a
     * subject, so that a prefix of a generic word names nothing.
     */
    private static boolean finds(Term term, List<String> words) {
        String search = term.word().toLowerCase(Locale.ROOT);
        for (int first = 0; first < words.size(); first++) {
            if (GENERIC.contains(stem(words.get(first)))) continue;
            int at = 0;
            for (int next = first; next < words.size() && at < search.length(); next++) {
                String word = words.get(next);
                String rest = search.substring(at);
                boolean sameWord = rest.length() <= word.length() && stem(rest).equals(stem(word));
                boolean ends = sameWord || (term.bounded() ? rest.equals(word) : word.startsWith(rest));
                if (rest.length() >= 3 && ends && !GENERIC.contains(stem(word))) return true;
                if (!rest.startsWith(word)) break;
                at += word.length();
                if (at < search.length() && "_-".indexOf(search.charAt(at)) >= 0) at++;
            }
        }
        return false;
    }

    private static Set<String> content(String text) {
        return words(text).stream()
                .map(AbsenceClaims::stem)
                .filter(stem -> stem.length() >= 3 && !GENERIC.contains(stem))
                .collect(Collectors.toSet());
    }

    private static List<String> words(String text) {
        return WORD_BREAK
                .splitAsStream(text)
                .filter(word -> !word.isEmpty())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
    }

    /** A crude stem: it needs only to bring the forms of a word together, and a miss costs one more search. */
    private static String stem(String word) {
        String stem = word.toLowerCase(Locale.ROOT);
        if (stem.length() > 4 && stem.endsWith("ies")) {
            stem = stem.substring(0, stem.length() - 3) + "y";
        } else if (stem.length() > 5 && stem.endsWith("ing")) {
            stem = stem.substring(0, stem.length() - 3);
        } else if (stem.length() > 4 && (stem.endsWith("ed") || stem.endsWith("es"))) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.length() > 3 && stem.endsWith("s")) {
            stem = stem.substring(0, stem.length() - 1);
        }
        return stem.length() > 3 && stem.endsWith("e") ? stem.substring(0, stem.length() - 1) : stem;
    }

    private static List<String> texts(Candidate candidate) {
        var texts = new ArrayList<String>();
        texts.add(candidate.summary());
        texts.add(candidate.explanation());
        for (var problem : candidate.problems()) {
            texts.add(problem.summary());
            texts.add(problem.explanation());
            if (problem.nextStep() != null) texts.add(problem.nextStep());
            texts.add(problem.basis().explanation());
        }
        var example = candidate.example();
        if (example != null) texts.add(example.note());
        var search = candidate.search();
        if (search != null) {
            texts.add(search.lookedFor());
            texts.add(search.boundary());
            if (search.hits() != null) texts.add(search.hits());
        }
        var inapplicability = candidate.inapplicability();
        if (inapplicability != null) {
            texts.add(inapplicability.prerequisite());
            texts.add(inapplicability.ruledOutBy());
        }
        for (var question : candidate.openQuestions()) {
            texts.add(question.openQuestion());
            texts.add(question.wouldSettleIt());
        }
        return texts;
    }

    static boolean claimed(String text) {
        return Arrays.stream(CLAUSES.split(text))
                .anyMatch(clause -> !DISCLAIMS.matcher(clause).find()
                        && PATTERNS.stream()
                                .anyMatch(pattern -> pattern.matcher(clause).find()));
    }
}
