package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

/**
 * Boot treats an empty mail host as configured. Gate its auto-configuration so Compose's empty
 * placeholders do not enable SMTP, while Boot still owns sender construction and SSL bundles.
 */
@Configuration
public class EmailTransportConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnServerRole
    @ConditionalOnExpression("!environment.getProperty('spring.mail.host', '').isBlank()")
    @Import(MailSenderAutoConfiguration.class)
    static class SmtpConfiguration {}

    /** Boot adds this TEXT resolver alongside its default HTML resolver on the shared engine. */
    @Bean
    ITemplateResolver emailTextTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".txt");
        resolver.setResolvablePatterns(java.util.Set.of("email/text/*"));
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCheckExistence(true);
        resolver.setOrder(1);
        return resolver;
    }
}
