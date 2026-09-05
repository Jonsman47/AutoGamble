package com.jonsman.autogamble.payment;

import java.math.BigDecimal;

public interface PaymentSender {
    enum Result { SENT, RETRY_LATER, UNCERTAIN }
    Result sendPayment(String username, BigDecimal amount, OutgoingPaymentTracker.Source source);
}
