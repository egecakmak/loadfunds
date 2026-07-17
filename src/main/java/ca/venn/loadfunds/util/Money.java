package ca.venn.loadfunds.util;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@NoArgsConstructor
@Slf4j
public final class Money {

    private static final Pattern PATTERN =
        Pattern.compile("^\\$(0|[1-9]\\d*)\\.(\\d{2})$");

    public static long toCents(String raw) {

        Matcher matcher = PATTERN.matcher(raw);

        if (!matcher.matches()) {
            log.atDebug()
               .addKeyValue("loadAmount", raw)
               .log("Invalid money format");
            throw new IllegalArgumentException(
                "load_amount must be a positive monetary amount in the format $123.45"
            );
        }

        try {
            long cents = new BigDecimal(matcher.group(1) + "." + matcher.group(2))
                .movePointRight(2)
                .longValueExact();
            log.atDebug()
               .addKeyValue("loadAmount", raw)
               .addKeyValue("amountCents", cents)
               .log("Converted money amount");
            return cents;
        } catch (Exception exception) {
            log.atDebug()
               .addKeyValue("loadAmount", raw)
               .addKeyValue("exceptionType", exception.getClass().getName())
               .setCause(exception)
               .log("Money conversion failed");
            throw new RuntimeException("Error while converting load amount to cents", exception);
        }
    }

}
