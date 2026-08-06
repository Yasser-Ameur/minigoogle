# MiniGoogle Engineering Design Specification
## Chapter 00 — Project Vision & Architecture

**Version:** 1.0

**Author:** Yasser Ameur

---

# Table of Contents

1. Introduction
2. Project Objectives
3. Design Philosophy
4. Project Scope
5. Functional Requirements
6. Non-Functional Requirements
7. Technology Stack
8. Repository Structure
9. High-Level Architecture
10. Data Flow
11. Core Components
12. Performance Goals
13. Coding Standards
14. Development Roadmap

---

# 1. Introduction

## What is this project?

MiniGoogle is a complete distributed search engine implemented almost entirely from scratch.

The objective is **not** to build a production competitor to Google Search.

The objective is to demonstrate mastery of the computer science principles behind modern search engines while avoiding frameworks that hide those concepts.

Every major algorithm should be implemented manually.

The finished project should showcase knowledge in

- Distributed Systems
- Information Retrieval
- Graph Algorithms
- Networking
- Concurrent Programming
- Storage Engines
- Search Ranking
- Software Architecture
- System Design

This project is intentionally designed to resemble the engineering challenges encountered at Google.

---

# 2. Project Objectives

This project should be able to

- Crawl websites
- Parse HTML
- Extract textual content
- Build an inverted index
- Persist indexes on disk
- Distribute indexes across multiple nodes
- Execute ranked search queries
- Continuously update modified pages
- Recover from node failures
- Scale horizontally

The project should remain understandable enough that every major component can be explained during a technical interview.

---

# 3. Design Philosophy

## Principle 1

Never use a library that implements the interesting part.

Examples

❌ Elasticsearch

❌ Lucene

❌ Solr

❌ Redis

❌ Kafka

❌ Spark

❌ Hadoop

❌ PostgreSQL

These technologies solve the exact problems we are trying to demonstrate.

Using them removes almost all engineering value from the project.

---

## Principle 2

Use libraries only for infrastructure.

Allowed libraries

Spring Boot

Purpose

HTTP server

---

Jackson

Purpose

JSON serialization

---

JSoup

Purpose

HTML parsing

---

JUnit

Purpose

Testing

---

React

Purpose

Frontend

---

Docker

Purpose

Deployment

---

GitHub Actions

Purpose

Continuous Integration

---

Everything else should be implemented manually.

---

# 4. Project Scope

## Included

Distributed crawler

Custom storage engine

Inverted index

Positional index

Phrase search

Boolean search

PageRank

BM25

Distributed coordinator

Index sharding

Heartbeat monitoring

REST API

Frontend

Benchmark suite

Unit tests

Integration tests

---

## Not Included

Machine learning ranking

Advertisement system

Image search

Video search

Voice search

OCR

Knowledge graph

User accounts

Distributed consensus algorithms such as Raft or Paxos

These features are intentionally excluded to keep the project focused.

---

# 5. Functional Requirements

## Crawling

The crawler shall

- Download HTML pages
- Follow hyperlinks
- Respect robots.txt
- Respect crawl delays
- Avoid duplicate crawling
- Detect modified pages
- Support concurrent workers

---

## Indexing

The indexer shall

- Normalize text
- Remove punctuation
- Remove stop words
- Stem words
- Compute term frequencies
- Record word positions
- Build inverted indexes
- Persist indexes on disk

---

## Query Engine

The search engine shall support

Single keyword search

Example

```
computer
```

Boolean search

```
computer AND science
```

Phrase search

```
"machine learning"
```

Negation

```
computer NOT hardware
```

Wildcard search

```
comput*
```

Typo correction

```
machien
```

Autocomplete

```
mach...
```

---

## Ranking

Results should be ranked using

BM25

combined with

PageRank

Final score

```
score = α × BM25 + β × PageRank
```

where

```
α + β = 1
```

---

## Storage

The storage engine must

- Avoid SQL
- Avoid NoSQL
- Use binary files
- Support sequential reads
- Support random access
- Minimize memory usage
- Allow partial loading

---

## Distributed System

The system shall support

- Multiple crawler nodes
- Multiple index nodes
- Coordinator node
- Heartbeats
- Failure detection
- Query routing
- Sharding

---

# 6. Non-Functional Requirements

The system should prioritize

Correctness

↓

Maintainability

↓

Performance

↓

Scalability

↓

Fault tolerance

Performance is important but never at the expense of code clarity.

---

# 7. Technology Stack

## Backend

Java 21

Reason

Excellent concurrency

Excellent tooling

Strong networking support

Excellent interview language

---

## Frontend

React

Purpose

Simple search interface

---

## Build Tool

Gradle

Reason

Fast

Simple

Well supported

---

## Testing

JUnit 5

---

## HTTP

Spring Boot

Only used for REST endpoints.

No Spring Data.

No Spring Security.

No ORM.

---

## HTML Parsing

JSoup

Purpose

Extract

- text
- title
- hyperlinks

Nothing else.

---

# 8. Repository Structure

```
MiniGoogle/

    coordinator/

    crawler/

    crawler-worker/

    indexer/

    query-engine/

    ranking/

    storage/

    communication/

    common/

    frontend/

    benchmarks/

    docs/

    scripts/
```

Every module should compile independently.

No circular dependencies.

---

# 9. High-Level Architecture

```
                   User
                     │
                     ▼
             React Frontend
                     │
                     ▼
          Search Coordinator Node
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   Index Node   Index Node   Index Node
        ▲            ▲            ▲
        │            │            │
    Index Builder Index Builder Index Builder
        ▲            ▲            ▲
        └────Crawler Coordinator──┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
Crawler Worker  Crawler Worker  Crawler Worker
                     │
                     ▼
                Internet
```

---

# 10. Data Flow

The entire engine follows the pipeline

```
Internet

↓

Crawler

↓

HTML Parser

↓

Tokenizer

↓

Normalizer

↓

Stemmer

↓

Inverted Index Builder

↓

Storage Engine

↓

Query Engine

↓

Ranking Engine

↓

Frontend
```

Every stage has exactly one responsibility.

---

# 11. Core Components

## Crawler

Responsible for discovering pages.

Input

URL

Output

Raw HTML

---

## Parser

Responsible for extracting useful information.

Input

HTML

Output

Document object

---

## Indexer

Responsible for building indexes.

Input

Document

Output

Posting lists

---

## Storage Engine

Responsible for persistence.

Input

Posting lists

Output

Binary files

---

## Query Engine

Responsible for query execution.

Input

Search query

Output

Matching documents

---

## Ranking Engine

Responsible for sorting results.

Input

Candidate documents

Output

Ranked documents

---

## Coordinator

Responsible for distributed orchestration.

Input

Search request

Output

Merged search results

---

# 12. Performance Goals

Target corpus

100,000 pages

---

Index build

Less than 10 minutes

---

Average query latency

Below 50 ms

---

Memory usage

Less than 1 GB

---

Index size

Below 40% of raw corpus size

---

Crawler throughput

100+ pages per second on localhost

---

# 13. Coding Standards

Every class must have one responsibility.

Every algorithm should be documented.

Avoid inheritance unless necessary.

Prefer composition.

Never expose mutable state.

Every public method must include JavaDoc.

No static utility classes unless justified.

Every feature must include unit tests.

No method should exceed approximately 60 lines without good reason.

---

# 14. Development Roadmap

Phase 1

Crawler

---

Phase 2

HTML parser

---

Phase 3

Text processing

---

Phase 4

Inverted index

---

Phase 5

Storage engine

---

Phase 6

Query engine

---

Phase 7

Ranking

---

Phase 8

Distributed architecture

---

Phase 9

Frontend

---

Phase 10

Benchmarking

---

Phase 11

Optimization

---

# End of Chapter 00

# Chapter 01 — Distributed Web Crawler

---

# Table of Contents

1. Introduction
2. Responsibilities
3. Overall Architecture
4. Crawling Strategy
5. URL Lifecycle
6. URL Frontier
7. Scheduler
8. Worker Threads
9. Downloader
10. HTML Parsing
11. URL Normalization
12. Duplicate Detection
13. robots.txt
14. Rate Limiting
15. Error Handling
16. Data Structures
17. Thread Synchronization
18. Communication With Indexer
19. Performance Optimizations
20. Java Class Design

---

# 1. Introduction

The crawler is the first subsystem of MiniGoogle.

Everything downstream depends on it.

A perfect ranking algorithm is useless if the crawler cannot efficiently discover pages.

The crawler is responsible for exploring the Internet as if it were a massive directed graph.

Each webpage becomes a node.

Each hyperlink becomes an edge.

Example

```
        A
      / | \
     B  C  D
     |  |  |
     E  F  G
```

The crawler traverses this graph while ensuring

- pages are never crawled twice
- websites are not overloaded
- crawling can be parallelized
- failures are tolerated

---

# 2. Responsibilities

The crawler has exactly six responsibilities.

1.

Receive URLs to visit.

2.

Download webpages.

3.

Extract hyperlinks.

4.

Normalize URLs.

5.

Send discovered pages to the indexer.

6.

Schedule new URLs.

Nothing else.

The crawler **does not**

- compute ranking
- tokenize text
- store indexes
- answer search queries

Keeping strict boundaries makes every subsystem easier to maintain.

---

# 3. High-Level Architecture

```
                Seed URLs
                     │
                     ▼
             Frontier Scheduler
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   Worker 1     Worker 2     Worker N
        │            │            │
        ▼            ▼            ▼
     Downloader   Downloader   Downloader
        │            │            │
        ▼            ▼            ▼
     HTML Parser  HTML Parser  HTML Parser
        │            │            │
        ▼            ▼            ▼
    URL Extractor URL Extractor URL Extractor
        │            │            │
        └────────────┼────────────┘
                     ▼
              URL Normalizer
                     ▼
             Duplicate Checker
                     ▼
               Frontier Queue
                     │
                     ▼
                 Indexer Queue
```

Every block has a single responsibility.

---

# 4. Crawling Strategy

There are several possible traversal algorithms.

Depth First Search

```
A

↓

B

↓

C

↓

D
```

Breadth First Search

```
        A

     B  C  D

   E F G H I
```

Google-style crawlers generally approximate BFS because

- pages closer to seeds are usually higher quality
- domain coverage improves
- discovery is faster
- freshness improves

Therefore MiniGoogle will use a BFS-style crawl frontier.

---

# 5. URL Lifecycle

Every URL follows exactly the same lifecycle.

```
Discovered

↓

Normalized

↓

Duplicate Check

↓

Frontier Queue

↓

Scheduled

↓

Downloaded

↓

Parsed

↓

Indexed

↓

Finished
```

No URL may skip a stage.

---

# 6. URL Frontier

The frontier is the heart of the crawler.

It stores every page waiting to be crawled.

Requirements

- thread-safe
- fast insertion
- fast removal
- scalable
- supports millions of URLs

Initially we can implement it with

```
BlockingQueue<UrlTask>
```

Later this can evolve into

```
PriorityBlockingQueue<UrlTask>
```

allowing intelligent scheduling.

---

# UrlTask

Every queued item represents one crawl request.

```java
public class UrlTask {

    private final String normalizedUrl;

    private final String domain;

    private final int depth;

    private final Instant discoveredAt;

}
```

Never store raw URLs.

Only normalized ones.

---

# 7. Scheduler

The scheduler decides which URL each worker receives.

Pseudo-code

```text
while(true)

    wait until queue is not empty

    select next URL

    verify crawl delay

    send URL to worker
```

Eventually this scheduler will become distributed.

For now it remains local.

---

# Scheduling Policy

Simple FIFO works initially.

Later

Priority can depend on

- depth
- domain freshness
- previous failures
- update frequency
- estimated quality

---

# 8. Worker Threads

Workers perform the actual crawling.

Each worker repeats forever.

```
Take URL

↓

Download page

↓

Extract HTML

↓

Extract links

↓

Send page to indexer

↓

Return to scheduler
```

Pseudo-code

```java
while(true){

    UrlTask task = frontier.take();

    Page page = downloader.download(task);

    parser.parse(page);

}
```

Workers never communicate directly.

Only through shared queues.

---

# Thread Pool

Instead of creating threads manually

Use

```java
ExecutorService
```

Example

```java
ExecutorService executor =
Executors.newFixedThreadPool(32);
```

Every worker becomes

```java
Runnable
```

This makes scaling trivial.

---

# 9. Downloader

The downloader performs HTTP requests.

Responsibilities

- open connection
- follow redirects
- download HTML
- record metadata
- handle failures

Return

```java
DownloadedPage
```

Never return raw strings.

---

# DownloadedPage

```java
public class DownloadedPage {

    private URI uri;

    private int statusCode;

    private String html;

    private Map<String,String> headers;

    private Instant downloadTime;

}
```

---

# HTTP Rules

Always send

```
User-Agent
```

Example

```
MiniGoogleBot/1.0
```

Always follow

```
301

302

307
```

Maximum redirects

```
5
```

Connection timeout

```
5 seconds
```

Read timeout

```
10 seconds
```

---

# 10. HTML Parsing

JSoup is used only here.

Example

```java
Document doc =
Jsoup.parse(html);
```

Extract

Title

```java
doc.title()
```

Body

```java
doc.body().text()
```

Links

```java
doc.select("a[href]")
```

Nothing else.

---

# 11. URL Normalization

This is surprisingly important.

These URLs all refer to the same page.

```
https://google.com

https://google.com/

HTTPS://GOOGLE.COM/

https://google.com:443/

https://google.com/index.html
```

Without normalization

the crawler wastes enormous effort.

---

Normalization Rules

Convert host to lowercase.

Remove default ports.

Remove fragments.

```
#section1
```

Remove duplicate slashes.

Resolve

```
../

./
```

Sort query parameters.

Remove tracking parameters.

```
utm_source

utm_campaign

fbclid
```

Remove trailing slash.

Convert relative URLs to absolute.

---

Example

Input

```
HTTPS://Google.com:443/docs/../index.html?utm=test
```

Output

```
https://google.com/index.html
```

---

# 12. Duplicate Detection

Every normalized URL is hashed.

```
SHA-256(url)
```

or simply

```
url.hashCode()
```

during development.

Store inside

```java
ConcurrentHashMap<String,Boolean>
```

Pseudo-code

```java
if(visited.putIfAbsent(url,true)==null){

    frontier.add(url);

}
```

This guarantees one crawl per normalized URL.

---

# 13. robots.txt

Before crawling a domain

download

```
https://domain.com/robots.txt
```

Parse

```
User-agent

Disallow

Allow

Crawl-delay
```

Cache robots rules.

Never download robots.txt repeatedly.

Respect every restriction.

---

# 14. Rate Limiting

Never send requests too quickly.

Maintain

```java
Map<Domain,Instant>
```

Before crawling

```
elapsed =
now-lastRequest
```

If

```
elapsed < crawlDelay
```

then

sleep.

This prevents abusive crawling.

---

# 15. Error Handling

Possible failures

404

Skip.

500

Retry later.

DNS failure

Retry.

Timeout

Retry with exponential backoff.

Malformed HTML

Parse what is possible.

Infinite redirects

Discard.

Connection refused

Log and continue.

The crawler must never crash because one page failed.

---

# 16. Data Structures

Visited URLs

```java
ConcurrentHashMap
```

Frontier

```java
PriorityBlockingQueue
```

Domain metadata

```java
ConcurrentHashMap
```

Worker pool

```java
ExecutorService
```

Recently failed URLs

```java
DelayQueue
```

---

# 17. Thread Synchronization

Workers never lock each other.

Communication occurs only through

```
BlockingQueue
```

Advantages

- simpler
- safer
- avoids deadlocks
- higher throughput

Shared mutable state should be minimized.

---

# 18. Communication With Indexer

Workers never index documents.

Instead they create

```java
ParsedDocument
```

```java
class ParsedDocument{

    UUID id;

    URI url;

    String title;

    String text;

    List<URI> outgoingLinks;

    Instant crawlTime;

}
```

The document is pushed into another

```
BlockingQueue
```

owned by the Indexer subsystem.

This decouples crawling speed from indexing speed.

---

# 19. Performance Optimizations

Future optimizations include

- HTTP connection pooling
- HTTP/2 support
- GZIP decompression
- Asynchronous networking
- Domain-aware scheduling
- Incremental recrawling
- Persistent frontier checkpoints
- Distributed frontier partitions

None of these are required for the first implementation but the architecture should make them easy to add.

---

# 20. Java Package Structure

```
crawler/

├── coordinator/
│      FrontierScheduler.java
│      CrawlCoordinator.java
│
├── worker/
│      CrawlWorker.java
│
├── downloader/
│      Downloader.java
│      HttpDownloader.java
│
├── parser/
│      HtmlParser.java
│      LinkExtractor.java
│
├── robots/
│      RobotsParser.java
│      RobotsCache.java
│
├── frontier/
│      UrlTask.java
│      FrontierQueue.java
│
├── normalization/
│      UrlNormalizer.java
│
├── duplicate/
│      VisitedUrlStore.java
│
├── model/
│      DownloadedPage.java
│      ParsedDocument.java
│
└── config/
       CrawlConfiguration.java
```

Each package has one responsibility, making the crawler easy to test and evolve independently.

---

# End of Chapter 01

# Chapter 02 — Text Processing & Index Construction

---

# Table of Contents

1. Overview
2. Why Text Processing Exists
3. Complete Processing Pipeline
4. Document Representation
5. Unicode Normalization
6. Tokenization
7. Case Folding
8. Stop Word Removal
9. Stemming
10. Term Statistics
11. Positional Information
12. Building the Inverted Index
13. Posting Lists
14. Dictionary Construction
15. Binary Storage Format
16. Gap Encoding
17. Skip Lists
18. Memory Management
19. Index Construction Algorithm
20. Package Structure

---

# 1. Overview

The crawler downloads webpages.

Those webpages are still just raw text.

Example

```
<html>

The quick brown fox jumps over the lazy dog.

</html>
```

A search engine cannot efficiently search billions of strings.

Instead, every document must be transformed into a data structure specifically designed for retrieval.

This process is called **index construction**.

The output is called an **inverted index**.

---

# 2. Why Text Processing Exists

Imagine searching

```
running
```

A webpage contains

```
run
```

Without processing

```
running != run
```

The page would never appear.

Similarly

```
APPLE

Apple

apple

ApPlE
```

should all become

```
apple
```

Therefore every document must undergo normalization before indexing.

---

# 3. Complete Processing Pipeline

Every document follows the exact same pipeline.

```
HTML

↓

Extract Text

↓

Unicode Normalization

↓

Tokenization

↓

Case Folding

↓

Stop Word Removal

↓

Stemming

↓

Term Statistics

↓

Positional Index

↓

Inverted Index

↓

Binary Storage
```

Every stage is independent.

This allows us to unit test each component individually.

---

# 4. Document Representation

After parsing HTML, every webpage becomes

```java
class ParsedDocument{

    UUID id;

    URI url;

    String title;

    String text;

    List<URI> outgoingLinks;

}
```

This object enters the indexing pipeline.

Nothing else in the system should manipulate raw HTML.

---

# 5. Unicode Normalization

Unicode is far more complicated than ASCII.

For example

```
é
```

can be represented internally as

```
U+00E9
```

or

```
e + combining accent
```

They look identical.

They are not identical in memory.

Without normalization

```
café
```

might never match

```
café
```

Java provides

```java
Normalizer.normalize(
text,
Normalizer.Form.NFKC
);
```

Every document must first be normalized.

---

# 6. Tokenization

Tokenization converts text into individual words.

Input

```
The quick brown fox.
```

Output

```
The

quick

brown

fox
```

We deliberately avoid NLP libraries.

Implement manually.

Pseudo-code

```
currentWord=""

for every character

    if alphabetic

        append

    else

        emit word
```

Complex tokenizers can be added later.

---

# 7. Case Folding

Convert everything to lowercase.

```
APPLE

↓

apple
```

```
Java

↓

java
```

```
Google

↓

google
```

Implementation

```java
word.toLowerCase(Locale.ROOT)
```

Always use Locale.ROOT.

Never rely on system locale.

---

# 8. Stop Word Removal

Certain words appear everywhere.

Examples

```
the

of

and

is

in

for

to
```

They consume huge amounts of storage while contributing almost no search value.

Maintain

```java
HashSet<String>
```

Example

```java
private static final Set<String> STOP_WORDS
```

Lookup complexity

```
O(1)
```

If

```
word ∈ stopWords
```

discard it.

---

# 9. Stemming

Words often share the same meaning.

```
run

running

runs

runner
```

Instead of indexing each separately

reduce them to

```
run
```

This process is called stemming.

Implement

Porter Stemmer.

Not because it is the best.

Because

- deterministic

- widely known

- interview friendly

- simple enough to implement manually

Never use external stemming libraries.

---

# 10. Term Statistics

Suppose the processed document becomes

```
dog

cat

dog

bird

dog
```

We compute

```
dog → 3

cat → 1

bird → 1
```

These frequencies become part of the posting list.

Store them inside

```java
HashMap<String,Integer>
```

Complexity

```
O(n)
```

---

# 11. Positional Information

Most beginner search engines ignore positions.

We do not.

Instead of

```
dog

↓

doc7
```

store

```
dog

↓

doc7

↓

positions

5

17

32
```

Why?

Phrase search.

Example

```
"machine learning"
```

requires

```
machine

position 12

learning

position 13
```

Without positions

phrase search is impossible.

---

# 12. Inverted Index

Normal storage

```
Document

↓

Words
```

Search engines reverse this.

```
Word

↓

Documents
```

Example

```
apple

↓

doc1

doc5

doc18
```

This is called an inverted index.

---

Internally

```java
HashMap<String, PostingList>
```

---

# 13. Posting Lists

Each term owns one posting list.

Example

```
computer

↓

doc3

doc8

doc10

doc27
```

Representation

```java
class Posting{

    int documentId;

    int frequency;

    List<Integer> positions;

}
```

PostingList

```java
class PostingList{

    List<Posting> postings;

}
```

The postings remain sorted by

```
documentId
```

This simplifies merging later.

---

# 14. Dictionary Construction

The dictionary maps words to posting lists.

```
computer

↓

Posting List 218
```

```
algorithm

↓

Posting List 941
```

Instead of storing posting lists directly

store

```
offset
```

inside a binary file.

```
computer

↓

245128 bytes
```

This allows lazy loading.

---

# 15. Binary Storage

We intentionally avoid databases.

Instead

three files are created.

```
dictionary.bin

postings.bin

documents.bin
```

dictionary.bin

Stores

```
term

↓

offset
```

postings.bin

Stores every posting list.

documents.bin

Stores metadata.

```
Document ID

URL

Title

Length

Timestamp
```

---

# 16. Gap Encoding

Document IDs increase monotonically.

Instead of

```
3

10

15

21
```

Store

```
3

7

5

6
```

These are called

gaps.

Smaller numbers compress significantly better.

Later

Variable Byte Encoding

or

Gamma Encoding

can compress them further.

---

# 17. Skip Lists

Searching long posting lists can be slow.

Example

```
doc1

doc2

...

doc100000
```

Instead

every √N postings

insert a skip pointer.

Example

```
1

2

3

4

↓

100

↓

200

↓

300
```

Boolean queries become dramatically faster.

Implementation

```java
class SkipPointer{

    int targetIndex;

}
```

---

# 18. Memory Management

Never load the entire index.

Instead

Dictionary

always in memory.

Posting lists

loaded only when searched.

Documents

loaded only for final results.

This dramatically reduces RAM usage.

---

# 19. Complete Index Construction Algorithm

Pseudo-code

```
for every document

    normalize

    tokenize

    lowercase

    remove stop words

    stem

    compute frequencies

    compute positions

    update inverted index

after all documents

    sort posting lists

    compress posting lists

    serialize dictionary

    serialize postings

    serialize document table
```

Time Complexity

```
O(total number of tokens)
```

Memory Complexity

```
O(unique terms)
```

---

# 20. Java Package Structure

```
indexer/

├── tokenizer/
│      Tokenizer.java
│
├── normalization/
│      UnicodeNormalizer.java
│      CaseFolder.java
│
├── stopwords/
│      StopWordFilter.java
│
├── stemming/
│      PorterStemmer.java
│
├── statistics/
│      TermFrequencyCalculator.java
│
├── positional/
│      PositionTracker.java
│
├── inverted/
│      InvertedIndex.java
│      Posting.java
│      PostingList.java
│
├── dictionary/
│      DictionaryBuilder.java
│
├── compression/
│      GapEncoder.java
│
├── storage/
│      DictionaryWriter.java
│      PostingWriter.java
│      DocumentWriter.java
│
└── model/
       IndexedDocument.java
```

Each package owns exactly one responsibility.

No package should depend on implementation details of another package—only on shared interfaces and models.

---

# End of Chapter 02

# Chapter 03 — Storage Engine

---

# Table of Contents

1. Introduction
2. Why We Need a Storage Engine
3. Design Goals
4. Storage Architecture
5. File Layout
6. Dictionary File
7. Posting File
8. Document File
9. Metadata File
10. Binary Serialization
11. Memory Mapping
12. Reading Posting Lists
13. Incremental Index Updates
14. Write Ahead Log
15. Crash Recovery
16. Index Loading
17. Memory Management
18. Future Optimizations
19. Java Package Structure

---

# 1. Introduction

Most beginner search engines keep everything in memory.

```
HashMap<String, PostingList>
```

This works.

Until your corpus becomes

```
100,000 pages
```

or

```
1,000,000 pages
```

Suddenly

```
OutOfMemoryError
```

becomes your biggest problem.

Professional search engines solve this by treating RAM as a cache rather than permanent storage.

Our search engine will do the same.

---

# 2. Why We Need a Storage Engine

Suppose

```
500,000 pages

Average page

15 KB
```

Raw data

```
≈ 7.5 GB
```

The inverted index itself might consume

```
2–4 GB
```

Keeping everything inside RAM is unrealistic.

Instead

```
Disk

↓

Persistent Storage

↓

Memory Cache

↓

CPU
```

---

# 3. Design Goals

Our storage engine should satisfy

- Fast sequential writes
- Fast random reads
- Low memory usage
- Crash safety
- Easy serialization
- Easy debugging
- Cross-platform compatibility

---

# 4. Storage Architecture

```
                    Search Engine

                          │

             ┌────────────┴────────────┐

             ▼                         ▼

      Dictionary               Document Metadata

             │

             ▼

        Posting Lists

             │

             ▼

          Binary Files
```

Unlike SQL

there is

no parser

no transactions

no joins

Everything is optimized for search.

---

# 5. File Layout

The index directory

```
index/

    dictionary.bin

    postings.bin

    documents.bin

    metadata.bin

    wal.log
```

Each file has one responsibility.

---

# dictionary.bin

Stores

```
Word

↓

Offset inside postings.bin
```

---

# postings.bin

Stores

Every posting list.

---

# documents.bin

Stores

```
Document ID

↓

Metadata
```

---

# metadata.bin

Stores

Global information

```
Number of documents

Vocabulary size

Version

Creation timestamp

Average document length
```

---

# wal.log

Temporary recovery log.

---

# 6. Dictionary File

Dictionary entries

```
Term

↓

Posting Offset

↓

Document Frequency
```

Representation

```java
class DictionaryEntry{

    String term;

    long postingOffset;

    int documentFrequency;

}
```

The dictionary stays entirely in memory.

Why?

Because

```
Vocabulary

≈

100,000 terms
```

which is relatively small.

Loading it during startup is inexpensive.

---

# Dictionary Binary Layout

Every entry

```
+----------------------+

Term Length (2 bytes)

+----------------------+

UTF-8 bytes

+----------------------+

Posting Offset (8)

+----------------------+

Document Frequency (4)

+----------------------+
```

This layout allows sequential reading.

---

# Example

```
computer

↓

Offset

1,248,921

↓

DocFreq

824
```

---

# 7. Posting File

The posting file is the largest file.

Structure

```
Posting List

Posting List

Posting List

Posting List

...
```

Nothing else.

Each posting list is variable length.

Therefore

dictionary.bin

stores offsets.

---

Posting Layout

```
Document Frequency

↓

Posting 1

↓

Posting 2

↓

Posting N
```

---

Posting

```
Document ID Gap

↓

Term Frequency

↓

Position Count

↓

Positions
```

Example

```
Gap

7

Frequency

3

Positions

12

48

102
```

---

Binary Representation

```
+-------------------+

Gap

4 bytes

+-------------------+

Frequency

4

+-------------------+

Position Count

4

+-------------------+

Positions

4 × N

+-------------------+
```

---

# 8. Document File

Each indexed document has metadata.

Representation

```java
class DocumentMetadata{

    int documentId;

    String url;

    String title;

    int length;

    long crawlTimestamp;

}
```

Document length

is required by BM25.

---

Binary Layout

```
Document ID

↓

Length

↓

Timestamp

↓

URL Length

↓

URL Bytes

↓

Title Length

↓

Title Bytes
```

---

# 9. Metadata File

This file stores

global statistics.

Example

```
MiniGoogle Index

Version

1.0

Documents

142,931

Vocabulary

281,009

Average Length

536 words

Created

2027-02-04
```

During startup

only this file is read first.

---

# 10. Binary Serialization

Never serialize Java objects directly.

Avoid

```
ObjectOutputStream
```

Reasons

- Java-version dependent

- slower

- bloated

Instead

serialize manually.

Example

```java
buffer.putInt(id);

buffer.putLong(offset);

buffer.putInt(length);
```

Reading

```java
buffer.getInt();

buffer.getLong();
```

Much faster.

---

# 11. Memory Mapping

Large posting files should not be copied into RAM.

Instead

use

```
MappedByteBuffer
```

Advantages

Operating system

handles paging.

The JVM only accesses required regions.

Memory usage remains low.

Pseudo-code

```java
FileChannel channel

↓

map()

↓

MappedByteBuffer
```

This is exactly how many high-performance databases work.

---

# 12. Reading Posting Lists

User searches

```
algorithm
```

Flow

```
Dictionary

↓

Offset

↓

Seek inside postings.bin

↓

Read posting list

↓

Return results
```

Only one posting list enters RAM.

Everything else stays on disk.

---

# Example

```
algorithm

↓

Offset

4,198,220

↓

Read

512 bytes

↓

Done
```

This is why searches remain fast.

---

# 13. Incremental Index Updates

Rebuilding

the entire index

after every crawl

is unacceptable.

Instead

new pages enter

```
Temporary Segment
```

Architecture

```
Main Index

+

Delta Index

↓

Periodic Merge
```

Very similar to

LSM Trees.

---

Search

checks

```
Main Index

AND

Delta Index
```

Later

both merge.

---

# 14. Write Ahead Log

Suppose

power fails

while writing

```
postings.bin
```

The index becomes corrupted.

Instead

every modification first enters

```
wal.log
```

Only after

successful disk write

is the WAL cleared.

Flow

```
Operation

↓

Write WAL

↓

Write Index

↓

Delete WAL Entry
```

---

# 15. Crash Recovery

Startup

checks

```
wal.log
```

If empty

continue.

Otherwise

Replay operations.

Flow

```
Startup

↓

Read WAL

↓

Replay

↓

Verify

↓

Continue
```

Crash recovery now takes only seconds.

---

# 16. Index Loading

System startup

```
metadata.bin

↓

dictionary.bin

↓

Ready
```

Posting lists

are

NOT

loaded.

Documents

are

NOT

loaded.

Only metadata.

Startup time remains very small.

---

# 17. Memory Management

Memory hierarchy

```
CPU Cache

↓

RAM

↓

Memory Mapped Files

↓

SSD
```

Posting lists

are cached

using

```
LRU Cache
```

Frequently searched words

remain in memory.

Rare terms

are evicted.

---

Example

```
computer

java

python
```

Always cached.

```
hippopotamus
```

Probably not.

---

# 18. Future Optimizations

Later improvements

- Variable-byte encoding

- Gamma encoding

- SIMD decoding

- Block compression

- Bloom filters

- Parallel disk reading

- Prefetching

- ZSTD compression

None are required initially.

The architecture is already prepared.

---

# 19. Java Package Structure

```
storage/

├── dictionary/
│       DictionaryReader.java
│       DictionaryWriter.java
│       DictionaryEntry.java
│
├── postings/
│       PostingReader.java
│       PostingWriter.java
│
├── documents/
│       DocumentReader.java
│       DocumentWriter.java
│
├── metadata/
│       MetadataReader.java
│       MetadataWriter.java
│
├── wal/
│       WriteAheadLog.java
│
├── cache/
│       PostingCache.java
│
├── mmap/
│       MemoryMappedIndex.java
│
└── serialization/
        BinaryWriter.java
        BinaryReader.java
```

Every package is responsible for exactly one aspect of persistence.

No package depends on indexing logic or ranking algorithms. The storage engine is intentionally generic, making it reusable for future projects.

---

# Storage Engine Summary

At this point, MiniGoogle is no longer just an algorithm running in memory. It has become a persistent search engine capable of storing millions of documents while keeping RAM usage low. By separating the dictionary, postings, document metadata, and recovery log into dedicated binary files, the engine achieves fast startup, efficient random access, incremental updates, and resilience against crashes.

# Chapter 04 — Query Engine

---

# Table of Contents

1. Introduction
2. Query Lifecycle
3. Query Parser
4. Query Language
5. Lexical Analysis
6. Syntax Tree
7. Query Planning
8. Posting List Retrieval
9. Boolean Query Processing
10. Phrase Query Processing
11. Wildcard Search
12. Prefix Search
13. Fuzzy Search
14. Candidate Generation
15. Result Aggregation
16. Query Cache
17. Query Optimizations
18. Complexity Analysis
19. Package Structure

---

# 1. Introduction

The Query Engine is the heart of the search engine.

The crawler discovers information.

The index stores information.

The query engine transforms a human request into efficient operations over the inverted index.

For example, when a user searches

```
distributed search engine
```

the engine should **not**

- scan every document
- compare every string
- iterate through every webpage

Instead, it should perform only a handful of disk accesses and retrieve the answer in milliseconds.

---

# 2. Query Lifecycle

Every query follows exactly the same pipeline.

```
User Query

↓

Lexical Analysis

↓

Parsing

↓

Query Tree

↓

Optimization

↓

Posting Retrieval

↓

Candidate Generation

↓

Ranking

↓

Snippet Generation

↓

Frontend
```

Each stage has exactly one responsibility.

---

# 3. Query Parser

The parser converts raw text into an internal representation.

Input

```
(machine learning OR AI) AND google
```

Output

```
          AND
         /   \
       OR    google
      /  \
machine learning
      AI
```

The parser never executes queries.

It only understands their structure.

---

# 4. Query Language

MiniGoogle supports the following syntax.

Simple keyword

```
google
```

Multiple keywords

```
google search engine
```

Boolean AND

```
google AND java
```

Boolean OR

```
google OR microsoft
```

Negation

```
google NOT ads
```

Phrase search

```
"machine learning"
```

Parentheses

```
(java OR kotlin) AND android
```

Wildcard

```
algorith*
```

Future versions may include

```
site:

filetype:

intitle:

language:
```

---

# 5. Lexical Analysis

The lexer transforms characters into tokens.

Example

Input

```
(java OR python) AND compiler
```

Output

```
LEFT_PAREN

WORD(java)

OR

WORD(python)

RIGHT_PAREN

AND

WORD(compiler)
```

Implementation

```java
enum TokenType{

    WORD,

    PHRASE,

    AND,

    OR,

    NOT,

    LEFT_PAREN,

    RIGHT_PAREN

}
```

The lexer does not understand precedence.

Only the parser does.

---

# 6. Syntax Tree

The parser constructs an Abstract Syntax Tree (AST).

Example

```
java AND compiler
```

becomes

```
      AND
     /   \
 java   compiler
```

Nested expressions

```
(java OR scala) AND compiler
```

become

```
          AND
         /   \
       OR   compiler
      / \
   java scala
```

Each node implements

```java
interface QueryNode
```

Examples

```
WordNode

PhraseNode

AndNode

OrNode

NotNode
```

---

# 7. Query Planning

The AST is optimized before execution.

Example

```
java AND distributed
```

Suppose

```
java

appears in

3 million documents

distributed

appears in

12 thousand documents
```

The planner executes

```
distributed

first
```

This drastically reduces comparisons.

Rule

Always begin with the smallest posting list.

---

# 8. Posting Retrieval

Each term

```
compiler
```

is looked up in

```
dictionary.bin
```

Result

```
Offset

↓

Read posting list

↓

Posting objects
```

No unnecessary disk access occurs.

---

# 9. Boolean Query Processing

Boolean operations are performed directly on posting lists.

Suppose

```
java

↓

1

5

7

12

20
```

and

```
compiler

↓

5

8

12

15

20
```

AND

```
↓

5

12

20
```

Implementation

Two-pointer algorithm.

Pseudo-code

```
i=0

j=0

while

i<n

and

j<m

    compare IDs

    advance pointer
```

Complexity

```
O(n+m)
```

No hashing required.

---

OR

```
java

↓

1

5

8

10
```

compiler

↓

2

5

9

10
```

Result

```
1

2

5

8

9

10
```

Merge while preserving order.

---

NOT

Universe

```
1

2

3

4

5

6
```

Posting list

```
2

5
```

Result

```
1

3

4

6
```

---

# 10. Phrase Query Processing

Phrase queries require positional indexes.

Example

```
"machine learning"
```

Suppose

```
machine

↓

doc7

positions

5

18

90
```

learning

↓

doc7

positions

6

91
```

Algorithm

Compare positions.

```
5

↓

6

```

Distance

```
1
```

Phrase matched.

```
18

↓

91
```

Distance

```
73
```

Reject.

Complexity

Linear in the number of positions.

---

# 11. Wildcard Search

Example

```
alg*
```

Searching the dictionary linearly would be expensive.

Instead

construct

```
Trie
```

Dictionary

```
algorithm

algebra

alpine

apple
```

Trie

```
a

↓

l

↓

g

↓

...
```

Traversal

```
alg

↓

DFS

↓

Every completion
```

Complexity

```
O(length of prefix)
```

---

# 12. Prefix Search

Autocomplete uses the same trie.

Input

```
mach
```

Suggestions

```
machine

machinery

machinist
```

Suggestions ranked by

- popularity
- frequency
- previous searches

---

# 13. Fuzzy Search

Users make mistakes.

Example

```
algoritm
```

Expected

```
algorithm
```

Solution

BK-Tree.

Each node

```
word
```

Children

organized by

Levenshtein distance.

Example

```
book

↓

distance

1

↓

books
```

Search

```
algorithm

distance ≤2
```

Candidates returned.

Much faster than comparing every word.

---

# 14. Candidate Generation

Every posting list retrieved

↓

Merged

↓

Candidate Set

Example

```
java

↓

5000 docs

compiler

↓

900 docs

↓

Intersection

↓

612 docs
```

Only these 612 documents continue.

Everything else is ignored.

---

# 15. Result Aggregation

Candidate documents

↓

Ranking Engine

↓

Scores

↓

Priority Queue

↓

Top 20

Never sort every document.

Instead

Maintain

```java
PriorityQueue<Result>
```

Size

```
20
```

Complexity

```
O(n log k)

k=20
```

Very efficient.

---

# 16. Query Cache

Popular queries repeat.

```
weather

google

youtube

chatgpt
```

Store

```
Query

↓

Top Results
```

Implementation

```
LinkedHashMap
```

configured as

```
LRU Cache
```

Capacity

```
1000
```

Oldest entries removed automatically.

---

# 17. Query Optimizations

Optimization 1

Execute shortest posting lists first.

---

Optimization 2

Cache dictionary.

---

Optimization 3

Cache posting lists.

---

Optimization 4

Parallel retrieval.

If

```
java

python

compiler
```

Retrieve all posting lists concurrently.

---

Optimization 5

Skip pointers.

Avoid unnecessary comparisons.

---

Optimization 6

Lazy loading.

Only load documents needed for the final page.

---

# 18. Complexity Analysis

Dictionary lookup

```
O(1)
```

Posting retrieval

```
O(1)

disk seek
```

Boolean AND

```
O(n+m)
```

Phrase search

```
O(total positions)
```

Trie lookup

```
O(prefix length)
```

BK-tree search

Approximately

```
O(log vocabulary)
```

Top-k selection

```
O(n log k)
```

Overall query latency target

```
<50 ms
```

---

# 19. Java Package Structure

```
query/

├── lexer/
│      Lexer.java
│      Token.java
│
├── parser/
│      Parser.java
│      ASTBuilder.java
│
├── ast/
│      QueryNode.java
│      AndNode.java
│      OrNode.java
│      NotNode.java
│      WordNode.java
│      PhraseNode.java
│
├── planner/
│      QueryPlanner.java
│
├── executor/
│      BooleanExecutor.java
│      PhraseExecutor.java
│      WildcardExecutor.java
│
├── trie/
│      Trie.java
│
├── bktree/
│      BKTree.java
│
├── cache/
│      QueryCache.java
│
└── result/
       SearchResult.java
```

Every package is isolated and independently testable.

---

# Query Engine Summary

The Query Engine transforms human-readable search expressions into highly optimized operations over the inverted index. Rather than scanning documents, it leverages specialized data structures—tries for prefixes, BK-trees for typo correction, positional indexes for phrase search, and optimized posting-list intersections—to minimize disk I/O and CPU work. By the time this chapter is complete, MiniGoogle has evolved from a passive index into a true search engine capable of understanding complex user queries efficiently.

---

# End of Chapter 04

# Chapter 05 — Ranking Engine

---

# Table of Contents

1. Introduction
2. Why Ranking Exists
3. Ranking Pipeline
4. Document Statistics
5. Term Frequency (TF)
6. Document Frequency (DF)
7. Inverse Document Frequency (IDF)
8. TF-IDF
9. BM25
10. Document Length Normalization
11. Multi-Term Queries
12. PageRank
13. Combining BM25 and PageRank
14. Score Normalization
15. Snippet Generation
16. Result Diversification
17. Future Ranking Signals
18. Package Structure

---

# 1. Introduction

Finding matching documents is only half of the problem.

Suppose the query is

```
distributed systems
```

Imagine

```
42,781

matching documents
```

Returning them alphabetically would be useless.

Returning them randomly would be even worse.

The ranking engine determines

> Which document should appear first?

This is arguably the most important subsystem of the entire search engine.

---

# 2. Why Ranking Exists

Suppose three documents contain

```
compiler
```

Document A

```
compiler

compiler

compiler

compiler

compiler
```

Document B

```
compiler
```

Document C

```
compiler

inside

one sentence

among

10,000 words
```

Clearly

```
A

>

B

>

C
```

The search engine must quantify this intuition mathematically.

---

# 3. Ranking Pipeline

```
Candidate Documents

↓

Compute TF

↓

Compute IDF

↓

Compute BM25

↓

Retrieve PageRank

↓

Normalize Scores

↓

Weighted Combination

↓

Priority Queue

↓

Top K Results
```

Notice

Ranking happens **after**

candidate generation.

Never compute BM25 for every document.

Only compute it for candidate documents.

---

# 4. Document Statistics

Before any scoring can occur

the engine stores global statistics.

Example

```
Total Documents

100,000
```

Average document length

```
420 words
```

Vocabulary size

```
315,000 words
```

Every document also stores

```
Document Length
```

These statistics are computed during indexing.

---

# 5. Term Frequency (TF)

Term Frequency measures

How important is this word inside this document?

Example

```
compiler

compiler

java

compiler

compiler
```

Frequency

```
compiler

↓

4
```

Simple TF

```
TF = frequency
```

Better

Logarithmic TF

```
TF = 1 + log(frequency)
```

This prevents very long documents from dominating.

---

# 6. Document Frequency (DF)

Suppose

```
100,000

documents
```

Word

```
computer

appears in

25,000
```

Then

```
DF(computer)=25,000
```

Common words

have

large DF.

Rare words

have

small DF.

---

# 7. Inverse Document Frequency (IDF)

Rare words should matter more.

Suppose

```
algorithm

appears in

100 documents
```

Very informative.

Now

```
the

appears in

99,900 documents
```

Almost useless.

IDF captures this.

Formula

```
IDF

=

log

(

N

/

DF

)
```

Where

```
N

=

number of documents
```

Example

```
N

100,000

DF

100

↓

IDF

≈6.9
```

Very important word.

---

# 8. TF-IDF

Classic search engines combine both.

```
Score

=

TF × IDF
```

Example

```
compiler

appears

10 times

inside one document

↓

High TF

Rare globally

↓

High IDF

↓

Excellent score
```

Although important historically,

TF-IDF has weaknesses.

Google does not use pure TF-IDF.

Neither will we.

---

# 9. BM25

BM25 is considered one of the best traditional ranking algorithms.

It improves TF-IDF by considering

- document length

- diminishing returns

- rare terms

- average corpus length

The BM25 score for one term is

```
IDF

×

((TF × (k1+1))

/

(TF

+

k1×

(1-b+b×

docLength/avgLength)))
```

Constants

```
k1

=

1.2

to

2.0
```

Usually

```
1.5
```

Parameter

```
b

=

0.75
```

These values are industry standards.

---

# Why BM25 Is Better

Suppose

Document A

```
500 words
```

Document B

```
15,000 words
```

Both contain

```
compiler

10 times
```

TF-IDF

Scores equally.

BM25

Recognizes

Document A

is much more focused.

---

# 10. Document Length Normalization

Long documents naturally contain more words.

Without normalization

Wikipedia would dominate every search.

Normalization prevents this.

Average document length

```
420 words
```

Current document

```
390 words
```

Very close.

Small adjustment.

Current document

```
15,000 words
```

Large penalty.

---

# 11. Multi-Term Queries

Suppose

```
distributed systems compiler
```

Score each term independently.

```
distributed

↓

BM25

1.82

systems

↓

2.41

compiler

↓

0.95
```

Final score

```
1.82

+

2.41

+

0.95

=

5.18
```

The total BM25 score is the sum of all term scores.

---

# 12. PageRank

BM25 measures

Text relevance.

But some pages are simply

more authoritative.

Example

Suppose

Wikipedia

is linked by

50,000 pages.

A personal blog

is linked by

2 pages.

The first is probably more trustworthy.

PageRank models this.

---

# The Web as a Graph

Every webpage

↓

Node

Every hyperlink

↓

Edge

```
A

↓

B

↓

C

↑

↓

D
```

Links become votes.

---

# Basic Idea

A page transfers its importance

to the pages it links to.

Suppose

```
Page A

Rank

10

Links

2 pages
```

Each destination receives

```
5
```

importance

before damping.

---

# Damping Factor

Users sometimes type URLs directly.

Google models this using

```
0.85
```

Formula

```
Rank

=

0.15

+

0.85×

Incoming Contributions
```

The computation repeats

until scores stabilize.

Usually

30–50 iterations

are enough.

---

# Implementation

Store

```
Outgoing Links

Incoming Links
```

Represent the web

using

```
Adjacency Lists
```

Pseudo-code

```
repeat

40 iterations

    for every page

        compute new rank

replace

old ranks
```

Complexity

```
O(E)

per iteration
```

Where

```
E

=

number of hyperlinks
```

---

# 13. Combining BM25 and PageRank

Text relevance

is not enough.

Authority

is not enough.

Combine both.

Formula

```
Final Score

=

0.75×

BM25

+

0.25×

PageRank
```

Weights

should remain configurable.

Different search engines

may prefer different values.

---

# 14. Score Normalization

BM25

may produce

```
13.2
```

PageRank

may produce

```
0.0048
```

Different scales.

Normalize both.

Simple Min-Max

```
(x-min)

/

(max-min)
```

or

Z-score normalization.

After normalization

weighted addition becomes meaningful.

---

# 15. Snippet Generation

The search result should show

not the beginning of the document,

but the most relevant part.

Example

Query

```
compiler optimization
```

Document

```
...

Modern compiler optimization techniques include...

...

```

Algorithm

Locate query terms.

Extract

approximately

```
150 characters
```

around them.

Highlight keywords.

Output

```
...Modern **compiler**
optimization techniques...
```

This greatly improves usability.

---

# 16. Result Diversification

Imagine

Top 10

results

all from

```
wikipedia.org
```

Poor user experience.

Instead

Limit

maximum consecutive results

per domain.

Example

```
Wikipedia

Wikipedia

Wikipedia

↓

Wikipedia

StackOverflow

MIT

Wikipedia
```

This increases diversity.

---

# 17. Future Ranking Signals

MiniGoogle intentionally excludes

Machine Learning ranking.

However

future versions could include

- Click-through rate

- Freshness

- Domain reputation

- Semantic similarity

- Neural embeddings

- BERT reranking

- User preferences

- Geographic relevance

The architecture should make adding new signals easy.

Every ranking component should implement

```java
RankingSignal
```

allowing the pipeline to remain modular.

---

# 18. Java Package Structure

```
ranking/

├── bm25/
│      BM25Calculator.java
│      BM25Parameters.java
│
├── pagerank/
│      PageRankCalculator.java
│      GraphBuilder.java
│
├── normalization/
│      ScoreNormalizer.java
│
├── fusion/
│      ScoreFusion.java
│
├── snippet/
│      SnippetGenerator.java
│
├── diversification/
│      DiversityFilter.java
│
├── model/
│      RankedDocument.java
│      Score.java
│
└── pipeline/
       RankingPipeline.java
```

Every ranking algorithm is isolated behind a common interface.

```java
public interface RankingAlgorithm {

    double score(Document document, Query query);

}
```

This allows future algorithms to be added without changing the rest of the search engine.

---

# Final Ranking Pipeline

```
Query

↓

Candidate Documents

↓

BM25

↓

PageRank

↓

Normalize

↓

Weighted Fusion

↓

Domain Diversification

↓

Snippet Generation

↓

Top 20 Results

↓

Frontend
```

At this point, MiniGoogle has all the core pieces required to retrieve and rank relevant information. However, it still runs on a single machine.

---

# End of Chapter 05

# Chapter 06 — Distributed Architecture

---

# Table of Contents

1. Introduction
2. Why Distribution?
3. Cluster Topology
4. Node Types
5. Cluster Startup
6. Service Discovery
7. Communication Protocol
8. Cluster Metadata
9. Sharding
10. Replication
11. Distributed Crawling
12. Distributed Index Construction
13. Distributed Query Execution
14. Scatter-Gather Pattern
15. Load Balancing
16. Failure Detection
17. Recovery
18. Consistency Model
19. Package Structure

---

# 1. Introduction

Up until now, MiniGoogle runs entirely on one machine.

That works for

```
10,000 pages
```

Maybe

```
100,000 pages
```

It completely breaks down for

```
100 million pages
```

No single machine has enough

- CPU
- RAM
- SSD
- Network bandwidth

to crawl, index and search the entire web.

Therefore, we distribute the workload across multiple independent machines.

---

# 2. Why Distribution?

Imagine we have

```
20 million webpages
```

One computer

```
RAM
32 GB

CPU
8 cores

SSD
1 TB
```

This machine will eventually become the bottleneck.

Instead

```
10 Machines

↓

320 GB RAM

80 CPU Cores

10 TB Storage
```

Almost every subsystem can now scale horizontally.

This is called

```
Horizontal Scaling
```

instead of

```
Vertical Scaling
```

---

# 3. Cluster Topology

MiniGoogle consists of several different node types.

```
                      Client
                         │
                         ▼
                Search Coordinator
                         │
      ┌──────────────────┼──────────────────┐
      ▼                  ▼                  ▼
 Index Node A      Index Node B      Index Node C
      ▲                  ▲                  ▲
      │                  │                  │
      └───────────Indexer Coordinator───────┘
                         ▲
                         │
               Crawl Coordinator
          ┌────────┼────────┐
          ▼        ▼        ▼
     Worker 1 Worker 2 Worker 3
```

Notice something important.

No node does everything.

Every node has exactly one responsibility.

---

# 4. Node Types

## Crawl Coordinator

Responsible for

- crawl scheduling
- robots.txt
- URL assignment
- duplicate prevention

---

## Crawl Worker

Responsible for

- downloading webpages
- parsing HTML
- extracting links

---

## Index Coordinator

Responsible for

- assigning documents
- managing shards
- tracking index versions

---

## Index Node

Responsible for

- storing posting lists
- serving search requests

---

## Search Coordinator

Responsible for

- receiving user queries
- forwarding requests
- merging results
- ranking final results

---

## Monitoring Node

Responsible for

- metrics
- logging
- cluster health
- alerts

---

# 5. Cluster Startup

Startup sequence matters.

Incorrect startup order

```
Workers

↓

Coordinator

↓

Index
```

Workers immediately fail.

Correct order

```
Coordinator

↓

Index Nodes

↓

Crawler

↓

Frontend
```

Each component waits until dependencies become available.

---

# 6. Service Discovery

How does a node know where another node is?

Hardcoding IP addresses is terrible.

Instead

Every node registers itself.

```
Node

↓

Coordinator

↓

Registration
```

Registration example

```json
{
    "nodeId": "index-03",
    "host": "10.0.0.13",
    "port": 8080,
    "type": "INDEX",
    "status": "ONLINE"
}
```

Coordinator maintains

```
Cluster Registry
```

Every node can now locate every other node.

---

# Cluster Registry

Internally

```
HashMap<NodeID, NodeInfo>
```

Example

```
index01

↓

10.0.0.2

↓

ONLINE
```

```
index02

↓

10.0.0.3
```

```
crawler04

↓

10.0.0.9
```

---

# 7. Communication Protocol

Nodes communicate using REST initially.

Example

```
POST

/search
```

Request

```json
{
    "query":"distributed systems"
}
```

Response

```json
{
    "results":[]
}
```

Later

REST can be replaced by

- gRPC

or

- custom TCP protocol

without changing the architecture.

---

# Internal APIs

Crawler

```
POST

/document
```

Index Node

```
POST

/index
```

Search

```
POST

/query
```

Heartbeat

```
POST

/heartbeat
```

Registration

```
POST

/register
```

---

# 8. Cluster Metadata

Coordinator stores

```
Node List

Shard List

Replica List

Health Status

Versions

Statistics
```

Representation

```java
class ClusterState{

    List<NodeInfo> nodes;

    List<ShardInfo> shards;

}
```

This object becomes the brain of the cluster.

---

# 9. Sharding

A search index becomes too large.

Split it.

Instead of

```
Entire Index

↓

Machine
```

Use

```
Shard A

↓

Machine 1

Shard B

↓

Machine 2

Shard C

↓

Machine 3
```

Each shard owns part of the vocabulary.

---

# Sharding Strategies

Several options exist.

---

## Strategy 1

Alphabetical

```
A-F

↓

Node 1

G-M

↓

Node 2

N-Z

↓

Node 3
```

Simple

Not balanced.

---

## Strategy 2

Hash Sharding

```
hash(term)

↓

mod

number_of_shards
```

Example

```
hash("compiler")

↓

482193

↓

482193 % 8

↓

Shard 1
```

Balanced.

Simple.

Chosen implementation.

---

## Strategy 3

Document Sharding

Instead of

splitting terms

split documents.

```
Document 1-1000

↓

Node A

1001-2000

↓

Node B
```

Used by many real search engines.

MiniGoogle will support this later.

---

# 10. Replication

One copy is dangerous.

If a machine dies

the shard disappears.

Instead

```
Primary

↓

Replica 1

↓

Replica 2
```

Every shard

has

```
3 copies
```

One primary

Two replicas.

---

Example

```
Shard 5

↓

Index01

Primary

↓

Index07

Replica

↓

Index11

Replica
```

---

# 11. Distributed Crawling

Without coordination

three workers could crawl

the same page.

```
google.com

↓

Worker1

Worker2

Worker3
```

Wasteful.

Instead

Coordinator assigns work.

```
Coordinator

↓

Worker2

↓

google.com
```

Only one worker receives the task.

---

# Crawl Queue

```
Frontier

↓

Coordinator

↓

Worker
```

Workers never pull directly from each other.

---

# 12. Distributed Index Construction

Each crawler sends

documents

to the appropriate shard.

Algorithm

```
Document

↓

Extract Terms

↓

Hash Terms

↓

Determine Shard

↓

Send Posting Updates
```

Example

```
compiler

↓

hash()

↓

Shard 3
```

Only

Shard 3

stores that posting list.

---

# 13. Distributed Query Execution

Suppose user searches

```
compiler optimization
```

Coordinator

↓

Determines shards

↓

Sends query

↓

Each shard computes

local ranking

↓

Returns

Top 50

↓

Coordinator merges

↓

Top 20

This is called

```
Distributed Search
```

---

# 14. Scatter-Gather Pattern

This is one of Google's most important architectural ideas.

Step 1

Scatter

```
Coordinator

↓

Node1

Node2

Node3

Node4
```

All nodes execute

in parallel.

Step 2

Gather

```
Node1

↓

Coordinator

↓

Merge
```

Latency

becomes

approximately

the slowest node,

not

the sum.

---

# Merge Algorithm

Suppose

Node1

```
A

10.2

B

9.1
```

Node2

```
C

10.8

D

8.4
```

Coordinator

merges

using

PriorityQueue

Final

```
C

A

B

D
```

Only

Top K

remain.

---

# 15. Load Balancing

Suppose

Node2

becomes overloaded.

Coordinator detects

```
CPU

95%
```

Future queries

redirected to

Replica

instead.

Simple strategy

Round Robin.

Later

Least Loaded.

Eventually

Latency Aware.

---

# 16. Failure Detection

Every node sends

Heartbeat

every

```
5 seconds
```

Example

```
POST

/heartbeat
```

Coordinator records

timestamp.

If

```
Current Time

-

Heartbeat

>

15 seconds
```

Node declared

```
OFFLINE
```

---

# 17. Recovery

Suppose

Index03

dies.

Coordinator

↓

Marks Offline

↓

Promotes Replica

↓

Future queries

ignore dead node

↓

Replacement node joins

↓

Replica rebuilt

The system never stops serving queries.

---

# 18. Consistency Model

MiniGoogle chooses

```
Eventual Consistency
```

Reason

Search engines

do not require

bank-level consistency.

If

a newly crawled page

appears

30 seconds later

that is acceptable.

This dramatically simplifies replication.

---

# Cluster Scaling

Adding machines should require

no code changes.

New Index Node

↓

Registers

↓

Receives shard assignment

↓

Starts serving traffic

Horizontal scaling should be almost automatic.

---

# 19. Java Package Structure

```
distributed/

├── coordinator/
│      SearchCoordinator.java
│      CrawlCoordinator.java
│      ClusterCoordinator.java
│
├── registry/
│      NodeRegistry.java
│      ClusterState.java
│
├── heartbeat/
│      HeartbeatManager.java
│
├── replication/
│      ReplicaManager.java
│
├── sharding/
│      ShardManager.java
│      HashSharder.java
│
├── communication/
│      RestClient.java
│      RestServer.java
│
├── balancing/
│      LoadBalancer.java
│
├── recovery/
│      RecoveryManager.java
│
└── model/
       NodeInfo.java
       ShardInfo.java
```

---

# Distributed Search Example

Imagine the user searches

```
"distributed search engine"
```

The execution flow is

```
Browser

↓

Search Coordinator

↓

Determine Shards

↓

Scatter Query

↓

Index Node 1

Index Node 2

Index Node 3

↓

Each Node Executes

Lexing

Parsing

Posting Retrieval

BM25

PageRank

↓

Top 50 Results

↓

Coordinator

↓

Merge Results

↓

Generate Snippets

↓

Return Top 20

↓

Browser
```

Notice that **no single machine ever processes the full search index**. Every node only searches its own shard, and the coordinator simply combines the best candidates. This architecture allows MiniGoogle to scale to hundreds of machines without changing the search algorithm itself.

---

# End of Chapter 06

The next chapter will cover what I consider the most important engineering chapter of the entire project:

# Chapter 07 — Network Protocol & Internal APIs

Instead of vaguely saying "the nodes communicate," we will design the exact REST APIs, JSON schemas, error codes, request lifecycle, retry policies, connection pooling, serialization format, idempotency guarantees, version negotiation, authentication between nodes, streaming responses, and timeout handling.

After that chapter, an engineer could implement every service independently and have them interoperate perfectly.

# Chapter 07 — Network Protocol & Internal APIs

---

# Table of Contents

1. Introduction
2. Communication Philosophy
3. Network Architecture
4. API Design Principles
5. Internal Services
6. API Gateway
7. Request Lifecycle
8. REST Endpoints
9. Request Models
10. Response Models
11. Error Handling
12. Serialization
13. Connection Management
14. Timeouts & Retries
15. Idempotency
16. Streaming Large Responses
17. Versioning
18. Internal Security
19. Observability
20. Java Package Structure

---

# 1. Introduction

A distributed system is fundamentally a collection of independent processes that communicate over a network.

Without a well-designed communication protocol, the cluster becomes fragile, difficult to debug, and nearly impossible to scale.

The goal of this chapter is to define **every interaction between services** in a precise and deterministic manner.

No service should ever need to know how another service is implemented.

Only the API contract matters.

---

# 2. Communication Philosophy

Every service follows four principles.

## Principle 1

Services are autonomous.

Each service owns its own memory and storage.

No service directly modifies another service's internal state.

---

## Principle 2

Communication occurs only through HTTP APIs.

Never share memory.

Never access another node's files.

Never expose internal data structures.

---

## Principle 3

Every request is stateless.

The receiver should be able to process the request without relying on previous requests.

---

## Principle 4

Every API must be deterministic.

The same request should always produce the same response unless the underlying data changes.

---

# 3. Network Architecture

```
                 Client

                   │

             HTTPS Request

                   │

                   ▼

        Search Coordinator API

      ┌──────────┼──────────┐

      ▼          ▼          ▼

 Index Node  Index Node  Index Node

      ▲

      │

Crawler / Indexer Services
```

Notice that the client **never communicates directly** with index nodes.

Only the coordinator is exposed.

---

# 4. API Design Principles

Every endpoint follows the same rules.

- JSON only
- UTF-8 encoding
- POST for search operations
- GET for read-only metadata
- Stateless
- Versioned
- Typed request models
- Typed response models

Example

```
POST

/api/v1/search
```

Future

```
/api/v2/search
```

Both APIs may coexist.

---

# 5. Internal Services

The distributed system exposes several logical services.

## Search Service

```
/search
```

Executes queries.

---

## Crawl Service

```
/crawl
```

Receives newly discovered pages.

---

## Index Service

```
/index
```

Updates posting lists.

---

## Cluster Service

```
/cluster
```

Registers nodes.

---

## Monitoring Service

```
/metrics
```

Returns health statistics.

---

# 6. API Gateway

The Search Coordinator acts as an API Gateway.

Responsibilities

- authenticate requests
- validate input
- distribute queries
- merge results
- return responses

Clients never communicate with storage nodes.

---

# 7. Complete Request Lifecycle

User searches

```
distributed search engine
```

The request flows through the system.

```
Browser

↓

Frontend

↓

Coordinator

↓

Parse Query

↓

Determine Target Shards

↓

Scatter Request

↓

Index Nodes

↓

Local Ranking

↓

Coordinator Merge

↓

Generate Snippets

↓

Return JSON

↓

Frontend Rendering
```

Every service performs exactly one stage.

---

# 8. REST Endpoints

## Search Endpoint

```
POST

/api/v1/search
```

---

Request

```json
{
  "query":"distributed systems",
  "page":1,
  "pageSize":20
}
```

---

Response

```json
{
  "executionTimeMs":18,

  "totalResults":14562,

  "results":[]
}
```

---

## Register Node

```
POST

/api/v1/cluster/register
```

---

Request

```json
{
    "nodeId":"index-05",

    "host":"10.0.0.8",

    "port":8080,

    "type":"INDEX"
}
```

---

Response

```json
{
    "status":"SUCCESS"
}
```

---

## Heartbeat

```
POST

/api/v1/cluster/heartbeat
```

---

Request

```json
{
    "nodeId":"crawler-3",

    "cpuUsage":0.42,

    "memoryUsage":0.61
}
```

---

## Index Update

```
POST

/api/v1/index/document
```

Payload

```
ParsedDocument
```

---

## Cluster State

```
GET

/api/v1/cluster/state
```

Response

Current nodes

Current shards

Current replicas

Cluster version

---

# 9. Request Models

Every endpoint receives immutable DTOs.

Example

```java
public record SearchRequest(

    String query,

    int page,

    int pageSize

){}
```

Response

```java
public record SearchResponse(

    long executionTime,

    int totalResults,

    List<SearchResult> results

){}
```

Using immutable records prevents accidental mutation.

---

# 10. Response Models

Search result

```java
public record SearchResult(

    String url,

    String title,

    String snippet,

    double score

){}
```

Every response contains

- execution time
- score
- title
- snippet
- URL

Nothing implementation-specific is leaked.

---

# 11. Error Handling

Every API returns consistent errors.

Example

Bad Request

```
400
```

```json
{
  "error":"INVALID_QUERY"
}
```

---

Node unavailable

```
503
```

```json
{
  "error":"NODE_UNAVAILABLE"
}
```

---

Internal error

```
500
```

```json
{
  "error":"INTERNAL_SERVER_ERROR"
}
```

---

Unknown endpoint

```
404
```

Every service uses identical error structures.

---

# 12. Serialization

Every message

↓

Java Record

↓

Jackson

↓

JSON

↓

Network

↓

JSON

↓

Java Record

No reflection-heavy frameworks.

No XML.

No Protocol Buffers (initially).

---

# 13. Connection Management

Creating TCP connections repeatedly is expensive.

Instead

each service maintains

```
HTTP Connection Pool
```

Reuse

```
Connection

↓

Request

↓

Request

↓

Request
```

Instead of

```
Connect

↓

Disconnect

↓

Connect

↓

Disconnect
```

This significantly reduces latency.

---

# 14. Timeouts & Retries

Every network call has a timeout.

Example

```
Connection Timeout

2 s

Read Timeout

5 s
```

If a request fails

Retry

```
1 s

↓

2 s

↓

4 s

↓

8 s
```

Exponential backoff prevents flooding unhealthy nodes.

---

# 15. Idempotency

Some requests may be retried.

Imagine

```
POST /index/document
```

Network fails after indexing.

Retry occurs.

Without protection

Document indexed twice.

Solution

Every request carries

```
Request ID

(UUID)
```

Processed IDs stored temporarily.

Duplicate requests ignored.

---

# 16. Streaming Large Responses

Suppose

```
Top

5000

results
```

Never send everything at once.

Instead

Pagination

```
page=1

pageSize=20
```

Future optimization

Chunked Transfer Encoding

or

Server-Sent Events

for progressive streaming.

---

# 17. Versioning

Eventually APIs evolve.

Instead of breaking compatibility

```
/api/v1/search

/api/v2/search
```

Older clients continue working.

New clients gain additional features.

Versioning is mandatory from day one.

---

# 18. Internal Security

Although MiniGoogle is not exposed publicly,

internal communication should still be protected.

Every node derives

```
Cluster Token = SHA-256(sharedSecret : nodeId)
```

From a shared cluster secret.

Every request includes

```
Authorization: Bearer <token>
X-Node-Id: <sourceNodeId>
```

An `AuthFilter` on every internal endpoint validates the token.

The token is derived from the claimed node ID,

so peers can authenticate before membership exists.

A valid token for a different source node ID is rejected (403).

Unauthorized nodes cannot join the cluster.

Unimplemented: mutual TLS remains future work.

---

# 19. Observability

Every request generates structured logs.

Example

```
Request ID

↓

Node

↓

Endpoint

↓

Latency

↓

Status
```

Log example

```text
2027-02-14T10:15:42Z

Request

92af...

Search

18 ms

200 OK
```

Metrics exposed

```
Average Query Latency

Queries / Second

CPU Usage

Memory Usage

Shard Size

Heartbeat Delay
```

This data powers dashboards and simplifies debugging.

---

# 20. Java Package Structure

```
network/

├── api/
│      SearchController.java
│      ClusterController.java
│      IndexController.java
│
├── dto/
│      SearchRequest.java
│      SearchResponse.java
│      SearchResult.java
│      ErrorResponse.java
│
├── client/
│      ClusterClient.java
│      SearchClient.java
│      IndexClient.java
│
├── serialization/
│      JsonSerializer.java
│
├── security/
│      TokenValidator.java
│
├── retry/
│      RetryPolicy.java
│
├── monitoring/
│      MetricsCollector.java
│
└── util/
       RequestIdGenerator.java
```

Every package represents one networking concern and remains independent of crawling, indexing, and ranking.

---

# End-to-End Request Example

Suppose the user searches

```
"distributed search engine"
```

The complete execution is

```
Browser

↓

POST /api/v1/search

↓

Search Coordinator

↓

Parse Request

↓

Generate Request ID

↓

Scatter to 8 Index Nodes

↓

Each Node

Retrieve Posting Lists

↓

Execute BM25

↓

Retrieve PageRank

↓

Return Local Top 50

↓

Coordinator Merge

↓

Generate Snippets

↓

Create JSON Response

↓

Frontend

↓

Render Results
```

From the user's perspective, this entire process should complete in under **50 milliseconds** for a warm cache, despite involving multiple machines.

---

# Chapter Summary

At this point, MiniGoogle has a well-defined communication layer. Every service knows how to discover other nodes, exchange structured messages, recover from failures, and expose stable APIs. This separation allows independent development and deployment of crawlers, indexers, coordinators, and search nodes without coupling their implementations.

---

# End of Chapter 07

The next chapter will cover **Distributed File Storage & Index Sharding**, where we move beyond simple binary files and design how shards are physically distributed across machines, replicated, balanced, migrated, compacted, and recovered. This chapter introduces concepts inspired by the Google File System (GFS) while remaining lightweight enough to implement from scratch without external distributed storage frameworks.

# Chapter 08 — Distributed Storage & Index Sharding

---

# Table of Contents

1. Introduction
2. Why Distributed Storage?
3. Storage Architecture
4. What is a Shard?
5. Shard Assignment
6. Physical Layout
7. Segment Files
8. Index Segments
9. Segment Lifecycle
10. Shard Replication
11. Leader-Follower Replication
12. Shard Metadata
13. Rebalancing
14. Adding New Nodes
15. Removing Failed Nodes
16. Segment Merging
17. Read Path
18. Write Path
19. Compaction
20. Package Structure

---

# 1. Introduction

Until now, every index has been stored as a collection of binary files on a single machine.

```
dictionary.bin

postings.bin

documents.bin
```

This works.

Until the index becomes too large.

Professional search engines never store the entire index on one machine.

Instead, they partition it into **shards**.

Each shard behaves like an independent miniature search engine.

---

# 2. Why Distributed Storage?

Imagine our corpus reaches

```
1 Billion Documents
```

Average index size

```
2 KB/document
```

Total index

```
≈2 TB
```

No single SSD should store everything.

Instead

```
2 TB

↓

64 shards

↓

32 GB each
```

Now each shard fits comfortably on one machine.

---

# 3. Storage Architecture

```
                    Search Cluster

                         │

        ┌────────────────┼────────────────┐

        ▼                ▼                ▼

    Index Node 1     Index Node 2     Index Node 3

        │                │                │

   Shard 0,1,2      Shard 3,4,5      Shard 6,7,8
```

Each node owns several shards.

No node owns the complete index.

---

# 4. What is a Shard?

A shard is a completely independent index.

Each shard contains

```
dictionary.bin

postings.bin

documents.bin

metadata.bin
```

Exactly the same structure as Chapter 03.

The difference is that

each shard stores only **part of the corpus**.

---

Example

```
Entire Index

↓

Shard 0

↓

Documents

1–50,000
```

```
Shard 1

↓

50,001–100,000
```

---

# 5. Shard Assignment

How do we decide where a document belongs?

Several possibilities exist.

---

## Strategy 1

Sequential

```
First 100,000 docs

↓

Shard 0
```

Bad.

Future insertions become uneven.

---

## Strategy 2

Random

Good distribution.

Poor locality.

---

## Strategy 3

Hash(Document ID)

```
hash(documentId)

↓

mod

number_of_shards
```

Example

```
Document

834922

↓

hash()

↓

27

↓

Shard 27
```

Chosen implementation.

Balanced.

Deterministic.

Simple.

---

# 6. Physical Layout

Each node stores

```
/data

    /shard-0

        dictionary.bin

        postings.bin

        documents.bin

    /shard-4

    /shard-7
```

Every shard lives inside its own directory.

Moving a shard becomes as simple as copying one folder.

---

# 7. Segment Files

Large indexes should never be rewritten entirely.

Instead

Every indexing session creates

a **new immutable segment**.

```
Shard 0

↓

Segment A

Segment B

Segment C
```

Each segment contains

```
dictionary

postings

documents
```

Segments never change after creation.

---

# 8. Index Segments

Instead of

```
One Huge Index
```

we have

```
Segment 1

+

Segment 2

+

Segment 3

+

Segment 4
```

Searching

means

searching every segment

then merging results.

This is the same idea used by Lucene.

---

# 9. Segment Lifecycle

```
Crawler

↓

Indexer

↓

Create Segment

↓

Persist Segment

↓

Register Segment

↓

Searchable

↓

Merged Later

↓

Deleted
```

Notice

No existing segment is modified.

Only new ones are added.

---

# 10. Shard Replication

Every shard should exist

on multiple machines.

```
Shard 12

↓

Node A

Primary
```

```
↓

Node D

Replica
```

```
↓

Node F

Replica
```

Replication protects against

- SSD failures
- machine crashes
- maintenance

---

# 11. Leader-Follower Replication

Each shard elects

one leader.

```
Leader

↓

Receives Writes
```

Followers

```
Receive Replication
```

Reads

can come from

leader

or

followers.

Writes

always go

to the leader.

---

Write Flow

```
Indexer

↓

Leader

↓

Follower 1

↓

Follower 2

↓

Acknowledgement
```

Only after replication

is the write considered complete.

---

# 12. Shard Metadata

Every shard maintains metadata.

```java
class ShardMetadata{

    int shardId;

    UUID leader;

    List<UUID> replicas;

    long documentCount;

    long sizeInBytes;

    long version;

}
```

The Search Coordinator caches this information.

---

# 13. Rebalancing

Suppose

```
Node A

stores

15 shards
```

Node B

stores

4 shards.

The cluster becomes unbalanced.

The coordinator detects this.

```
Move

Shard 9

↓

Node B
```

After transfer

```
Node A

↓

14 shards

Node B

↓

5 shards
```

Repeated until balanced.

---

# 14. Adding New Nodes

Imagine

```
Cluster

↓

4 Nodes
```

A fifth node joins.

Flow

```
Register

↓

Heartbeat

↓

Empty Node

↓

Coordinator

↓

Assign Shards

↓

Copy Segments

↓

Ready
```

No downtime.

---

# 15. Removing Failed Nodes

Suppose

```
Node 3

fails
```

Coordinator detects

missing heartbeats.

```
Shard Leader

↓

Replica Promotion
```

Example

```
Leader

↓

Offline
```

Replica

↓

New Leader

Queries continue.

---

# 16. Segment Merging

Too many segments slow searches.

Example

```
Shard

↓

83 segments
```

Searching

83 indexes

is inefficient.

Background thread performs

```
Merge

↓

4 segments

↓

1 larger segment
```

Old segments deleted afterward.

---

Merge Process

```
Segment A

+

Segment B

↓

Merge Posting Lists

↓

Merge Dictionaries

↓

Merge Metadata

↓

New Segment
```

Everything occurs

offline.

Queries continue uninterrupted.

---

# 17. Read Path

Suppose

user searches

```
compiler
```

Execution

```
Coordinator

↓

Shard 0

↓

Segment A

↓

Posting List
```

```
↓

Segment B

↓

Posting List
```

```
↓

Merge Local Results
```

Repeat for every shard.

Coordinator merges everything.

---

# 18. Write Path

Crawler discovers

```
New Page
```

↓

Indexer

↓

Determine Shard

↓

Build Posting Lists

↓

Create New Segment

↓

Persist

↓

Register

↓

Replicate

↓

Searchable

Notice

Old segments remain untouched.

---

# 19. Compaction

Deleted pages

leave

holes.

Example

```
Doc

4

Deleted

↓

Gap
```

Eventually

many gaps appear.

Compaction

creates

```
New Clean Segment
```

without deleted entries.

Benefits

- smaller index
- faster queries
- less fragmentation

Compaction runs

only in the background.

---

# 20. Java Package Structure

```
storage/

├── shard/
│      Shard.java
│      ShardManager.java
│      ShardMetadata.java
│
├── segment/
│      Segment.java
│      SegmentWriter.java
│      SegmentReader.java
│      SegmentMerger.java
│
├── replication/
│      ReplicationManager.java
│      ReplicaState.java
│
├── balancing/
│      Rebalancer.java
│
├── compaction/
│      CompactionManager.java
│
├── migration/
│      ShardMigrator.java
│
└── filesystem/
       StorageLayout.java
```

Every component focuses on a single responsibility, making shard management modular and independently testable.

---

# Complete Storage Flow

```
Crawler

↓

ParsedDocument

↓

Indexer

↓

Determine Shard

↓

Create Immutable Segment

↓

Persist

↓

Replicate

↓

Register Metadata

↓

Segment Searchable

↓

Background Merge

↓

Compaction

↓

Long-Term Storage
```

Notice that indexing never blocks searching. New segments become immediately searchable while older segments continue serving requests until they are merged away. This immutable-segment design minimizes locking, improves reliability, and simplifies crash recovery.

---

# Design Decisions

## Why Immutable Segments?

Instead of modifying an existing posting list, MiniGoogle always creates a new segment.

Advantages

- No file locking
- Crash-safe writes
- Easy rollback
- Background merges
- Better concurrency

---

## Why Document Sharding?

Unlike term-based sharding, document sharding allows every query to execute independently on each shard using the exact same algorithms. Each shard computes its own local top-k results, making distributed search simple and highly parallel.

---

## Why Leader-Follower Replication?

It avoids conflicting writes while allowing read scalability. Since search workloads are overwhelmingly read-heavy, follower replicas significantly increase throughput with minimal complexity.

---

# End of Chapter 08

The next chapter is one of the most algorithmically interesting parts of the project:

# Chapter 09 — Distributed Query Execution & Scatter-Gather Engine

We will design the entire distributed execution pipeline, including:

- Parallel query execution
- Local top-k algorithms
- Global top-k merge
- Query scheduling
- Thread pools
- Backpressure
- Partial failures
- Slow-node mitigation
- Time budgets
- Early termination algorithms (WAND/Block-Max WAND)
- Network-efficient result merging
- Distributed caching

This chapter will make MiniGoogle behave much more like a real production search engine capable of serving thousands of concurrent queries per second.

# Chapter 09 — Distributed Query Execution & Scatter-Gather Engine

---

# Table of Contents

1. Introduction
2. Query Execution Philosophy
3. End-to-End Query Lifecycle
4. Coordinator Responsibilities
5. Query Context
6. Distributed Execution Pipeline
7. Scatter Phase
8. Local Execution
9. Local Top-K Algorithm
10. Gather Phase
11. Global Merge Algorithm
12. Parallel Execution Model
13. Thread Pool Design
14. Time Budget Management
15. Handling Slow Nodes
16. Partial Failures
17. Distributed Query Cache
18. Network Optimization
19. Early Termination (WAND)
20. Block-Max WAND
21. Future Optimizations
22. Package Structure

---

# 1. Introduction

This chapter transforms MiniGoogle from a collection of independent search nodes into **one coherent distributed search engine**.

A user submits

```
compiler optimization
```

The system must simultaneously

- contact every required shard
- execute searches in parallel
- merge thousands of results
- return the global Top-K

all within

```
≈50 ms
```

The challenge is no longer searching.

The challenge is coordinating many searches simultaneously.

---

# 2. Query Execution Philosophy

A distributed query should behave exactly like a local query.

The user should never know

- how many nodes exist
- where data is stored
- which node answered

The cluster should appear as

```
One Search Engine
```

even though internally

```
100 machines
```

may be working simultaneously.

---

# 3. Complete Query Lifecycle

```
Browser

↓

Search Coordinator

↓

Parse Query

↓

Optimize Query

↓

Determine Target Shards

↓

Scatter

↓

Parallel Local Search

↓

Local Ranking

↓

Return Top-K

↓

Global Merge

↓

Snippet Generation

↓

JSON Response

↓

Browser
```

Every stage has one responsibility.

---

# 4. Coordinator Responsibilities

The Search Coordinator never searches documents.

Instead it performs orchestration.

Responsibilities

- Parse query
- Generate Request ID
- Determine target shards
- Dispatch requests
- Collect responses
- Merge rankings
- Handle failures
- Return results

The coordinator is intentionally stateless.

---

# 5. Query Context

Every distributed search receives a context object.

```java
public class QueryContext {

    UUID requestId;

    Instant startTime;

    Duration timeout;

    Query query;

    int topK;

}
```

This object travels through every service.

It enables

- logging
- tracing
- cancellation
- timeout enforcement

---

# 6. Distributed Execution Pipeline

Suppose

```
128 shards
```

Query

```
distributed systems
```

Execution

```
Coordinator

↓

Select 128 shards

↓

Launch 128 requests

↓

Wait

↓

Merge

↓

Return
```

Notice

Every shard works independently.

No shard communicates with another.

---

# 7. Scatter Phase

The coordinator sends the query to every required shard.

```
Coordinator

↓

Shard 0

Shard 1

Shard 2

...

Shard 127
```

All requests begin simultaneously.

Never sequentially.

Pseudo-code

```java
for (Shard shard : shards)

    executor.submit(
        () -> search(shard)
    );
```

Latency becomes

```
Maximum(node latency)

instead of

Sum(node latencies)
```

---

# 8. Local Execution

Each index node performs

exactly the same pipeline.

```
Receive Query

↓

Lexer

↓

Parser

↓

Posting Retrieval

↓

Boolean Evaluation

↓

BM25

↓

PageRank

↓

Local Top-K
```

Nodes never know

other nodes exist.

---

# 9. Local Top-K Algorithm

Suppose

Shard 3

matches

```
320,000 documents
```

Sorting everything

is wasteful.

Instead

Maintain

```
PriorityQueue<Result>
```

Capacity

```
Top 50
```

Pseudo-code

```
for every candidate

    compute score

    if heap not full

        insert

    else

        compare

        replace if better
```

Complexity

```
O(n log k)
```

instead of

```
O(n log n)
```

---

# 10. Gather Phase

Every shard returns

```
Top 50
```

Suppose

```
128 shards
```

Total returned

```
6,400 documents
```

Coordinator merges

```
6400

↓

20
```

instead of

```
Millions

↓

20
```

Huge reduction.

---

# 11. Global Merge Algorithm

Each node returns

```
Sorted List
```

Coordinator performs

K-way merge.

Example

```
Node1

10.4

10.1

9.8
```

```
Node2

10.5

10.0

9.2
```

```
Node3

9.9

9.8

8.5
```

Coordinator

↓

Priority Queue

↓

Top 20

Complexity

```
O(k log n)

k

=

results

n

=

shards
```

Very efficient.

---

# 12. Parallel Execution Model

Coordinator owns

```
ExecutorService
```

Example

```java
ExecutorService executor =
Executors.newFixedThreadPool(64);
```

Every shard request

↓

Future

```java
Future<SearchResponse>
```

Coordinator waits

```java
future.get(timeout)
```

Thousands of searches

can execute simultaneously.

---

# 13. Thread Pool Design

Separate thread pools.

```
HTTP Requests

↓

16 threads
```

```
Merge

↓

4 threads
```

```
Background Tasks

↓

2 threads
```

Never mix workloads.

This prevents starvation.

---

# 14. Time Budget Management

Suppose

Target latency

```
50 ms
```

Allocate

```
5 ms

Parsing
```

```
25 ms

Search
```

```
10 ms

Merge
```

```
10 ms

Network
```

If a stage exceeds

its budget,

cancel remaining work.

---

# 15. Handling Slow Nodes

Suppose

127 nodes finish

in

```
15 ms
```

One node

takes

```
2 seconds
```

Should everyone wait?

No.

Coordinator

uses timeout.

```
Timeout

↓

Ignore slow node

↓

Return partial results
```

The search engine remains responsive.

---

# 16. Partial Failures

Suppose

```
Shard 9

offline
```

Coordinator

tries replica.

```
Primary

↓

Unavailable

↓

Replica

↓

Success
```

If every replica fails

mark shard unavailable.

Return

partial search

with warning logged.

The user should almost never notice.

---

# 17. Distributed Query Cache

Popular queries

repeat.

Coordinator stores

```
Query

↓

Merged Top-K
```

Example

```
weather

youtube

google

github
```

Future identical searches

avoid

cluster execution.

Implementation

```
ConcurrentHashMap

+

LRU eviction
```

---

# 18. Network Optimization

Never send unnecessary data.

Instead of

```
Entire Document
```

Return

```
Document ID

Score

Title

Snippet

URL
```

Document body

is never transmitted.

Network traffic remains minimal.

---

# 19. Early Termination (WAND)

Suppose

```
2 million candidates
```

Most

cannot possibly reach

Top 20.

WAND

(Weighted AND)

computes

upper score bounds.

If

```
Maximum Possible Score

<

Current Top-K Threshold
```

Skip the document entirely.

This avoids millions of useless BM25 computations.

---

## WAND Intuition

Imagine

Current Top 20 threshold

```
9.85
```

Remaining document

can score

at most

```
7.2
```

Immediately discard it.

No ranking needed.

This dramatically improves throughput.

---

# 20. Block-Max WAND

Improvement over WAND.

Posting lists

are divided into blocks.

Each block stores

```
Maximum Possible Score
```

Example

```
Block

1

↓

Max

12.4
```

```
Block

2

↓

Max

3.1
```

If threshold

```
9.5
```

Skip

entire

Block 2.

Instead of skipping

one document,

we skip

thousands.

Modern search engines heavily rely on this optimization.

---

# 21. Future Optimizations

Future improvements

- Asynchronous HTTP/2

- gRPC streaming

- Vectorized scoring

- SIMD BM25

- GPU ranking

- Learned indexes

- Adaptive caching

- Query prediction

- Distributed result compression

The architecture intentionally allows these to be added incrementally.

---

# 22. Java Package Structure

```
distributed-query/

├── coordinator/
│      DistributedSearchCoordinator.java
│      QueryDispatcher.java
│
├── execution/
│      LocalSearchExecutor.java
│      DistributedExecutor.java
│
├── merge/
│      GlobalResultMerger.java
│      KWayMerger.java
│
├── timeout/
│      TimeoutManager.java
│
├── cache/
│      DistributedQueryCache.java
│
├── wand/
│      WANDExecutor.java
│      BlockMaxWAND.java
│
├── scheduling/
│      QueryScheduler.java
│
└── model/
       QueryContext.java
       LocalSearchResponse.java
```

Each component performs exactly one task and communicates only through immutable request and response models.

---

# Example Execution

A user searches

```
"distributed search engine"
```

Cluster

```
64 shards
```

Execution timeline

```
0 ms

↓

Coordinator receives request

↓

2 ms

↓

Query parsed

↓

3 ms

↓

Scatter begins

↓

15 ms

↓

All shards searching simultaneously

↓

24 ms

↓

63 shards finished

↓

28 ms

↓

Last shard finished

↓

31 ms

↓

Global merge

↓

36 ms

↓

Snippet generation

↓

42 ms

↓

JSON serialization

↓

45 ms

↓

Response returned
```

The user experiences a single fast search, while internally dozens of machines cooperated in parallel.

---

# Chapter Summary

This chapter introduced the execution engine that ties the distributed system together. By using scatter-gather execution, parallel local ranking, efficient top-k merging, timeout management, and advanced early-termination techniques such as WAND and Block-Max WAND, MiniGoogle can answer large-scale distributed queries with low latency and high throughput.

At this point, the core distributed search pipeline is complete.

---

# End of Chapter 09

The next chapter will cover **Distributed Web Crawling at Scale**, where we redesign the crawler to operate across hundreds of machines. We will implement a distributed crawl frontier, URL scheduling, politeness policies, duplicate elimination using Bloom Filters, frontier persistence, incremental recrawling, crawl prioritization, and fault-tolerant coordination inspired by Google's original crawler architecture. This chapter connects the distributed execution engine back to the continuous acquisition of fresh web content.

# Chapter 10 — Distributed Web Crawling at Scale

---

# Table of Contents

1. Introduction
2. The Internet as a Graph
3. Distributed Crawling Goals
4. Cluster Architecture
5. Seed URLs
6. The Distributed Frontier
7. URL States
8. URL Assignment
9. Bloom Filters
10. URL Scheduler
11. Domain Queues
12. Politeness Policy
13. robots.txt Management
14. Distributed Workers
15. Failure Recovery
16. Incremental Crawling
17. Crawl Prioritization
18. Crawl Persistence
19. Performance Optimizations
20. Java Package Structure

---

# 1. Introduction

Our crawler from Chapter 01 worked well for a single machine.

However, Google's crawler does not crawl the web using one machine.

Imagine

```
1 Billion Pages
```

If one worker downloads

```
100 pages/sec
```

Total crawl time

```
≈115 days
```

Clearly unacceptable.

Instead

```
100 Workers

↓

10,000 pages/sec
```

Now

```
≈28 hours
```

The crawler becomes horizontally scalable.

---

# 2. The Internet as a Graph

The crawler views the web as a directed graph.

```
Homepage

↓

Products

↓

Product A

↓

Reviews
```

Each page

↓

Node

Each hyperlink

↓

Directed Edge

```
A

↓

B

↓

C

↓

D
```

The crawler's job is graph exploration.

---

# 3. Distributed Crawling Goals

The crawler should

- Never crawl the same page twice
- Respect every website
- Scale linearly
- Recover after crashes
- Continue indefinitely
- Prioritize important pages
- Discover new pages continuously

Unlike indexing,

crawling never truly finishes.

---

# 4. Cluster Architecture

```
                    Crawl Coordinator

                           │

      ┌────────────────────┼────────────────────┐

      ▼                    ▼                    ▼

 Frontier Server     URL Scheduler      Robots Manager

      │

      ▼

Distributed Frontier Queue

      │

┌─────┼─────┬─────┬─────┐

▼     ▼     ▼     ▼     ▼

Crawler Crawler Crawler Crawler Crawler

Worker Worker Worker Worker Worker
```

Every component has one responsibility.

---

# 5. Seed URLs

Every crawl begins with a small set of trusted URLs.

Example

```
https://wikipedia.org

https://github.com

https://mit.edu

https://stanford.edu
```

These are called

```
Seed URLs
```

Every discovered hyperlink expands the graph.

---

# 6. The Distributed Frontier

The frontier stores every URL waiting to be crawled.

Single-machine version

```
BlockingQueue
```

Distributed version

```
Coordinator

↓

Persistent Frontier

↓

Workers
```

The frontier becomes one logical queue distributed across the cluster.

---

## Frontier Entry

```java
class FrontierEntry {

    URI url;

    int priority;

    int depth;

    Instant discoveredAt;

    Instant nextAllowedFetch;

}
```

Notice

Priority is now explicit.

---

# 7. URL States

Every URL always belongs to one state.

```
DISCOVERED

↓

QUEUED

↓

ASSIGNED

↓

FETCHING

↓

FETCHED

↓

INDEXED
```

Possible failure

```
FAILED

↓

RETRY
```

State transitions are irreversible except retries.

---

# 8. URL Assignment

Coordinator assigns work.

```
Worker 1

↓

URL A
```

```
Worker 2

↓

URL B
```

```
Worker 3

↓

URL C
```

No URL may be assigned twice simultaneously.

---

Assignment Algorithm

```
Worker requests work

↓

Coordinator selects eligible URL

↓

Mark ASSIGNED

↓

Return task
```

---

# 9. Bloom Filters

Duplicate detection using

```
HashSet
```

works

until

```
500 million URLs
```

Memory usage explodes.

Instead

use

```
Bloom Filter
```

---

## What is a Bloom Filter?

A Bloom Filter is a probabilistic data structure.

It answers

```
"Have I probably seen this URL?"
```

Memory

```
Very Small
```

Time

```
O(k)

k = hash functions
```

False positives

Possible.

False negatives

Impossible.

---

## Example

Hash URL

```
https://google.com
```

Hash Functions

```
h1

↓

1281
```

```
h2

↓

89312
```

```
h3

↓

671
```

Set

three bits.

Later

same URL

↓

All bits already set

↓

Probably visited.

---

## Why False Positives Are Acceptable

Suppose

one page

is accidentally skipped.

The search engine loses

one page.

Suppose

duplicates are not filtered.

Millions of redundant requests occur.

The second problem is much worse.

---

# 10. URL Scheduler

Not every page deserves immediate crawling.

Example

```
CNN Homepage
```

changes

every few minutes.

```
Personal Blog
```

changes

once a year.

The scheduler computes

priority.

---

Priority Formula

```
Priority

=

Freshness

+

Domain Authority

+

Link Popularity

+

Recrawl Score
```

Higher score

↓

Earlier crawl.

---

# 11. Domain Queues

Never crawl

```
google.com
```

1000 times consecutively.

Instead

Maintain

```
Queue

per Domain
```

Example

```
google.com

↓

Queue
```

```
github.com

↓

Queue
```

```
mit.edu

↓

Queue
```

Scheduler alternates domains.

Benefits

- fairness
- politeness
- better Internet coverage

---

# 12. Politeness Policy

Every domain has

```
Last Request Time
```

Before downloading

compute

```
elapsed

=

now-lastRequest
```

If

```
elapsed

<

crawlDelay
```

Delay request.

Example

```
crawlDelay

=

2 seconds
```

Workers simply move on to another domain.

---

# 13. robots.txt Management

Downloading robots.txt repeatedly wastes bandwidth.

Maintain

```
Robots Cache
```

```
Domain

↓

Rules

↓

Expiration Time
```

When expired

download again.

Otherwise

reuse cached version.

---

# 14. Distributed Workers

Each worker executes

```
Receive URL

↓

Download

↓

Extract HTML

↓

Extract Links

↓

Normalize URLs

↓

Send Document

↓

Request Another URL
```

Workers never communicate directly.

All coordination happens through the coordinator.

---

# 15. Failure Recovery

Suppose

Worker 17

crashes while downloading.

Coordinator notices

heartbeat timeout.

Assigned URLs

return to

```
QUEUED
```

Another worker receives them.

No pages are permanently lost.

---

# 16. Incremental Crawling

The web changes constantly.

Rather than recrawling everything

compute

```
Next Crawl Time
```

Example

Wikipedia

```
30 minutes
```

University page

```
7 days
```

Archived PDF

```
1 year
```

Every URL stores

```java
Instant nextCrawl;
```

---

# 17. Crawl Prioritization

Not every page has equal value.

Possible signals

- PageRank
- Incoming Links
- Domain Reputation
- Update Frequency
- HTTP Change History

Example

```
github.com

Priority

98
```

```
randomblog.xyz

Priority

14
```

The scheduler always chooses the highest-priority eligible URL.

---

# 18. Crawl Persistence

Suppose

the cluster shuts down.

Without persistence

every frontier disappears.

Instead

Periodically

serialize

```
Frontier Queue

Visited Bloom Filter

Worker States

Robots Cache
```

Startup

↓

Restore

↓

Continue

Exactly where crawling stopped.

---

# 19. Performance Optimizations

Connection Pooling

Reuse TCP connections.

---

HTTP Compression

Accept

```
gzip
```

Downloads become significantly smaller.

---

Asynchronous Downloads

One worker

may manage

multiple outstanding requests.

---

Adaptive Scheduling

Workers dynamically request additional URLs when idle.

---

Priority Buckets

Instead of sorting millions of URLs

maintain several queues

```
Critical

High

Medium

Low
```

Insertion becomes constant time.

---

# 20. Java Package Structure

```
crawler/

├── coordinator/
│      CrawlCoordinator.java
│
├── frontier/
│      DistributedFrontier.java
│      FrontierEntry.java
│
├── scheduler/
│      UrlScheduler.java
│      DomainQueue.java
│
├── bloom/
│      BloomFilter.java
│      HashFunctions.java
│
├── robots/
│      RobotsCache.java
│      RobotsManager.java
│
├── worker/
│      CrawlWorker.java
│
├── persistence/
│      FrontierSnapshot.java
│
├── heartbeat/
│      WorkerHeartbeat.java
│
└── model/
       CrawlTask.java
```

---

# Complete Crawling Pipeline

```
Seed URLs

↓

Distributed Frontier

↓

Priority Scheduler

↓

Domain Queue

↓

Worker Assignment

↓

HTTP Download

↓

HTML Parser

↓

Extract Links

↓

Normalize URLs

↓

Bloom Filter

↓

New URLs

↓

Frontier

↓

Parsed Document

↓

Indexer
```

The crawler now operates as a continuously running distributed system that discovers, schedules, and revisits pages indefinitely while respecting website policies and efficiently utilizing cluster resources.

---

# Engineering Notes

## Why Bloom Filters?

A `HashSet<String>` storing hundreds of millions of URLs can consume tens of gigabytes of RAM. A Bloom Filter reduces memory usage dramatically while accepting a very small false-positive rate, making it a standard choice for large-scale crawlers.

---

## Why Domain Queues?

Without domain queues, one popular website could monopolize the crawler. Separating URLs by domain naturally enforces politeness and improves coverage across the web.

---

## Why Immutable Crawl States?

Representing every URL with explicit lifecycle states (`DISCOVERED → QUEUED → ASSIGNED → FETCHED → INDEXED`) simplifies recovery after failures, monitoring, and debugging.

---

# End of Chapter 10

The next chapter will cover **Monitoring, Metrics & Observability**, where we will build the operational side of MiniGoogle:

- Distributed logging
- Metrics collection
- Health dashboards
- Query tracing
- Performance profiling
- Cluster monitoring
- Alerting
- Benchmark framework
- Stress testing
- Capacity planning

This chapter will make the system production-ready and provide the tooling needed to operate and debug a distributed search engine at scale.

# Chapter 11 — Monitoring, Metrics & Observability

---

# Table of Contents

1. Introduction
2. Why Monitoring Matters
3. Observability Pillars
4. Metrics Collection
5. Logging Architecture
6. Distributed Tracing
7. Health Checks
8. Cluster Dashboard
9. Performance Profiling
10. Benchmark Framework
11. Alerting System
12. Capacity Planning
13. Automatic Scaling Metrics
14. Failure Investigation
15. Query Analytics
16. Long-Term Storage
17. Monitoring APIs
18. Package Structure
19. End-to-End Monitoring Pipeline

---

# 1. Introduction

A distributed system is useless if engineers cannot answer questions like

- Is the cluster healthy?
- Which node is overloaded?
- Why did query latency increase?
- Which shard is failing?
- How many pages are crawled per second?

Professional distributed systems spend enormous effort on monitoring.

The monitoring subsystem should answer every operational question in seconds.

---

# 2. Why Monitoring Matters

Imagine

```
64 Nodes
```

One node suddenly becomes slow.

Without monitoring

Users only experience

```
Search is slower.
```

No explanation.

With monitoring

```
Node 18

CPU

99%

Memory

96%

Disk

92%

Network

Healthy
```

The problem becomes immediately obvious.

---

# 3. The Three Pillars of Observability

Modern distributed systems rely on three complementary sources of information.

```
Metrics

↓

Numbers over time
```

```
Logs

↓

Individual events
```

```
Traces

↓

Complete request lifecycle
```

Together they explain

- What happened
- Why it happened
- Where it happened

---

# 4. Metrics Collection

Every service exports metrics.

Crawler

```
Pages Crawled / Second
```

Indexer

```
Documents Indexed / Second
```

Search Node

```
Queries / Second
```

Coordinator

```
Average Query Latency
```

Node

```
CPU

Memory

Disk

Network
```

Metrics are updated continuously.

---

## Metric Model

```java
public record Metric(

    String name,

    double value,

    Instant timestamp

){}
```

Every metric is timestamped.

---

# 5. Logging Architecture

Every significant event generates a structured log.

Example

```
Node Started

Shard Loaded

Query Executed

Crawler Failed

Replication Complete

Segment Merged
```

Example log

```text
2027-07-15T10:22:14Z

INFO

IndexNode-12

Loaded Shard 18

Duration=842ms
```

Logs are structured instead of free-form text.

---

## Log Levels

```
TRACE

↓

DEBUG

↓

INFO

↓

WARN

↓

ERROR

↓

FATAL
```

Production typically records

```
INFO

WARN

ERROR
```

---

# 6. Distributed Tracing

Suppose a search takes

```
210 ms
```

Where?

Coordinator?

Shard?

Network?

Ranking?

Tracing answers this.

Every request receives

```
Trace ID
```

Example

```
8f32aa71...
```

Every service propagates this identifier.

---

## Trace Example

```
Coordinator

↓

Shard 4

↓

Posting Retrieval

↓

Ranking

↓

Merge

↓

Response
```

Entire request becomes visible.

---

## Trace Span

Every operation creates a span.

```java
public record Span(

    UUID traceId,

    String operation,

    Instant start,

    Instant end

){}
```

Latency becomes measurable for every stage.

---

# 7. Health Checks

Every node exposes

```
GET

/api/v1/health
```

Response

```json
{
    "status":"UP",

    "cpu":0.42,

    "memory":0.61,

    "disk":0.37
}
```

Coordinator periodically checks every node.

---

Health States

```
UP

↓

DEGRADED

↓

DOWN
```

Nodes in

```
DOWN
```

receive no traffic.

---

# 8. Cluster Dashboard

The monitoring service aggregates metrics.

Dashboard

```
Cluster

↓

Node List

↓

Health

↓

Latency

↓

Shard Allocation

↓

Network

↓

Storage

↓

Crawl Progress
```

Example

```
Nodes

64/64 Healthy
```

```
Queries/sec

12,418
```

```
Average Latency

24 ms
```

```
Crawler

6,821 pages/sec
```

Operators can understand the entire cluster at a glance.

---

# 9. Performance Profiling

Monitoring explains

what

happened.

Profiling explains

why.

Examples

```
BM25

65%
```

```
Network

12%
```

```
Serialization

8%
```

```
Merge

15%
```

Optimization efforts become data-driven.

---

# 10. Benchmark Framework

Every subsystem must be measurable.

Benchmarks include

Crawler

```
Pages/sec
```

Indexer

```
Documents/sec
```

Search

```
Queries/sec
```

Ranking

```
Documents Ranked/sec
```

Merge

```
Results/sec
```

Benchmarks should be reproducible.

---

## Example Benchmark

```
Dataset

10 Million Documents

Queries

100,000

Concurrency

64

Warm Cache

Enabled
```

Output

```
Median Latency

18 ms

P95

31 ms

P99

48 ms
```

---

# 11. Alerting System

Metrics are useful only if someone notices problems.

Rules

```
CPU

>

90%

↓

Alert
```

```
Heartbeat Missing

>

15 s

↓

Alert
```

```
Disk

>

85%

↓

Alert
```

```
Latency

>

100 ms

↓

Alert
```

Alerts are generated automatically.

---

## Alert Model

```java
public record Alert(

    Severity severity,

    String message,

    Instant timestamp

){}
```

Severity

```
INFO

WARNING

CRITICAL
```

---

# 12. Capacity Planning

Monitoring also predicts future needs.

Suppose

```
Current Growth

5 GB/day
```

Remaining storage

```
1.2 TB
```

Estimated exhaustion

```
240 days
```

The cluster can be expanded before failures occur.

---

# 13. Automatic Scaling Metrics

Future versions may support automatic scaling.

Scaling inputs

```
CPU

Memory

Disk

Queries/sec

Average Latency
```

Example

```
Latency

>

75 ms

↓

Add Index Node
```

Although MiniGoogle will not automatically provision machines, the architecture supports automated decisions.

---

# 14. Failure Investigation

Suppose

```
Query

"compiler"

↓

Error
```

Engineer searches

```
Trace ID
```

Immediately sees

```
Coordinator

↓

Shard 12 Timeout

↓

Replica Retry

↓

Success

↓

Response

73 ms
```

Root cause becomes obvious.

---

# 15. Query Analytics

The coordinator records anonymous statistics.

Examples

```
Top Queries

Most Frequent Domains

Average Query Length

Cache Hit Rate

Search Latency Distribution
```

These metrics guide optimization efforts.

---

# 16. Long-Term Storage

Metrics are periodically persisted.

Example

```
Every Minute

↓

Aggregate

↓

Write Snapshot
```

Historical data enables trend analysis.

Questions such as

```
Was latency increasing over the last month?
```

become answerable.

---

# 17. Monitoring APIs

Metrics

```
GET

/api/v1/metrics
```

Health

```
GET

/api/v1/health
```

Cluster

```
GET

/api/v1/cluster/status
```

Tracing

```
GET

/api/v1/traces/{id}
```

Alerts

```
GET

/api/v1/alerts
```

Every operational tool uses these APIs.

---

# 18. Java Package Structure

```
monitoring/

├── metrics/
│      MetricsCollector.java
│      MetricRegistry.java
│
├── logging/
│      StructuredLogger.java
│      LogFormatter.java
│
├── tracing/
│      TraceManager.java
│      Span.java
│
├── health/
│      HealthChecker.java
│      HealthStatus.java
│
├── benchmark/
│      BenchmarkRunner.java
│      BenchmarkReport.java
│
├── alerts/
│      AlertManager.java
│
├── analytics/
│      QueryAnalytics.java
│
└── dashboard/
       ClusterDashboard.java
```

Every monitoring concern remains isolated from the search engine itself.

---

# 19. Complete Monitoring Pipeline

```
Search Request

↓

Trace Created

↓

Coordinator Logs Event

↓

Shard Metrics Updated

↓

Latency Recorded

↓

Query Completed

↓

Metrics Aggregated

↓

Dashboard Updated

↓

Historical Snapshot Stored

↓

Alert Rules Evaluated
```

Monitoring is therefore not an afterthought—it accompanies every request from start to finish.

---

# Engineering Decisions

## Why Structured Logging?

Free-form log messages are difficult to search and aggregate. Structured logs allow filtering by request ID, node, shard, latency, or severity without parsing arbitrary text.

---

## Why Distributed Tracing?

Metrics tell us that latency increased. Tracing tells us **which exact operation** caused the increase, making debugging distributed requests dramatically easier.

---

## Why Benchmark Every Component?

Optimizing without measurements often wastes time. By benchmarking each subsystem independently, improvements become quantifiable and regressions are detected early.

---

# Final Architecture After Chapter 11

```
                    User

                     │

             Search Coordinator

        ┌────────┼─────────┐

        ▼        ▼         ▼

   Index     Crawl     Monitoring

    Nodes    Cluster      Service

        │        │

        ▼        ▼

 Distributed Storage

        │

   Metrics • Logs • Traces

        │

 Cluster Dashboard
```

At this point, MiniGoogle is no longer just a functional search engine. It has the operational tooling expected of a production distributed system.

---

# End of Chapter 11

The next chapter is one of the most advanced in the entire project:

# Chapter 12 — Performance Optimization & Search Engine Internals

We will dive into the low-level engineering techniques that distinguish toy search engines from production systems, including:

- Memory-mapped index files
- Cache-aware data structures
- SIMD-friendly posting list layouts
- Variable-byte and Frame-of-Reference compression
- Skip lists
- Block-based posting lists
- Branch prediction optimizations
- Zero-copy file access
- Parallel indexing
- NUMA considerations
- Lock-free data structures
- Cache locality optimization

This chapter focuses on extracting every possible millisecond from the system while keeping the implementation understandable and written entirely in plain Java.


# Chapter 12 — Performance Optimization & Search Engine Internals

---

# Table of Contents

1. Introduction
2. The Performance Mindset
3. CPU vs Memory vs Disk
4. The Modern Memory Hierarchy
5. Data-Oriented Design
6. Memory-Mapped Files
7. Cache-Friendly Data Structures
8. Posting List Compression
9. Skip Lists
10. SIMD-Friendly Layout
11. Branch Prediction
12. Zero-Copy Search
13. Lock-Free Reads
14. Parallel Query Execution
15. Parallel Index Construction
16. Cache Design
17. JVM Optimizations
18. Benchmarking Strategy
19. Java Package Structure

---

# 1. Introduction

At this point, MiniGoogle works.

It crawls.

It indexes.

It ranks.

It distributes work.

Now comes something equally important.

Making it **fast**.

Professional search engines spend years optimizing tiny details because every millisecond matters.

Suppose

```
100 million

queries/day
```

Saving

```
5 ms
```

per query means

hundreds of CPU-hours saved every day.

Performance is therefore a feature.

---

# 2. The Performance Mindset

Most beginners optimize algorithms.

Professionals optimize

- cache misses
- memory allocations
- disk seeks
- network packets
- branch prediction
- compression
- concurrency

The biggest bottleneck is rarely CPU arithmetic.

It is almost always

```
Memory
```

---

# 3. CPU vs Memory vs Disk

Approximate latencies

```
CPU Register

≈1 ns
```

```
L1 Cache

≈1 ns
```

```
L2 Cache

≈4 ns
```

```
L3 Cache

≈12 ns
```

```
RAM

≈100 ns
```

```
SSD

≈100 μs
```

```
Network

≈1 ms
```

```
Internet

≈10-100 ms
```

Notice

Accessing RAM is already

100×

slower than L1.

Disk is

1000×

slower than RAM.

---

# 4. The Modern Memory Hierarchy

```
CPU

↓

L1 Cache

↓

L2 Cache

↓

L3 Cache

↓

RAM

↓

SSD

↓

Network
```

Good software tries to stay

as high as possible.

Every cache miss hurts performance.

---

# 5. Data-Oriented Design

Object-oriented programming is excellent for modeling.

It is often terrible for performance.

Bad

```java
class Posting{

    int docId;

    float score;

    Posting next;

}
```

Every posting

is a different object.

Memory becomes fragmented.

---

Better

```java
int[] docIds;

float[] scores;
```

Now

memory is contiguous.

CPU prefetching becomes effective.

---

Advantages

- fewer cache misses
- better locality
- fewer allocations
- faster iteration

---

# 6. Memory-Mapped Files

Instead of reading

```
File

↓

Byte[]

↓

Copy

↓

Parser
```

Use

```
Memory Mapping
```

Java

```java
MappedByteBuffer
```

Operating System

maps the file directly into virtual memory.

The application accesses bytes

as if they were already in RAM.

---

Advantages

- no explicit reads
- OS page cache
- zero-copy access
- lazy loading

---

Example

```java
FileChannel channel;

MappedByteBuffer buffer =
channel.map(
READ_ONLY,
0,
fileSize
);
```

Searching becomes

pointer arithmetic.

---

# 7. Cache-Friendly Data Structures

Bad

```
HashMap<String,List<Posting>>
```

Thousands of pointers.

Poor locality.

---

Better

```
Dictionary Array

↓

Offset Array

↓

Posting Array
```

Everything stored sequentially.

Example

```
compiler

↓

offset

284921
```

↓

Jump directly

into posting array.

---

# 8. Posting List Compression

Posting lists dominate storage.

Example

```
2

7

14

18

31
```

Instead of storing

absolute IDs

store differences.

```
2

5

7

4

13
```

Smaller numbers

compress better.

---

## Variable Byte Encoding

Store integers using

only the necessary bytes.

Example

```
5

↓

1 byte
```

```
70000

↓

3 bytes
```

instead of

always

```
4 bytes
```

Savings

30–70%

depending on corpus.

---

## Delta Encoding

Original

```
102

109

117

118
```

Delta

```
102

7

8

1
```

Again

compression improves dramatically.

---

# 9. Skip Lists

Suppose

Posting List

contains

```
8 million documents
```

Sequential search

is expensive.

Instead

insert skip pointers.

```
1

↓

5

↓

9

↓

13

↓

17
```

Skip

```
1

↓

17
```

Now

large regions

can be skipped immediately.

---

Example

Searching

```
docID

50000
```

Instead of

50,000 comparisons

↓

Skip

↓

Jump

↓

Binary-like traversal.

---

# 10. SIMD-Friendly Layout

Modern CPUs execute

multiple operations simultaneously.

Instead of

```
score(doc1)

↓

score(doc2)

↓

score(doc3)
```

Process

```
doc1

doc2

doc3

doc4
```

in one CPU instruction.

Java's

```
Vector API
```

supports this.

Although optional,

our data layout should enable vectorization.

---

# 11. Branch Prediction

Bad

```java
if(random){

...
}
```

CPU cannot predict.

Pipeline stalls.

---

Better

Keep execution predictable.

Example

Instead of

```
if deleted

continue
```

Maintain

separate arrays

without deleted entries.

Branches disappear.

Performance improves.

---

# 12. Zero-Copy Search

Traditional approach

```
SSD

↓

Kernel Buffer

↓

User Buffer

↓

Parser
```

Two copies.

Memory-mapped files

```
SSD

↓

Virtual Memory

↓

Application
```

No intermediate copies.

The operating system handles paging automatically.

---

# 13. Lock-Free Reads

Searching is

99%

reads.

Never block readers.

Segments are immutable.

Readers simply access them.

When a merge finishes

```
Old Segment

↓

New Segment
```

Coordinator swaps references atomically.

Readers continue uninterrupted.

No locks required.

---

# 14. Parallel Query Execution

Suppose

Query

contains

```
8 terms
```

Instead of processing

sequentially

process posting retrieval

in parallel.

```
compiler

↓

Thread 1
```

```
optimization

↓

Thread 2
```

```
java

↓

Thread 3
```

Merge afterwards.

Useful for long queries.

---

# 15. Parallel Index Construction

Indexing pipeline

```
HTML

↓

Tokenizer

↓

Normalization

↓

Posting Creation

↓

Compression

↓

Serialization
```

Each stage can execute independently.

Pipeline parallelism

improves throughput.

---

Example

```
Worker

1

Tokenization
```

```
Worker

2

Compression
```

```
Worker

3

Serialization
```

CPU utilization increases.

---

# 16. Cache Design

Caching occurs at multiple levels.

---

## Dictionary Cache

Frequently searched terms

remain in RAM.

---

## Posting Cache

Popular posting lists

stay memory-resident.

---

## Query Cache

Entire query

↓

Top-K results.

---

## Snippet Cache

Frequently viewed snippets

avoid regeneration.

---

## Segment Cache

Recently searched segments

remain mapped.

---

Cache Hierarchy

```
CPU Cache

↓

OS Page Cache

↓

Dictionary Cache

↓

Posting Cache

↓

Query Cache
```

Each level reduces latency.

---

# 17. JVM Optimizations

Although MiniGoogle is written in Java,

performance depends heavily on allocation behavior.

Recommendations

- Prefer primitive arrays
- Avoid unnecessary boxing
- Reuse buffers
- Minimize garbage generation
- Use immutable objects
- Keep hot loops allocation-free

Example

Avoid

```java
new Posting(...)
```

millions of times.

Instead

write directly

into primitive arrays.

---

# 18. Benchmarking Strategy

Every optimization

must be measured.

Example

```
Without Compression

Index

12 GB

Latency

27 ms
```

After optimization

```
Index

6 GB

Latency

18 ms
```

Optimization accepted.

Otherwise

revert.

Never optimize

without evidence.

---

## Microbenchmarks

Measure

individual methods.

Example

```
Posting Intersection

100 million iterations
```

---

## Macrobenchmarks

Measure

entire cluster.

Example

```
10 million documents

64 nodes

100 concurrent users
```

This reflects

real-world performance.

---

# 19. Java Package Structure

```
performance/

├── mmap/
│      MemoryMappedIndex.java
│      MappedSegment.java
│
├── compression/
│      DeltaEncoder.java
│      VariableByteEncoder.java
│
├── cache/
│      PostingCache.java
│      QueryCache.java
│      DictionaryCache.java
│
├── vector/
│      VectorScorer.java
│
├── benchmark/
│      MicroBenchmark.java
│      ClusterBenchmark.java
│
├── profiler/
│      PerformanceProfiler.java
│
├── allocator/
│      BufferPool.java
│
└── util/
       Timer.java
```

Each optimization remains modular and can be enabled or disabled independently.

---

# Complete Performance Pipeline

```
User Query

↓

Dictionary Cache

↓

Memory-Mapped Segment

↓

Compressed Posting List

↓

Delta Decode

↓

BM25

↓

Block-Max WAND

↓

Top-K Heap

↓

Merge

↓

Query Cache

↓

Return Results
```

Every stage is designed to minimize memory movement, maximize cache locality, and avoid unnecessary allocations.

---

# Engineering Decisions

## Why Memory-Mapped Files?

Reading large index files using standard I/O introduces copying overhead and system calls. Memory mapping allows the operating system to page data automatically, reducing latency and simplifying the code.

---

## Why Immutable Segments?

Immutable data structures eliminate synchronization during searches. Readers never wait for writers, enabling extremely high query throughput.

---

## Why Primitive Arrays?

Arrays such as `int[]`, `long[]`, and `float[]` are compact, contiguous, and CPU cache-friendly. Millions of Java objects would significantly increase memory usage and garbage collection overhead.

---

## Why Compress Posting Lists?

Compression not only reduces disk usage but also decreases the amount of data transferred from storage to memory. Since decompression is often faster than additional disk I/O, compressed indexes can actually improve search latency.

---

# Current Architecture Overview

```
                    Browser
                       │
                       ▼
             Search Coordinator
                       │
      ┌────────────────┼────────────────┐
      ▼                ▼                ▼
  Index Node      Index Node      Index Node
      │                │                │
 Memory-Mapped   Memory-Mapped   Memory-Mapped
   Segments         Segments         Segments
      │                │                │
Compressed Posting Lists + Skip Lists + WAND
      │                │                │
      └────────────────┼────────────────┘
                       ▼
                 Global Top-K Merge
                       ▼
                 JSON Response
```

The architecture is now capable of efficiently searching datasets that are several orders of magnitude larger than RAM while maintaining low query latency.

---

# End of Chapter 12

The next chapter will elevate MiniGoogle from a traditional keyword search engine to a **modern semantic search platform**.

# Chapter 13 — Semantic Search, Embeddings & Hybrid Retrieval

We will implement, **from scratch**, a vector search engine alongside the inverted index. Topics include:

- Dense vector embeddings
- Approximate Nearest Neighbor (ANN) search
- HNSW graph construction
- Cosine similarity
- Hybrid BM25 + Vector retrieval
- Query expansion
- Synonym graphs
- Spell correction
- Autocomplete
- Learning-to-rank foundations
- RAG integration

This is the chapter that will make the project look like a **2027-generation search engine**, combining classical information retrieval with modern AI retrieval while keeping the implementation educational and framework-light.

# Chapter 13 — Semantic Search, Embeddings & Hybrid Retrieval

---

# Table of Contents

1. Introduction
2. Why Keyword Search Isn't Enough
3. The Evolution of Search
4. Lexical vs Semantic Retrieval
5. Hybrid Search Architecture
6. Embedding Fundamentals
7. Embedding Generation Pipeline
8. Dense Vector Storage
9. Cosine Similarity
10. Approximate Nearest Neighbor Search
11. HNSW Index
12. Hybrid Ranking
13. Query Expansion
14. Synonym Graph
15. Spell Correction
16. Autocomplete
17. Reranking
18. RAG Integration
19. Java Package Structure

---

# 1. Introduction

Everything we have built until now is based on one assumption.

> If two documents contain the same words,
> they are probably related.

Unfortunately,

language doesn't work like that.

Suppose a user searches

```
car
```

A document contains

```
automobile
```

BM25

↓

Score

```
0
```

because the word

```
car
```

never appears.

Humans know

```
car

≈

automobile
```

Traditional search engines do not.

This chapter solves that problem.

---

# 2. Why Keyword Search Isn't Enough

Consider

Query

```
How do airplanes stay in the air?
```

Document

```
Aircraft generate lift by accelerating airflow above the wing.
```

Keyword overlap

```
Very Small
```

Meaning overlap

```
Extremely High
```

A lexical search engine misses this document.

A semantic search engine retrieves it immediately.

---

# 3. The Evolution of Search

Generation 1

```
Boolean Search
```

↓

Generation 2

```
TF-IDF
```

↓

Generation 3

```
BM25
```

↓

Generation 4

```
BM25

+

Vector Search
```

↓

Generation 5

```
Hybrid

+

LLMs

+

Reasoning
```

MiniGoogle now enters

Generation 4.

---

# 4. Lexical vs Semantic Retrieval

Lexical

```
Exact Words
```

Semantic

```
Meaning
```

Example

Query

```
physician
```

Document

```
doctor
```

BM25

↓

No match

Vector Search

↓

High similarity

---

Advantages

Lexical

- precise
- fast
- interpretable

Semantic

- understands meaning
- robust to synonyms
- robust to paraphrases

Neither replaces the other.

They complement each other.

---

# 5. Hybrid Search Architecture

MiniGoogle now has

two independent retrieval systems.

```
User Query

↓

Lexer

↓

BM25 Retrieval
```

AND

```
User Query

↓

Embedding

↓

Vector Search
```

Coordinator

↓

Merge Results

↓

Final Ranking

---

Architecture

```
                 Query

                   │

      ┌────────────┴────────────┐

      ▼                         ▼

Lexical Retrieval         Semantic Retrieval

      ▼                         ▼

 BM25 Results             Vector Results

      └────────────┬────────────┘

                   ▼

           Hybrid Ranking

                   ▼

              Final Results
```

---

# 6. Embedding Fundamentals

An embedding is simply

a list of numbers.

Example

```
car

↓

[

0.32,

-0.18,

0.91,

...

768 values

]
```

Words with similar meaning

produce nearby vectors.

Example

```
doctor

↓

[0.71,...]
```

```
physician

↓

[0.69,...]
```

Distance

↓

Very small.

---

Instead of comparing words,

we compare vectors.

---

# 7. Embedding Generation Pipeline

Every indexed document now follows

two parallel pipelines.

Pipeline 1

```
Tokenizer

↓

Inverted Index
```

Pipeline 2

```
Embedding Model

↓

Dense Vector

↓

Vector Index
```

The document is searchable

through either pipeline.

---

# 8. Dense Vector Storage

Each document stores

```
Document ID

↓

Embedding
```

Example

```java
class DenseVector{

    int documentId;

    float[] values;

}
```

Typical dimensions

```
384

768

1024

1536
```

MiniGoogle will initially use

```
384 dimensions
```

to reduce memory.

---

# Memory Estimate

Suppose

```
10 Million Documents
```

384 dimensions

```
float

=

4 bytes
```

Storage

```
384

×

4

=

1536 bytes/document
```

Total

```
≈15 GB
```

Vector storage is substantial.

Compression becomes important later.

---

# 9. Cosine Similarity

Semantic similarity is measured using

```
Cosine Similarity
```

Formula

```
A · B

/

(|A||B|)
```

Result

```
1

↓

Identical
```

```
0

↓

Unrelated
```

```
-1

↓

Opposite
```

Example

```
doctor

↓

0.96

physician
```

```
doctor

↓

0.12

volcano
```

---

Implementation

```java
double cosine(

float[] a,

float[] b
)
```

Iterate once.

Complexity

```
O(d)

d

=

dimensions
```

---

# 10. Approximate Nearest Neighbor Search

Suppose

```
50 Million Vectors
```

Computing cosine similarity

against every vector

is impossible.

Instead

we use

```
Approximate Nearest Neighbor

(ANN)
```

The goal

Find almost the best answer

100×

faster.

---

# 11. HNSW Index

MiniGoogle will implement

```
Hierarchical Navigable Small World Graph

(HNSW)
```

One of the best ANN algorithms.

---

## Core Idea

Instead of comparing against

every vector,

build

a graph.

```
A

────

B

│

│

C

────

D

```

Nearby vectors

connect together.

Searching becomes

graph traversal,

not brute force.

---

## Multiple Layers

HNSW consists of layers.

```
Layer 3

Few Nodes

↓

Layer 2

↓

Layer 1

↓

Layer 0

Entire Dataset
```

Search starts

at the top.

Each layer narrows

the search space.

---

Search Example

```
Entry Point

↓

Nearest Node

↓

Greedy Search

↓

Lower Layer

↓

Greedy Search

↓

Lower Layer

↓

Best Neighbors
```

Average complexity

approximately

```
O(log n)
```

instead of

```
O(n)
```

---

# 12. Hybrid Ranking

Now we have

two scores.

BM25

```
13.4
```

Cosine

```
0.91
```

Normalize both.

Example

```
Final Score

=

0.65

×

BM25

+

0.35

×

Cosine
```

Weights

should remain configurable.

Different queries

may favor different mixtures.

---

Example

Query

```
Java HashMap implementation
```

Lexical score dominates.

Query

```
Why do planes fly?
```

Semantic score dominates.

---

# 13. Query Expansion

Suppose

user searches

```
AI
```

Automatically expand

```
AI

↓

Artificial Intelligence

↓

Machine Learning

↓

Neural Networks
```

The search engine retrieves

far more relevant documents.

Expansion occurs

before retrieval.

---

Pipeline

```
Query

↓

Expansion

↓

BM25

+

Vector Search
```

---

# 14. Synonym Graph

Rather than

a simple dictionary,

store

relationships.

Example

```
doctor

↓

physician

↓

medical practitioner
```

```
car

↓

automobile

↓

vehicle
```

Graph traversal

provides richer expansion.

---

Implementation

```java
Map<String,

Set<String>>
```

Later

replace

with weighted graphs.

---

# 15. Spell Correction

Suppose

user types

```
algoritm
```

The search engine should infer

```
algorithm
```

MiniGoogle implements

Levenshtein Distance.

Example

```
kitten

↓

sitting

Distance

3
```

Words

within

distance

```
1

or

2
```

become candidates.

---

Correction Pipeline

```
Query

↓

Dictionary Lookup

↓

Miss

↓

Candidate Generation

↓

Best Candidate

↓

Search
```

---

# 16. Autocomplete

While typing

```
comp
```

Suggestions

```
compiler

compression

computer

competitive programming
```

Implementation

```
Trie
```

Every node stores

```
frequency
```

Most popular completions

appear first.

---

# 17. Reranking

Initial retrieval

returns

```
Top 200
```

Instead of trusting BM25

rerank them

using

a heavier model.

Pipeline

```
BM25

↓

Top 200

↓

Cross Encoder

↓

Top 20
```

Only

200 documents

need expensive computation.

---

# 18. RAG Integration

MiniGoogle now becomes

an information retrieval engine

for LLMs.

Pipeline

```
Question

↓

Hybrid Retrieval

↓

Top Documents

↓

LLM Context

↓

Generated Answer
```

Instead of hallucinating,

the LLM receives

real evidence.

---

Future

```
MiniGoogle

↓

Atlas

↓

Reasoning Agent
```

This architecture naturally integrates

with your future AI platform.

---

# 19. Java Package Structure

```
semantic/

├── embedding/
│      EmbeddingGenerator.java
│      DenseVector.java
│
├── vector/
│      VectorIndex.java
│      CosineSimilarity.java
│
├── hnsw/
│      HNSWGraph.java
│      HNSWNode.java
│      HNSWSearcher.java
│
├── hybrid/
│      HybridRanker.java
│
├── expansion/
│      QueryExpander.java
│
├── synonym/
│      SynonymGraph.java
│
├── spell/
│      SpellCorrector.java
│      Levenshtein.java
│
├── autocomplete/
│      TrieAutocomplete.java
│
├── reranking/
│      CrossEncoderRanker.java
│
└── rag/
       RetrievalPipeline.java
```

---

# Complete Hybrid Retrieval Pipeline

```
User Query

↓

Spell Correction

↓

Query Expansion

↓

Embedding Generation

↓

┌──────────────────────────┐

│                          │

▼                          ▼

BM25                  HNSW Search

│                          │

▼                          ▼

Top 200               Top 200

└──────────────┬──────────────┘

               ▼

        Hybrid Ranker

               ▼

        Cross Encoder

               ▼

          Top 20

               ▼

      Snippet Generation

               ▼

      JSON Response
```

---

# Engineering Decisions

## Why Keep BM25?

Despite advances in AI, BM25 remains exceptionally strong for exact keyword matching, identifiers, code, and technical terms. Removing it would reduce precision.

---

## Why Hybrid Retrieval?

Vector search excels at semantic understanding, while BM25 excels at lexical precision. Combining both consistently outperforms either method alone on diverse search workloads.

---

## Why HNSW?

Among ANN algorithms, HNSW offers an excellent balance of search quality, insertion speed, and implementation complexity. It is widely used in production vector databases and search systems.

---

## Why Query Expansion Before Embeddings?

Expanding queries benefits both lexical and semantic retrieval, improving recall without requiring additional vector computations for every possible synonym.

---

# Final Architecture

```
                           Browser
                              │
                              ▼
                     Search Coordinator
                              │
                 ┌────────────┴────────────┐
                 ▼                         ▼
          Lexical Pipeline         Semantic Pipeline
                 │                         │
            BM25 Engine              HNSW Engine
                 │                         │
                 └────────────┬────────────┘
                              ▼
                      Hybrid Ranker
                              ▼
                     Cross-Encoder
                              ▼
                     Top-K Results
                              ▼
                     Snippet Engine
                              ▼
                      JSON Response
```

MiniGoogle is now a **modern hybrid search engine** capable of combining traditional information retrieval with semantic understanding.

---

# End of Chapter 13

The next chapter will be **Chapter 14 — Building a Google-Scale System**, where we move beyond algorithms into systems engineering. We'll cover:

- Distributed consensus (Raft)
- Consistent hashing
- Gossip protocols
- Leader election
- Distributed transactions
- Snapshotting
- Write-ahead logs
- Cluster upgrades
- Zero-downtime deployments
- Multi-datacenter replication
- Disaster recovery
- Real-world production architecture

This chapter is where MiniGoogle evolves from an excellent academic project into something that resembles the engineering principles behind systems built at companies like Google, Meta, and Amazon.


# Chapter 14 — Building a Google-Scale Distributed System

---

# Table of Contents

1. Introduction
2. Why Distributed Systems Become Difficult
3. Cluster Architecture
4. Node Discovery
5. Consistent Hashing
6. Leader Election
7. Consensus with Raft
8. Write-Ahead Logging (WAL)
9. Snapshotting
10. Distributed Transactions
11. Cluster Membership
12. Gossip Protocol
13. Failure Detection
14. Multi-Datacenter Replication
15. Zero-Downtime Deployments
16. Disaster Recovery
17. Security
18. Package Structure
19. Complete Cluster Lifecycle
20. Cluster Transport Protocol

---

# 1. Introduction

Until now, MiniGoogle assumes that all machines cooperate perfectly.

Reality is much harsher.

Machines

- crash
- reboot
- lose network connectivity
- become slow
- corrupt disks
- restart unexpectedly

The challenge is no longer *searching documents.*

The challenge is keeping the **entire cluster functioning despite failures**.

This chapter focuses on **distributed systems engineering**, the discipline that powers systems such as

- Google Search
- Spanner
- Bigtable
- Kubernetes
- CockroachDB
- Elasticsearch

---

# 2. Why Distributed Systems Become Difficult

Suppose

```
64 nodes
```

Probability

one machine crashes

today

↓

High.

Probability

at least one machine crashes

this week

↓

Almost certain.

Distributed systems therefore assume

```
Failures are normal.
```

Everything is designed around this principle.

---

# 3. Cluster Architecture

The cluster consists of several independent services.

```
                    Load Balancer

                           │

            ┌──────────────┴──────────────┐

            ▼                             ▼

    Search Coordinator          Search Coordinator

            │                             │

    ┌───────┼────────┐          ┌─────────┼────────┐

    ▼       ▼        ▼          ▼         ▼        ▼

 Index   Index    Index      Crawl    Crawl   Monitoring

 Nodes    Nodes    Nodes      Nodes    Nodes    Service

            │

      Distributed Storage

            │

     Consensus Cluster
```

Every subsystem remains independently scalable.

---

# 4. Node Discovery

How does a new machine join?

Boot sequence

```
Start JVM

↓

Generate Node ID

↓

Contact Bootstrap Server

↓

Receive Cluster Metadata

↓

Register

↓

Heartbeat Begins

↓

Ready
```

Coordinator now knows

```
Node 42 exists.
```

---

## Node Metadata

```java
public class ClusterNode{

    UUID nodeId;

    String host;

    int port;

    NodeRole role;

    Instant joinedAt;

}
```

---

# 5. Consistent Hashing

Earlier

documents were assigned

```
hash(id)

%

shards
```

Problem

Add one server.

Everything moves.

Very expensive.

---

Instead

use

```
Consistent Hashing
```

Imagine

a circle.

```
0°

↓

90°

↓

180°

↓

270°
```

Nodes occupy positions.

Documents occupy positions.

Each document belongs to

the next node clockwise.

---

Example

```
Node A

30°
```

```
Node B

120°
```

```
Node C

240°
```

Document

```
145°
```

belongs to

```
Node C
```

---

Adding a node

only moves

a small fraction

of the data.

Not everything.

---

# 6. Leader Election

Some operations require

one authoritative node.

Examples

- shard allocation
- cluster metadata
- replication management

Only one leader exists.

---

Election

```
Node A

↓

Fails
```

Remaining nodes

vote.

```
Node B

↓

Leader
```

The cluster continues.

---

Leader Responsibilities

- Assign shards
- Detect failures
- Coordinate replication
- Publish metadata

Followers

perform work

but do not coordinate.

---

# 7. Consensus with Raft

Leader election alone

is insufficient.

All nodes must agree

on

cluster state.

MiniGoogle uses

```
Raft
```

Raft guarantees

every node eventually agrees

on the same sequence of operations.

---

Example

Leader receives

```
Create Shard 91
```

Append

to log.

```
Entry 1048
```

Replicate

to majority.

Only then

commit.

---

Consensus Flow

```
Leader

↓

Append Log

↓

Follower A

Follower B

Follower C

↓

Majority ACK

↓

Commit

↓

Apply
```

Nothing becomes permanent

until a majority agrees.

---

# 8. Write-Ahead Logging (WAL)

Never modify storage directly.

Instead

```
Operation

↓

Write WAL

↓

Flush Disk

↓

Apply Changes
```

Suppose power fails.

After reboot

```
Read WAL

↓

Replay

↓

Recover
```

No committed operation is lost.

---

Example

```
1045

ADD_DOCUMENT

18
```

```
1046

DELETE_DOCUMENT

9
```

```
1047

MERGE_SEGMENT

2
```

Replay sequentially.

---

# 9. Snapshotting

Eventually

logs become huge.

Instead

periodically

create snapshots.

```
Current State

↓

Snapshot

↓

Delete Old Logs
```

Recovery becomes

```
Load Snapshot

↓

Replay Recent Logs
```

instead of

replaying

millions

of entries.

---

# 10. Distributed Transactions

Suppose

moving

a shard

between nodes.

Need

```
Remove

Old Node
```

AND

```
Add

New Node
```

Both

must succeed.

Otherwise

data disappears.

---

Simplified Two-Phase Commit

```
Prepare

↓

All Nodes Ready?

↓

Yes

↓

Commit

↓

Done
```

If any node refuses

```
Rollback
```

---

MiniGoogle uses

transactions only

for metadata,

never

for search requests.

---

# 11. Cluster Membership

Coordinator maintains

```
Current Members
```

Example

```
Node 1

UP
```

```
Node 2

UP
```

```
Node 3

DOWN
```

```
Node 4

JOINING
```

Membership changes

are versioned.

Every node eventually

shares

the same view.

---

# 12. Gossip Protocol

Constantly broadcasting

cluster state

to everyone

is expensive.

Instead

use gossip.

Every node periodically

contacts

a few random peers.

```
Node 7

↓

Node 15
```

```
Node 15

↓

Node 33
```

```
Node 33

↓

Node 52
```

Information spreads

like a rumor.

Eventually

everyone knows.

---

Benefits

- scalable
- fault tolerant
- decentralized

---

# 13. Failure Detection

Every node sends

heartbeats.

```
Every

2 seconds
```

Coordinator records

last heartbeat.

```
Current Time

-

Last Heartbeat
```

If

```
>

10 seconds
```

Node

↓

Suspected Failed.

If replicas confirm

↓

Marked DOWN.

---

Avoid false alarms

using

multiple missed heartbeats.

---

# 14. Multi-Datacenter Replication

Imagine

Europe

fails.

MiniGoogle

should continue.

Architecture

```
Europe

↓

Primary
```

```
North America

↓

Replica
```

```
Asia

↓

Replica
```

Metadata

and critical indexes

are replicated

between regions.

Search requests

go to

nearest healthy region.

---

# 15. Zero-Downtime Deployments

Never stop

the cluster.

Deployment

```
Node 1

↓

Drain Traffic

↓

Upgrade

↓

Restart

↓

Healthy
```

Repeat

for every node.

Users

never notice.

---

Rolling Update

```
64 Nodes

↓

63 Available

↓

62 Available

↓

...

↓

64 Available
```

Capacity remains.

---

# 16. Disaster Recovery

Worst case

Entire cluster lost.

Recovery plan

```
Nightly Snapshot

↓

Remote Backup

↓

Restore Cluster

↓

Replay WAL

↓

Resume
```

Target

```
Minimal Data Loss

Minimal Downtime
```

---

# 17. Security

Every node

must authenticate.

Implemented:

```
Bearer Token

Authorization: Bearer <token>

X-Node-Id: <nodeId>
```

Validated by

an `AuthFilter` on every internal endpoint.

Unauthorized machines

cannot join.

Not yet implemented (future work):

```
TLS

+

Mutual Certificates
```

Every API

requires

```
Authentication

Authorization

Audit Logging
```

Authentication is implemented for internal RPCs.

Authorization and audit logging are not yet implemented.

Cluster metadata

is encrypted.

Sensitive traffic

uses TLS — not yet implemented.

---

# 18. Java Package Structure

```
cluster/

├── discovery/
│      NodeRegistry.java
│      BootstrapService.java
│
├── hashing/
│      ConsistentHashRing.java
│
├── raft/
│      RaftNode.java
│      LogEntry.java
│      LeaderElection.java
│
├── wal/
│      WriteAheadLog.java
│      LogReplayer.java
│
├── snapshot/
│      SnapshotManager.java
│
├── membership/
│      ClusterMembership.java
│
├── gossip/
│      GossipProtocol.java
│
├── heartbeat/
│      FailureDetector.java
│
├── replication/
│      ReplicationCoordinator.java
│
└── security/
       ClusterSecurity.java
```

Every subsystem can evolve independently while exposing a clean API to the rest of the search engine.

---

# 19. Complete Cluster Lifecycle

```
Machine Boots

↓

Registers

↓

Joins Gossip

↓

Leader Receives Membership

↓

Shard Assigned

↓

Heartbeat Starts

↓

Receives Traffic

↓

Replicates Data

↓

Writes WAL

↓

Creates Snapshots

↓

Periodic Health Checks

↓

Graceful Upgrade

↓

Continues Serving

↓

Eventually Leaves Cluster
```

At no point does a single machine become indispensable. Every critical component is replicated, monitored, and recoverable.

---

# Engineering Decisions

## Why Raft Instead of Paxos?

Paxos is theoretically elegant but notoriously difficult to implement correctly. Raft provides equivalent safety guarantees with a much more understandable design, making it ideal for an educational project.

---

## Why Consistent Hashing?

Traditional modulo-based partitioning causes almost every key to move whenever the number of nodes changes. Consistent hashing minimizes data movement, enabling elastic scaling.

---

## Why Write-Ahead Logging?

A WAL guarantees durability. Even if the JVM crashes after acknowledging an operation, replaying the log reconstructs the exact committed state.

---

## Why Gossip?

Broadcasting cluster state to every node scales poorly. Gossip spreads updates efficiently with logarithmic communication complexity while remaining resilient to failures.

---

# Final Production Architecture

```
                         Internet
                              │
                       Load Balancer
                              │
               ┌──────────────┴──────────────┐
               ▼                             ▼
      Search Coordinator            Search Coordinator
               │                             │
      ┌────────┼────────┐           ┌────────┼────────┐
      ▼        ▼        ▼           ▼        ▼        ▼
  Index    Crawl   Monitoring   Index    Crawl   Monitoring
  Cluster   Cluster   Service    Cluster  Cluster  Service
      │
      ▼
 Distributed Storage
      │
      ▼
 Raft Consensus Cluster
      │
      ▼
 WAL + Snapshots + Replication
      │
      ▼
 Multi-Region Backups
```

MiniGoogle has now evolved into a resilient distributed platform capable of surviving machine failures, network partitions, rolling upgrades, and large-scale deployments.

---

# 20. Cluster Transport Protocol

Until now the cluster algorithms ran *logically*.

Gossip exchanged state in memory.

Raft elected a leader in memory.

Nothing crossed the wire.

That changes now.

Every node in MiniGoogle speaks one internal language.

A single, versioned, traceable RPC protocol.

---

## Why an Internal Protocol?

External REST is for humans.

Internal RPC is for machines.

```
External (REST)          Internal (RPC)
-------------            -----------------
/api/v1/search           /cluster/v1/search/dispatch
human-readable JSON      compact, versioned envelopes
public                  private to the cluster
timeouts: seconds       timeouts: milliseconds
```

The two are separated on purpose:

- REST is stable and versioned for API consumers.
- RPC may evolve quickly as the cluster internals change.
- A burst of cluster traffic must never starve the public API.

Each node therefore runs a **dedicated internal RPC server**.

---

## The Wire Envelope

Every message carries the same metadata.

This makes each message:

- self-describing
- traceable
- version-checkable

```
protocolVersion   current wire version (bumped on incompatible changes)
requestId         identifies the logical request
correlationId     matches a response to its request
sourceNodeId      which node produced the message
timestamp         epoch milliseconds at creation
```

The envelope is a Java interface: `ClusterMessage`.

Every request and response record implements it.

---

## Protocol Versioning

A node that misparses an incompatible peer is worse than a node that rejects it.

So the version is checked before anything else.

```
incoming message
        |
        v
version == PROTOCOL_VERSION ?
        |
   +----+----+
   |         |
  yes       no
   |         |
   |      ProtocolViolationException  -> HTTP 400
   v
 process
```

Bump the version whenever the on-the-wire layout changes incompatibly:

- new required field
- removed field
- reordering
- type change

---

## Correlation Matching

Asynchronous replies are matched by correlation ID.

The client generates a fresh correlation ID per request.

The handler echoes it back.

The client rejects a mismatched reply as a protocol violation.

```
Client                     Server
  |                          |
  |  request (corr=abc123)   |
  |------------------------->|
  |                          |
  |  response (corr=abc123)  |
  |<-------------------------|
  |
  corr matches -> accept
```

A buggy or malicious peer that returns a wrong correlation ID is rejected explicitly.

---

## The Four Transports

The cluster has four kinds of traffic.

Each gets its own transport interface.

| Transport | Purpose |
|-----------|---------|
| `MembershipTransport` | gossip state exchange |
| `RaftTransport` | request-vote, append-entries |
| `SearchTransport` | fan-out queries to shard nodes |
| `ShardTransferTransport` | move shard chunks during rebalancing |

All four share the envelope and the validation rules.

All four are asynchronous (`CompletableFuture`).

---

## Node Directory

Nodes know each other by ID, not by address.

A `NodeDirectory` resolves a node ID to its base URI.

```
nodeId -> http://10.0.0.7:9091
```

This decouples the protocol from the network topology.

A node can be moved to a new address without touching the transport code.

---

## The Internal RPC Server

One `HttpServer` per node, a bounded worker pool, and one handler per endpoint.

The worker pool is capped so a burst of cluster RPCs cannot exhaust the JVM.

Handlers are registered before the server starts.

```
/cluster/v1/gossip/exchange      GossipHandler
/cluster/v1/raft/request-vote    RaftHandler
/cluster/v1/raft/append-entries  RaftHandler
/cluster/v1/search/dispatch      SearchHandler
```

Every handler follows the same shape:

1. reject non-POST methods with 405
2. parse the envelope
3. validate the protocol version
4. process
5. respond with the echoed metadata

---

## Gossip Exchange

Each gossip round picks a random live peer.

The local membership table is sent over the wire.

```
Node A                        Node B
  |                              |
  |  GossipExchangeRequest       |
  |  (state, corr)               |
  |----------------------------->|
  |                              | merge state,
  |                              | fire join/leave events
  |  GossipExchangeResponse      |
  |  (accepted, corr)            |
  |<-----------------------------|
```

New nodes discovered this way:

- are inserted into the membership table
- notify listeners
- the consistent hash ring adds them automatically

Seeded peers bootstrap discovery before gossip converges.

---

## Raft RPCs

Leader election and heartbeats travel the same protocol.

Request-vote:

```
candidate -> peer   RequestVoteRequest
                    (candidateId, term, lastLogIndex, lastLogTerm)
peer -> candidate   RequestVoteResponse
                    (term, voteGranted)
```

Append-entries (heartbeat + log replication):

```
leader -> follower  AppendEntriesRequest
                    (leaderId, term, prevLogIndex, entries)
follower -> leader  AppendEntriesResponse
                    (term, success)
```

A higher term wins a vote.

Heartbeats reset the follower election timeout.

---

## Query Dispatch

The coordinator scatters a query to every target shard over the wire.

The request carries only what a shard needs:

```
query            the query text
topK             how many local results to return
remainingTimeMs  the coordinator's remaining budget
```

The receiving node rebuilds a `QueryContext` from these fields:

- fresh local start time
- the coordinator's request ID preserved for tracing
- the deadline respected across the whole fan-out

```
Coordinator                    Shard Node
    |                              |
    |  DispatchQueryRequest        |
    |  (query, topK, budget)       |
    |----------------------------->|
    |                              | run local Top-K
    |  LocalSearchResponse         |
    |  (shardId, results, hits)    |
    |<-----------------------------|
```

The shard returns only its local Top-K.

Not the whole posting list.

The wire stays small.

A shard without a local search configured replies 503, and the coordinator simply skips it.

---

## Shard Transfer

Rebalancing streams shards in chunks.

```
start  ->  /shards/{id}/transfer/start
chunks ->  /shards/{id}/transfer/chunk   (offset + data + checksum)
commit ->  /shards/{id}/transfer/commit
```

Each chunk is a full envelope.

Checksums catch corruption in flight.

---

## Timeouts

Every RPC carries a deadline.

The HTTP client bounds its own wait by the caller's remaining budget.

A hung peer cannot outlive the scatter deadline.

```
RemoteSearchExecutor.execute
        |
        v
dispatchQuery(...).get(remainingTimeMs)
        |
   +----+----+
   |         |
 success   timeout -> shard is skipped
```

Slow shards degrade gracefully:

the cluster returns partial results instead of failing the query.

---

## Failure Handling

Transport failures are expected, not exceptional.

- unknown node -> failed future ("Unknown node")
- HTTP error    -> failed future
- mismatch      -> protocol violation
- timeout       -> shard skipped

Gossip treats a failed exchange as ordinary:

the peer is not contacted next round.

Failure detection handles the rest.

---

## Security

The internal protocol is private to the cluster.

Defense in depth:

- version validation rejects incompatible peers
- correlation matching rejects spoofed replies
- every request authenticates via `Authorization: Bearer <token>`
  and `X-Node-Id` against `ClusterSecurity`
- an `AuthFilter` on every internal endpoint rejects invalid credentials
  with 401 before the handler runs
- the envelope `sourceNodeId` is bound to the authenticated identity:
  a mismatch is rejected with 403

An attacker that cannot present a valid token is excluded before any message is processed.

---

## How It Ties Together

Chapters 6, 7, 12, and 13 described the algorithms.

This chapter is the pipe between them.

```
         MembershipTransport
                |
   Gossip -> ring -> routing
                |
         RaftTransport
                |
   election -> heartbeats -> shard assignment
                |
         SearchTransport
                |
   coordinator -> shards -> merged Top-K
                |
      ShardTransferTransport
                |
   rebalancer -> chunked shard moves
```

One protocol.

One envelope.

Four flows.

Every machine speaks the same language.

---

# End of Chapter 14

The next chapter will be **Chapter 15 — Developer Experience, APIs & Deployment**, where we will complete the project with:

- REST API design
- Internal RPC interfaces
- OpenAPI documentation
- Docker and Docker Compose
- Kubernetes deployment
- CI/CD pipelines
- Integration testing
- Load testing
- Configuration management
- Project documentation
- Demo environment
- Portfolio presentation

This final engineering chapter will transform MiniGoogle from an impressive distributed systems project into a polished, production-quality portfolio piece suitable for top software engineering internships and full-time roles.

# Chapter 15 — Developer Experience, APIs & Production Deployment

---

# Table of Contents

1. Introduction
2. Engineering Philosophy
3. Project Structure
4. Configuration Management
5. REST API Design
6. Internal RPC Architecture
7. API Versioning
8. Authentication
9. Error Handling
10. OpenAPI Documentation
11. Docker Architecture
12. Docker Compose
13. Kubernetes Deployment
14. CI/CD Pipeline
15. Testing Strategy
16. Load Testing
17. Logging in Production
18. Deployment Strategy
19. Portfolio Presentation
20. Final Package Structure

---

# 1. Introduction

A great distributed system is not only judged by its algorithms.

Professional software engineering also includes

- clean APIs
- documentation
- testing
- deployment
- automation
- reproducibility

A recruiter should be able to clone the repository and execute

```bash
docker compose up
```

and obtain a fully working search engine.

This chapter focuses on making MiniGoogle feel like an actual production system.

---

# 2. Engineering Philosophy

Every engineering decision should satisfy four principles.

## Simplicity

Avoid unnecessary frameworks.

The architecture should be understandable after reading the code.

---

## Modularity

Each service should have one responsibility.

```
Crawler

↓

discovers pages
```

```
Indexer

↓

creates indexes
```

```
Search Node

↓

retrieves documents
```

No service should perform unrelated work.

---

## Reproducibility

Every developer should obtain the exact same environment.

Docker solves this problem.

---

## Observability

Every action should be measurable.

Logs

↓

Metrics

↓

Tracing

↓

Monitoring

---

# 3. Project Structure

The repository should be organized as independent modules.

```
MiniGoogle/

│

├── crawler/

├── parser/

├── indexer/

├── storage/

├── ranking/

├── distributed-query/

├── semantic/

├── cluster/

├── monitoring/

├── gateway/

├── common/

├── benchmarks/

├── scripts/

├── docker/

├── docs/

└── deployment/
```

Every module builds independently.

---

# 4. Configuration Management

Never hardcode values.

Bad

```java
int PORT = 8080;
```

Better

```yaml
server:

  port: 8080

cluster:

  replicationFactor: 3

crawler:

  workers: 32

search:

  topK: 20
```

---

Configuration Loader

```java
Configuration.load("config.yml");
```

The same binary can run

Development

Testing

Production

without recompilation.

---

# 5. REST API Design

External clients communicate

using REST.

Example

```
GET

/api/v1/search
```

Parameters

```
q

↓

query
```

```
limit

↓

topK
```

```
page

↓

pagination
```

Example

```
GET

/api/v1/search?q=java&limit=20
```

---

Search Response

```json
{

 "query":"java",

 "took":18,

 "hits":[...]

}
```

---

# 6. Internal RPC Architecture

External users use REST.

Internal nodes should use

RPC.

Reason

- lower latency
- binary serialization
- smaller payloads

MiniGoogle will implement a lightweight binary protocol over TCP rather than relying on a large RPC framework.

Example

```
Coordinator

↓

SEARCH_REQUEST

↓

Node

↓

SEARCH_RESPONSE
```

---

# 7. API Versioning

Never expose APIs without versions.

Correct

```
/api/v1/search
```

Future

```
/api/v2/search
```

Old clients continue working.

---

# 8. Authentication

Public APIs

may require authentication.

Example

```
Authorization

Bearer Token
```

Future support

- OAuth
- API Keys
- JWT

Internal node communication

is authenticated with

```
Authorization: Bearer <token>

X-Node-Id: <nodeId>
```

Every internal RPC carries

a token derived from the shared cluster secret.

Unimplemented: mutual TLS remains future work.

---

# 9. Error Handling

Errors should be predictable.

Example

```json
{

 "error":"INVALID_QUERY",

 "message":"Query cannot be empty"

}
```

Never expose

stack traces

to users.

---

HTTP Status

```
200

Success
```

```
400

Invalid Request
```

```
404

Not Found
```

```
500

Internal Error
```

---

# 10. OpenAPI Documentation

Every endpoint

is documented.

Example

```
GET

/api/v1/search
```

Description

```
Executes a distributed search.
```

Parameters

```
query

limit

page
```

Responses

```
200

400

500
```

Developers can generate SDKs automatically.

---

# 11. Docker Architecture

Each service becomes one container.

```
Coordinator

↓

Container
```

```
Crawler

↓

Container
```

```
Indexer

↓

Container
```

```
Search Node

↓

Container
```

```
Monitoring

↓

Container
```

Containers communicate through a private network.

---

# 12. Docker Compose

Local development

```
docker compose up
```

starts

```
Coordinator

Searcher

Crawler

Indexer

Monitoring

Dashboard
```

with one command.

No manual setup required.

---

Example

```
version: "3.9"

services:

 coordinator:

 search-node:

 crawler:

 monitoring:
```

---

# 13. Kubernetes Deployment

Production deployment

uses Kubernetes.

Each service

↓

Deployment

Example

```
Search Node

Replicas

8
```

Kubernetes automatically

- restarts failed containers
- balances traffic
- performs rolling updates
- scales replicas

---

Example Architecture

```
Ingress

↓

Coordinator Pods

↓

Search Pods

↓

Crawler Pods

↓

Monitoring Pods
```

---

# 14. CI/CD Pipeline

Every commit triggers

```
Checkout

↓

Compile

↓

Unit Tests

↓

Integration Tests

↓

Static Analysis

↓

Docker Build

↓

Publish Image

↓

Deploy
```

Broken code

never reaches production.

---

Possible GitHub Actions Workflow

```
push

↓

build

↓

test

↓

package

↓

deploy
```

---

# 15. Testing Strategy

Testing occurs at multiple levels.

---

## Unit Tests

One class.

Example

```
BM25

Cosine Similarity

Bloom Filter
```

---

## Integration Tests

Multiple services.

Example

```
Coordinator

↓

Searcher

↓

Response
```

---

## Cluster Tests

Entire distributed system.

Example

```
64 Nodes

↓

Random Failures

↓

Verify Correctness
```

---

## End-to-End Tests

```
HTTP Request

↓

Crawler

↓

Indexer

↓

Search

↓

JSON Response
```

Entire pipeline verified.

---

# 16. Load Testing

Functional correctness

is not enough.

Example

```
Concurrent Users

1000
```

Queries

```
1 Million
```

Measure

```
Latency

Throughput

Failures

CPU

Memory
```

Target

```
P95

<50 ms
```

---

# 17. Logging in Production

Every request receives

```
Request ID
```

Every log includes

```
Timestamp

Node

Shard

Request ID

Severity
```

Example

```
INFO

Node-14

Request

7af1...

Latency

18 ms
```

Searching logs becomes trivial.

---

# 18. Deployment Strategy

Rolling deployment

```
Node

↓

Drain

↓

Upgrade

↓

Health Check

↓

Return Traffic
```

Repeat

for every node.

No downtime.

---

Release Strategy

```
Development

↓

Staging

↓

Production
```

Production

never receives untested code.

---

# 19. Portfolio Presentation

The repository should immediately communicate quality.

Suggested layout

```
README.md

Architecture.md

QuickStart.md

API.md

Benchmark.md

Roadmap.md
```

---

README should include

- Project overview
- System architecture diagram
- Technologies used
- Performance benchmarks
- Screenshots
- Demo instructions
- Future work

---

A recruiter should understand the project within five minutes.

---

Suggested Demo

```
docker compose up

↓

Crawler starts

↓

Indexer builds segments

↓

Monitoring dashboard updates

↓

User searches

↓

Distributed query executes

↓

Hybrid retrieval

↓

Results returned

↓

Metrics visible live
```

The demo showcases every subsystem working together.

---

# 20. Final Package Structure

```
MiniGoogle/

├── common/
├── crawler/
├── parser/
├── tokenizer/
├── indexing/
├── storage/
├── ranking/
├── query/
├── semantic/
├── distributed-query/
├── cluster/
├── monitoring/
├── gateway/
├── dashboard/
├── benchmarks/
├── docker/
├── deployment/
├── scripts/
├── docs/
├── tests/
└── README.md
```

---

# Complete Production Pipeline

```
User

↓

REST Gateway

↓

Coordinator

↓

Scatter-Gather Search

↓

Hybrid Retrieval

↓

Global Ranking

↓

Snippet Generation

↓

JSON Response

↓

Monitoring

↓

Metrics

↓

Dashboard

↓

Logs

↓

Long-Term Storage
```

Every request is authenticated, traced, measured, logged, and observable from beginning to end.

---

# Engineering Decisions

## Why REST Externally and RPC Internally?

REST provides a simple and widely supported interface for clients, while a lightweight binary RPC protocol minimizes latency and bandwidth between cluster nodes.

---

## Why Docker?

Docker guarantees reproducible development and deployment environments. Every contributor runs the same software stack regardless of operating system.

---

## Why Kubernetes?

Kubernetes automates container orchestration, scaling, self-healing, and rolling updates, allowing the cluster to remain available even during deployments or failures.

---

## Why Multiple Testing Levels?

Unit tests validate algorithms, integration tests validate service interactions, and end-to-end tests validate the complete user experience. Together they provide confidence that the system behaves correctly under both normal and failure scenarios.

---

# Final Architecture Overview

```
                        Internet
                            │
                       Load Balancer
                            │
                    REST API Gateway
                            │
                  Search Coordinator
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
   Search Nodes        Crawler Nodes      Monitoring
        │                   │                  │
        └───────────────┬───┴──────────────────┘
                        ▼
              Distributed Storage
                        │
                 Raft Consensus
                        │
          WAL • Snapshots • Replication
                        │
             Docker / Kubernetes
                        │
                 Production Cluster
```

MiniGoogle is now a complete, deployable distributed search engine with modern engineering practices from development through production.

---

# End of Chapter 15

The next and final chapter will be **Chapter 16 — Future Directions, Research Extensions & Google-Scale Evolution**. It will explore how MiniGoogle could evolve toward cutting-edge search systems by incorporating:

- Learned indexes
- Neural ranking models
- Distributed vector databases
- TPU/GPU acceleration
- Reinforcement learning for ranking
- Online learning from clicks
- Federated search
- Knowledge graphs
- Multi-modal retrieval
- Agentic search
- LLM-native search
- Research roadmap toward a Google-scale architecture

This chapter will conclude the project by connecting its current implementation to the frontier of search engine research and industry.

# Chapter 16 — Future Directions, Research Extensions & Google-Scale Evolution

---

# Table of Contents

1. Introduction
2. From MiniGoogle to Google
3. Scaling Beyond Billions of Documents
4. Learned Indexes
5. Neural Ranking Models
6. Online Learning from User Feedback
7. Distributed Vector Databases
8. Knowledge Graph Integration
9. Federated Search
10. Multi-Modal Search
11. TPU & GPU Acceleration
12. LLM-Native Search
13. Agentic Search
14. Real-Time Indexing
15. Toward a Planet-Scale Search Engine
16. Research Roadmap
17. Final Architecture
18. Closing Thoughts

---

# 1. Introduction

MiniGoogle is now a complete distributed search engine.

It can

- crawl
- parse
- index
- rank
- replicate
- recover
- monitor
- deploy
- scale horizontally
- perform semantic retrieval

The remaining question is

> How do companies like Google continue improving search?

This chapter explores the technologies currently shaping the future of information retrieval.

---

# 2. From MiniGoogle to Google

Although inspired by Google's architecture, MiniGoogle intentionally sacrifices complexity for clarity.

| Feature | MiniGoogle | Google |
|----------|------------|---------|
| Documents | Millions | Hundreds of Billions |
| Servers | Tens | Millions |
| Data Centers | One | Hundreds |
| Index Updates | Minutes | Seconds |
| Ranking Signals | Dozens | Thousands |
| ML Models | Simple | Thousands of continuously trained models |

Despite this difference in scale, the architectural principles remain remarkably similar.

---

# 3. Scaling Beyond Billions of Documents

As datasets grow, new bottlenecks emerge.

### Storage

```
10 Million Documents

↓

≈20 GB Index
```

```
10 Billion Documents

↓

≈20 TB Index
```

```
100 Billion Documents

↓

Petabyte Scale
```

Compression alone is no longer enough.

The storage architecture itself must evolve.

Future improvements include

- columnar storage
- distributed object stores
- tiered SSD/HDD architectures
- remote memory

---

# 4. Learned Indexes

Traditional search engines rely on

```
Hash Tables

B-Trees

Posting Lists
```

Recent research proposes

```
Machine Learning

↓

Predict Data Locations
```

Instead of

searching

the model predicts

approximately where data resides.

Advantages

- fewer cache misses
- lower memory usage
- improved lookup latency

---

# 5. Neural Ranking Models

Current ranking

```
BM25

+

PageRank

+

Cosine Similarity
```

Future ranking

```
Transformer

↓

Predict Relevance
```

Instead of manually engineered scoring,

the model directly estimates

```
P(Document Relevant | Query)
```

Examples include

- BERT
- T5
- ColBERT
- RankT5

MiniGoogle can easily replace the reranking stage with these models.

---

# 6. Online Learning from User Feedback

Every click teaches the search engine.

Example

```
Query

↓

Results

↓

User Clicks

↓

Positive Signal
```

Ignored results become

negative examples.

The ranking model continuously improves.

Possible signals

- click-through rate
- dwell time
- query reformulation
- abandonment
- scrolling depth

---

# 7. Distributed Vector Databases

Instead of storing vectors beside the inverted index,

future architectures separate them.

```
Search Engine

↓

Vector Database

↓

Nearest Neighbors
```

The vector database manages

- HNSW
- replication
- compression
- sharding
- persistence

This enables semantic search at very large scale.

---

# 8. Knowledge Graph Integration

Search is more than documents.

It also understands

entities.

Example

```
Alan Turing

↓

Scientist

↓

Worked At

↓

University of Manchester
```

Instead of matching text,

the engine traverses relationships.

Knowledge graphs improve

- factual accuracy
- entity search
- question answering
- recommendations

---

# 9. Federated Search

Information comes from multiple sources.

```
Web

↓

Documents
```

```
Images

↓

Image Index
```

```
Videos

↓

Video Index
```

```
Academic Papers

↓

Research Index
```

The coordinator queries all indexes simultaneously.

Results are merged into one interface.

---

# 10. Multi-Modal Search

Future search engines retrieve more than text.

Possible inputs

```
Text

Image

Audio

Video

PDF

Code
```

Example

User uploads

```
Circuit Diagram
```

Search engine retrieves

similar diagrams,

documentation,

and source code.

All modalities share

a common embedding space.

---

# 11. TPU & GPU Acceleration

Vector operations are massively parallel.

Instead of

CPU

```
1 cosine

↓

next cosine
```

GPU

```
10,000 cosine similarities

simultaneously
```

Benefits

- ANN construction
- embedding generation
- neural reranking
- LLM inference

MiniGoogle's architecture separates retrieval and ranking, allowing GPU acceleration without redesigning the system.

---

# 12. LLM-Native Search

Traditional search returns links.

Modern systems increasingly return

answers.

Pipeline

```
User Question

↓

Hybrid Retrieval

↓

Evidence

↓

Large Language Model

↓

Grounded Answer
```

The search engine becomes

the factual memory

of the LLM.

Hallucinations decrease dramatically.

---

# 13. Agentic Search

Instead of one query,

future systems execute

plans.

Example

```
"Compare all open-source vector databases."
```

Agent

↓

Break Task

↓

Search

↓

Read

↓

Search Again

↓

Compare

↓

Summarize

↓

Final Report
```

Search becomes iterative.

Reasoning and retrieval cooperate.

---

# 14. Real-Time Indexing

Current architecture

```
Crawler

↓

Indexer

↓

Segment

↓

Merge
```

Future

```
Document Published

↓

Streaming Pipeline

↓

Immediate Index

↓

Immediately Searchable
```

Latency

drops from minutes

to seconds.

Streaming systems

make this possible.

---

# 15. Toward a Planet-Scale Search Engine

A hypothetical evolution

```
Millions of Servers

↓

Hundreds of Regions

↓

Petabytes of Indexes

↓

Continuous Crawling

↓

Continuous Learning

↓

Continuous Deployment
```

Every subsystem must

- self-heal
- self-balance
- self-monitor
- self-optimize

Human intervention becomes minimal.

---

# 16. Research Roadmap

Future improvements for MiniGoogle

## Information Retrieval

- Learning-to-Rank
- Personalized Ranking
- Query Intent Detection
- Dynamic Pruning
- Adaptive BM25

---

## Distributed Systems

- Byzantine Fault Tolerance
- CRDT Replication
- Geo-Distributed Consensus
- Edge Search Nodes

---

## Machine Learning

- Personalized Embeddings
- Session-Based Retrieval
- Reinforcement Learning
- Retrieval-Augmented Generation
- Multi-Agent Search

---

## Infrastructure

- Autoscaling
- Spot Instance Scheduling
- Storage Tiering
- Cost-Aware Scheduling

---

## Security

- Confidential Computing
- Secure Multi-Party Search
- Differential Privacy
- Homomorphic Encryption

---

# 17. Final Architecture

```
                           Internet

                               │

                         Load Balancer

                               │

                        REST API Gateway

                               │

                   Search Coordinator Cluster

                               │

         ┌─────────────────────┼─────────────────────┐

         ▼                     ▼                     ▼

 Lexical Retrieval      Semantic Retrieval     Knowledge Graph

         ▼                     ▼                     ▼

     BM25 Engine          HNSW Engine         Entity Engine

         └───────────────┬────────────────────┘

                         ▼

                 Hybrid Ranking Engine

                         ▼

                Cross-Encoder Reranker

                         ▼

                  Retrieval Pipeline

                         ▼

                LLM / Agent Interface

                         ▼

                  Grounded Responses

                         ▼

                      Monitoring

                         ▼

                  Metrics & Tracing

                         ▼

                  Distributed Storage

                         ▼

            Raft • Replication • Snapshots

                         ▼

            Kubernetes Production Cluster
```

Every subsystem is independently scalable while communicating through well-defined interfaces.

---

# 18. Closing Thoughts

MiniGoogle demonstrates that a modern search engine is not a single algorithm but the composition of many carefully engineered systems.

Over the course of this project, we implemented concepts from

- Information Retrieval
- Distributed Systems
- Networking
- Databases
- Operating Systems
- Machine Learning
- Software Engineering
- DevOps
- Observability
- Cloud Computing

The final result is far more than a search engine.

It is a comprehensive software engineering project that showcases the ability to design, implement, and operate a complex distributed system from first principles.

More importantly, the architecture is intentionally modular. Every component can be replaced, optimized, or extended independently, making MiniGoogle an ideal foundation for future research projects, production experiments, or advanced AI systems.

---

# Complete System Pipeline

```
Web

↓

Distributed Crawler

↓

HTML Parser

↓

Tokenizer

↓

Inverted Index

+

Embedding Generator

↓

Immutable Segments

↓

Distributed Storage

↓

Replication

↓

Scatter-Gather Search

↓

BM25

+

HNSW

↓

Hybrid Ranking

↓

Cross-Encoder

↓

Knowledge Graph

↓

Snippet Generator

↓

REST API

↓

Browser

↓

Metrics

↓

Logs

↓

Tracing

↓

Monitoring

↓

Continuous Improvement
```

---

# Final Outcome

MiniGoogle is no longer simply a programming project.

It demonstrates competence in:

- Algorithms and Data Structures
- Distributed Systems
- Information Retrieval
- Large-Scale Data Processing
- Search Engine Design
- High-Performance Java
- Production Infrastructure
- Cloud-Native Deployment
- AI-Augmented Retrieval
- Systems Architecture

The project is intentionally designed to grow. Future work can focus on any subsystem without requiring a rewrite of the entire platform, making it a realistic long-term engineering platform rather than a one-off academic exercise.

---

# End of Chapter 16

## End of the MiniGoogle Architecture Specification

This concludes the full architectural design. The next phase is implementation, where each chapter becomes production-quality Java code with comprehensive unit tests, integration tests, benchmarks, and documentation. By following the progression established in these sixteen chapters, the implementation can evolve incrementally while remaining maintainable, testable, and faithful to the overall architecture.

