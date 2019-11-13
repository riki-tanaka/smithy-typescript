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

import java.util.List;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.typescript.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin;

/**
 * Generates a client command using plugins.
 */
final class CommandGenerator implements Runnable {

    static final String COMMAND_PROPERTIES_SECTION = "command_properties";
    static final String COMMAND_BODY_EXTRA_SECTION = "command_body_extra";
    static final String COMMAND_CONSTRUCTOR_SECTION = "command_constructor";

    private final TypeScriptSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final OperationShape operation;
    private final SymbolProvider symbolProvider;
    private final TypeScriptWriter writer;
    private final Symbol symbol;
    private final List<RuntimeClientPlugin> runtimePlugins;
    private final OperationIndex operationIndex;
    private final String inputType;
    private final String outputType;
    private final ApplicationProtocol applicationProtocol;

    CommandGenerator(
            TypeScriptSettings settings,
            Model model,
            ServiceShape service,
            OperationShape operation,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer,
            List<RuntimeClientPlugin> runtimePlugins,
            ApplicationProtocol applicationProtocol
    ) {
        this.settings = settings;
        this.model = model;
        this.service = service;
        this.operation = operation;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.runtimePlugins = runtimePlugins.stream()
                .filter(plugin -> plugin.matchesOperation(model, service, operation))
                .collect(Collectors.toList());
        this.applicationProtocol = applicationProtocol;

        symbol = symbolProvider.toSymbol(operation);
        operationIndex = model.getKnowledge(OperationIndex.class);
        inputType = symbol.getName() + "Input";
        outputType = symbol.getName() + "Output";
    }

    @Override
    public void run() {
        Symbol serviceSymbol = symbolProvider.toSymbol(service);
        String configType = ServiceGenerator.getResolvedConfigTypeName(serviceSymbol);

        // Add required imports.
        writer.addImport(configType, configType, serviceSymbol.getNamespace());
        writer.addImport("ServiceInputTypes", "ServiceInputTypes", serviceSymbol.getNamespace());
        writer.addImport("ServiceOutputTypes", "ServiceOutputTypes", serviceSymbol.getNamespace());
        writer.addImport("Command", "$Command", "@aws-sdk/smithy-client");
        writer.addImport("FinalizeHandlerArguments", "FinalizeHandlerArguments", "@aws-sdk/types");
        writer.addImport("Handler", "Handler", "@aws-sdk/types");
        writer.addImport("HandlerExecutionContext", "HandlerExecutionContext", "@aws-sdk/types");
        writer.addImport("MiddlewareStack", "MiddlewareStack", "@aws-sdk/types");
        writer.addImport("SerdeContext", "SerdeContext", "@aws-sdk/types");

        addInputAndOutputTypes();

        String name = symbol.getName();
        writer.openBlock("export class $L extends $$Command<$L, $L> {", "}", name, inputType, outputType, () -> {

            // Section for adding custom command properties.
            writer.write("// Start section: $L", COMMAND_PROPERTIES_SECTION);
            writer.pushState(COMMAND_PROPERTIES_SECTION).popState();
            writer.write("// End section: $L", COMMAND_PROPERTIES_SECTION);
            writer.write("");

            generateCommandConstructor();
            writer.write("");
            generateCommandMiddlewareResolver(configType);
            writeSerde();

            // Hook for adding more methods to the command.
            writer.write("// Start section: $L", COMMAND_BODY_EXTRA_SECTION)
                    .pushState(COMMAND_BODY_EXTRA_SECTION)
                    .popState()
                    .write("// End section: $L", COMMAND_BODY_EXTRA_SECTION);
        });
    }

    private void generateCommandConstructor() {
        writer.openBlock("constructor(readonly input: $L) {", "}", inputType, () -> {
            // The constructor can be intercepted and changed.
            writer.write("// Start section: $L", COMMAND_CONSTRUCTOR_SECTION)
                    .pushState(COMMAND_CONSTRUCTOR_SECTION)
                    .write("super();")
                    .popState()
                    .write("// End section: $L", COMMAND_CONSTRUCTOR_SECTION);
        });
    }

    private void generateCommandMiddlewareResolver(String configType) {
        Symbol serde = Symbol.builder()
                .name("getSerdePlugin")
                .namespace("@aws-sdk/middleware-serde", "/")
                .addDependency(TypeScriptDependencies.MIDDLEWARE_SERDE)
                .build();

        writer.write("resolveMiddleware(")
                .indent()
                .write("clientStack: MiddlewareStack<$L, $L>,", inputType, outputType)
                .write("configuration: $L,", configType)
                .write("options?: $T", applicationProtocol.getOptionsType())
                .dedent();
        writer.openBlock("): Handler<$L, $L> {", "}", inputType, outputType, () -> {
            // Add serialization and deserialization plugin.
            writer.write("this.middlewareStack.use($T(configuration, this.serialize, this.deserialize));", serde);

            // Add customizations.
            addCommandSpecificPlugins();

            // Resolve the middleware stack.
            writer.write("\nconst stack = clientStack.concat(this.middlewareStack);\n");
            writer.openBlock("const handlerExecutionContext: HandlerExecutionContext = {", "}", () -> {
                writer.write("logger: {} as any,");
            });
            writer.write("const { httpHandler } = configuration;");
            writer.openBlock("return stack.resolve(", ");", () -> {
                writer.write("(request: FinalizeHandlerArguments<any>) => ");
                writer.write("  httpHandler.handle(request.request as $T, options || {}),",
                             applicationProtocol.getRequestType());
                writer.write("handlerExecutionContext");
            });
        });
    }

    private void addInputAndOutputTypes() {
        writeInputOrOutputType(inputType, operationIndex.getInput(operation).orElse(null));
        writeInputOrOutputType(outputType, operationIndex.getOutput(operation).orElse(null));
        writer.write("");
    }

    private void writeInputOrOutputType(String typeName, StructureShape struct) {
        // If the input or output are non-existent, then use an empty object.
        if (struct == null) {
            writer.write("export type $L = {};", typeName);
        } else {
            writer.write("export type $L = $T;", typeName, symbolProvider.toSymbol(struct));
        }
    }

    private void addCommandSpecificPlugins() {
        // Some plugins might only apply to specific commands. They are added to the
        // command's middleware stack here. Plugins that apply to all commands are
        // applied automatically when the Command's middleware stack is copied from
        // the service's middleware stack.
        for (RuntimeClientPlugin plugin : runtimePlugins) {
            plugin.getPluginFunction().ifPresent(symbol -> {
                writer.write("this.middlewareStack.use($T(configuration));", symbol);
            });
        }
    }

    private void writeSerde() {
        writer.write("")
                .write("private serialize(")
                .indent()
                    .write("input: $L,", inputType)
                    .write("protocol: string,")
                    .write("context: SerdeContext")
                .dedent()
                .openBlock("): $T {", "}", applicationProtocol.getRequestType(), () -> writeSerdeDispatcher(true));

        writer.write("")
                .write("private deserialize(")
                .indent()
                    .write("output: $T,", applicationProtocol.getResponseType())
                    .write("protocol: string,")
                    .write("context: SerdeContext")
                .dedent()
                .openBlock("): Promise<$L> {", "}", outputType, () -> writeSerdeDispatcher(false))
                .write("");
    }

    private void writeSerdeDispatcher(boolean isInput) {
        writer.openBlock("switch (protocol) {", "}", () -> {
            // Generate case statements for each supported protocol.
            // For example:
            // case 'aws.rest-json-1.1':
            //   return getFooCommandAws_RestJson1_1Serialize(input, utils);
            // TODO Validate this is the right set of protocols; settings.protocols was empty here.
            for (String protocol : settings.resolveServiceProtocols(service)) {
                String serdeFunctionName = isInput
                        ? ProtocolGenerator.getSerFunctionName(symbol, protocol)
                        : ProtocolGenerator.getDeserFunctionName(symbol, protocol);
                writer.addImport(serdeFunctionName, serdeFunctionName,
                        "./protocols/" + ProtocolGenerator.getSanitizedName(protocol));
                writer.write("case '$L':", protocol)
                        .write("  return $L($L, context);", serdeFunctionName, isInput ? "input" : "output");
            }

            writer.write("default:")
                    .write("  throw new Error(\"Unknown protocol, \" + protocol + \". Expected one of: $L\");",
                           settings.getProtocols());
        });
    }
}
