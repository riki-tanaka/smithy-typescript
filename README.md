## Smithy Typescript

Smithy code generators for TypeScript.

## Generating a client

This repository builds TypeScript declarations and JavaScript clients from Smithy
models.

The `smithy-typescript-codegen-test` package in this repos is an example of
how to build a TypeScript client. The steps needed to build a TypeScript client
are as follows:

1. Create a new directory for your package. For example, "foo-client".
2. Create a `build.gradle.kts` file with the following contents:

   ```kotlin
   plugins {
       id("software.amazon.smithy").version("0.3.0")
   }

   dependencies {
       implementation(project(":smithy-typescript-codegen"))
   }
   ```

nb. If you wish to setup your package outside the `smithy-typescript` package,
use the fully qualified implementation in your dependencies instead of the
`project`.

   ```kotlin
   plugins {
       id("software.amazon.smithy").version("0.3.0")
   }

   dependencies {
       implementation("software.amazon.smithy:smithy-typescript-codegen:0.1.0")
   }
   ```


3. Create a `smithy-build.json` file with the following contents,
   substituting "example.foo#MyClient" with the name of the service
   to generate and "foo" with the name of the TypeScript package to
   create.:

   ```json
   {
       "version": "1.0",
       "plugins": {
           "typescript-codegen": {
               "service": "example.weather#Weather",
               "package": "weather",
               "packageVersion": "0.0.1",
               "packageJson": {
                   "license": "Apache-2.0"
               }
           }
       }
   }

   ```

4. Create a directory named `model`. This is where all of your Smithy models
   will go.

5. Run `gradle build` (alternatively, you can use a
   [Gradle wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)).

6. The generated client can be found in `build/smithyprojections/foo-client/source/typescript-codegen`.

See [the Smithy documentation](https://awslabs.github.io/smithy/guides/building-models/gradle-plugin.html)
for more information on build Smithy projects with Gradle.

## TypeScript code generation

TODO

## Steps to build

This repo is under heavy development. You will need to use unreleased
features to build.

First, build software.amazon.smithy.* packages and publish them
to Maven local:

- `git clone https://github.com/awslabs/smithy.git`
- `cd smithy`
- `./gradlew publishToMavenLocal`
- `cd ..`
- `git clone https://github.com/awslabs/smithy-typescript.git`
- `cd smithy-typescript`
- `git checkout develop`
- `./gradlew build`

You can find the build artifacts of the test package at:
`build/smithyprojections/smithy-typescript-codegen-test/source/typescript-codegen`


## License

This library is licensed under the Apache 2.0 License.
