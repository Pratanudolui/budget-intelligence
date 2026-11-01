# Prompt History

The submission guidelines require every prompt submitted to every AI tool. This file records them verbatim, in order.

| Tool | Model | Used for |
|---|---|---|
| Claude Code (Anthropic CLI agent) | Claude Opus | Reading the brief/FAQ/guidelines, design, code generation, running builds/tests/data generation, documentation |

Claude Code works agentically: one prompt can lead to many tool actions (reading files, writing code, running
`mvn`, running the generator and evaluator, fixing failures). Those actions are not prompts, so they are summarised
under each prompt instead of listed. AI responses are optional per the FAQ and are not reproduced in full.

> **Keep this file current.** Append every further prompt you send to any AI tool (including follow-up questions,
> fixes and README edits) before the deadline, with a one-line note on what changed.

---

## Prompt 1 — initial build request (Claude Code)

```text
read all the docs in the folder i think its has 4 files and

You are an expert AI software engineer and technical assistant. I am providing you with four documents:
1. The 72 Hours Code Challenge brief from YottaFlex.ai (Round 2: Associate AI Forward Deployed Engineer).
2. The Candidate Project FAQ.
3. The Candidate Project Submission Guidelines.
4. My resume (Pratanu Dolui, Java & Backend Software Engineer).

Using these documents and my technical background, I want you to design, build, and generate a complete,
production-ready, end-to-end solution for this challenge using my core tech stack: Java 17/21, Spring Boot,
Hibernate (JPA), PostgreSQL, and Maven (with support for data generation scripts, REST APIs, and exception handling).

Please structure your entire response and outputs so that we can accomplish the following two primary deliverables:

1. A complete project structure and codebase (which I will extract into a ZIP file) containing:
   - All source code files, data generator scripts, database configurations, and schema/data dictionary definitions
     built with Spring Boot and Java.
   - The required data files (including synthetic datasets of 1,000 profiles and 500,000 transactions across 12
     months, plus separate ground_truth files for anomalies/edge cases like transfers as spending twice, refunds as
     new expenses, timezone boundary crossings, etc.).
   - A complete history of prompts used during development.
   - A comprehensive, professional README.md explaining the architecture, local setup, data generation instructions,
     and how the edge cases are handled.

2. A detailed Microsoft Word (.docx) document (or clear markdown/text content formatted for easy export to a Word
   document) providing step-by-step instructions:
   - Comprehensive system requirements (Java JDK 17/21, Maven, PostgreSQL) and environment setup specifically
     optimized for Windows 11.
   - Exact step-by-step terminal commands to extract files, set up dependencies, configure the PostgreSQL database,
     seed data, run the Spring Boot application, and execute validations.
   - Clear instructions on how to initialize a Git repository, stage files, make meaningful commits, and push the
     code to a public GitHub repository.

Please write clean, robust backend code matching my professional experience, make reasonable engineering
assumptions where necessary, and ensure all constraints from the challenge brief and guidelines are fully satisfied.
Let's begin building the complete solution now!
```

**What the AI did in response (summary of tool actions):**

1. Read the four PDFs (brief, FAQ, submission guidelines, resume) and checked the local toolchain (JDK 21,
   Maven 3.9; no local PostgreSQL or Docker).
2. Designed the solution: plain-Java decision engine shared by an offline CLI and a Spring Boot/PostgreSQL service;
   ground truth kept in separate files; outputs appended per pipeline run.
3. Wrote the generator (`Catalog`, `SyntheticDataGenerator`), engine (`RuleCategorizer`, `RecurringDetector`,
   `BudgetIntelligenceEngine`), `Evaluator`, `DatasetValidator`, CLI, Flyway schema, JPA entities, services,
   controllers and `GlobalExceptionHandler`.
4. Generated starter (50k) and full (500k) datasets; ran validation (62/62 pass) and evaluation.
5. Wrote unit tests and a Spring Boot integration test against embedded PostgreSQL. Running them exposed bugs that
   were then fixed (validator NPE on orphan FKs; `ConstraintViolationException` mapped to 500 instead of 400;
   dataset reload deleting pipeline-run history).
6. Ran an opt-in full-scale test: 500k rows COPY-loaded into PostgreSQL in ~4 s; pipeline + persistence ~13 s.
7. Ran the reproducibility check (two generations, identical SHA-256 for every file) and fixed a cross-platform
   checksum risk (JSON pretty-printer line separator; added `.gitattributes`).
8. Wrote README, data dictionary, scripts (PowerShell + bash + SQL) and the Windows 11 setup guide (.docx).

---
