# Contributing to cms-api-java-flatfile

Thanks for taking a look. This is a build-in-public project at version 0.3.0, so it is still moving and contributions of every kind are welcome: bug reports, questions, ideas, and code.

## Ground rules

- Stay on the JDK. The point of this project is the standard library, so please do not add Spring, Jackson, or any other dependency.
- The conformance test suite is the contract. If you change behavior, change the tests in the same pull request and explain why. Keep them green.
- This is not production software, and the README says so. Please keep that framing.

## Getting started

```sh
git clone https://github.com/ericbinek/cms-api-java-flatfile.git
cd cms-api-java-flatfile
find src -name "*.java" > sources.txt && javac -d out @sources.txt && rm sources.txt
```

Run it:

```sh
java -cp out cms.Server
```

Run the tests:

```sh
java -cp out cms.test.TestRunner
```

There is no Maven or Gradle build to learn: a plain `javac` compiles everything.

## Sending a change

1. For anything beyond a small fix, open an issue or discussion first so we do not duplicate work.
2. Keep each pull request focused on one thing.
3. Run the test suite locally and make sure it is green before you open the pull request.
4. Describe what changed and why.

## Style

Plain JDK Java: `com.sun.net.httpserver` for HTTP, the standard library for the rest, no framework. Match the surrounding code.
