package com.airlineprep.bot.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PhoneIdentityTests {
    private final EthiopianPhoneNormalizer normalizer = new EthiopianPhoneNormalizer();
    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "+251912345678", "251912345678", "09 1234 5678", "+251 (91) 234-5678"})
    void normalizesEquivalentMobileFormats(String input) {
        assertThat(normalizer.normalize(input)).isEqualTo("+251912345678");
    }
    @Test void acceptsSevenPrefix() {
        assertThat(normalizer.normalize("0712345678")).isEqualTo("+251712345678");
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "123", "+254912345678", "0812345678", "09123456789", "+2510912345678", "0912abc678", "++251912345678"})
    void rejectsMalformedAndForeignNumbers(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> normalizer.normalize(input));
    }
    @Test void rejectsNull() { assertThatIllegalArgumentException().isThrownBy(() -> normalizer.normalize(null)); }
    @Test void keyedHashIsDeterministicAndNotRawPhone() {
        var identity = new PhoneIdentity("dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=", true);
        assertThat(identity.hash(normalizer.normalize("0912345678")))
            .isEqualTo(identity.hash(normalizer.normalize("+251912345678")))
            .isEqualTo("58c00037a5d53df2352ca0fd6e3588a8a948ae94d0c9813d0b83e721288979cf")
            .hasSize(64).doesNotContain("912345678");
        assertThat(identity.hash(normalizer.normalize("0712345678")))
            .isEqualTo("4556fa102b5a987a681c712421fd4b5f43f2e1574ea71b8524d02421cae95c13");
        var other = new PhoneIdentity("YW5vdGhlci10ZXN0LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=", true);
        assertThat(other.hash("+251912345678")).isNotEqualTo(identity.hash("+251912345678"));
    }
    @Test void rejectsMissingAndWeakKeysOnlyWhenNeeded() {
        assertThatIllegalStateException().isThrownBy(() -> new PhoneIdentity("", true));
        assertThatIllegalStateException().isThrownBy(() -> new PhoneIdentity("change_me", true));
        assertThatIllegalStateException().isThrownBy(() -> new PhoneIdentity("dGVzdA==", true));
        assertThat(new PhoneIdentity("", false).configured()).isFalse();
    }
}
