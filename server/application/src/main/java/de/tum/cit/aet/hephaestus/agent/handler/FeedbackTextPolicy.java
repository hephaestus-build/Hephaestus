package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;

/**
 * The text policy for model-written feedback on every destination. Hephaestus places line notes and adds its own
 * links, so a composed text carries no link that a cited quote of the work did not supply, no provider mention, no raw
 * HTML, no image and no hidden or bidirectional control character. Code spans and code blocks render as text, so they
 * may quote code; Slack syntax is refused in them too. Delivery still sanitizes each copy for its provider
 * ({@code ScmFeedbackFormatter}); this policy refuses the text before anything is prepared.
 */
final class FeedbackTextPolicy {
    enum Refusal {
        HIDDEN_CHARACTER,
        MENTION,
        HTML,
        IMAGE,
        LINK
    }

    private static final Parser MARKDOWN = Parser.builder().build();

    // Slack user, group, channel and broadcast syntax. No feedback needs to quote it, even in code.
    private static final Pattern SLACK_SYNTAX = Pattern.compile("<[@#!][^<>\\s]*>");

    // A GitHub or GitLab user, team or group mention. An e-mail address is not one.
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@[A-Za-z0-9]");

    // GitHub and GitLab turn a bare web address into a link, also right after emphasis marks such as `_` or `*`.
    private static final Pattern BARE_LINK =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?:https?://|ftp://|www\\.)[^\\s<>\"'`\\\\]+");

    // A zero-width joiner composes emoji and hides nothing on its own.
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private final List<AdmittedResultSnapshot> admitted;
    private @Nullable Set<String> supplied;

    FeedbackTextPolicy(List<AdmittedResultSnapshot> admitted) {
        this.admitted = admitted;
    }

    /** The first rule that a model-written text of the piece breaks, or null when the piece may be prepared. */
    @Nullable
    Refusal refusal(ComposedFeedback piece) {
        var texts = new ArrayList<String>();
        String summary = piece.summary();
        if (summary != null) texts.add(summary);
        piece.notes().forEach(note -> texts.add(note.body()));
        var page = piece.page();
        if (page != null) texts.addAll(List.of(page.title(), page.body(), page.nextStep()));
        return texts.stream()
                .map(this::refusal)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** The first rule that the text breaks, or null when it may be prepared. */
    @Nullable
    Refusal refusal(String text) {
        if (text.codePoints().anyMatch(FeedbackTextPolicy::hidden)) return Refusal.HIDDEN_CHARACTER;
        if (SLACK_SYNTAX.matcher(text).find()) return Refusal.MENTION;
        var read = Reading.of(text);
        if (read.html) return Refusal.HTML;
        if (read.image) return Refusal.IMAGE;
        if (MENTION.matcher(read.prose).find()) return Refusal.MENTION;
        if (!read.links.isEmpty() && !supplied().containsAll(read.links)) return Refusal.LINK;
        return null;
    }

    private static boolean hidden(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.FORMAT -> codePoint != ZERO_WIDTH_JOINER;
            case Character.CONTROL -> codePoint != '\n' && codePoint != '\r' && codePoint != '\t';
            case Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }

    /** The links in the verified quotes that the admitted results and their problems cite. */
    private Set<String> supplied() {
        var known = supplied;
        if (known == null) {
            known = admitted.stream()
                    .flatMap(row -> Stream.concat(
                            row.candidate().evidence().citations().stream(),
                            row.problems().stream().flatMap(problem -> problem.candidate().citations().stream())))
                    .flatMap(citation -> Reading.of(citation.quote()).links.stream())
                    .collect(Collectors.toUnmodifiableSet());
            supplied = known;
        }
        return known;
    }

    /** A bare link ends before trailing punctuation, and before a closing parenthesis that it does not open. */
    private static String trimmed(String link) {
        int end = link.length();
        while (end > 0) {
            char last = link.charAt(end - 1);
            if (".,:;!?*_~]".indexOf(last) >= 0 || (last == ')' && count(link, end, ')') > count(link, end, '(')))
                end--;
            else break;
        }
        return link.substring(0, end);
    }

    private static long count(String text, int end, char wanted) {
        return text.substring(0, end).chars().filter(c -> c == wanted).count();
    }

    /** What a text renders outside code: its words, the links it makes, and whether it carries HTML or an image. */
    private static final class Reading extends AbstractVisitor {
        private final StringBuilder prose = new StringBuilder();
        private final Set<String> links = new HashSet<>();
        private boolean html;
        private boolean image;

        static Reading of(String text) {
            var read = new Reading();
            MARKDOWN.parse(text).accept(read);
            var bare = BARE_LINK.matcher(read.prose);
            while (bare.find()) read.links.add(trimmed(bare.group()));
            return read;
        }

        @Override
        public void visit(Text text) {
            prose.append(text.getLiteral());
        }

        @Override
        public void visit(Code code) {
            prose.append(' ');
        }

        @Override
        public void visit(SoftLineBreak lineBreak) {
            prose.append('\n');
        }

        @Override
        public void visit(HardLineBreak lineBreak) {
            prose.append('\n');
        }

        @Override
        public void visit(Paragraph paragraph) {
            visitChildren(paragraph);
            prose.append('\n');
        }

        @Override
        public void visit(Heading heading) {
            visitChildren(heading);
            prose.append('\n');
        }

        @Override
        public void visit(HtmlInline markup) {
            html = true;
        }

        @Override
        public void visit(HtmlBlock markup) {
            html = true;
        }

        @Override
        public void visit(Image picture) {
            image = true;
        }

        @Override
        public void visit(Link link) {
            links.add(link.getDestination());
            visitChildren(link);
        }
    }
}
