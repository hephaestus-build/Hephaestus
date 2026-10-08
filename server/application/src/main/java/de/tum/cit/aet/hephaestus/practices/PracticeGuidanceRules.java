package de.tum.cit.aet.hephaestus.practices;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * What a practice visual and a practice guide may contain. The webapp draws an SVG inline, where markup runs with the
 * page's privileges, so this is an allowlist: only shapes and text pass, and anything else is refused with words an
 * admin can act on. A guide is Markdown that shows only its own figures and links only to web pages.
 */
public final class PracticeGuidanceRules {

    public static final int MAX_SVG_BYTES = 64 * 1024;
    public static final int MAX_ALT_LENGTH = 300;
    public static final int MAX_GUIDE_LENGTH = 12_000;
    public static final int MAX_FIGURES = 4;

    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";

    private static final List<String> ELEMENTS = List.of(
            "svg",
            "g",
            "path",
            "rect",
            "circle",
            "ellipse",
            "line",
            "polyline",
            "polygon",
            "text",
            "tspan",
            "title",
            "desc");

    private static final Set<String> ATTRIBUTES = Set.of(
            "viewBox",
            "width",
            "height",
            "preserveAspectRatio",
            "version",
            "x",
            "y",
            "x1",
            "y1",
            "x2",
            "y2",
            "cx",
            "cy",
            "r",
            "rx",
            "ry",
            "d",
            "points",
            "dx",
            "dy",
            "transform",
            "class",
            "fill",
            "fill-rule",
            "fill-opacity",
            "clip-rule",
            "stroke",
            "stroke-width",
            "stroke-linecap",
            "stroke-linejoin",
            "stroke-miterlimit",
            "stroke-dasharray",
            "stroke-dashoffset",
            "stroke-opacity",
            "opacity",
            "font-size",
            "font-weight",
            "font-style",
            "text-anchor",
            "dominant-baseline",
            "letter-spacing",
            "vector-effect",
            "role",
            "aria-hidden",
            "focusable");

    /**
     * Numbers, colors, transforms and class names. A browser reads presentation attributes as CSS, so a backslash
     * escape could spell {@code url(} past the check below; no escape, quote, colon or semicolon is needed to draw.
     */
    private static final Pattern PLAIN_VALUE = Pattern.compile("[\\w\\s#.,%+()/-]*");

    private static final Pattern FIGURE_NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern FIGURE_SOURCE = Pattern.compile("figures/(" + FIGURE_NAME.pattern() + ")\\.svg");
    private static final Pattern WEB_LINK = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private static final Parser MARKDOWN = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private PracticeGuidanceRules() {}

    public static void validate(PracticeVisual visual) {
        validateSvg("The visual", visual.svg());
        if (visual.alt().isEmpty()) {
            throw new IllegalArgumentException(
                    "Describe what the visual shows, for people who cannot see it. Add a description.");
        }
        if (visual.alt().length() > MAX_ALT_LENGTH) {
            throw new IllegalArgumentException(
                    "Shorten the visual's description to " + MAX_ALT_LENGTH + " characters or fewer.");
        }
    }

    public static void validate(PracticeGuide guide) {
        if (guide.markdown().isBlank()) {
            throw new IllegalArgumentException("Write the guide, or remove it.");
        }
        if (guide.markdown().length() > MAX_GUIDE_LENGTH) {
            throw new IllegalArgumentException("Shorten the guide to " + MAX_GUIDE_LENGTH + " characters or fewer.");
        }
        if (guide.figures().size() > MAX_FIGURES) {
            throw new IllegalArgumentException("A guide shows at most " + MAX_FIGURES + " figures. Remove one.");
        }
        Markdown markdown = Markdown.parse(guide.markdown());
        if (markdown.html) {
            throw new IllegalArgumentException("The guide uses HTML. Write it in Markdown only.");
        }
        for (String destination : markdown.links) {
            if (!WEB_LINK.matcher(destination).matches()) {
                throw new IllegalArgumentException("The guide links to “" + destination
                        + "”. Link only to web pages, with addresses that start with https:// or http://.");
            }
        }
        Set<String> shown = new HashSet<>();
        for (Image image : markdown.images) {
            Matcher figure = FIGURE_SOURCE.matcher(image.getDestination());
            if (!figure.matches()) {
                throw new IllegalArgumentException("The guide shows the image “" + image.getDestination()
                        + "”. A guide shows only its own figures. Add the picture as a figure, "
                        + "then show it with ![description](figures/name.svg).");
            }
            String name = figure.group(1);
            if (!guide.figures().containsKey(name)) {
                throw new IllegalArgumentException(
                        "The guide shows the figure “" + name + "”, but it has no such figure. Add it.");
            }
            if (alt(image).isBlank()) {
                throw new IllegalArgumentException("Describe the figure “" + name
                        + "” for people who cannot see it. Write the description between the brackets.");
            }
            shown.add(name);
        }
        for (Map.Entry<String, String> figure : guide.figures().entrySet()) {
            if (!FIGURE_NAME.matcher(figure.getKey()).matches()) {
                throw new IllegalArgumentException("Name the figure “" + figure.getKey()
                        + "” with lowercase letters, digits and single hyphens, for example split-order.");
            }
            if (!shown.contains(figure.getKey())) {
                throw new IllegalArgumentException(
                        "The guide never shows the figure “" + figure.getKey() + "”. Show it, or remove it.");
            }
            validateSvg("The figure “" + figure.getKey() + "”", figure.getValue());
        }
    }

    /** The names of the figures the Markdown shows. */
    public static Set<String> figureNames(String markdown) {
        Set<String> names = new TreeSet<>();
        for (Image image : Markdown.parse(markdown).images) {
            Matcher figure = FIGURE_SOURCE.matcher(image.getDestination());
            if (figure.matches()) {
                names.add(figure.group(1));
            }
        }
        return names;
    }

    /**
     * The guide for a reader without pictures: every image becomes "(Figure: description)", so a text-only reader
     * such as Heph learns what each figure shows.
     */
    public static String describeFigures(String markdown) {
        StringBuilder described = new StringBuilder(markdown);
        for (Image image : Markdown.parse(markdown).images.reversed()) {
            List<SourceSpan> spans = image.getSourceSpans();
            SourceSpan last = spans.getLast();
            described.replace(
                    spans.getFirst().getInputIndex(),
                    last.getInputIndex() + last.getLength(),
                    "(Figure: " + alt(image) + ")");
        }
        return described.toString();
    }

    /** The images, link targets and raw HTML of one Markdown text, as CommonMark parses it. */
    private static final class Markdown extends AbstractVisitor {
        private final List<Image> images = new ArrayList<>();
        private final List<String> links = new ArrayList<>();
        private boolean html;

        static Markdown parse(String markdown) {
            Markdown parsed = new Markdown();
            MARKDOWN.parse(markdown).accept(parsed);
            return parsed;
        }

        @Override
        public void visit(Image image) {
            images.add(image);
            visitChildren(image);
        }

        @Override
        public void visit(Link link) {
            links.add(link.getDestination());
            visitChildren(link);
        }

        @Override
        public void visit(HtmlBlock block) {
            html = true;
        }

        @Override
        public void visit(HtmlInline inline) {
            html = true;
        }
    }

    private static String alt(Image image) {
        StringBuilder alt = new StringBuilder();
        image.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                alt.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                alt.append(code.getLiteral());
            }
        });
        return alt.toString().strip();
    }

    private static void validateSvg(String subject, String svg) {
        if (svg.getBytes(StandardCharsets.UTF_8).length > MAX_SVG_BYTES) {
            throw new IllegalArgumentException(
                    subject + " is larger than " + MAX_SVG_BYTES / 1024 + " KB. Simplify the drawing.");
        }
        Document document = parse(subject, svg);
        Element root = document.getDocumentElement();
        if (!SVG_NAMESPACE.equals(root.getNamespaceURI()) || !"svg".equals(root.getLocalName())) {
            throw new IllegalArgumentException(subject
                    + " is not an SVG. Start it with <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"…\">.");
        }
        if (!root.hasAttribute("viewBox")) {
            throw new IllegalArgumentException(
                    subject + " has no viewBox. Add one, for example viewBox=\"0 0 640 320\".");
        }
        for (Node child = document.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                checkNonElement(subject, child);
            }
        }
        check(subject, root);
    }

    private static void check(String subject, Element element) {
        if (!SVG_NAMESPACE.equals(element.getNamespaceURI()) || !ELEMENTS.contains(element.getLocalName())) {
            throw new IllegalArgumentException(subject + " uses <" + element.getTagName()
                    + ">. A picture can only draw shapes and text: " + String.join(", ", ELEMENTS) + ".");
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr attribute = (Attr) attributes.item(index);
            String namespace = attribute.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)) {
                // A namespace declaration: the parser has already placed every element by it.
                continue;
            }
            boolean allowed = namespace == null
                    ? ATTRIBUTES.contains(attribute.getLocalName())
                    : XMLConstants.XML_NS_URI.equals(namespace) && "space".equals(attribute.getLocalName());
            if (!allowed) {
                throw new IllegalArgumentException(subject + " uses the attribute “" + attribute.getName()
                        + "”. Remove it. Color shapes with the pv-* classes, and use no links, ids or styles.");
            }
            String value = attribute.getValue();
            if (value.toLowerCase(Locale.ROOT).contains("url(")) {
                throw new IllegalArgumentException(subject + " refers to something outside itself in “"
                        + attribute.getName() + "”. Use plain numbers, colors or the pv-* classes.");
            }
            if (!PLAIN_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException(subject + " uses a character Hephaestus does not accept in “"
                        + attribute.getName() + "”. Use only letters, digits, spaces and # . , % + ( ) / - _.");
            }
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested) {
                check(subject, nested);
            } else {
                checkNonElement(subject, child);
            }
        }
    }

    /**
     * Text and comments pass; CDATA and processing instructions do not. An HTML parser ends a comment that starts
     * with ">" or "->" at once, so markup inside it would become elements once the picture is drawn inline: a comment
     * passes only without angle brackets, where both parsers read it the same.
     */
    private static void checkNonElement(String subject, Node node) {
        boolean plain = node.getNodeType() == Node.TEXT_NODE
                || (node.getNodeType() == Node.COMMENT_NODE
                        && !node.getNodeValue().contains("<")
                        && !node.getNodeValue().contains(">"));
        if (!plain) {
            throw new IllegalArgumentException(
                    subject + " contains markup a picture cannot use. Keep only shapes, text and plain comments.");
        }
    }

    private static Document parse(String subject, String svg) {
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            // Silent: a malformed file is answered once, below, not also written to stderr.
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new InputSource(new StringReader(svg)));
        } catch (SAXException | IOException exception) {
            throw new IllegalArgumentException(
                    subject + " is not valid SVG markup. Export it again from your drawing tool.", exception);
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("the XML parser cannot be configured safely", exception);
        }
    }

    /**
     * The OWASP XXE configuration for the JDK parser: no DOCTYPE, so no entity can reach a file or the network, and
     * no XInclude. Secure processing also caps what a document can make the parser do.
     */
    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        return factory;
    }
}
