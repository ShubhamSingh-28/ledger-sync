package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rows = source.all();
        long read = rows.size();
        long written = 0;
        long skipped = 0;

        Set<String> seen = new HashSet<>();
        for (NormalizedTxn txn : rows) {
            String key = txn.accountLast4() + "|" + txn.occurredAt() + "|"
                    + txn.amount().toPlainString() + "|" + txn.direction().name();
            if (!seen.add(key)) {
                skipped++;
                continue;
            }
            target.save(txn);
            written++;
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
