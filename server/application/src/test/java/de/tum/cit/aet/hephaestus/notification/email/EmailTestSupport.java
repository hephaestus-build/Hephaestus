package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** The production template engine, assembled by hand so the unit tier renders real templates. */
final class EmailTestSupport {

    static final String WEBAPP_URL = "https://hephaestus.example";

    private EmailTestSupport() {}

    static EmailRenderer renderer() {
        ClassLoaderTemplateResolver html = new ClassLoaderTemplateResolver();
        html.setPrefix("templates/");
        html.setSuffix(".html");
        html.setTemplateMode(TemplateMode.HTML);
        html.setCharacterEncoding("UTF-8");
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(new EmailTransportConfiguration().emailTextTemplateResolver());
        engine.addTemplateResolver(html);
        engine.setTemplateEngineMessageSource(messages);
        return new EmailRenderer(
                engine, messages, new ApplicationProperties(null, new ApplicationProperties.Webapp(WEBAPP_URL)));
    }
}
