package com.jonsman.autogamble.payment;

import java.math.BigDecimal;
import java.util.Optional;

/** Future adapters must accept only verified incoming server payment notices, never outgoing echoes. */
@FunctionalInterface
public interface PaymentParser {
    Optional<IncomingPayment> parse(String visibleServerMessage, String localUsername);
    default Optional<IncomingPayment> parse(ReceivedMessage message, String localUsername) {
        return message == null || message.channel() == ReceivedMessage.Channel.PLAYER_CHAT
                ? Optional.empty() : parse(message.text(), localUsername);
    }
    record IncomingPayment(String sender, BigDecimal amount, String receiptId) {
        public IncomingPayment {
            if (sender == null || !sender.matches("[A-Za-z0-9_]{3,16}") || amount == null
                    || amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000000")) > 0
                    || amount.stripTrailingZeros().scale() > 2 || receiptId == null)
                throw new IllegalArgumentException("Invalid incoming receipt");
        }
    }
    static PaymentParser inactive() { return (message, local) -> Optional.empty(); }
}
