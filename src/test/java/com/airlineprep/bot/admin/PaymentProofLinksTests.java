package com.airlineprep.bot.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProofLinksTests {
    @Test void extractsOnlyUniqueHttpLinksInOriginalOrder() {
        assertThat(PaymentProofLinks.extract("before https://example.test/receipt/123, then http://example.test/help. "
                + "again https://example.test/receipt/123 javascript:alert(1) data:text/html,evil"))
                .containsExactly("https://example.test/receipt/123", "http://example.test/help");
    }

    @Test void rejectsMalformedSchemesHostsUserInfoAndBackslashes() {
        assertThat(PaymentProofLinks.extract("javascript:alert(1) data:text/html,evil file:///tmp/a "
                + "https://user@example.test/secret https://example.test\\@evil.test/"))
                .isEmpty();
    }

    @Test void referenceOnlyProofHasNoLinks() {
        assertThat(PaymentProofLinks.extract("TEST-REF-001")).isEmpty();
    }
}
