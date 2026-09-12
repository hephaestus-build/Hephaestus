package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Captures MIME and simulates transport failures; {@link EmailSmtpIntegrationTest} covers real SMTP. */
public class CapturingJavaMailSender extends JavaMailSenderImpl {

    private final List<MimeMessage> sent = new ArrayList<>();
    private @Nullable MailException failure;

    public List<MimeMessage> sent() {
        return sent;
    }

    /** Every subsequent send throws {@code failure}; {@code null} restores delivery. */
    public void failWith(@Nullable MailException failure) {
        this.failure = failure;
    }

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object @Nullable [] originalMessages) throws MailException {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("blocking SMTP must not hold a database transaction")
                .isFalse();
        if (failure != null) {
            throw failure;
        }
        for (MimeMessage message : mimeMessages) {
            try {
                // Match JavaMailSenderImpl's preservation of an explicit Message-ID across saveChanges().
                String messageId = message.getMessageID();
                message.saveChanges();
                if (messageId != null) {
                    message.setHeader("Message-ID", messageId);
                }
            } catch (MessagingException e) {
                throw new MailPreparationException(e);
            }
            sent.add(message);
        }
    }
}
