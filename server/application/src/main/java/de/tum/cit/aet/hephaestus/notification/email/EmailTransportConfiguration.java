package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
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
    static class SmtpConfiguration {

        // Boot can test the connection while constructing its validator, before singleton callbacks.
        @Bean
        static BeanPostProcessor authenticatedSmtpRequiresTls() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof JavaMailSenderImpl sender) {
                        validateEncryption(sender);
                    }
                    return bean;
                }
            };
        }

        private static void validateEncryption(JavaMailSenderImpl sender) {
            var session = sender.getSession();
            String protocol = sender.getProtocol();
            if (protocol == null) {
                protocol = session.getProperty("mail.transport.protocol");
            }
            if (protocol == null) {
                protocol = JavaMailSenderImpl.DEFAULT_PROTOCOL;
            }
            String prefix = "mail." + protocol + ".";
            // Angus also attempts AUTH with supplied credentials when the auth property is false.
            boolean authenticated = Boolean.parseBoolean(session.getProperty(prefix + "auth"))
                    || (StringUtils.hasLength(sender.getUsername()) && sender.getPassword() != null);
            boolean implicitTls =
                    "smtps".equals(protocol) || Boolean.parseBoolean(session.getProperty(prefix + "ssl.enable"));
            boolean requiredStartTls = Boolean.parseBoolean(session.getProperty(prefix + "starttls.enable"))
                    && Boolean.parseBoolean(session.getProperty(prefix + "starttls.required"));
            Assert.state(
                    !authenticated || implicitTls || requiredStartTls,
                    "Authenticated SMTP requires implicit TLS or STARTTLS both enabled and required");
        }
    }

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
