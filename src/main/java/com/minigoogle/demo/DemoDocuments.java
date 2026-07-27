package com.minigoogle.demo;

import com.minigoogle.crawler.model.ParsedDocument;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hardcoded test corpus for the demo. 20 pages covering diverse topics
 * so the search engine has something meaningful to query against.
 */
public final class DemoDocuments {

    private DemoDocuments() {}

    public static List<ParsedDocument> all() {
        List<ParsedDocument> docs = new ArrayList<>();
        Instant now = Instant.now();

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/java-overview"),
            "Java Programming Language",
            "Java is a class-based, object-oriented programming language designed for portability. " +
            "Java applications are compiled to bytecode that runs on the Java Virtual Machine. " +
            "The language was created by James Gosling at Sun Microsystems in 1995. " +
            "Java is widely used for enterprise server applications, Android mobile development, " +
            "and large-scale distributed systems. Key features include garbage collection, " +
            "strong type safety, and the write once run anywhere philosophy. " +
            "The latest long-term support version introduces records, sealed classes, and pattern matching.",
            List.of(
                URI.create("https://example.com/distributed-systems"),
                URI.create("https://example.com/software-engineering"),
                URI.create("https://example.com/algorithms-sorting")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/python-data-science"),
            "Python for Data Science",
            "Python has become the dominant language for data science and machine learning. " +
            "Libraries like NumPy, Pandas, and Scikit-learn provide powerful tools for numerical computing. " +
            "TensorFlow and PyTorch enable deep learning model development. " +
            "Python simple syntax makes it accessible to researchers and analysts. " +
            "Jupyter notebooks combine code, visualization, and narrative text. " +
            "Data pipelines built in Python process petabytes of information daily at companies like Netflix and Instagram.",
            List.of(
                URI.create("https://example.com/machine-learning"),
                URI.create("https://example.com/data-structures"),
                URI.create("https://example.com/algorithms-sorting")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/distributed-systems"),
            "Building Distributed Systems",
            "Distributed systems consist of multiple networked computers communicating through message passing. " +
            "Key challenges include network partitions, clock synchronization, and consensus. " +
            "The CAP theorem states that a distributed system cannot simultaneously guarantee consistency, availability, and partition tolerance. " +
            "Apache Kafka provides distributed event streaming. " +
            "Raft consensus protocol elects leaders for replicated state machines. " +
            "Gossip protocols enable eventually consistent membership tracking across clusters.",
            List.of(
                URI.create("https://example.com/databases-intro"),
                URI.create("https://example.com/computer-networks"),
                URI.create("https://example.com/cloud-computing"),
                URI.create("https://example.com/java-overview")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/databases-intro"),
            "Introduction to Databases",
            "Databases store and organize structured data for efficient retrieval. " +
            "Relational databases like PostgreSQL and MySQL use SQL for querying. " +
            "ACID transactions guarantee atomicity, consistency, isolation, and durability. " +
            "NoSQL databases like MongoDB and Cassandra optimize for horizontal scaling. " +
            "Indexing strategies such as B-trees and hash indexes dramatically improve query performance. " +
            "Database normalization reduces redundancy by splitting data into logical tables.",
            List.of(
                URI.create("https://example.com/data-structures"),
                URI.create("https://example.com/search-engines"),
                URI.create("https://example.com/software-engineering")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/algorithms-sorting"),
            "Sorting Algorithms Explained",
            "Sorting algorithms arrange elements in a specific order. " +
            "Quicksort averages O(n log n) time complexity using divide and conquer. " +
            "Mergesort guarantees O(n log n) worst case and is stable. " +
            "Heapsort uses a binary heap data structure for in-place sorting. " +
            "Timsort, used in Python and Java, combines merge sort and insertion sort. " +
            "Radix sort achieves linear time for fixed-length integer keys. " +
            "The choice of sorting algorithm depends on data size, memory constraints, and stability requirements.",
            List.of(
                URI.create("https://example.com/data-structures"),
                URI.create("https://example.com/search-engines")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/machine-learning"),
            "Machine Learning Fundamentals",
            "Machine learning enables computers to learn patterns from data without explicit programming. " +
            "Supervised learning trains models on labeled datasets for classification and regression. " +
            "Unsupervised learning discovers hidden structures through clustering and dimensionality reduction. " +
            "Reinforcement learning optimizes sequential decision making through reward signals. " +
            "Neural networks with multiple layers form the basis of deep learning. " +
            "Gradient descent iteratively adjusts model parameters to minimize prediction error.",
            List.of(
                URI.create("https://example.com/artificial-intelligence"),
                URI.create("https://example.com/python-data-science"),
                URI.create("https://example.com/data-structures")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/web-development"),
            "Modern Web Development",
            "Web development encompasses frontend and backend technologies. " +
            "HTML5, CSS3, and JavaScript form the foundation of web pages. " +
            "React, Vue, and Angular provide component-based frontend frameworks. " +
            "Node.js enables server-side JavaScript execution. " +
            "REST APIs and GraphQL facilitate client-server communication. " +
            "Progressive web apps combine the best features of web and native applications. " +
            "WebAssembly brings near-native performance to browser applications.",
            List.of(
                URI.create("https://example.com/software-engineering"),
                URI.create("https://example.com/cloud-computing"),
                URI.create("https://example.com/databases-intro")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/cloud-computing"),
            "Cloud Computing Infrastructure",
            "Cloud computing delivers computing resources over the internet on demand. " +
            "Infrastructure as a Service provides virtual machines and storage. " +
            "Platform as a Service abstracts infrastructure for application deployment. " +
            "Software as a Service delivers applications directly to users. " +
            "AWS, Azure, and Google Cloud dominate the cloud market. " +
            "Serverless computing eliminates server management overhead. " +
            "Auto-scaling adjusts capacity based on traffic patterns and demand.",
            List.of(
                URI.create("https://example.com/distributed-systems"),
                URI.create("https://example.com/devops-practices"),
                URI.create("https://example.com/computer-networks")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/cybersecurity"),
            "Cybersecurity Best Practices",
            "Cybersecurity protects systems and data from digital attacks. " +
            "Encryption transforms readable data into ciphertext using algorithms like AES and RSA. " +
            "Multi-factor authentication adds layers of security beyond passwords. " +
            "Penetration testing identifies vulnerabilities before attackers do. " +
            "Zero trust architecture verifies every request regardless of origin. " +
            "Security information and event management systems monitor for threats in real time. " +
            "Regular security audits and compliance checks maintain organizational security posture.",
            List.of(
                URI.create("https://example.com/computer-networks"),
                URI.create("https://example.com/operating-systems"),
                URI.create("https://example.com/blockchain")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/operating-systems"),
            "Operating System Concepts",
            "Operating systems manage hardware resources and provide services to applications. " +
            "Process scheduling algorithms include round robin, priority, and completely fair scheduler. " +
            "Virtual memory enables programs to use more memory than physically available. " +
            "The Linux kernel uses a monolithic architecture with loadable modules. " +
            "File systems like ext4 and NTFS organize data on storage devices. " +
            "Inter-process communication uses pipes, shared memory, and message queues.",
            List.of(
                URI.create("https://example.com/computer-networks"),
                URI.create("https://example.com/data-structures"),
                URI.create("https://example.com/cybersecurity")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/computer-networks"),
            "Computer Networking Fundamentals",
            "Computer networks connect devices for communication and resource sharing. " +
            "The TCP/IP model defines four layers: link, internet, transport, and application. " +
            "HTTP protocol powers the World Wide Web using request-response messaging. " +
            "DNS translates human-readable domain names to IP addresses. " +
            "Load balancers distribute incoming traffic across multiple servers. " +
            "Virtual private networks encrypt traffic between remote users and corporate networks. " +
            "Software-defined networking separates control plane from data plane.",
            List.of(
                URI.create("https://example.com/distributed-systems"),
                URI.create("https://example.com/cloud-computing"),
                URI.create("https://example.com/cybersecurity")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/artificial-intelligence"),
            "Artificial Intelligence Overview",
            "Artificial intelligence aims to create systems that mimic human cognitive abilities. " +
            "Natural language processing enables machines to understand and generate human language. " +
            "Computer vision interprets images and video using convolutional neural networks. " +
            "Generative AI creates new content including text, images, and code. " +
            "Large language models are trained on vast text corpora using transformer architecture. " +
            "AI alignment research focuses on ensuring artificial intelligence systems remain beneficial to humanity.",
            List.of(
                URI.create("https://example.com/machine-learning"),
                URI.create("https://example.com/python-data-science"),
                URI.create("https://example.com/quantum-computing")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/software-engineering"),
            "Software Engineering Principles",
            "Software engineering applies systematic approaches to software development. " +
            "Agile methodology emphasizes iterative development and continuous feedback. " +
            "Design patterns provide reusable solutions to common architectural problems. " +
            "Test-driven development writes tests before implementation code. " +
            "Code reviews improve quality through peer inspection. " +
            "Continuous integration and deployment automate building, testing, and releasing software. " +
            "Technical debt accumulates when shortcuts compromise code quality.",
            List.of(
                URI.create("https://example.com/web-development"),
                URI.create("https://example.com/devops-practices"),
                URI.create("https://example.com/algorithms-sorting")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/computer-graphics"),
            "Computer Graphics and Rendering",
            "Computer graphics generates visual content from mathematical models. " +
            "Rasterization converts vector graphics into pixel grids for display. " +
            "Ray tracing simulates light physics for photorealistic rendering. " +
            "Graphics processing units accelerate parallel computation for rendering. " +
            "OpenGL and Vulkan provide low-level APIs for GPU programming. " +
            "Shader programs run on the GPU to calculate vertex positions and pixel colors. " +
            "Real-time rendering achieves interactive frame rates for games and simulations.",
            List.of(
                URI.create("https://example.com/java-overview"),
                URI.create("https://example.com/data-structures")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/blockchain"),
            "Blockchain Technology",
            "Blockchain is a distributed ledger technology enabling decentralized trust. " +
            "Cryptographic hash functions link blocks in an immutable chain. " +
            "Consensus mechanisms like proof of work and proof of stake validate transactions. " +
            "Smart contracts execute automatically when predetermined conditions are met. " +
            "Ethereum extends blockchain beyond currency to decentralized applications. " +
            "Decentralized finance protocols recreate traditional financial services without intermediaries. " +
            "Scalability solutions like layer two networks increase transaction throughput.",
            List.of(
                URI.create("https://example.com/cybersecurity"),
                URI.create("https://example.com/distributed-systems"),
                URI.create("https://example.com/internet-of-things")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/internet-of-things"),
            "Internet of Things",
            "The Internet of Things connects physical devices to the internet for data collection and control. " +
            "Sensors measure temperature, humidity, motion, and environmental conditions. " +
            "MQTT and CoAP are lightweight protocols designed for constrained IoT devices. " +
            "Edge computing processes data near the source to reduce latency. " +
            "Smart home devices automate lighting, climate control, and security systems. " +
            "Industrial IoT monitors manufacturing equipment for predictive maintenance. " +
            "IoT security remains a critical concern with billions of connected devices.",
            List.of(
                URI.create("https://example.com/computer-networks"),
                URI.create("https://example.com/cloud-computing"),
                URI.create("https://example.com/cybersecurity")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/quantum-computing"),
            "Quantum Computing Basics",
            "Quantum computing harnesses quantum mechanical phenomena for computation. " +
            "Qubits exist in superposition of zero and one states simultaneously. " +
            "Quantum entanglement correlates qubits across distances. " +
            "Quantum gates manipulate qubits using unitary transformations. " +
            "Shor algorithm factors large integers exponentially faster than classical methods. " +
            "Grover algorithm searches unsorted databases with quadratic speedup. " +
            "Quantum error correction protects fragile quantum states from decoherence.",
            List.of(
                URI.create("https://example.com/algorithms-sorting"),
                URI.create("https://example.com/machine-learning")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/data-structures"),
            "Essential Data Structures",
            "Data structures organize and store data for efficient access and modification. " +
            "Arrays provide O(1) random access but fixed size. " +
            "Linked lists enable efficient insertions and deletions. " +
            "Hash tables achieve average O(1) lookup using key-value pairs. " +
            "Binary search trees maintain sorted order for efficient range queries. " +
            "Graphs represent relationships between entities using vertices and edges. " +
            "Bloom filters provide space-efficient probabilistic membership testing with configurable false positive rates. " +
            "Skip lists enable O(log n) search in sorted sequences using layered linked lists.",
            List.of(
                URI.create("https://example.com/algorithms-sorting"),
                URI.create("https://example.com/search-engines"),
                URI.create("https://example.com/databases-intro")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/search-engines"),
            "How Search Engines Work",
            "Search engines discover, index, and rank web pages for information retrieval. " +
            "Web crawlers follow links to discover new pages systematically. " +
            "Inverted indexes map terms to the documents containing them for fast lookup. " +
            "TF-IDF measures term importance by balancing frequency against document rarity. " +
            "BM25 is a probabilistic ranking function used by Elasticsearch and Lucene. " +
            "PageRank evaluates page authority through link analysis and graph theory. " +
            "Query processing involves parsing, planning, execution, and result ranking. " +
            "Distributed search engines shard their index across multiple nodes for scalability. " +
            "Spell correction and query suggestion improve the user search experience.",
            List.of(
                URI.create("https://example.com/distributed-systems"),
                URI.create("https://example.com/data-structures"),
                URI.create("https://example.com/algorithms-sorting"),
                URI.create("https://example.com/databases-intro")
            ), now
        ));

        docs.add(new ParsedDocument(
            UUID.randomUUID(),
            URI.create("https://example.com/devops-practices"),
            "DevOps and Platform Engineering",
            "DevOps bridges software development and IT operations for faster delivery. " +
            "Continuous integration merges code changes frequently and runs automated tests. " +
            "Continuous delivery automates deployment to production environments. " +
            "Infrastructure as code defines servers and networks using version-controlled configuration. " +
            "Docker containers package applications with their dependencies for consistent deployment. " +
            "Kubernetes orchestrates container deployment, scaling, and management. " +
            "Monitoring and observability provide insights into system behavior using metrics, logs, and traces. " +
            "Incident response procedures minimize downtime when production issues occur.",
            List.of(
                URI.create("https://example.com/cloud-computing"),
                URI.create("https://example.com/distributed-systems"),
                URI.create("https://example.com/software-engineering")
            ), now
        ));

        return docs;
    }
}
