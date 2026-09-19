**What broke:** `Amounts.first()` regex required exactly two decimal places (`\.[0-9]{2}`), so "Rs.5" was skipped and the available balance "Rs.92,213.10" was extracted as the transaction amount instead.
**How found:** The app.log showed `amount_extracted=92213.10` for a body starting with "Rs.5 debited" — the first rupee figure without decimals fell through the regex.
**Who was affected:** Every HDFC and ICICI message where the bank wrote the amount without a decimal point (e.g., "Rs.5", "Rs 8,000", "INR 18,000") — these all silently picked up the stated balance as the amount.
**Fix:** Changed the AMOUNT regex from `[0-9,]+\.[0-9]{2}` to `[0-9,]+(?:\.[0-9]{1,2})?`, making the decimal portion optional.
**Why it cannot recur:** Added `IncidentTest.waterCanAmountIsFiveNotBalance` which asserts that "Rs.5 debited…Avl Bal: Rs.92,213.10" extracts 5.00, not 92213.10 — this test fails on the old regex and passes on the fix.
