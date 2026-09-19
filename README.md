# Simplify Money — Ledger Sync Take-Home

A Java 21 implementation of the Simplify Money ledger-sync take-home assignment.

The project covers:

- Task 0 — App feedback and one-pager
- Task 1 — Track/data-source flow teardown
- Task 2 — Transaction ingestion and reconciliation
- Task 3 — Production incident investigation and fix
- Task 4 — SQL to document-store migration and benchmark
- Verification, idempotency, consistency checking, and supporting documentation

---

# Table of Contents

- [Project Overview](#project-overview)
- [Tech Stack](#tech-stack)
- [Repository Structure](#repository-structure)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Task 0 — App Feedback](#task-0--app-feedback)
- [Task 1 — Track Flow Teardown](#task-1--track-flow-teardown)
- [Task 2 — Ledger Ingestion](#task-2--ledger-ingestion)
  - [Input](#input)
  - [Transaction Categories](#transaction-categories)
  - [Ingestion Pipeline](#ingestion-pipeline)
  - [Deduplication and Idempotency](#deduplication-and-idempotency)
  - [Task 2 Results](#task-2-results)
  - [Reconciliation](#reconciliation)
  - [Traceability](#traceability)
- [Task 3 — Production Incident](#task-3--production-incident)
  - [Incident](#incident)
  - [Reproduction](#reproduction)
  - [Root Cause](#root-cause)
  - [Blast Radius](#blast-radius)
  - [Fix](#fix)
  - [Regression Test](#regression-test)
  - [Five-Line Incident Note](#five-line-incident-note)
- [Task 4 — SQL to Document Store](#task-4--sql-to-document-store)
  - [Architecture](#architecture)
  - [Document Model](#document-model)
  - [Indexes](#indexes)
  - [Queries](#queries)
  - [Backfill](#backfill)
  - [Idempotency](#idempotency)
  - [Consistency Checker](#consistency-checker)
  - [100k Document Benchmark](#100k-document-benchmark)
- [Database Migration](#database-migration)
- [Verification](#verification)
- [Important Commands](#important-commands)
- [Decision Log](#decision-log)
- [Data Observations](#data-observations)
- [AI Disclosure](#ai-disclosure)
- [Unfinished / Limitations](#unfinished--limitations)
- [Final Submission Checklist](#final-submission-checklist)

---

# Project Overview

This repository implements a small ledger-processing system for financial transaction messages.

The system takes the supplied JSONL corpus, identifies real financial transactions, normalizes them into a common ledger representation, classifies them, persists them into SQL, generates the required JSON outputs, and provides reconciliation information.

The project also contains:

- A production incident reproduction and regression test
- A document-store implementation using MongoDB
- SQL to MongoDB backfill
- Idempotent migration behavior
- A consistency checker
- A 100,000-document MongoDB benchmark
- Task 0 and Task 1 documentation
- Decision logs
- AI usage disclosure
- Verification scripts

The main implementation language is Java 21.

---

# Tech Stack

## Application

- Java 21
- Gradle
- JUnit
- H2
- JDBC
- MongoDB Java Driver
- Jackson
- Java HTTP client / standard Java APIs where applicable

## Infrastructure

- Docker
- Docker Compose
- MongoDB
- H2 for the SQL ledger used by the implementation

## Testing

- JUnit
- Integration-style command verification
- Incident regression test
- MongoDB benchmark
- SQL/document-store consistency checker

---

# Repository Structure

```text
.
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
├── verify.sh
├── README.md
│
├── db/
│   └── migration/
│       ├── V1__create_ledger.sql
│       └── V2__...
│
├── fixtures/
│   ├── corpus-a.jsonl
│   └── corpus-a-totals.json
│
├── src/
│   ├── main/
│   │   └── java/
│   │       └── in/
│   │           └── simplifymoney/
│   │               └── ledgersync/
│   │                   ├── App.java
│   │                   ├── ...
│   │                   └── store/
│   │                       ├── Backfill.java
│   │                       ├── ConsistencyChecker.java
│   │                       ├── DocumentStore.java
│   │                       ├── InMemoryLedgerStore.java
│   │                       ├── LedgerStore.java
│   │                       ├── MongoBenchmark.java
│   │                       ├── MongoDocumentStore.java
│   │                       └── SqlLedgerStore.java
│   │
│   └── test/
│       └── java/
│           └── in/
│               └── simplifymoney/
│                   └── ledgersync/
│                       ├── AmountsTest.java
│                       ├── IncidentTest.java
│                       ├── NormalizedTxnContractTest.java
│                       └── ParsersTest.java
│
├── submission/
│   ├── ledger.json
│   ├── summary.json
│   └── reconciliation.json
│
└── task-teardowns/
    ├── task0-feedback-onepager.md
    └── task1-track-flow-teardown.md