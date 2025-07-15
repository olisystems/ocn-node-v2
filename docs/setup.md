### Requirements
- Java 21+
- Postgres (not for tests)
- [OCN Registry](https://github.com/olisystems/ocn-registry-v2.0) contracts indexed on a graphql server
- [OCN Parties Registered](https://github.com/olisystems/transit-ocn-onboarding) (soft dependency)
### Spring Profiles
By default, Spring apps have multiple target environments mapped by the `src/main/resources/application-*.properties` files.

The default spring boot profile is `dev`. It is loaded during build because some variables define router paths and other compilation-time definitions. Existing configurations are:
- `test` (uses H2 in-memory db)
- `dev` (depends on Postgres, use `docker compose`)
- `local-minikube` (kubernetes deployment)
- `prod`

### Build and Run Command Line
- Install Java 21 or latest
- Make sure Java is set by running `java --version` and `echo $JAVA_HOME` 
- Run the `gradlew build` script for your OS
- Building will trigger all unit tests
- Tests force the `test` spring profile to load
- Run the app with embedded DB `gradew bootTest`

Or run a local postgres and application.
```shell
docker compose up
gradew bootRunDev
```
### Configuring IntelliJ
Follow your IDE tips to download Java 21 or latest and to load Gradle. Here are instructions on how to [build](https://www.jetbrains.com/help/idea/getting-started-with-gradle.html#deploy_gradle) with gradle. Then configure the Spring Boot Profile using this  [instructions](https://www.jetbrains.com/help/idea/running-a-java-app-in-a-container.html#run_java_app_in_container).

> Tip: When using IntelliJ you can debug directly from `docker compose` by following these [instructions](https://www.jetbrains.com/help/idea/running-a-java-app-in-a-container.html#run_java_app_in_container). It must be possible to replicate this using other IDEs or from command-line using `jdb`, as IntelliJ cannot perform _magic_ ✨. It is simply attaching to a file or port to perform the debugging.





