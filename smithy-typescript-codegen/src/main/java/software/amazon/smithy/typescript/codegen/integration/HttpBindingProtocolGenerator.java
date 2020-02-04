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

package software.amazon.smithy.typescript.codegen.integration;

import static software.amazon.smithy.model.knowledge.HttpBinding.Location;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.pattern.Pattern.Segment;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.NumberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EndpointTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.typescript.codegen.ApplicationProtocol;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.OptionalUtils;

/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final Set<Shape> serializingDocumentShapes = new TreeSet<>();
    private final Set<Shape> deserializingDocumentShapes = new TreeSet<>();
    private final Set<StructureShape> deserializingErrorShapes = new TreeSet<>();
    private final boolean isErrorCodeInBody;

    /**
     * Creates a Http binding protocol generator.
     *
     * @param isErrorCodeInBody A boolean that indicates if the error code for the implementing protocol is located in
     *   the error response body, meaning this generator will parse the body before attempting to load an error code.
     */
    public HttpBindingProtocolGenerator(boolean isErrorCodeInBody) {
        this.isErrorCodeInBody = isErrorCodeInBody;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    /**
     * Gets the default serde format for timestamps.
     *
     * @return Returns the default format.
     */
    protected abstract Format getDocumentTimestampFormat();

    /**
     * Gets the default content-type when a document is synthesized in the body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeInputDocument}. The {@link DocumentShapeSerVisitor} and {@link DocumentMemberSerVisitor}
     * are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Generates deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@code deserializeOutputDocument}. The {@link DocumentShapeDeserVisitor} and
     * {@link DocumentMemberDeserVisitor} are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateSharedComponents(GenerationContext context) {
        deserializingErrorShapes.forEach(error -> generateErrorDeserializer(context, error));
        generateDocumentBodyShapeSerializers(context, serializingDocumentShapes);
        generateDocumentBodyShapeDeserializers(context, deserializingDocumentShapes);
        HttpProtocolGeneratorUtils.generateMetadataDeserializer(context, getApplicationProtocol().getResponseType());
        HttpProtocolGeneratorUtils.generateCollectBody(context);
        HttpProtocolGeneratorUtils.generateCollectBodyString(context);
    }

    /**
     * Detects if the target shape is expressed as a native simple type.
     *
     * @param target The shape of the value being provided.
     * @return Returns if the shape is a native simple type.
     */
    private boolean isNativeSimpleType(Shape target) {
        return target instanceof BooleanShape || target instanceof DocumentShape
                       || target instanceof NumberShape || target instanceof StringShape;
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationSerializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol request bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }
    }

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationDeserializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol response bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }
    }

    private void generateOperationSerializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference requestType = getApplicationProtocol().getRequestType();
        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
        TypeScriptWriter writer = context.getWriter();

        // Ensure that the request type is imported.
        writer.addUseImports(requestType);
        writer.addImport("SerdeContext", "__SerdeContext", "@aws-sdk/types");
        writer.addImport("Endpoint", "__Endpoint", "@aws-sdk/types");
        // e.g., serializeAws_restJson1_1ExecuteStatement
        String methodName = ProtocolGenerator.getSerFunctionName(symbol, getName());
        // Add the normalized input type.
        Symbol inputType = symbol.expectProperty("inputType", Symbol.class);

        writer.openBlock("export async function $L(\n"
                       + "  input: $T,\n"
                       + "  context: __SerdeContext\n"
                       + "): Promise<$T> {", "}", methodName, inputType, requestType, () -> {
            writeHeaders(context, operation, bindingIndex);
            writeResolvedPath(context, operation, bindingIndex, trait);
            boolean hasQueryComponents = writeRequestQueryString(context, operation, bindingIndex, trait);
            List<HttpBinding> documentBindings = writeRequestBody(context, operation, bindingIndex);
            boolean hasHostPrefix = operation.hasTrait(EndpointTrait.class);

            if (hasHostPrefix) {
                HttpProtocolGeneratorUtils.writeHostPrefix(context, operation);
            }

            writer.openBlock("return new $T({", "});", requestType, () -> {
                writer.write("...context.endpoint,");
                if (hasHostPrefix) {
                    writer.write("hostname: resolvedHostname,");
                }
                writer.write("protocol: \"https\",");
                writer.write("method: $S,", trait.getMethod());
                writer.write("headers: headers,");
                writer.write("path: resolvedPath,");
                if (hasQueryComponents) {
                    writer.write("query: query,");
                }
                if (!documentBindings.isEmpty()) {
                    // Track all shapes bound to the document so their serializers may be generated.
                    documentBindings.stream()
                            .map(HttpBinding::getMember)
                            .map(member -> context.getModel().expectShape(member.getTarget()))
                            .forEach(serializingDocumentShapes::add);
                    writer.write("body: body,");
                }
            });
        });

        writer.write("");
    }

    private void writeResolvedPath(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            HttpTrait trait
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        List<HttpBinding> labelBindings = bindingIndex.getRequestBindings(operation, Location.LABEL);

        // Always write the bound path, but only the actual segments.
        writer.write("let resolvedPath = $S;", "/" + trait.getUri().getSegments().stream()
                .map(Segment::toString)
                .collect(Collectors.joining("/")));

        // Handle any label bindings.
        if (!labelBindings.isEmpty()) {
            Model model = context.getModel();
            List<Segment> uriLabels = trait.getUri().getLabels();
            for (HttpBinding binding : labelBindings) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                Shape target = model.expectShape(binding.getMember().getTarget());
                String labelValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                        binding.getMember(), target);
                // Get the correct label to use.
                Segment uriLabel = uriLabels.stream().filter(s -> s.getContent().equals(memberName)).findFirst().get();

                // Set the label's value and throw a clear error if empty or undefined.
                writer.write("if (input.$L !== undefined) {", memberName).indent()
                    .write("const labelValue: any = $L;", labelValue)
                    .openBlock("if (labelValue.length <= 0) {", "}", () -> {
                        writer.write("throw new Error('Empty value provided for input HTTP label: $L.');", memberName);
                    })
                    .write("resolvedPath = resolvedPath.replace($S, labelValue);", uriLabel.toString()).dedent()
                .write("} else {").indent()
                    .write("throw new Error('No value provided for input HTTP label: $L.');", memberName).dedent()
                .write("}");
            }
        }
    }

    private boolean writeRequestQueryString(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            HttpTrait trait
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        List<HttpBinding> queryBindings = bindingIndex.getRequestBindings(operation, Location.QUERY);

        // Build the initial query bag.
        Map<String, String> queryLiterals = trait.getUri().getQueryLiterals();
        if (!queryLiterals.isEmpty()) {
            // Write any query literals present in the uri.
            writer.openBlock("const query: any = {", "};",
                    () -> queryLiterals.forEach((k, v) -> writer.write("$S: $S,", k, v)));
        } else if (!queryBindings.isEmpty()) {
            writer.write("const query: any = {};");
        }

        // Handle any additional query bindings.
        if (!queryBindings.isEmpty()) {
            Model model = context.getModel();
            for (HttpBinding binding : queryBindings) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
                    Shape target = model.expectShape(binding.getMember().getTarget());
                    String queryValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                            binding.getMember(), target);
                    writer.write("query['$L'] = $L;", binding.getLocationName(), queryValue);
                });
            }
        }

        // Any binding or literal means we generated a query bag.
        return !queryBindings.isEmpty() || !queryLiterals.isEmpty();
    }

    private void writeHeaders(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        // Headers are always present either from the default document or the payload.
        writer.write("const headers: any = {};");
        writer.write("headers['Content-Type'] = $S;", bindingIndex.determineRequestContentType(
                operation, getDocumentContentType()));
        writeDefaultHeaders(context, operation);

        operation.getInput().ifPresent(outputId -> {
            Model model = context.getModel();
            for (HttpBinding binding : bindingIndex.getRequestBindings(operation, Location.HEADER)) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
                    Shape target = model.expectShape(binding.getMember().getTarget());
                    String headerValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                            binding.getMember(), target);
                    writer.write("headers[$S] = $L;", binding.getLocationName(), headerValue);
                });
            }

            // Handle assembling prefix headers.
            for (HttpBinding binding : bindingIndex.getRequestBindings(operation, Location.PREFIX_HEADERS)) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
                    MapShape prefixMap = model.expectShape(binding.getMember().getTarget()).asMapShape().get();
                    Shape target = model.expectShape(prefixMap.getValue().getTarget());
                    // Iterate through each entry in the member.
                    writer.openBlock("Object.keys(input.$L).forEach(suffix => {", "});", memberName, () -> {
                        // Use a ! since we already validated the input member is defined above.
                        String headerValue = getInputValue(context, binding.getLocation(),
                                "input." + memberName + "![suffix]", binding.getMember(), target);
                        // Append the suffix to the defined prefix and serialize the value in to that key.
                        writer.write("headers[$S + suffix] = $L;", binding.getLocationName(), headerValue);
                    });
                });
            }
        });
    }

    private List<HttpBinding> writeRequestBody(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        List<HttpBinding> documentBindings = bindingIndex.getRequestBindings(operation, Location.DOCUMENT);
        documentBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
        List<HttpBinding> payloadBindings = bindingIndex.getRequestBindings(operation, Location.PAYLOAD);

        if (!documentBindings.isEmpty()) {
            // Write the default `body` property.
            writer.write("let body: any = {};");
            serializeInputDocument(context, operation, documentBindings);
            return documentBindings;
        }
        if (!payloadBindings.isEmpty()) {
            // Write the default `body` property.
            writer.write("let body: any = {};");
            // There can only be one payload binding.
            HttpBinding payloadBinding = payloadBindings.get(0);
            serializeInputPayload(context, operation, payloadBinding);
            return payloadBindings;
        }

        return ListUtils.of();
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * shape. This may use native types (like getting Date formats for timestamps,)
     * converters (like a base64Encoder,) or invoke complex type serializers to
     * manipulate the dataSource into the proper input content.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input value.
     */
    protected String getInputValue(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MemberShape member,
            Shape target
    ) {
        if (isNativeSimpleType(target)) {
            return dataSource + ".toString()";
        } else if (target instanceof TimestampShape) {
            HttpBindingIndex httpIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
            Format format = httpIndex.determineTimestampFormat(member, bindingType, getDocumentTimestampFormat());
            return HttpProtocolGeneratorUtils.getTimestampInputParam(dataSource, member, format);
        } else if (target instanceof BlobShape) {
            return getBlobInputParam(bindingType, dataSource);
        } else if (target instanceof CollectionShape) {
            return getCollectionInputParam(bindingType, dataSource);
        } else if (target instanceof StructureShape || target instanceof UnionShape) {
            return getNamedMembersInputParam(context, bindingType, dataSource, target);
        }

        throw new CodegenException(String.format(
                "Unsupported %s binding of %s to %s in %s using the %s protocol",
                bindingType, member.getMemberName(), target.getType(), member.getContainer(), getName()));
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * blob. By default, this base64 encodes content in headers and query strings,
     * and passes through for payloads.
     *
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the input blob.
     */
    private String getBlobInputParam(Location bindingType, String dataSource) {
        switch (bindingType) {
            case PAYLOAD:
                return dataSource;
            case HEADER:
            case QUERY:
                // Encode these to base64.
                return "context.base64Encoder(" + dataSource + ")";
            default:
                throw new CodegenException("Unexpected blob binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * collection. By default, this separates the list with commas in headers, and
     * relies on the HTTP implementation for query strings.
     *
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the input collection.
     */
    private String getCollectionInputParam(
            Location bindingType,
            String dataSource
    ) {
        switch (bindingType) {
            case HEADER:
                // Join these values with commas.
                return "(" + dataSource + " || []).toString()";
            case QUERY:
                return dataSource;
            default:
                throw new CodegenException("Unexpected collection binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * shape. This redirects to a serialization function for payloads,
     * and fails otherwise.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input shape.
     */
    private String getNamedMembersInputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            Shape target
    ) {
        switch (bindingType) {
            case PAYLOAD:
                // Redirect to a serialization function.
                Symbol symbol = context.getSymbolProvider().toSymbol(target);
                return ProtocolGenerator.getSerFunctionName(symbol, context.getProtocolName())
                               + "(" + dataSource + ", context)";
            default:
                throw new CodegenException("Unexpected named member shape binding location `" + bindingType + "`");
        }
    }

    /**
     * Writes any additional HTTP headers required by the protocol implementation.
     *
     * <p>Two parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code context: SerdeContext}: a TypeScript type containing context and tools for type serde.</li>
     * </ul>
     *
     * <p>For example:
     *
     * <pre>{@code
     * headers['foo'] = "This is a custom header";
     * }</pre>
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     */
    protected void writeDefaultHeaders(GenerationContext context, OperationShape operation) {}

    /**
     * Writes the code needed to serialize the input document of a request.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * <p>For example:
     *
     * <pre>{@code
     * const bodyParams: any = {};
     * if (input.barValue !== undefined) {
     *   bodyParams['barValue'] = input.barValue;
     * }
     * body = JSON.stringify(bodyParams);
     * }</pre>
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param documentBindings The bindings to place in the document.
     */
    protected abstract void serializeInputDocument(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code needed to serialize the input payload of a request.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (input.body !== undefined) {
     *   body = context.base64Encoder(input.body);
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param payloadBinding The payload binding to serialize.
     */
    protected void serializeInputPayload(
            GenerationContext context,
            OperationShape operation,
            HttpBinding payloadBinding
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        String memberName = symbolProvider.toMemberName(payloadBinding.getMember());

        writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
            Shape target = context.getModel().expectShape(payloadBinding.getMember().getTarget());
            writer.write("body = $L;", getInputValue(
                    context, Location.PAYLOAD, "input." + memberName, payloadBinding.getMember(), target));
        });
    }

    private void generateOperationDeserializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference responseType = getApplicationProtocol().getResponseType();
        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
        Model model = context.getModel();
        TypeScriptWriter writer = context.getWriter();

        // Ensure that the response type is imported.
        writer.addUseImports(responseType);
        writer.addImport("SerdeContext", "__SerdeContext", "@aws-sdk/types");
        // e.g., deserializeAws_restJson1_1ExecuteStatement
        String methodName = ProtocolGenerator.getDeserFunctionName(symbol, getName());
        String errorMethodName = methodName + "Error";
        // Add the normalized output type.
        Symbol outputType = symbol.expectProperty("outputType", Symbol.class);

        // Handle the general response.
        writer.openBlock("export async function $L(\n"
                       + "  output: $T,\n"
                       + "  context: __SerdeContext\n"
                       + "): Promise<$T> {", "}", methodName, responseType, outputType, () -> {
            // Redirect error deserialization to the dispatcher if we receive an error range
            // status code that's not the modeled code (400 or higher). This allows for
            // returning other 2XX or 3XX codes that don't match the defined value.
            writer.openBlock("if (output.statusCode !== $L && output.statusCode >= 400) {", "}", trait.getCode(),
                    () -> writer.write("return $L(output, context);", errorMethodName));

            // Start deserializing the response.
            writer.openBlock("const contents: $T = {", "};", outputType, () -> {
                writer.write("$$metadata: deserializeMetadata(output),");

                // Only set a type and the members if we have output.
                operation.getOutput().ifPresent(outputId -> {
                    writer.write("__type: $S,", outputId.getName());
                    // Set all the members to undefined to meet type constraints.
                    StructureShape target = model.expectShape(outputId).asStructureShape().get();
                    new TreeMap<>(target.getAllMembers())
                            .forEach((memberName, memberShape) -> writer.write("$L: undefined,", memberName));
                });
            });
            readHeaders(context, operation, bindingIndex);
            List<HttpBinding> documentBindings = readResponseBody(context, operation, bindingIndex);
            // Track all shapes bound to the document so their deserializers may be generated.
            documentBindings.forEach(binding -> {
                Shape target = model.expectShape(binding.getMember().getTarget());
                deserializingDocumentShapes.add(target);
            });
            writer.write("return Promise.resolve(contents);");
        });
        writer.write("");

        // Write out the error deserialization dispatcher.
        Set<StructureShape> errorShapes = HttpProtocolGeneratorUtils.generateErrorDispatcher(
                context, operation, responseType, this::writeErrorCodeParser,
                isErrorCodeInBody, this::getErrorBodyLocation);
        deserializingErrorShapes.addAll(errorShapes);
    }

    private void generateErrorDeserializer(GenerationContext context, StructureShape error) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
        Model model = context.getModel();
        Symbol errorSymbol = symbolProvider.toSymbol(error);
        String errorDeserMethodName = ProtocolGenerator.getDeserFunctionName(errorSymbol,
                context.getProtocolName()) + "Response";
        String outputName = isErrorCodeInBody ? "parsedOutput" : "output";

        writer.openBlock("const $L = async (\n"
                       + "  $L: any,\n"
                       + "  context: __SerdeContext\n"
                       + "): Promise<$T> => {", "};",
                errorDeserMethodName, outputName, errorSymbol, () -> {
            writer.openBlock("const contents: $T = {", "};", errorSymbol, () -> {
                writer.write("name: $S,", error.getId().getName());
                writer.write("$$fault: $S,", error.getTrait(ErrorTrait.class).get().getValue());
                writer.write("$$metadata: deserializeMetadata($L),", outputName);
                // Set all the members to undefined to meet type constraints.
                new TreeMap<>(error.getAllMembers())
                        .forEach((memberName, memberShape) -> writer.write("$L: undefined,", memberName));
            });

            readHeaders(context, error, bindingIndex);
            List<HttpBinding> documentBindings = readErrorResponseBody(context, error, bindingIndex);
            // Track all shapes bound to the document so their deserializers may be generated.
            documentBindings.forEach(binding -> {
                Shape target = model.expectShape(binding.getMember().getTarget());
                deserializingDocumentShapes.add(target);
            });
            writer.write("return contents;");
        });

        writer.write("");
    }

    private List<HttpBinding> readErrorResponseBody(
            GenerationContext context,
            Shape error,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        if (isErrorCodeInBody) {
            // Body is already parsed in the error dispatcher, simply assign the body.
            writer.write("const data: any = $L;", getErrorBodyLocation(context, "parsedOutput.body"));
            List<HttpBinding> responseBindings = bindingIndex.getResponseBindings(error, Location.DOCUMENT);
            responseBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
            return responseBindings;
        } else {
            // Deserialize response body just like in a normal response.
            return readResponseBody(context, error, bindingIndex);
        }
    }

    private void readHeaders(
            GenerationContext context,
            Shape operationOrError,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        Model model = context.getModel();
        for (HttpBinding binding : bindingIndex.getResponseBindings(operationOrError, Location.HEADER)) {
            String memberName = symbolProvider.toMemberName(binding.getMember());
            String headerName = binding.getLocationName().toLowerCase();
            writer.openBlock("if (output.headers[$S] !== undefined) {", "}", headerName, () -> {
                Shape target = model.expectShape(binding.getMember().getTarget());
                String headerValue = getOutputValue(context, binding.getLocation(),
                        "output.headers['" + headerName + "']", binding.getMember(), target);
                writer.write("contents.$L = $L;", memberName, headerValue);
            });
        }

        // Handle loading up prefix headers.
        List<HttpBinding> prefixHeaderBindings =
                bindingIndex.getResponseBindings(operationOrError, Location.PREFIX_HEADERS);
        if (!prefixHeaderBindings.isEmpty()) {
            // Run through the headers one time, matching any prefix groups.
            writer.openBlock("Object.keys(output.headers).forEach(header => {", "});", () -> {
                for (HttpBinding binding : prefixHeaderBindings) {
                    // Generate a single block for each group of prefix headers.
                    writer.openBlock("if (header.startsWith($S)) {", "}", binding.getLocationName(), () -> {
                        String memberName = symbolProvider.toMemberName(binding.getMember());
                        MapShape prefixMap = model.expectShape(binding.getMember().getTarget()).asMapShape().get();
                        Shape target = model.expectShape(prefixMap.getValue().getTarget());
                        String headerValue = getOutputValue(context, binding.getLocation(),
                                "output.headers[header]", binding.getMember(), target);

                        // Prepare a grab bag for these headers if necessary
                        writer.openBlock("if (contents.$L === undefined) {", "}", memberName, () -> {
                            writer.write("contents.$L = {};", memberName);
                        });

                        // Extract the non-prefix portion as the key.
                        writer.write("contents.$L[header.substring($L)] = $L;",
                                memberName, binding.getLocationName().length(), headerValue);
                    });
                }
            });
        }
    }

    private List<HttpBinding> readResponseBody(
            GenerationContext context,
            Shape operationOrError,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        List<HttpBinding> documentBindings = bindingIndex.getResponseBindings(operationOrError, Location.DOCUMENT);
        documentBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
        List<HttpBinding> payloadBindings = bindingIndex.getResponseBindings(operationOrError, Location.PAYLOAD);

        if (!documentBindings.isEmpty()) {
            // If the response has document bindings, the body can be parsed to a JavaScript object.
            // Use the protocol specific error location for retrieving contents.
            writer.write("const data: any = $L;",
                    getErrorBodyLocation(context, "(await parseBody(output.body, context))"));

            deserializeOutputDocument(context, operationOrError, documentBindings);
            return documentBindings;
        }
        if (!payloadBindings.isEmpty()) {
            return readResponsePayload(context, operationOrError, payloadBindings);
        }
        return ListUtils.of();
    }

    private List<HttpBinding> readResponsePayload(
            GenerationContext context,
            Shape operationOrError,
            List<HttpBinding> payloadBindings
    ) {
        TypeScriptWriter writer = context.getWriter();
        // Detect if operation output or error shape contains a streaming member.
        OperationIndex operationIndex = context.getModel().getKnowledge(OperationIndex.class);
        StructureShape operationOutputOrError = operationOrError.asStructureShape()
                .orElseGet(() -> operationIndex.getOutput(operationOrError).orElse(null));
        boolean hasStreamingComponent = Optional.ofNullable(operationOutputOrError)
                .map(structure -> structure.getAllMembers().values().stream()
                        .anyMatch(memberShape -> memberShape.hasTrait(StreamingTrait.class)))
                .orElse(false);

        // There can only be one payload binding.
        HttpBinding binding = payloadBindings.get(0);
        Shape target = context.getModel().expectShape(binding.getMember().getTarget());
        if (hasStreamingComponent) {
            // If payload is streaming, return raw low-level stream directly.
            writer.write("const data: any = output.body;");
        } else if (target instanceof BlobShape) {
            // If payload is non-streaming blob, only need to collect stream to binary data(Uint8Array).
            writer.write("const data: any = await collectBody(output.body, context);");
        } else if (target instanceof StructureShape || target instanceof UnionShape) {
            // If body is Structure or Union, they we need to parse the string into JavaScript object.
            writer.write("const data: any = await parseBody(output.body, context);");
        } else if (target instanceof StringShape) {
            // If payload is string, we need to collect body and encode binary to string.
            writer.write("const data: any = await collectBodyString(output.body, context);");
        } else {
            throw new CodegenException(String.format("Unexpected shape type bound to payload: `%s`",
                    target.getType()));
        }
        writer.write("contents.$L = $L;", binding.getMemberName(), getOutputValue(context,
                Location.PAYLOAD, "data", binding.getMember(), target));
        return payloadBindings;
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * shape. This may use native types (like generating a Date for timestamps,)
     * converters (like a base64Decoder,) or invoke complex type deserializers to
     * manipulate the dataSource into the proper output content.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output value.
     */
    private String getOutputValue(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MemberShape member,
            Shape target
    ) {
        if (target instanceof NumberShape) {
            return getNumberOutputParam(bindingType, dataSource, target);
        } else if (target instanceof BooleanShape) {
            return getBooleanOutputParam(bindingType, dataSource);
        } else if (target instanceof StringShape || target instanceof DocumentShape) {
            return dataSource;
        } else if (target instanceof TimestampShape) {
            HttpBindingIndex httpIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
            Format format = httpIndex.determineTimestampFormat(member, bindingType, getDocumentTimestampFormat());
            return HttpProtocolGeneratorUtils.getTimestampOutputParam(dataSource, member, format);
        } else if (target instanceof BlobShape) {
            return getBlobOutputParam(bindingType, dataSource);
        } else if (target instanceof CollectionShape) {
            return getCollectionOutputParam(bindingType, dataSource);
        } else if (target instanceof StructureShape || target instanceof UnionShape) {
            return getNamedMembersOutputParam(context, bindingType, dataSource, target);
        }

        throw new CodegenException(String.format(
                "Unsupported %s binding of %s to %s in %s using the %s protocol",
                bindingType, member.getMemberName(), target.getType(), member.getContainer(), getName()));
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * boolean. By default, this checks strict equality to 'true'in headers and passes
     * through for documents.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output boolean.
     */
    private String getBooleanOutputParam(Location bindingType, String dataSource) {
        switch (bindingType) {
            case HEADER:
                return dataSource + " === 'true'";
            default:
                throw new CodegenException("Unexpected blob binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * blob. By default, this base64 decodes content in headers and passes through
     * for payloads.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output blob.
     */
    private String getBlobOutputParam(Location bindingType, String dataSource) {
        switch (bindingType) {
            case PAYLOAD:
                return dataSource;
            case HEADER:
                // Decode these from base64.
                return "context.base64Decoder(" + dataSource + ")";
            default:
                throw new CodegenException("Unexpected blob binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * collection. By default, this splits a comma separated string in headers.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output collection.
     */
    private String getCollectionOutputParam(
            Location bindingType,
            String dataSource
    ) {
        switch (bindingType) {
            case HEADER:
                // Split these values on commas.
                return "(" + dataSource + " || \"\").split(',')";
            default:
                throw new CodegenException("Unexpected collection binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * shape. This redirects to a deserialization function for documents and payloads,
     * and fails otherwise.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output shape.
     */
    private String getNamedMembersOutputParam(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            Shape target
    ) {
        switch (bindingType) {
            case PAYLOAD:
                // Redirect to a deserialization function.
                Symbol symbol = context.getSymbolProvider().toSymbol(target);
                return ProtocolGenerator.getDeserFunctionName(symbol, context.getProtocolName())
                               + "(" + dataSource + ", context)";
            default:
                throw new CodegenException("Unexpected named member shape binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * number. By default, invokes parseInt on byte/short/integer/long types in headers,
     * invokes parseFloat on float/double types in headers, and fails otherwise.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output number.
     */
    private String getNumberOutputParam(Location bindingType, String dataSource, Shape target) {
        switch (bindingType) {
            case HEADER:
                if (target instanceof FloatShape || target instanceof DoubleShape) {
                    return "parseFloat(" + dataSource + ", 10)";
                }
                return "parseInt(" + dataSource + ", 10)";
            default:
                throw new CodegenException("Unexpected number binding location `" + bindingType + "`");
        }
    }

    /**
     * Writes the code that loads an {@code errorCode} String with the content used
     * to dispatch errors to specific serializers.
     *
     * <p>Two variables will be in scope:
     *   <ul>
     *       <li>{@code output} or {@code parsedOutput}: a value of the HttpResponse type.
     *          <ul>
     *              <li>{@code output} is a raw HttpResponse, available when {@code isErrorCodeInBody} is set to
     *              {@code false}</li>
     *              <li>{@code parsedOutput} is a HttpResponse type with body parsed to JavaScript object, available
     *              when {@code isErrorCodeInBody} is set to {@code true}</li>
     *          </ul>
     *       </li>
     *       <li>{@code context}: the SerdeContext.</li>
     *   </ul>
     *
     * <p>For example:
     *
     * <pre>{@code
     * errorCode = output.headers["x-amzn-errortype"].split(':')[0];
     * }</pre>
     *
     * @param context The generation context.
     */
    protected abstract void writeErrorCodeParser(GenerationContext context);

    /**
     * Provides where within the passed output variable the actual error resides. This is useful
     * for protocols that wrap the specific error in additional elements within the body.
     *
     * @param context The generation context.
     * @param outputLocation The name of the variable containing the output body.
     * @return A string of the variable containing the error body within the output.
     */
    protected String getErrorBodyLocation(GenerationContext context, String outputLocation) {
        return outputLocation;
    }

    /**
     * Writes the code needed to deserialize the output document of a response.
     *
     * <p>Implementations of this method are expected to set members in the
     * {@code contents} variable that represents the type generated for the
     * response. This variable will already be defined in scope.
     *
     * <p>The contents of the response body will be available in a {@code data} variable.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (data.fieldList !== undefined) {
     *   contents.fieldList = deserializeAws_restJson1_1FieldList(data.fieldList, context);
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param operationOrError The operation or error with a document being deserialized.
     * @param documentBindings The bindings to read from the document.
     */
    protected abstract void deserializeOutputDocument(
            GenerationContext context,
            Shape operationOrError,
            List<HttpBinding> documentBindings
    );
}
