package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import org.bson.Document;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class MongoBenchmark {

    private static final String URI =
            System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");

    private static final String DB = "ledger_sync_benchmark";
    private static final String COLLECTION = "transactions";
    private static final int TOTAL = 100_000;

    public static void main(String[] args) {
        try (MongoClient client = MongoClients.create(URI)) {
            MongoDatabase db = client.getDatabase(DB);
            MongoCollection<Document> transactions =
                    db.getCollection(COLLECTION);

            transactions.drop();

            transactions.createIndex(
                    Indexes.compoundIndex(
                            Indexes.ascending("account_last4"),
                            Indexes.descending("occurred_at")
                    )
            );

            transactions.createIndex(
                    Indexes.ascending("dedup_key")
            );

            seed(transactions);

            System.out.println();
            System.out.println("MongoDB benchmark");
            System.out.println("=================");
            System.out.println("Documents: " + transactions.countDocuments());

            runQuery1(db);
            runQuery2(db);
            runQuery3(db);
        }
    }

    private static void seed(MongoCollection<Document> collection) {
        List<Document> batch = new ArrayList<>(1000);

        for (int i = 0; i < TOTAL; i++) {
            String account = (i % 2 == 0) ? "4821" : "9075";

            OffsetDateTime date = OffsetDateTime.of(
                    2025 + (i % 2),
                    (i % 12) + 1,
                    (i % 28) + 1,
                    i % 24,
                    i % 60,
                    0,
                    0,
                    ZoneOffset.ofHoursMinutes(5, 30)
            );

            String category;

            if (i % 4 == 0) {
                category = "SPEND";
            } else if (i % 4 == 1) {
                category = "INCOME";
            } else if (i % 4 == 2) {
                category = "MICRO";
            } else {
                category = "TRANSFER";
            }

            Document doc = new Document()
                    .append("dedup_key", "benchmark-" + i)
                    .append("account_last4", account)
                    .append("occurred_at", date.toString())
                    .append("direction", i % 2 == 0 ? "DEBIT" : "CREDIT")
                    .append("amount", "100.00")
                    .append("category", category)
                    .append("merchant", "Benchmark Merchant")
                    .append("source_message_ids",
                            List.of("benchmark-message-" + i));

            batch.add(doc);

            if (batch.size() == 1000) {
                collection.insertMany(
                        batch,
                        new InsertManyOptions().ordered(false)
                );
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            collection.insertMany(
                    batch,
                    new InsertManyOptions().ordered(false)
            );
        }

        System.out.println(
                "Seeded " + collection.countDocuments() + " documents."
        );
    }

    private static void runQuery1(MongoDatabase db) {
        Document filter = new Document("$and", List.of(
                new Document("account_last4", "4821"),
                new Document("occurred_at",
                        new Document("$gte",
                                "2025-01-01T00:00:00+05:30")
                                .append("$lt",
                                        "2025-02-01T00:00:00+05:30"))
        ));

        Document explainCommand = new Document(
                "explain",
                new Document("find", COLLECTION)
                        .append("filter", filter)
                        .append("sort",
                                new Document("occurred_at", -1))
        ).append("verbosity", "executionStats");

        Document result = db.runCommand(explainCommand);

        printStats("Q1 account + month", result);
    }

    private static void runQuery2(MongoDatabase db) {
        Document filter =
                new Document("account_last4", "4821");

        Document explainCommand = new Document(
                "explain",
                new Document("find", COLLECTION)
                        .append("filter", filter)
        ).append("verbosity", "executionStats");

        Document result = db.runCommand(explainCommand);

        printStats("Q2 account scan", result);
    }

    private static void runQuery3(MongoDatabase db) {
        Document filter =
                new Document("dedup_key", "benchmark-50000");

        Document explainCommand = new Document(
                "explain",
                new Document("find", COLLECTION)
                        .append("filter", filter)
        ).append("verbosity", "executionStats");

        Document result = db.runCommand(explainCommand);

        printStats("Q3 point lookup", result);
    }

    private static void printStats(String name, Document result) {
        Document executionStats =
                result.get("executionStats", Document.class);

        if (executionStats == null) {
            System.out.println(
                    name + " | unable to read executionStats"
            );
            return;
        }

        Number examined =
                executionStats.get("totalDocsExamined", Number.class);

        Number returned =
                executionStats.get("nReturned", Number.class);

        System.out.println(
                name +
                        " | examined=" + examined +
                        " | returned=" + returned
        );
    }
}