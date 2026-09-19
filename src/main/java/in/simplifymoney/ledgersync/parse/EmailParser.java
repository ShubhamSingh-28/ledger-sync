package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailParser implements MessageParser {

    private static final Pattern DATE_HEADER = Pattern.compile(
            "Date: \\w+, (\\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} [+\\-]\\d{4})");

    private static final Pattern ACCOUNT = Pattern.compile(
            "account ending (\\d{4})");

    private static final Pattern DIR = Pattern.compile(
            "has been (debited|credited)");

    private static final Pattern MERCHANT = Pattern.compile(
            "Merchant / Remarks: (.+)");

    private static final DateTimeFormatter EMAIL_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher dateMatcher = DATE_HEADER.matcher(body);
        if (!dateMatcher.find()) return Optional.empty();

        Matcher acctMatcher = ACCOUNT.matcher(body);
        if (!acctMatcher.find()) return Optional.empty();

        Matcher dirMatcher = DIR.matcher(body);
        if (!dirMatcher.find()) return Optional.empty();

        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        String merchantStr = "";
        Matcher merchantMatcher = MERCHANT.matcher(body);
        if (merchantMatcher.find()) {
            merchantStr = merchantMatcher.group(1).trim();
        }

        OffsetDateTime emailDate = OffsetDateTime.parse(
                dateMatcher.group(1), EMAIL_DATE);
        OffsetDateTime occurredAt = emailDate.withOffsetSameInstant(Dates.IST);

        Direction direction = "debited".equals(dirMatcher.group(1))
                ? Direction.DEBIT : Direction.CREDIT;

        return Optional.of(new ParsedTxn(
                acctMatcher.group(1), occurredAt, direction, amount,
                merchantStr, null, m.messageId()));
    }
}
