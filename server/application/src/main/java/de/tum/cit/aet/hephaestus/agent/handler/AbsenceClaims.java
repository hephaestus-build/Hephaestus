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
 * asks the session once before admission withholds the result.
 *
 * <p>A search of the whole checkout backs a claim only when it names what the claim says is absent, or a model could
 * search for nonsense and claim anything. The server does not run a model's pattern: it reads the words of a plain one,
 * words joined by {@code |}, and any other pattern names nothing. Each claim must hold one of those words, as a word
 * stem when the search ignored case, or as written when it did not, because {@code RETRY} does not find {@code retry}.
 */
final class AbsenceClaims {

    static final List<String> MARKERS = List.of(
            "\\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|"
                    + "absent)\\b[^.]{0,80}\\b(?:in|across|throughout|under) (?:the )?(?:whole |entire |"
                    + "reviewed |pinned |checked-out |rest of the )?(?:repository|repo|codebase|code base|"
                    + "checkout|project|application|source tree|src)\\b(?!\\.\\w|[ -](?:code|manifest|"
                    + "inventory|policy|board|tracker)\\b)",
            "\\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|"
                    + "absent)\\b[^.]{0,80}\\b(?:anywhere|elsewhere) (?:in|across|throughout|"
                    + "under) (?:the )?(?:whole |entire |reviewed |pinned |checked-out |"
                    + "rest of the )?(?:code|app|source|sources)\\b(?![ -](?:file|files|change|diff)\\b)",
            "\\bno other files?\\b(?! (?:in|of) (?:the|this) (?:change|diff|commit|"
                    + "pull request)\\b)[^.;]{0,30}\\b(?:references?|imports?|calls?|uses?|instantiates?|"
                    + "consumes?|wires?|depends? on|handles?|catches?|validates?)\\b|\\b(?:referenced|"
                    + "imported|called|used|instantiated|consumed|handled) (?:by|from|in) no other files?\\b|"
                    + "\\breferences from other files\\b",
            "\\b(?:the|this) (?:whole |entire |reviewed |pinned )?(?:repository|repo|codebase|"
                    + "code base|checkout)\\b(?! (?:method|call|class|interface|layer|bean|query|lookup|"
                    + "result|field|object|instance|step|action|policy|"
                    + "settings?)s?\\b)[^.]{0,40}\\b(?:contains no|has no|holds no|defines no|declares no|"
                    + "registers no|lacks?|never|does not|doesn't)\\b|\\b(?:the|this) (?:project|"
                    + "application)\\b(?![ -](?:code|manifest|inventory|policy|board|"
                    + "tracker)\\b)[^.]{0,40}\\b(?:defines no|declares no|registers no|never)\\b",
            "\\b(?:nothing else|nothing in the (?:project|application|code ?base|repository|repo|"
                    + "checkout)|no (?:other )?(?:code|callers?)(?! path| (?:in|of)\\b)|none of (?:the|"
                    + "its) callers)\\b[^.]{0,60}\\b(?:catch(?:es)?|handles?)\\b(?!\\()");

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
            "an",
            "and",
            "any",
            "are",
            "as",
            "at",
            "base",
            "be",
            "by",
            "call",
            "catch",
            "check",
            "checked",
            "checkout",
            "code",
            "codebase",
            "commit",
            "consume",
            "define",
            "depend",
            "diff",
            "do",
            "does",
            "else",
            "elsewhere",
            "entire",
            "exist",
            "file",
            "find",
            "for",
            "found",
            "from",
            "handle",
            "has",
            "have",
            "hold",
            "import",
            "in",
            "instantiate",
            "into",
            "is",
            "it",
            "its",
            "lack",
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
            "that",
            "the",
            "there",
            "this",
            "throughout",
            "to",
            "tree",
            "under",
            "use",
            "validate",
            "was",
            "were",
            "whole",
            "wire",
            "with",
            "without");

    private static final Pattern PLAIN = Pattern.compile(PLAIN_SEARCH);
    private static final Pattern WORD_BREAK = Pattern.compile("(?<=\\p{Ll})(?=\\p{Lu})|[^\\p{L}\\p{N}]+");
    private static final Pattern TOKEN_BREAK = Pattern.compile("[^\\p{L}\\p{N}_-]+");
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
        var search = candidate.search();
        var inapplicability = candidate.inapplicability();
        String subject = search != null
                ? search.lookedFor()
                : inapplicability != null ? inapplicability.prerequisite() : candidate.summary();
        return searchedFor(subject, texts(candidate), patterns, consulted);
    }

    static boolean searchedFor(String subject, List<String> texts, List<String> patterns, boolean consulted) {
        var terms =
                patterns.stream().flatMap(pattern -> terms(pattern).stream()).toList();
        return (!consulted || named(terms, subject))
                && texts.stream()
                        .flatMap(text -> Arrays.stream(CLAUSES.split(text)))
                        .filter(AbsenceClaims::claimed)
                        .allMatch(clause -> named(terms, content(clause).isEmpty() ? subject : clause));
    }

    private record Term(String word, boolean ignoresCase, Set<String> stems) {}

    private static List<Term> terms(String pattern) {
        if (!PLAIN.matcher(pattern).matches()) return List.of();
        boolean ignoresCase = pattern.startsWith("(?i)");
        return Arrays.stream(pattern.replace("(?i)", "").replace("\\b", "").split("\\|"))
                .filter(word -> !content(word).isEmpty())
                .map(word -> new Term(word, ignoresCase, stems(word)))
                .toList();
    }

    private static boolean named(List<Term> terms, String text) {
        var stems = stems(text);
        var tokens = Set.of(TOKEN_BREAK.splitAsStream(text).distinct().toArray(String[]::new));
        return terms.stream()
                .anyMatch(term -> term.ignoresCase() ? stems.containsAll(term.stems()) : tokens.contains(term.word()));
    }

    private static Set<String> content(String text) {
        return stems(text).stream()
                .filter(stem -> stem.length() >= 3 && !GENERIC.contains(stem))
                .collect(Collectors.toSet());
    }

    private static Set<String> stems(String text) {
        return WORD_BREAK
                .splitAsStream(text)
                .filter(word -> !word.isEmpty())
                .map(AbsenceClaims::stem)
                .collect(Collectors.toSet());
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
