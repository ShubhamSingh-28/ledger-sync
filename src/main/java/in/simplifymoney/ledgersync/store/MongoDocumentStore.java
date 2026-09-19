package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;

public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private final MongoClient client;
    private final MongoCollection<Document> transactions;
    private final MongoCollection<Document> messageIndex;

    public MongoDocumentStore(String connectionString) {
        this.client = MongoClients.create(connectionString);
        MongoDatabase db = client.getDatabase("ledger_sync");
        this.transactions = db.getCollection("transactions");
        this.messageIndex = db.getCollection("message_index");

        transactions.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("account_last4"),
                        Indexes.descending("occurred_at")),
                new IndexOptions().name("acct_date"));

        transactions.createIndex(
                Indexes.ascending("dedup_key"),
                new IndexOptions().name("dedup").unique(true));

        messageIndex.createIndex(
                Indexes.ascending("message_id"),
                new IndexOptions().name("msg_id").unique(true));
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay()
                .atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30));
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay()
                .atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30));

        List<NormalizedTxn> result = new ArrayList<>();
        transactions.find(Filters.and(
                        Filters.eq("account_last4", accountLast4),
                        Filters.gte("occurred_at", start.toString()),
                        Filters.lt("occurred_at", end.toString())))
                .sort(Sorts.descending("occurred_at"))
                .forEach(doc -> result.add(fromDocument(doc)));
        return result;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        for (Category c : Category.values()) totals.put(c, BigDecimal.ZERO.setScale(2));

        transactions.find(Filters.eq("account_last4", accountLast4))
                .forEach(doc -> {
                    Category c = Category.valueOf(doc.getString("category"));
                    BigDecimal amount = new BigDecimal(doc.getString("amount"));
                    totals.put(c, totals.get(c).add(amount));
                });
        return totals;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document idx = messageIndex.find(Filters.eq("message_id", messageId)).first();
        if (idx == null) return Optional.empty();
        String dedupKey = idx.getString("dedup_key");
        Document doc = transactions.find(Filters.eq("dedup_key", dedupKey)).first();
        if (doc == null) return Optional.empty();
        return Optional.of(fromDocument(doc));
    }

    @Override
    public void save(NormalizedTxn txn) {
        String dedupKey = txn.accountLast4() + "|" + txn.occurredAt() + "|"
                + txn.amount().toPlainString() + "|" + txn.direction().name();

        Document doc = new Document()
                .append("dedup_key", dedupKey)
                .append("account_last4", txn.accountLast4())
                .append("occurred_at", txn.occurredAt().toString())
                .append("direction", txn.direction().name())
                .append("amount", txn.amount().toPlainString())
                .append("category", txn.category().name())
                .append("merchant", txn.merchant())
                .append("source_message_ids", txn.sourceMessageIds());

        transactions.replaceOne(
                Filters.eq("dedup_key", dedupKey),
                doc,
                new ReplaceOptions().upsert(true));

        for (String msgId : txn.sourceMessageIds()) {
            Document idxDoc = new Document()
                    .append("message_id", msgId)
                    .append("dedup_key", dedupKey);
            messageIndex.replaceOne(
                    Filters.eq("message_id", msgId),
                    idxDoc,
                    new ReplaceOptions().upsert(true));
        }
    }

    public List<NormalizedTxn> all() {
        List<NormalizedTxn> result = new ArrayList<>();
        transactions.find()
                .sort(Sorts.ascending("occurred_at"))
                .forEach(doc -> result.add(fromDocument(doc)));
        return result;
    }

    public long count() {
        return transactions.countDocuments();
    }

    public void clear() {
        transactions.deleteMany(new Document());
        messageIndex.deleteMany(new Document());
    }

    @Override
    public void close() {
        client.close();
    }

    @SuppressWarnings("unchecked")
    private static NormalizedTxn fromDocument(Document doc) {
        return new NormalizedTxn(
                doc.getString("account_last4"),
                OffsetDateTime.parse(doc.getString("occurred_at")),
                Direction.valueOf(doc.getString("direction")),
                new BigDecimal(doc.getString("amount")),
                Category.valueOf(doc.getString("category")),
                doc.getString("merchant"),
                (List<String>) doc.get("source_message_ids"));
    }
}
