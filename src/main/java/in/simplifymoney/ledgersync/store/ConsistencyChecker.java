package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        Map<String, NormalizedTxn> sqlByKey = new LinkedHashMap<>();
        for (NormalizedTxn txn : sql.all()) {
            String key = dedupKey(txn);
            sqlByKey.putIfAbsent(key, txn);
        }

        Map<String, NormalizedTxn> docByKey = new LinkedHashMap<>();
        for (NormalizedTxn txn : documents.all()) {
            String key = dedupKey(txn);
            docByKey.put(key, txn);
        }

        for (var entry : sqlByKey.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn sqlTxn = entry.getValue();
            NormalizedTxn docTxn = docByKey.remove(key);

            if (docTxn == null) {
                divergences.add(new Divergence(
                        "missing in documents: " + key,
                        describe(sqlTxn),
                        "<absent>"));
                continue;
            }

            if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
                divergences.add(new Divergence(
                        "amount mismatch: " + key,
                        sqlTxn.amount().toPlainString(),
                        docTxn.amount().toPlainString()));
            }
            if (sqlTxn.category() != docTxn.category()) {
                divergences.add(new Divergence(
                        "category mismatch: " + key,
                        sqlTxn.category().name(),
                        docTxn.category().name()));
            }
            if (!sqlTxn.merchant().equals(docTxn.merchant())) {
                divergences.add(new Divergence(
                        "merchant mismatch: " + key,
                        sqlTxn.merchant(),
                        docTxn.merchant()));
            }
            if (!sqlTxn.sourceMessageIds().equals(docTxn.sourceMessageIds())) {
                divergences.add(new Divergence(
                        "source_message_ids mismatch: " + key,
                        sqlTxn.sourceMessageIds().toString(),
                        docTxn.sourceMessageIds().toString()));
            }
        }

        for (var entry : docByKey.entrySet()) {
            divergences.add(new Divergence(
                    "extra in documents: " + entry.getKey(),
                    "<absent>",
                    describe(entry.getValue())));
        }

        return divergences;
    }

    private static String dedupKey(NormalizedTxn txn) {
        return txn.accountLast4() + "|" + txn.occurredAt() + "|"
                + txn.amount().toPlainString() + "|" + txn.direction().name();
    }

    private static String describe(NormalizedTxn txn) {
        return txn.accountLast4() + " " + txn.direction() + " "
                + txn.amount().toPlainString() + " " + txn.merchant()
                + " at " + txn.occurredAt();
    }

    public record Divergence(String what, String inSql, String inDocuments) {}
}
