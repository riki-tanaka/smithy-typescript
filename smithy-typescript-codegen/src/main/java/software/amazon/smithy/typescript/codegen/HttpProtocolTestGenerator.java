/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.typescript.codegen;

import static java.lang.String.format;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBinding.Location;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeVisitor;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.protocoltests.traits.AppliesTo;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.Pair;

/**
 * Generates HTTP protocol test cases to be run using Jest.
 *
 * <p>Protocol tests are defined for HTTP protocols using the
 * {@code smithy.test#httpRequestTests} and {@code smithy.test#httpResponseTests}
 * traits. When found on operations or errors attached to operations, a
 * protocol test case will be generated that asserts that the protocol
 * serialization and deserialization code creates the correct HTTP requests
 * and responses for a specific set of parameters.
 *
 * TODO: try/catch and if/else are still cumbersome with TypeScriptWriter.
 */
final class HttpProtocolTestGenerator implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(HttpProtocolTestGenerator.class.getName());
    private static final String TEST_CASE_FILE_TEMPLATE = "tests/functional/%s.spec.ts";

    private final TypeScriptSettings settings;
    private final Model model;
    private final ShapeId protocol;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final Symbol serviceSymbol;
    private final Set<String> additionalStubs = new TreeSet<>();

    /** Vends a TypeScript IFF it's needed. */
    private final TypeScriptDelegator delegator;

    /** The TypeScript writer that's only allocated once if needed. */
    private TypeScriptWriter writer;

    HttpProtocolTestGenerator(
            TypeScriptSettings settings,
            Model model,
            ShapeId protocol,
            SymbolProvider symbolProvider,
            TypeScriptDelegator delegator
    ) {
        this.settings = settings;
        this.model = model;
        this.protocol = protocol;
        this.service = settings.getService(model);
        this.symbolProvider = symbolProvider;
        this.delegator = delegator;
        serviceSymbol = symbolProvider.toSymbol(service);
    }

    @Override
    public void run() {
        OperationIndex operationIndex = OperationIndex.of(model);
        TopDownIndex topDownIndex = TopDownIndex.of(model);

        // Use a TreeSet to have a fixed ordering of tests.
        for (OperationShape operation : new TreeSet<>(topDownIndex.getContainedOperations(service))) {
            if (!operation.hasTag("server-only")) {
                // 1. Generate test cases for each request.
                operation.getTrait(HttpRequestTestsTrait.class).ifPresent(trait -> {
                    for (HttpRequestTestCase testCase : trait.getTestCasesFor(AppliesTo.CLIENT)) {
                        onlyIfProtocolMatches(testCase, () -> generateRequestTest(operation, testCase));
                    }
                });
                // 2. Generate test cases for each response.
                operation.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                    for (HttpResponseTestCase testCase : trait.getTestCasesFor(AppliesTo.CLIENT)) {
                        onlyIfProtocolMatches(testCase, () -> generateResponseTest(operation, testCase));
                    }
                });
                // 3. Generate test cases for each error on each operation.
                for (StructureShape error : operationIndex.getErrors(operation)) {
                    if (!error.hasTag("server-only")) {
                        error.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                            for (HttpResponseTestCase testCase : trait.getTestCasesFor(AppliesTo.CLIENT)) {
                                onlyIfProtocolMatches(testCase,
                                        () -> generateErrorResponseTest(operation, error, testCase));
                            }
                        });
                    }
                }
            }
        }

        // Include any additional stubs required.
        for (String additionalStub : additionalStubs) {
            writer.write(IoUtils.readUtf8Resource(getClass(), additionalStub));
        }
    }

    // Only generate test cases when its protocol matches the target protocol.
    private <T extends HttpMessageTestCase> void onlyIfProtocolMatches(T testCase, Runnable runnable) {
        if (testCase.getProtocol().equals(protocol)) {
            LOGGER.fine(() -> format("Generating protocol test case for %s.%s", service.getId(), testCase.getId()));
            allocateWriterIfNeeded();
            runnable.run();
        }
    }

    private void allocateWriterIfNeeded() {
        if (writer == null) {
            delegator.useFileWriter(createTestCaseFilename(), writer -> this.writer = writer);
            writer.addDependency(TypeScriptDependency.AWS_SDK_TYPES);
            writer.addDependency(TypeScriptDependency.AWS_SDK_PROTOCOL_HTTP);
            // Add the template to each generated test.
            writer.write(IoUtils.readUtf8Resource(getClass(), "protocol-test-stub.ts"));
        }
    }

    private String createTestCaseFilename() {
        String baseName = protocol.getName().toLowerCase(Locale.US)
                .replace("-", "_")
                .replace(".", "_");
        return TEST_CASE_FILE_TEMPLATE.replace("%s", baseName);
    }

    private void generateRequestTest(OperationShape operation, HttpRequestTestCase testCase) {
        Symbol operationSymbol = symbolProvider.toSymbol(operation);

        String testName = testCase.getId() + ":Request";
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        writer.openBlock("it($S, async () => {", "});\n", testName, () -> {
            // Create a client with a custom request handler that intercepts requests.
            writer.openBlock("const client = new $T({", "});\n", serviceSymbol, () -> {
                    writer.write("...clientParams,");
                    writer.write("requestHandler: new RequestSerializationTestHandler(),");
            });

            // Run the parameters through a visitor to adjust for TS specific inputs.
            ObjectNode params = testCase.getParams();
            Optional<ShapeId> inputOptional = operation.getInput();
            if (inputOptional.isPresent()) {
                StructureShape inputShape = model.expectShape(inputOptional.get(), StructureShape.class);
                writer.write("const command = new $T(", operationSymbol)
                        .call(() -> params.accept(new CommandInputNodeVisitor(inputShape)))
                        .write(");");
            } else {
                writer.write("const command = new $T({});", operationSymbol);
            }

            // Send the request and look for the expected exception to then perform assertions.
            writer.write("try {\n"
                       + "  await client.send(command);\n"
                       + "  fail('Expected an EXPECTED_REQUEST_SERIALIZATION_ERROR to be thrown');\n"
                       + "  return;\n"
                       + "} catch (err) {\n"
                       + "  if (!(err instanceof EXPECTED_REQUEST_SERIALIZATION_ERROR)) {\n"
                       + "    fail(err);\n"
                       + "    return;\n"
                       + "  }\n"
                       + "  const r = err.request;")
                    .indent()
                    .call(() -> writeRequestAssertions(operation, testCase))
                    .dedent()
                    .write("}");
        });
    }

    // Ensure that the serialized request matches the expected request.
    private void writeRequestAssertions(OperationShape operation, HttpRequestTestCase testCase) {
        writer.write("expect(r.method).toBe($S);", testCase.getMethod());
        writer.write("expect(r.path).toBe($S);", testCase.getUri());

        writeRequestHeaderAssertions(testCase);
        writeRequestQueryAssertions(testCase);
        writeRequestBodyAssertions(operation, testCase);
    }

    private void writeRequestQueryAssertions(HttpRequestTestCase testCase) {
        testCase.getRequireQueryParams().forEach(requiredQueryParam ->
                writer.write("expect(r.query[$S]).toBeDefined();", requiredQueryParam));
        writer.write("");

        testCase.getForbidQueryParams().forEach(forbidQueryParam ->
                writer.write("expect(r.query[$S]).toBeUndefined();", forbidQueryParam));
        writer.write("");

        List<String> explicitQueryValues = testCase.getQueryParams();
        if (!explicitQueryValues.isEmpty()) {
            // Use buildQueryString like the fetch handler will.
            writer.addDependency(TypeScriptDependency.AWS_SDK_QUERYSTRING_BUILDER);
            writer.addImport("buildQueryString", "buildQueryString", "@aws-sdk/querystring-builder");

            writer.write("const queryString = buildQueryString(r.query);");
            explicitQueryValues.forEach(explicitQueryValue ->
                    writer.write("expect(queryString).toContain($S);", explicitQueryValue));
        }
        writer.write("");
    }

    private void writeRequestHeaderAssertions(HttpRequestTestCase testCase) {
        testCase.getRequireHeaders().forEach(requiredHeader -> {
            writer.write("expect(r.headers[$S]).toBeDefined();", requiredHeader.toLowerCase());
        });
        writer.write("");

        testCase.getForbidHeaders().forEach(forbidHeader ->
                writer.write("expect(r.headers[$S]).toBeUndefined();", forbidHeader.toLowerCase()));
        writer.write("");

        testCase.getHeaders().forEach((header, value) -> {
            header = header.toLowerCase();
            writer.write("expect(r.headers[$S]).toBeDefined();", header);
            writer.write("expect(r.headers[$S]).toBe($S);", header, value);
        });
        writer.write("");
    }

    private void writeRequestBodyAssertions(OperationShape operation, HttpRequestTestCase testCase) {
        testCase.getBody().ifPresent(body -> {
            // If we expect an empty body, expect it to be falsy.
            if (body.isEmpty()) {
                writer.write("expect(r.body).toBeFalsy();");
                return;
            }

            // Fast fail if we don't have a body.
            writer.write("expect(r.body).toBeDefined();");

            // Otherwise load a media type specific comparator and do a comparison.
            String mediaType = testCase.getBodyMediaType().orElse("UNKNOWN");
            String comparatorInvoke = registerBodyComparatorStub(mediaType);

            // Handle escaping strings with quotes inside them.
            writer.write("const bodyString = `$L`;", body.replace("\"", "\\\""));
            writer.write("const unequalParts: any = $L;", comparatorInvoke);
            writer.write("expect(unequalParts).toBeUndefined();");
        });
    }

    private String registerBodyComparatorStub(String mediaType) {
        // Load an additional stub to handle body comparisons for the
        // set of bodyMediaType values we know of.
        switch (mediaType) {
            case "application/x-www-form-urlencoded":
                additionalStubs.add("protocol-test-form-urlencoded-stub.ts");
                return "compareEquivalentFormUrlencodedBodies(bodyString, r.body.toString())";
            case "application/json":
                additionalStubs.add("protocol-test-json-stub.ts");
                return "compareEquivalentJsonBodies(bodyString, r.body.toString())";
            case "application/xml":
                writer.addDependency(TypeScriptDependency.XML_PARSER);
                writer.addImport("parse", "xmlParse", "fast-xml-parser");
                additionalStubs.add("protocol-test-xml-stub.ts");
                return "compareEquivalentXmlBodies(bodyString, r.body.toString())";
            case "application/octet-stream":
                additionalStubs.add("protocol-test-octet-stream-stub.ts");
                return "compareEquivalentOctetStreamBodies(client.config, bodyString, r.body)";
            case "text/plain":
                additionalStubs.add("protocol-test-text-stub.ts");
                return "compareEquivalentTextBodies(bodyString, r.body)";
            default:
                LOGGER.warning("Unable to compare bodies with unknown media type `" + mediaType
                        + "`, defaulting to direct comparison.");
                additionalStubs.add("protocol-test-unknown-type-stub.ts");
                return "compareEquivalentUnknownTypeBodies(client.config, bodyString, r.body)";
        }
    }

    private void generateResponseTest(OperationShape operation, HttpResponseTestCase testCase) {
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        String testName = testCase.getId() + ":Response";
        writer.openBlock("it($S, async () => {", "});\n", testName, () -> {
            writeResponseTestSetup(operation, testCase, true);

            // Invoke the handler and look for the expected response to then perform assertions.
            writer.write("let r: any;");
            writer.write("try {\n"
                       + "  r = await client.send(command);\n"
                       + "} catch (err) {\n"
                       + "  fail('Expected a valid response to be returned, got err.');\n"
                       + "  return;\n"
                       + "}");
            writeResponseAssertions(operation, testCase);
        });
    }

    private void generateErrorResponseTest(
            OperationShape operation,
            StructureShape error,
            HttpResponseTestCase testCase
    ) {
        Symbol errorSymbol = symbolProvider.toSymbol(error);

        // Use a compound test_case name so we generate unique tests
        // for each error on each operation safely. This is useful in validating
        // that operation parsers are all correctly identifying errors and that
        // we can test for any operation specific values properly.
        String testName = testCase.getId() + ":Error:" + operation.getId().getName();
        testCase.getDocumentation().ifPresent(writer::writeDocs);
        writer.openBlock("it($S, async () => {", "});\n", testName, () -> {
            writeResponseTestSetup(operation, testCase, false);

            // Invoke the handler and look for the expected exception to then perform assertions.
            writer.write("try {\n"
                       + "  await client.send(command);\n"
                       + "} catch (err) {\n"
                       + "  if (err.name !== \"$T\") {\n"
                       + "    console.log(err);\n"
                       + "    fail(`Expected a $L to be thrown, got $${err.name} instead`);\n"
                       + "    return;\n"
                       + "  }\n"
                       + "  const r: any = err;", errorSymbol, error.getId().getName())
                    .indent()
                    .call(() -> writeResponseAssertions(error, testCase))
                    .write("return;")
                    .dedent()
                    .write("}");
            writer.write("fail('Expected an exception to be thrown from response');");
        });
    }

    private void writeResponseTestSetup(OperationShape operation, HttpResponseTestCase testCase, boolean isSuccess) {
        Symbol operationSymbol = symbolProvider.toSymbol(operation);

        // Lowercase all the headers we're expecting as this is what we'll get.
        Map<String, String> headers = testCase.getHeaders().entrySet().stream()
                .map(entry -> new Pair<>(entry.getKey().toLowerCase(Locale.US), entry.getValue()))
                .collect(MapUtils.toUnmodifiableMap(Pair::getLeft, Pair::getRight));
        String headerParameters = Node.prettyPrintJson(ObjectNode.fromStringMap(headers));
        String body = testCase.getBody().orElse(null);

        // Create a client with a custom request handler that intercepts requests.
        writer.openBlock("const client = new $T({", "});\n", serviceSymbol, () -> {
                writer.write("...clientParams,");
                writer.openBlock("requestHandler: new ResponseDeserializationTestHandler(", ")", () -> {
                    writer.write("$L,", isSuccess);
                    writer.write("$L,", testCase.getCode());
                    writer.write("$L,", headers.isEmpty() ? "undefined" : headerParameters);
                    if (body != null) {
                        writer.write("`$L`,", body);
                    }
                });
            });

        // Set the command's parameters to empty, using the any type to
        // trick TS in to letting us send this command through.
        writer.write("const params: any = {};");
        writer.write("const command = new $T(params);\n", operationSymbol);

    }

    // Ensure that the serialized response matches the expected response.
    private void writeResponseAssertions(Shape operationOrError, HttpResponseTestCase testCase) {
        writer.write("expect(r['$$metadata'].httpStatusCode).toBe($L);", testCase.getCode());

        writeParamAssertions(operationOrError, testCase);
    }

    private void writeParamAssertions(Shape operationOrError, HttpResponseTestCase testCase) {
        ObjectNode params = testCase.getParams();
        if (!params.isEmpty()) {
            StructureShape testOutputShape;
            if (operationOrError.isStructureShape()) {
                testOutputShape = operationOrError.asStructureShape().get();
            } else {
                testOutputShape = model.expectShape(
                        operationOrError.asOperationShape().get().getOutput()
                                .orElseThrow(() -> new CodegenException("Foo")),
                        StructureShape.class);
            }

            // Use this trick wrapper to not need more complex trailing comma handling.
            writer.write("const paramsToValidate: any = [")
                    .call(() -> params.accept(new CommandOutputNodeVisitor(testOutputShape)))
                    .write("][0];");

            // Extract a payload binding if present.
            Optional<HttpBinding> payloadBinding = operationOrError.asOperationShape()
                    .map(operationShape -> {
                        HttpBindingIndex index = HttpBindingIndex.of(model);
                        List<HttpBinding> payloadBindings = index.getResponseBindings(operationOrError,
                                Location.PAYLOAD);
                        if (!payloadBindings.isEmpty()) {
                            return payloadBindings.get(0);
                        }
                        return null;
                    });

            // If we have a streaming payload blob, we need to collect it to something that
            // can be compared with the test contents. This emulates the customer experience.
            boolean hasStreamingPayloadBlob = payloadBinding
                    .map(binding ->
                        model.getShape(binding.getMember().getTarget())
                                .filter(Shape::isBlobShape)
                                .filter(s -> s.hasTrait(StreamingTrait.ID))
                                .isPresent())
                    .orElse(false);

            // Do the collection for payload blobs.
            if (hasStreamingPayloadBlob) {
                writer.write("const comparableBlob = await client.config.streamCollector(r[$S]);",
                        payloadBinding.get().getMemberName());
            }

            // Perform parameter comparisons.
            writer.openBlock("Object.keys(paramsToValidate).forEach(param => {", "});", () -> {
                writer.write("expect(r[param]).toBeDefined();");
                if (hasStreamingPayloadBlob) {
                    writer.openBlock("if (param === $S) {", "} else {", payloadBinding.get().getMemberName(), () ->
                            writer.write("expect(equivalentContents(comparableBlob, "
                                    + "paramsToValidate[param])).toBe(true);"));
                    writer.indent();
                }

                writer.write("expect(equivalentContents(r[param], paramsToValidate[param])).toBe(true);");

                if (hasStreamingPayloadBlob) {
                    writer.dedent();
                    writer.write("}");
                }
            });
        }
    }

    /**
     * Supports writing out TS specific input types in the generated code
     * through visiting the target shape at the same time as the node. If
     * instead we just printed out the node, many values would not match on
     * type signatures.
     *
     * This handles properly generating Set types for Set shapes, Date types
     * for numbers that are Timestamp shapes, "undefined" for nulls, boolean
     * values, and auto-filling idempotency token Structure members.
     */
    private final class CommandInputNodeVisitor implements NodeVisitor<Void> {
        private final StructureShape inputShape;
        private Shape workingShape;

        private CommandInputNodeVisitor(StructureShape inputShape) {
            this.inputShape = inputShape;
            this.workingShape = inputShape;
        }

        @Override
        public Void arrayNode(ArrayNode node) {
            String openElement = "[";
            String closeElement = "]";

            // Write the value out directly.
            writer.openBlock("$L\n", closeElement + ",\n", openElement, () -> {
                Shape wrapperShape = this.workingShape;
                node.getElements().forEach(element -> {
                    // Swap the working shape to the member of the collection.
                    this.workingShape = model.expectShape(((CollectionShape) wrapperShape).getMember().getTarget());
                    writer.call(() -> element.accept(this)).write("\n");
                });
                this.workingShape = wrapperShape;
            });
            return null;
        }

        @Override
        public Void booleanNode(BooleanNode node) {
            // Handle needing to write the boolean's value properly.
            writer.write(node.getValue() ? "true," : "false,");
            return null;
        }

        @Override
        public Void nullNode(NullNode node) {
            writer.write("null,");
            return null;
        }

        @Override
        public Void numberNode(NumberNode node) {
            // Handle timestamps needing to be converted from numbers to their input type of Date.
            // Also handle that a Date in TS takes milliseconds, so add 000 to the end.
            if (workingShape.isTimestampShape()) {
                writer.write("new Date($L000),", node.getValue());
            } else {
                writer.write("$L,", node.getValue().toString());
            }
            return null;
        }

        @Override
        public Void objectNode(ObjectNode node) {
            // Short circuit document types, as the direct value is what we want.
            if (workingShape.isDocumentShape()) {
                writer.writeInline(Node.prettyPrintJson(node));
                return null;
            }

            // Both objects and maps can use a majority of the same logic.
            // Use "as any" to have TS complain less about undefined entries.
            writer.openBlock("{", "} as any,\n", () -> {
                Shape wrapperShape = this.workingShape;
                node.getMembers().forEach((keyNode, valueNode) -> {
                    writer.writeInline("$L: ", keyNode.getValue());

                    // Grab the correct member related to the node member we have.
                    MemberShape memberShape;
                    if (wrapperShape.isStructureShape()) {
                        memberShape = wrapperShape.asStructureShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isUnionShape()) {
                        memberShape = wrapperShape.asUnionShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isMapShape()) {
                        memberShape = wrapperShape.asMapShape().get().getValue();
                    } else {
                        throw new CodegenException("Unknown shape type for object node when "
                                + "generating protocol test input: " + wrapperShape.getType());
                    }

                    // Handle auto-filling idempotency token values to the explicit value.
                    if (isIdempotencyTokenWithoutValue(memberShape, valueNode)) {
                        writer.write("\"00000000-0000-4000-8000-000000000000\",");
                    } else {
                        this.workingShape = model.expectShape(memberShape.getTarget());
                        writer.call(() -> valueNode.accept(this));
                    }
                    writer.write("\n");
                });
                // Check for setting a potentially unspecified member value for the
                // idempotency token.
                if (node.getMembers().isEmpty() && wrapperShape.isStructureShape()) {
                    StructureShape structureShape = wrapperShape.asStructureShape().get();
                    for (Map.Entry<String, MemberShape> entry : structureShape.getAllMembers().entrySet()) {
                        if (entry.getValue().hasTrait(IdempotencyTokenTrait.class)) {
                            writer.write("$L: \"00000000-0000-4000-8000-000000000000\",", entry.getKey());
                        }
                    }
                }
                this.workingShape = wrapperShape;
            });
            return null;
        }

        private boolean isIdempotencyTokenWithoutValue(MemberShape memberShape, Node valueNode) {
            // Short circuit non-tokens.
            if (!memberShape.hasTrait(IdempotencyTokenTrait.class)) {
                return false;
            }

            // Return if the token has a test-specific value.
            return valueNode.expectStringNode().getValue().isEmpty();
        }

        @Override
        public Void stringNode(StringNode node) {
            // Handle blobs needing to be converted from strings to their input type of UInt8Array.
            if (workingShape.isBlobShape()) {
                writer.write("Uint8Array.from($S, c => c.charCodeAt(0)),", node.getValue());
            } else {
                writer.write("$S,", node.getValue());
            }
            return null;
        }
    }

    /**
     * Supports writing out TS specific output types in the generated code
     * through visiting the target shape at the same time as the node. If
     * instead we just printed out the node, many values would not match on
     * type signatures.
     *
     * This handles properly generating Date types for numbers that are
     * Timestamp shapes, downcasing prefix headers, boolean values, Uint8Array
     * types for blobs, and error Message field standardization.
     */
    private final class CommandOutputNodeVisitor implements NodeVisitor<Void> {
        private final StructureShape outputShape;
        private Shape workingShape;

        private CommandOutputNodeVisitor(StructureShape outputShape) {
            this.outputShape = outputShape;
            this.workingShape = outputShape;
        }

        @Override
        public Void arrayNode(ArrayNode node) {
            String openElement = "[";
            String closeElement = "]";

            // Write the value out directly.
            writer.openBlock("$L\n", closeElement + ",\n", openElement, () -> {
                Shape wrapperShape = this.workingShape;
                node.getElements().forEach(element -> {
                    // Swap the working shape to the member of the collection.
                    this.workingShape = model.expectShape(((CollectionShape) wrapperShape).getMember().getTarget());
                    writer.call(() -> element.accept(this)).write("\n");
                });
                this.workingShape = wrapperShape;
            });
            return null;
        }

        @Override
        public Void booleanNode(BooleanNode node) {
            // Handle needing to write the boolean's value properly.
            writer.write(node.getValue() ? "true," : "false,");
            return null;
        }

        @Override
        public Void nullNode(NullNode node) {
            // Nulls on the wire are nulls in parsed content.
            writer.write("null,");
            return null;
        }

        @Override
        public Void numberNode(NumberNode node) {
            // Handle timestamps needing to be converted from numbers to their input type of Date.
            // Also handle that a Date in TS takes milliseconds, so add 000 to the end.
            if (workingShape.isTimestampShape()) {
                writer.write("new Date($L000),", node.getValue());
            } else {
                writer.write("$L,", node.getValue().toString());
            }
            return null;
        }

        @Override
        public Void objectNode(ObjectNode node) {
            // Short circuit document types, as the direct value is what we want.
            if (workingShape.isDocumentShape()) {
                writer.writeInline(Node.prettyPrintJson(node));
                return null;
            }

            // Both objects and maps can use a majority of the same logic.
            // Use "as any" to have TS complain less about undefined entries.
            writer.openBlock("{", "},\n", () -> {
                Shape wrapperShape = this.workingShape;
                node.getMembers().forEach((keyNode, valueNode) -> {
                    // Grab the correct member related to the node member we have.
                    MemberShape memberShape;
                    if (wrapperShape.isStructureShape()) {
                        memberShape = wrapperShape.asStructureShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isUnionShape()) {
                        memberShape = wrapperShape.asUnionShape().get().getMember(keyNode.getValue()).get();
                    } else if (wrapperShape.isMapShape()) {
                        memberShape = wrapperShape.asMapShape().get().getValue();
                    } else {
                        throw new CodegenException("Unknown shape type for object node when "
                                + "generating protocol test output: " + wrapperShape.getType());
                    }

                    // Handle error standardization to the down-cased "message".
                    String validationName = keyNode.getValue();
                    if (wrapperShape.hasTrait(ErrorTrait.class) && validationName.equals("Message")) {
                        validationName = "message";
                    }
                    writer.write("$S: ", validationName);

                    this.workingShape = model.expectShape(memberShape.getTarget());
                    // Alter valueNode to downcase keys if it's a map for prefixHeaders.
                    // This is an enforced behavior of the fetch handler.
                    Node renderNode = memberShape.hasTrait(HttpPrefixHeadersTrait.class)
                            ? downcaseNodeKeys(valueNode.expectObjectNode())
                            : valueNode;
                    writer.call(() -> renderNode.accept(this));
                    writer.write("\n");
                });
                this.workingShape = wrapperShape;
            });
            return null;
        }

        private ObjectNode downcaseNodeKeys(ObjectNode startingNode) {
            ObjectNode downcasedNode = Node.objectNode();
            for (Map.Entry<StringNode, Node> entry : startingNode.getMembers().entrySet()) {
                downcasedNode = downcasedNode.withMember(entry.getKey().getValue().toLowerCase(Locale.US),
                        entry.getValue());
            }
            return downcasedNode;
        }

        @Override
        public Void stringNode(StringNode node) {
            // Handle blobs needing to be converted from strings to their input type of UInt8Array.
            if (workingShape.isBlobShape()) {
                writer.write("Uint8Array.from($S, c => c.charCodeAt(0)),", node.getValue());
            } else {
                writer.write("$S,", node.getValue());
            }
            return null;
        }
    }
}
