package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParsersTest {

    private static RawMessage sms(String sender, String body) {
        return new RawMessage("m-test", "sms", sender,
                OffsetDateTime.parse("2026-07-01T10:00:00+05:30"), "dev-test", body);
    }

    private static RawMessage email(String sender, String body) {
        return new RawMessage("m-test", "email", sender,
                OffsetDateTime.parse("2026-07-01T10:00:00+05:30"), "dev-test", body);
    }

    @Test
    @DisplayName("ICICI V2: debit with whole number amount")
    void iciciV2Debit() {
        var m = sms("VM-ICICIB-T",
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; "
                        + "UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30");
        var txn = new IciciSmsParser().parse(m).orElseThrow();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("5.00"), txn.amount());
        assertEquals("UPI/BARBER", txn.merchant());
    }

    @Test
    @DisplayName("ICICI V2: credit with decimal amount")
    void iciciV2Credit() {
        var m = sms("VM-ICICIB-T",
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; "
                        + "INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30");
        var txn = new IciciSmsParser().parse(m).orElseThrow();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("1250.33"), txn.amount());
        assertEquals("INTEREST CREDIT", txn.merchant());
    }

    @Test
    @DisplayName("ICICI promotional message is skipped")
    void iciciPromoSkipped() {
        var m = sms("VM-ICICIB-T",
                "Get a pre-approved Personal Loan of upto Rs.5,00,000 at 10.5% p.a. "
                        + "Click to know more. T&C apply. -ICICI Bank");
        Optional<ParsedTxn> result = new IciciSmsParser().parse(m);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Email parser: HDFC debit email")
    void hdfcEmailDebit() {
        var m = email("alerts@hdfcbank.net",
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been debited with INR 99.99.\n"
                        + "Merchant / Remarks: IRCTC\n"
                        + "Transaction reference: 8085121323\n\n"
                        + "This is a system generated email.");
        var txn = new EmailParser().parse(m).orElseThrow();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("99.99"), txn.amount());
        assertEquals("IRCTC", txn.merchant());
    }

    @Test
    @DisplayName("Email parser: ICICI debit email with Rs. amount")
    void iciciEmailDebit() {
        var m = email("alerts@icicibank.com",
                "Date: Tue, 07 Jul 2026 16:48:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 9075 has been debited with Rs.2,750.\n"
                        + "Merchant / Remarks: APOLLO PHARMACY\n"
                        + "Transaction reference: 7166141125\n\n"
                        + "This is a system generated email.");
        var txn = new EmailParser().parse(m).orElseThrow();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("2750.00"), txn.amount());
        assertEquals("APOLLO PHARMACY", txn.merchant());
    }

    @Test
    @DisplayName("Email with UTC timezone converts to IST")
    void emailUtcToIst() {
        var m = email("alerts@hdfcbank.net",
                "Date: Sat, 18 Jul 2026 18:50:00 +0000\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been debited with INR 412.67.\n"
                        + "Merchant / Remarks: UBER INDIA\n"
                        + "Transaction reference: 4190129089\n\n"
                        + "This is a system generated email.");
        var txn = new EmailParser().parse(m).orElseThrow();
        assertEquals(OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), txn.occurredAt());
    }

    @Test
    @DisplayName("HDFC E-mandate parsed as debit")
    void hdfcEmandate() {
        var m = sms("AD-HDFCBK-S",
                "E-mandate! Rs.649.00 will be deducted from your HDFC Bank A/c XX4821 "
                        + "on 22-07-26 at 06:15 for NETFLIX ENTERTAINMENT. Avl Bal: Rs.46,868.04");
        var txn = new HdfcSmsParser().parse(m).orElseThrow();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("649.00"), txn.amount());
        assertEquals("NETFLIX ENTERTAINMENT", txn.merchant());
    }

    @Test
    @DisplayName("Phishing SMS from VK-ICICIB is not parsed")
    void phishingNotParsed() {
        var m = sms("VK-ICICIB",
                "Dear Customer your ICICI netbanking will be suspended today. "
                        + "Verify PAN immediately at icicibank-secure.co/152459 "
                        + "to avoid debit of Rs.5126.00");
        assertTrue(new IciciSmsParser().parse(m).isEmpty());
        assertTrue(new HdfcSmsParser().parse(m).isEmpty());
    }

    @Test
    @DisplayName("OTP message from HDFC is not parsed")
    void otpNotParsed() {
        var m = sms("AD-HDFCBK-S",
                "268880 is your OTP for txn of Rs.5160.00 on HDFC Bank Card. "
                        + "Valid for 5 min. Do not share with anyone.");
        assertTrue(new HdfcSmsParser().parse(m).isEmpty());
    }

    @Test
    @DisplayName("Balance inquiry is not parsed")
    void balanceInquiryNotParsed() {
        var m = sms("AD-HDFCBK-S",
                "Avl Bal in a/c **9075 is Rs.50,862.08 as on 11-07-26. "
                        + "Download HDFC Bank MobileBanking app.");
        assertTrue(new HdfcSmsParser().parse(m).isEmpty());
    }
}
