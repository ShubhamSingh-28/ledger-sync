package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import in.simplifymoney.ledgersync.parse.Amounts;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncidentTest {

    private static final String WATER_CAN_SMS =
            "Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 to UPI/WATER CAN. "
                    + "Avl Bal: Rs.92,213.10. Not you? Call 18002586161";

    @Test
    @DisplayName("INC-2026-09-11: Rs.5 water can must not be read as Rs.92,213.10")
    void waterCanAmountIsFiveNotBalance() {
        BigDecimal amount = Amounts.first(WATER_CAN_SMS);
        assertEquals(new BigDecimal("5.00"), amount);
        assertNotEquals(new BigDecimal("92213.10"), amount);
    }

    @Test
    @DisplayName("amounts without decimals are extracted correctly")
    void amountsWithoutDecimals() {
        assertEquals(new BigDecimal("5.00"),
                Amounts.first("Rs.5 debited from a/c **4821"));
        assertEquals(new BigDecimal("8000.00"),
                Amounts.first("Rs 8,000 debited from a/c **4821 on 05-07-26"));
        assertEquals(new BigDecimal("18000.00"),
                Amounts.first("INR 18,000 on 01/07/2026"));
        assertEquals(new BigDecimal("20.00"),
                Amounts.first("Rs 20 debited from a/c **4821"));
        assertEquals(new BigDecimal("90.00"),
                Amounts.first("Rs 90 debited from a/c **4821"));
        assertEquals(new BigDecimal("25.00"),
                Amounts.first("Rs.25 on 04/07/2026"));
    }

    @Test
    @DisplayName("amounts with one decimal digit are extracted correctly")
    void amountsWithOneDecimal() {
        assertEquals(new BigDecimal("5.50"),
                Amounts.first("Rs.5.5 debited"));
    }

    @Test
    @DisplayName("parser extracts Rs.5 water can correctly end to end")
    void parserExtractsWaterCanCorrectly() {
        RawMessage m = new RawMessage("m-test", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T11:56:00+05:30"), "dev-test",
                WATER_CAN_SMS);
        var txn = new HdfcSmsParser().parse(m).orElseThrow();
        assertEquals(new BigDecimal("5.00"), txn.amount());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals("4821", txn.accountLast4());
        assertEquals("UPI/WATER CAN", txn.merchant());
    }
}
