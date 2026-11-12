package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A structural repair can retain a real approval only while its visible words still match. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackRepairApproval {
    private static final Parser MARKDOWN = Parser.builder().build();
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackDecisionRepository decisions;
    private final FeedbackContentDigest digests;

    public boolean approved(Feedback row) {
        var inherited = row.getComposition().path("approvalFrom");
        if (!inherited.isString()) return false;
        UUID priorId;
        try {
            priorId = UUID.fromString(inherited.asString());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        var prior =
                feedback.findByIdAndWorkspaceId(priorId, row.getWorkspaceId()).orElse(null);
        if (prior == null
                || prior.getDeveloperId() != row.getDeveloperId()
                || !Objects.equals(prior.getItemId(), row.getItemId())
                || prior.getDestination() != row.getDestination()
                || !words(prior).equals(words(row))) return false;
        return decisions
                .findLatestApproval(row.getWorkspaceId(), priorId)
                .filter(decision -> decision.getAction() == FeedbackDecisionAction.APPROVE
                        && Objects.equals(decision.getContentDigest(), digests.current(row.getWorkspaceId(), priorId)))
                .isPresent();
    }

    public boolean sameWords(Feedback before, Feedback after) {
        return words(before).equals(words(after));
    }

    private List<ApprovedContent> words(Feedback row) {
        return copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(row.getWorkspaceId(), row.getId()).stream()
                .map(copy -> visible(copy.getKind() == FeedbackCopyKind.SUMMARY ? row.getBody() : copy.getBody()))
                .toList();
    }

    private record Destination(
            boolean image, String uri, @Nullable String title) {}

    private record CodeContent(
            boolean block, String literal, @Nullable String info) {}

    private record ApprovedContent(String words, List<Destination> destinations, List<CodeContent> code) {}

    private static ApprovedContent visible(@Nullable String markdown) {
        if (markdown == null) return new ApprovedContent("", List.of(), List.of());
        var words = new StringBuilder();
        var destinations = new ArrayList<Destination>();
        var codeContent = new ArrayList<CodeContent>();
        MARKDOWN.parse(markdown).accept(new AbstractVisitor() {
            private void add(String literal) {
                words.append(literal).append(' ');
            }

            @Override
            public void visit(Text text) {
                add(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                codeContent.add(new CodeContent(false, code.getLiteral(), null));
                add(code.getLiteral());
            }

            @Override
            public void visit(FencedCodeBlock code) {
                codeContent.add(new CodeContent(true, code.getLiteral(), code.getInfo()));
                add(code.getLiteral());
            }

            @Override
            public void visit(IndentedCodeBlock code) {
                codeContent.add(new CodeContent(true, code.getLiteral(), null));
                add(code.getLiteral());
            }

            @Override
            public void visit(HtmlInline html) {
                add(html.getLiteral());
            }

            @Override
            public void visit(HtmlBlock html) {
                add(html.getLiteral());
            }

            @Override
            public void visit(Link link) {
                destinations.add(new Destination(false, link.getDestination(), link.getTitle()));
                visitChildren(link);
            }

            @Override
            public void visit(Image image) {
                destinations.add(new Destination(true, image.getDestination(), image.getTitle()));
                visitChildren(image);
            }
        });
        return new ApprovedContent(
                words.toString().replaceAll("\\s+", " ").strip(), List.copyOf(destinations), List.copyOf(codeContent));
    }
}
