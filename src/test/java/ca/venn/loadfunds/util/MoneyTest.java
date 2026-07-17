package ca.venn.loadfunds.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void convertsDollarAmountToCents() {
        assertThat(Money.toCents("$123.45")).isEqualTo(12_345L);
    }

    @Test
    void convertsZeroDollarAmountToCents() {
        assertThat(Money.toCents("$0.99")).isEqualTo(99L);
    }

    @Test
    void convertsWholeDollarAmountToCents() {
        assertThat(Money.toCents("$10.00")).isEqualTo(1_000L);
    }

    @Test
    void rejectsAmountsThatDoNotMatchExpectedFormat() {
        assertInvalid("123.45");
        assertInvalid("$123");
        assertInvalid("$123.4");
        assertInvalid("$123.456");
        assertInvalid("$0123.45");
        assertInvalid("$-1.00");
    }

    @Test
    void wrapsConversionFailures() {
        assertThatThrownBy(() -> Money.toCents("$92233720368547758.08"))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Error while converting load amount to cents")
            .hasCauseInstanceOf(ArithmeticException.class);
    }

    private static void assertInvalid(String raw) {
        assertThatThrownBy(() -> Money.toCents(raw))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("load_amount must be a positive monetary amount in the format $123.45");
    }
}
