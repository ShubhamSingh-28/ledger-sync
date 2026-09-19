package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class IngestService {

    private static final BigDecimal MICRO_THRESHOLD =
            new BigDecimal("100.00");

    private static final Duration TRANSFER_WINDOW =
            Duration.ofMinutes(5);

    private static final BigDecimal ZERO =
            BigDecimal.ZERO.setScale(2);

    private final Parsers parsers;
    private final LedgerStore store;

    private List<Discrepancy> lastDiscrepancies = List.of();

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public List<Discrepancy> discrepancies() {
        return lastDiscrepancies;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        store.clear();

        List<RawMessage> messages = readCorpus(corpus);

        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;

        for (RawMessage message : messages) {
            Optional<ParsedTxn> parsedTxn = parsers.parse(message);

            if (parsedTxn.isEmpty()) {
                skipped++;
                continue;
            }

            parsed.add(parsedTxn.get());
        }

        List<MergedTxn> merged = deduplicate(parsed);

        List<MergedTxn> inferred = inferMissingTransactions(merged);
        merged.addAll(inferred);

        Set<MergedTxn> transferLegs = detectTransfers(merged);

        lastDiscrepancies = findDiscrepancies(merged);

        int written = 0;

        for (MergedTxn mergedTxn : merged) {
            Category category = categorize(
                    mergedTxn,
                    transferLegs.contains(mergedTxn)
            );

            NormalizedTxn txn = new NormalizedTxn(
                    mergedTxn.accountLast4,
                    mergedTxn.occurredAt,
                    mergedTxn.direction,
                    mergedTxn.amount.setScale(2),
                    category,
                    mergedTxn.merchant,
                    mergedTxn.sourceMessageIds.stream()
                            .sorted()
                            .distinct()
                            .toList()
            );

            store.save(txn);
            written++;
        }

        return new Stats(
                messages.size(),
                written,
                skipped
        );
    }

    private List<MergedTxn> inferMissingTransactions(
            List<MergedTxn> transactions
    ) {
        List<MergedTxn> inferred = new ArrayList<>();

        Map<String, List<MergedTxn>> byAccount =
                new LinkedHashMap<>();

        for (MergedTxn txn : transactions) {
            byAccount
                    .computeIfAbsent(
                            txn.accountLast4,
                            key -> new ArrayList<>()
                    )
                    .add(txn);
        }

        for (var entry : byAccount.entrySet()) {
            String account = entry.getKey();

            if ("3310".equals(account)) {
                continue;
            }

            List<MergedTxn> txns = entry.getValue();

            txns.sort(
                    Comparator.comparing(
                            txn -> txn.occurredAt
                    )
            );

            for (int i = 0; i < txns.size() - 1; i++) {
                MergedTxn current = txns.get(i);
                MergedTxn next = txns.get(i + 1);

                if (current.statedBalance == null ||
                        next.statedBalance == null) {
                    continue;
                }

                BigDecimal expectedBalance;

                if (next.direction == Direction.DEBIT) {
                    expectedBalance =
                            current.statedBalance
                                    .subtract(next.amount);
                } else {
                    expectedBalance =
                            current.statedBalance
                                    .add(next.amount);
                }

                BigDecimal gap =
                        expectedBalance
                                .subtract(next.statedBalance)
                                .setScale(2);

                if (gap.compareTo(ZERO) <= 0) {
                    continue;
                }

                boolean alreadyExists = transactions.stream()
                        .anyMatch(txn ->
                                txn.accountLast4.equals(account)
                                        && txn.direction == Direction.DEBIT
                                        && txn.amount.compareTo(gap) == 0
                                        && !txn.occurredAt.isBefore(
                                        current.occurredAt
                                )
                                        && !txn.occurredAt.isAfter(
                                        next.occurredAt
                                )
                        );

                if (alreadyExists) {
                    continue;
                }

                boolean alreadyInferred = inferred.stream()
                        .anyMatch(txn ->
                                txn.accountLast4.equals(account)
                                        && txn.direction == Direction.DEBIT
                                        && txn.amount.compareTo(gap) == 0
                        );

                if (alreadyInferred) {
                    continue;
                }

                MergedTxn missing = new MergedTxn(
                        account,
                        next.occurredAt,
                        Direction.DEBIT,
                        gap,
                        "UNACCOUNTED BALANCE MOVEMENT"
                );

                missing.inferred = true;

                missing.sourceMessageIds.addAll(
                        current.sourceMessageIds
                );

                missing.sourceMessageIds.addAll(
                        next.sourceMessageIds
                );

                inferred.add(missing);
            }
        }

        return inferred;
    }

    private List<MergedTxn> deduplicate(
            List<ParsedTxn> parsed
    ) {
        Map<String, MergedTxn> groups =
                new LinkedHashMap<>();

        for (ParsedTxn parsedTxn : parsed) {
            String key =
                    parsedTxn.accountLast4() + "|"
                            + parsedTxn.occurredAt() + "|"
                            + parsedTxn.amount()
                            .setScale(2)
                            .toPlainString() + "|"
                            + parsedTxn.direction();

            groups
                    .computeIfAbsent(
                            key,
                            ignored -> new MergedTxn(parsedTxn)
                    )
                    .addSource(
                            parsedTxn.sourceMessageId()
                    );

            MergedTxn merged = groups.get(key);

            if (merged.statedBalance == null &&
                    parsedTxn.statedBalance() != null) {
                merged.statedBalance =
                        parsedTxn.statedBalance();
            }
        }

        return new ArrayList<>(groups.values());
    }

    private Set<MergedTxn> detectTransfers(
            List<MergedTxn> transactions
    ) {
        Set<MergedTxn> transferLegs =
                new LinkedHashSet<>();

        Set<String> accounts =
                new LinkedHashSet<>();

        for (MergedTxn txn : transactions) {
            accounts.add(txn.accountLast4);
        }

        if (accounts.size() < 2) {
            return transferLegs;
        }

        List<MergedTxn> debits =
                new ArrayList<>();

        List<MergedTxn> credits =
                new ArrayList<>();

        for (MergedTxn txn : transactions) {
            if ("3310".equals(txn.accountLast4)) {
                continue;
            }

            if (txn.direction == Direction.DEBIT) {
                debits.add(txn);
            } else {
                credits.add(txn);
            }
        }

        for (MergedTxn debit : debits) {
            for (MergedTxn credit : credits) {

                if (debit.accountLast4.equals(
                        credit.accountLast4
                )) {
                    continue;
                }

                if (debit.amount.compareTo(
                        credit.amount
                ) != 0) {
                    continue;
                }

                if (transferLegs.contains(credit)) {
                    continue;
                }

                Duration gap =
                        Duration.between(
                                debit.occurredAt,
                                credit.occurredAt
                        ).abs();

                if (gap.compareTo(
                        TRANSFER_WINDOW
                ) <= 0) {
                    transferLegs.add(debit);
                    transferLegs.add(credit);
                    break;
                }
            }
        }

        return transferLegs;
    }

    private List<Discrepancy> findDiscrepancies(
            List<MergedTxn> transactions
    ) {
        List<Discrepancy> discrepancies =
                new ArrayList<>();

        Map<String, List<MergedTxn>> byAccount =
                new LinkedHashMap<>();

        for (MergedTxn txn : transactions) {
            byAccount
                    .computeIfAbsent(
                            txn.accountLast4,
                            key -> new ArrayList<>()
                    )
                    .add(txn);
        }

        for (var entry : byAccount.entrySet()) {
            List<MergedTxn> txns = entry.getValue();

            txns.sort(
                    Comparator.comparing(
                            txn -> txn.occurredAt
                    )
            );

            for (int i = 0; i < txns.size() - 1; i++) {
                MergedTxn current = txns.get(i);
                MergedTxn next = txns.get(i + 1);

                if (current.statedBalance == null ||
                        next.statedBalance == null) {
                    continue;
                }

                BigDecimal expectedBalance =
                        current.statedBalance;

                BigDecimal actualPreNext =
                        next.direction == Direction.DEBIT
                                ? next.statedBalance
                                .add(next.amount)
                                : next.statedBalance
                                .subtract(next.amount);

                BigDecimal gap =
                        expectedBalance
                                .subtract(actualPreNext)
                                .setScale(2);

                if (gap.compareTo(ZERO) == 0) {
                    continue;
                }

                if (hasInferredTransaction(
                        txns,
                        current.occurredAt,
                        next.occurredAt,
                        gap.abs()
                )) {
                    continue;
                }

                String direction =
                        gap.signum() > 0
                                ? "debit"
                                : "credit";

                discrepancies.add(
                        new Discrepancy(
                                entry.getKey(),
                                current.occurredAt.toString(),
                                gap.abs()
                                        .setScale(2)
                                        .toPlainString(),
                                "Missing " + direction
                                        + " of Rs."
                                        + gap.abs()
                                        .setScale(2)
                                        .toPlainString()
                                        + " between "
                                        + current.occurredAt
                                        + " and "
                                        + next.occurredAt
                                        + ". Bank balance moved from "
                                        + current.statedBalance
                                        .toPlainString()
                                        + " to "
                                        + actualPreNext
                                        .toPlainString()
                                        + " with no matching message."
                        )
                );
            }
        }

        return discrepancies;
    }

    private boolean hasInferredTransaction(
            List<MergedTxn> transactions,
            OffsetDateTime from,
            OffsetDateTime to,
            BigDecimal amount
    ) {
        return transactions.stream()
                .anyMatch(txn ->
                        txn.inferred
                                && txn.direction == Direction.DEBIT
                                && txn.amount.compareTo(amount) == 0
                                && !txn.occurredAt.isBefore(from)
                                && !txn.occurredAt.isAfter(to)
                );
    }

    private Category categorize(
            MergedTxn txn,
            boolean isTransfer
    ) {
        if (isTransfer) {
            return Category.TRANSFER;
        }

        if (txn.direction == Direction.CREDIT) {
            return Category.INCOME;
        }

        if (isMicro(txn)) {
            return Category.MICRO;
        }

        return Category.SPEND;
    }

    private boolean isMicro(MergedTxn txn) {
        if (txn.direction != Direction.DEBIT) {
            return false;
        }

        if (txn.amount.compareTo(
                MICRO_THRESHOLD
        ) > 0) {
            return false;
        }

        String merchant =
                txn.merchant.toUpperCase();

        return merchant.startsWith("UPI/")
                || merchant.startsWith("UPI ");
    }

    public static List<RawMessage> readCorpus(
            Path corpus
    ) throws IOException {

        List<RawMessage> out =
                new ArrayList<>();

        try (Stream<String> lines =
                     Files.lines(corpus)) {

            for (String line :
                    (Iterable<String>)
                            lines
                                    .filter(s -> !s.isBlank())
                                    ::iterator) {

                Map<String, Object> object =
                        Json.parseObject(line);

                out.add(
                        new RawMessage(
                                (String) object.get(
                                        "message_id"
                                ),
                                (String) object.get(
                                        "channel"
                                ),
                                (String) object.get(
                                        "sender"
                                ),
                                OffsetDateTime.parse(
                                        (String) object.get(
                                                "received_at"
                                        )
                                ),
                                (String) object.get(
                                        "device_id"
                                ),
                                (String) object.get(
                                        "body"
                                )
                        )
                );
            }
        }

        return out;
    }

    public static class MergedTxn {

        public final String accountLast4;
        public final OffsetDateTime occurredAt;
        public final Direction direction;
        public final BigDecimal amount;
        public final String merchant;

        public final Set<String> sourceMessageIds =
                new LinkedHashSet<>();

        public BigDecimal statedBalance;

        public boolean inferred;

        MergedTxn(ParsedTxn first) {
            this.accountLast4 =
                    first.accountLast4();

            this.occurredAt =
                    first.occurredAt();

            this.direction =
                    first.direction();

            this.amount =
                    first.amount().setScale(2);

            this.merchant =
                    first.merchant();

            this.statedBalance =
                    first.statedBalance();

            this.inferred = false;

            this.sourceMessageIds.add(
                    first.sourceMessageId()
            );
        }

        MergedTxn(
                String accountLast4,
                OffsetDateTime occurredAt,
                Direction direction,
                BigDecimal amount,
                String merchant
        ) {
            this.accountLast4 = accountLast4;
            this.occurredAt = occurredAt;
            this.direction = direction;
            this.amount = amount.setScale(2);
            this.merchant = merchant;
            this.statedBalance = null;
            this.inferred = true;
        }

        void addSource(String messageId) {
            sourceMessageIds.add(messageId);
        }
    }

    public record Discrepancy(
            String accountLast4,
            String occurredAt,
            String amount,
            String note
    ) {
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped
    ) {
    }
}