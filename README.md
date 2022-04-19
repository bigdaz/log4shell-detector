# Export API Dependency Extractor

This Java example provides a summary of dependency versions used across builds.
By default, this project will detect all `log4j` dependencies used, but can be adapted for other dependencies.

This project is based on the Java sample in https://github.com/gradle/gradle-enterprise-export-api-samples.

## Setup

To run this sample:

1. Replace the hostname value of the `GRADLE_ENTERPRISE_SERVER` constant in [`ExportApiDependencyExtractor.java`][ExportApiDependencyExtractor] with your Gradle Enterprise hostname.
2. Optionally change the value of the `DEPENDENCY_GROUP_PREFIX` constant to filter for different dependencies. Set this value to '' to extract _all_ dependency versions.
3. Optionally change the value of the `PROCESS_SCANS_SINCE` constant to extend the range of scans to process. By default, scans published in the past 24 hrs are processed.
4. Set the appropriate authentication environment variables (see below).
5. Run `./gradlew run` from the command line.

### Authentication

We recommend using the Bearer token authentication which has been available since Gradle Enterprise 2021.1.

#### Bearer token authentication (access key)

1. Create a Gradle Enterprise access key for a user with the `Export API` role as described in the [Export API Access Control] documentation.
2. Set an environment variable locally: `EXPORT_API_ACCESS_KEY` to match the newly created Gradle Enterprise access key.

#### Basic authentication (user / password)

Non-SAML users can authenticate via basic auth.

1. Create a Gradle Enterprise user with the `Export API` role as described in the [Export API Access Control] documentation.
2. Set two environment variables locally: `EXPORT_API_USER` `EXPORT_API_PASSWORD` to match the newly created Export API user credentials.

## Sample output
```
Streaming builds...
Processing build: hmvewcdjvsl54
Processing build: nymyvw7pt32b2
mtrpn4sdlcnq6: org.apache.logging.log4j:log4j-api:2.17.1
mtrpn4sdlcnq6: org.apache.logging.log4j:log4j-core:2.17.1
Processing build: ozvdxgi2yr66e
...

Processing build: wguszvsvcvysy

VERSION SUMMARY: (Lists first instance found for each version)
--------------------------------------------------------------
txfjcwedzacmw: org.apache.logging.log4j:log4j-api:2.11.2
arp4cremlczjw: org.apache.logging.log4j:log4j-api:2.17.1
arp4cremlczjw: org.apache.logging.log4j:log4j-core:2.17.1
```

[ExportApiDependencyExtractor]: src/main/java/com/gradle/enterprise/export/ExportApiDependencyExtractor.java
[Export API Access Control]: https://docs.gradle.com/enterprise/export-api/#access_control
