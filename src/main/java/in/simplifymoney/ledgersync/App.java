package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");
    private static final Path DISCREPANCY_FILE = Path.of("data", "discrepancies.json");
    private static final String MONGO_URI = "mongodb://localhost:27017";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus> | report <dir> | backfill | check");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    IngestService ingest = new IngestService(new Parsers(), store);
                    var stats = ingest.ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());

                    var discrepancies = ingest.discrepancies();
                    if (!discrepancies.isEmpty()) {
                        var doc = Reports.reconciliation(discrepancies);
                        Files.writeString(DISCREPANCY_FILE, Json.writePretty(doc));
                        System.out.println("discrepancies found: " + discrepancies.size());
                    }
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));

                    List<IngestService.Discrepancy> discrepancies = List.of();
                    if (Files.exists(DISCREPANCY_FILE)) {
                        discrepancies = loadDiscrepancies();
                    }
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(discrepancies)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                try (SqlLedgerStore sql = new SqlLedgerStore(DB);
                     MongoDocumentStore mongo = new MongoDocumentStore(MONGO_URI)) {
                    var result = new Backfill(sql, mongo).run();
                    System.out.println("backfill: read=" + result.read()
                            + " written=" + result.written()
                            + " skipped=" + result.skipped());
                    System.out.println("mongo docs: " + mongo.count());
                }
            }
            case "check" -> {
                try (SqlLedgerStore sql = new SqlLedgerStore(DB);
                     MongoDocumentStore mongo = new MongoDocumentStore(MONGO_URI)) {
                    var divergences = new ConsistencyChecker(sql, mongo).check();
                    if (divergences.isEmpty()) {
                        System.out.println("CONSISTENT — stores agree");
                    } else {
                        System.out.println("DIVERGENCES FOUND: " + divergences.size());
                        for (var d : divergences) {
                            System.out.println("  " + d.what());
                            System.out.println("    sql:  " + d.inSql());
                            System.out.println("    docs: " + d.inDocuments());
                        }
                    }
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<IngestService.Discrepancy> loadDiscrepancies() throws Exception {
        var doc = Json.parseObject(Files.readString(DISCREPANCY_FILE));
        var list = (List<Object>) doc.get("discrepancies");
        List<IngestService.Discrepancy> out = new java.util.ArrayList<>();
        for (Object o : list) {
            var m = (java.util.Map<String, Object>) o;
            out.add(new IngestService.Discrepancy(
                    (String) m.get("account_last4"),
                    (String) m.get("occurred_at"),
                    (String) m.get("amount"),
                    (String) m.get("note")));
        }
        return out;
    }
}
