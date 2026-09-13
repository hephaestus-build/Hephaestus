package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/** Renders a subject and text/HTML alternatives from one model, using the shared Thymeleaf engine. */
@Component
public class EmailRenderer {

    public static final Locale LOCALE = Locale.ENGLISH;

    private final ITemplateEngine templateEngine;
    private final MessageSource messages;
    private final ApplicationProperties applicationProperties;

    public EmailRenderer(
            ITemplateEngine templateEngine, MessageSource messages, ApplicationProperties applicationProperties) {
        this.templateEngine = templateEngine;
        this.messages = messages;
        this.applicationProperties = applicationProperties;
    }

    public RenderedEmail render(EmailKind kind, Map<String, Object> model) {
        Map<String, Object> variables = new HashMap<>(model);
        variables.put("webappUrl", applicationProperties.webapp().url());
        Context context = new Context(LOCALE, variables);
        String subject = messages.getMessage("email." + kind.templateName() + ".subject", null, LOCALE);
        String text = templateEngine.process("email/text/" + kind.templateName(), context);
        String html = templateEngine.process("email/html/" + kind.templateName(), context);
        return new RenderedEmail(subject, text, html);
    }
}
