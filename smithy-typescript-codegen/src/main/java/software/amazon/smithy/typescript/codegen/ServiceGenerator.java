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

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;
import software.amazon.smithy.utils.OptionalUtils;

/**
 * Generates a service client and configuration using plugins.
 */
final class ServiceGenerator implements Runnable {

    static final String CLIENT_CONFIG_SECTION = "client_config";
    static final String CLIENT_PROPERTIES_SECTION = "client_properties";
    static final String CLIENT_BODY_EXTRA_SECTION = "client_body_extra";
    static final String CLIENT_CONSTRUCTOR_SECTION = "client_constructor";
    static final String CLIENT_DESTROY_SECTION = "client_destroy";

    private final TypeScriptSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final TypeScriptWriter writer;
    private final Symbol symbol;
    private final String configType;
    private final String resolvedConfigType;
    private final List<TypeScriptIntegration> integrations;
    private final List<RuntimeClientPlugin> runtimePlugins;
    private final ApplicationProtocol applicationProtocol;

    ServiceGenerator(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer,
            List<TypeScriptIntegration> integrations,
            List<RuntimeClientPlugin> runtimePlugins,
            ApplicationProtocol applicationProtocol
    ) {
        this.settings = settings;
        this.model = model;
        this.service = settings.getService(model);
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.integrations = integrations;
        this.runtimePlugins = runtimePlugins.stream()
                // Only apply plugins that target the entire client.
                .filter(plugin -> plugin.matchesService(model, service))
                .collect(Collectors.toList());
        this.applicationProtocol = applicationProtocol;

        symbol = symbolProvider.toSymbol(service);
        configType = symbol.getName() + "Config";
        resolvedConfigType = getResolvedConfigTypeName(symbol);
    }

    static String getResolvedConfigTypeName(Symbol symbol) {
        return symbol.getName() + "ResolvedConfig";
    }

    @Override
    public void run() {
        OperationIndex operationIndex = model.getKnowledge(OperationIndex.class);
        writer.addImport("Client", "__Client", "@aws-sdk/smithy-client");
        writer.addImport("ClientDefaultValues", "__ClientDefaultValues", "./runtimeConfig");

        // Normalize the input and output types of the command to account for
        // things like an operation adding input where there once wasn't any
        // input, adding output, naming differences between services, etc.
        writeInputOutputTypeUnion("ServiceInputTypes", writer, operationIndex::getInput, writer -> {
            // Use an empty object if an operation doesn't define input.
            writer.write("| {}");
        });
        writeInputOutputTypeUnion("ServiceOutputTypes", writer, operationIndex::getOutput, writer -> {
            // Use a MetadataBearer if an operation doesn't define output.
            writer.addImport("MetadataBearer", "__MetadataBearer", TypeScriptDependency.AWS_SDK_TYPES.packageName);
            writer.write("| __MetadataBearer");
        });

        generateConfig();
        writer.write("");
        generateService();
    }

    private void writeInputOutputTypeUnion(
            String typeName,
            TypeScriptWriter writer,
            Function<OperationShape, Optional<StructureShape>> mapper,
            Consumer<TypeScriptWriter> defaultTypeGenerator
    ) {
        TopDownIndex topDownIndex = model.getKnowledge(TopDownIndex.class);
        Set<OperationShape> containedOperations = topDownIndex.getContainedOperations(service);
        List<Symbol> symbols = containedOperations.stream()
                .flatMap(operation -> OptionalUtils.stream(mapper.apply(operation)))
                .map(symbolProvider::toSymbol)
                .sorted(Comparator.comparing(Symbol::getName))
                .collect(Collectors.toList());

        writer.write("export type $L = ", typeName);
        writer.indent();
        // If we have less symbols than operations, at least one doesn't have a type, so add the default.
        if (containedOperations.size() != symbols.size()) {
            defaultTypeGenerator.accept(writer);
        }
        for (int i = 0; i < symbols.size(); i++) {
            writer.write("| $T$L", symbols.get(i), i == symbols.size() - 1 ? ";" : "");
        }
        writer.dedent();
        writer.write("");
    }

    private void generateConfig() {
        writer.addImport("SmithyConfiguration", "__SmithyConfiguration", "@aws-sdk/smithy-client");
        writer.addImport("SmithyResolvedConfiguration", "__SmithyResolvedConfiguration", "@aws-sdk/smithy-client");

        // Hook for intercepting the client configuration.
        writer.pushState(CLIENT_CONFIG_SECTION);

        generateClientDefaults();

        // The default configuration type is always just the base-level
        // Smithy configuration requirements.
        writer.write("export type $L = Partial<__SmithyConfiguration<$T>>", configType,
                applicationProtocol.getOptionsType());
        writer.write("  & ClientDefaults");

        // Get the configuration symbol types to reference in code. These are
        // all "&"'d together to create a big configuration type that aggregates
        // more modular configuration types.
        List<SymbolReference> inputTypes = runtimePlugins.stream()
                .flatMap(p -> OptionalUtils.stream(p.getInputConfig()))
                .collect(Collectors.toList());

        if (!inputTypes.isEmpty()) {
            writer.indent();
            for (SymbolReference symbolReference : inputTypes) {
                writer.write("& $T", symbolReference);
            }
            writer.dedent();
        }

        // Generate the corresponding "Resolved" configuration type to account for
        // each "Input" configuration type.
        writer.write("");
        writer.write("export type $L = __SmithyResolvedConfiguration<$T>",
                     resolvedConfigType, applicationProtocol.getOptionsType());
        writer.write("  & Required<ClientDefaults>");

        if (!inputTypes.isEmpty()) {
            writer.indent();
            runtimePlugins.stream()
                    .flatMap(p -> OptionalUtils.stream(p.getResolvedConfig()))
                    .forEach(symbol -> writer.write("& $T", symbol));
            writer.dedent();
        }

        writer.popState();
    }

    private void generateClientDefaults() {
        if (!applicationProtocol.isHttpProtocol()) {
            throw new UnsupportedOperationException(
                    "Protocols other than HTTP are not yet implemented: " + applicationProtocol);
        }

        writer.openBlock("export interface ClientDefaults\n"
                         + "  extends Partial<__SmithyResolvedConfiguration<$T>> {", "}",
                applicationProtocol.getOptionsType(), () -> {
            writer.addImport("HttpHandler", "__HttpHandler", "@aws-sdk/protocol-http");
            writer.writeDocs("The HTTP handler to use. Fetch in browser and Https in Nodejs.");
            writer.write("requestHandler?: __HttpHandler;\n");

            writer.addImport("HashConstructor", "__HashConstructor", "@aws-sdk/types");
            writer.writeDocs("A constructor for a class implementing the @aws-sdk/types.Hash interface \n"
                             + "that computes the SHA-256 HMAC or checksum of a string or binary buffer.");
            writer.write("sha256?: __HashConstructor;\n");

            writer.addImport("UrlParser", "__UrlParser", "@aws-sdk/types");
            writer.writeDocs("The function that will be used to convert strings into HTTP endpoints.");
            writer.write("urlParser?: __UrlParser;\n");

            writer.writeDocs("A function that can calculate the length of a request body.");
            writer.write("bodyLengthChecker?: (body: any) => number | undefined;\n");

            writer.addImport("StreamCollector", "__StreamCollector", "@aws-sdk/types");
            writer.writeDocs("A function that converts a stream into an array of bytes.");
            writer.write("streamCollector?: __StreamCollector;\n");

            // Note: Encoder and Decoder are both used for base64 and UTF.
            writer.addImport("Encoder", "__Encoder", "@aws-sdk/types");
            writer.addImport("Decoder", "__Decoder", "@aws-sdk/types");

            writer.writeDocs("The function that will be used to convert a base64-encoded string to a byte array");
            writer.write("base64Decoder?: __Decoder;\n");

            writer.writeDocs("The function that will be used to convert binary data to a base64-encoded string");
            writer.write("base64Encoder?: __Encoder;\n");

            writer.writeDocs("The function that will be used to convert a UTF8-encoded string to a byte array");
            writer.write("utf8Decoder?: __Decoder;\n");

            writer.writeDocs("The function that will be used to convert binary data to a UTF-8 encoded string");
            writer.write("utf8Encoder?: __Encoder;\n");

            writer.writeDocs("The function that will be used to populate default value in 'User-Agent' header");
            writer.write("defaultUserAgent?: string;\n");

            // Write custom configuration dependencies.
            for (TypeScriptIntegration integration : integrations) {
                integration.addConfigInterfaceFields(settings, model, symbolProvider, writer);
            }
        }).write("");
    }

    private void generateService() {
        // Write out the service.
        writer.writeShapeDocs(service);
        writer.openBlock("export class $L extends __Client<\n"
                         + "  $T,\n"
                         + "  ServiceInputTypes,\n"
                         + "  ServiceOutputTypes,\n"
                         + "  $L\n"
                         + "> {", "}",
                symbol.getName(), applicationProtocol.getOptionsType(), resolvedConfigType, () -> {
            generateClientProperties();
            generateConstructor();
            writer.write("");
            generateDestroyMethod();
            // Hook for adding more methods to the client.
            writer.pushState(CLIENT_BODY_EXTRA_SECTION).popState();
        });
    }

    private void generateClientProperties() {
        // Hook for adding/changing client properties.
        writer.pushState(CLIENT_PROPERTIES_SECTION);
        writer.write("readonly config: $L;\n", resolvedConfigType);
        writer.popState();
    }

    private void generateConstructor() {
        writer.openBlock("constructor(configuration: $L) {", "}", configType, () -> {
            // Hook for adding/changing the client constructor.
            writer.pushState(CLIENT_CONSTRUCTOR_SECTION);

            int configVariable = 0;
            writer.write("let $L = {\n"
                         + "  ...__ClientDefaultValues,\n"
                         + "  ...configuration\n"
                         + "};", generateConfigVariable(configVariable));

            // Add runtime plugin "resolve" method calls. These are invoked one
            // after the other until all of the runtime plugins have been called.
            // Only plugins that have configuration are called. Each time the
            // configuration is updated, the configuration variable is incremented
            // (e.g., _config_0, _config_1, etc).
            for (RuntimeClientPlugin plugin : runtimePlugins) {
                if (plugin.getResolveFunction().isPresent()) {
                    configVariable++;
                    writer.write("let $L = $T($L);",
                                 generateConfigVariable(configVariable),
                                 plugin.getResolveFunction().get(),
                                 generateConfigVariable(configVariable - 1));
                }
            }

            writer.write("super($L);", generateConfigVariable(configVariable));
            writer.write("this.config = $L;", generateConfigVariable(configVariable));

            // Add runtime plugins that contain middleware to the middleware stack
            // of the client.
            for (RuntimeClientPlugin plugin : runtimePlugins) {
                plugin.getPluginFunction().ifPresent(symbol -> {
                    writer.write("this.middlewareStack.use($T(this.config));", symbol);
                });
            }

            writer.popState();
        });
    }

    private String generateConfigVariable(int number) {
        return "_config_" + number;
    }

    private void generateDestroyMethod() {
        // Generates the destroy() method, and calls the destroy() method of
        // any runtime plugin that claims to have a destroy method.
        writer.openBlock("destroy(): void {", "}", () -> {
            writer.pushState(CLIENT_DESTROY_SECTION);
            for (RuntimeClientPlugin plugin : runtimePlugins) {
                plugin.getDestroyFunction().ifPresent(destroy -> {
                    writer.write("$T(this.config);", destroy);
                });
            }
            writer.popState();
        });
    }
}
