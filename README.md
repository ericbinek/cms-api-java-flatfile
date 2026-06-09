# schema.org aligned CMS API (Java)

[![Tests](https://github.com/ericbinek/cms-api-java-flatfile/actions/workflows/test.yml/badge.svg)](https://github.com/ericbinek/cms-api-java-flatfile/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)
![Status](https://img.shields.io/badge/status-work_in_progress-orange.svg)
![Build in public](https://img.shields.io/badge/build-in_public-ff69b4.svg)
![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)
![Java 25](https://img.shields.io/badge/Java-25-orange.svg)

A standalone, schema.org aligned CMS API written in plain Java 25.

It runs on the JDK alone: `com.sun.net.httpserver` handles HTTP and the standard library does the rest, with no runtime dependencies.

It exposes CRUD endpoints for 10 schema.org entity types such as BlogPosting, Person, and WebPage, backed by flat-file JSON storage, with validation, pagination, filtering, sorting, ETag caching, and reference embedding.

A conformance test suite defines the HTTP contract.

## Status: work in progress (v0.1.0)

This is an ongoing build-in-public project, shared only for community and communication purposes. Do not deploy it in production. Do not rely on its interfaces or data format remaining stable.

## No framework

There is no Spring, no Jakarta, and no Maven plugins doing work behind your back. The HTTP layer is `com.sun.net.httpserver`, JSON is handled by a small hand-written parser, and the build is a plain `javac`. If you know the JDK, you already know how this runs.

## Requirements

- JDK 25 or newer

## Building

```sh
find src -name "*.java" > sources.txt && javac -d out @sources.txt && rm sources.txt
```

## Running

```sh
java -cp out cms.Server
```

The server listens on `PORT` (default 3006).

## Usage

```sh
curl http://localhost:3006/blog-postings
```

All list endpoints return `{ items, total }`. See per-entity routes below.

## Entities

- `BlogPosting`
- `Person`
- `WebPage`
- `ImageObject`
- `CategoryCode`
- `CategoryCodeSet`
- `DefinedTerm`
- `DefinedTermSet`
- `Comment`
- `WebSite`

## Testing

```sh
java -cp out cms.test.TestRunner
```

## Contributing

Contributions are welcome. This is a build-in-public project, so issues, questions, and ideas count as much as pull requests. If you send code, keep it on the JDK standard library with no new dependencies, and keep the conformance suite green, since the tests are the contract. Run them with `java -cp out cms.test.TestRunner`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full guidelines.

## License

MIT. See [LICENSE](LICENSE).
