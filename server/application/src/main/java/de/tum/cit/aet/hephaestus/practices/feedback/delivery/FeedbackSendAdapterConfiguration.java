package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The adapters of a feedback's sends to its work: each kind of send is its own action kind. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
class FeedbackSendAdapterConfiguration {

    @Bean
    FeedbackSendAdapter deliverFeedbackAdapter(FeedbackSendService sender, FeedbackSendTransactions transactions) {
        return new FeedbackSendAdapter(ActionKind.DELIVER_FEEDBACK, sender, transactions);
    }

    @Bean
    FeedbackSendAdapter editFeedbackAdapter(FeedbackSendService sender, FeedbackSendTransactions transactions) {
        return new FeedbackSendAdapter(ActionKind.EDIT_FEEDBACK, sender, transactions);
    }

    @Bean
    FeedbackSendAdapter retractFeedbackAdapter(FeedbackSendService sender, FeedbackSendTransactions transactions) {
        return new FeedbackSendAdapter(ActionKind.RETRACT_FEEDBACK, sender, transactions);
    }
}
