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

import java.util.Collections;
import java.util.List;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.typescript.codegen.LanguageTarget;
import software.amazon.smithy.typescript.codegen.TypeScriptSettings;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;

/**
 * Java SPI for customizing TypeScript code generation, registering
 * new protocol code generators, renaming shapes, modifying the model,
 * adding custom code, etc.
 */
public interface TypeScriptIntegration {
    /**
     * Preprocess the model before code generation.
     *
     * <p>This can be used to remove unsupported features, remove traits
     * from shapes (e.g., make members optional), etc.
     *
     * @param context Plugin context.
     * @param settings Setting used to generate.
     * @return Returns the updated model.
     */
    default Model preprocessModel(PluginContext context, TypeScriptSettings settings) {
        return context.getModel();
    }

    /**
     * Updates the {@link SymbolProvider} used when generating code.
     *
     * <p>This can be used to customize the names of shapes, the package
     * that code is generated into, add dependencies, add imports, etc.
     *
     * @param settings Setting used to generate.
     * @param model Model being generated.
     * @param symbolProvider The original {@code SymbolProvider}.
     * @return The decorated {@code SymbolProvider}.
     */
    default SymbolProvider decorateSymbolProvider(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider
    ) {
        return symbolProvider;
    }

    /**
     * Called each time a writer is used that defines a shape.
     *
     * <p>This method could be called multiple times for the same writer
     * but for different shapes. It gives an opportunity to intercept code
     * sections of a {@link TypeScriptWriter} by name using the shape for
     * context. For example:
     *
     * <pre>
     * {@code
     * public final class MyIntegration implements TypeScriptIntegration {
     *     public onWriterUse(TypeScriptSettings settings, Model model, SymbolProvider symbolProvider,
     *             TypeScriptWriter writer, Shape definedShape) {
     *         writer.onSection("example", text -&gt; writer.write("Intercepted: " + text"));
     *     }
     * }
     * }</pre>
     *
     * <p>Any mutations made on the writer (for example, adding
     * section interceptors) are removed after the callback has completed;
     * the callback is invoked in between pushing and popping state from
     * the writer.
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     * @param symbolProvider Symbol provider used for codegen.
     * @param writer Writer that will be used.
     * @param definedShape Shape that is being defined in the writer.
     */
    default void onShapeWriterUse(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer,
            Shape definedShape
    ) {
        // pass
    }

    /**
     * Gets a list of plugins to apply to the generated client.
     *
     * @return Returns the list of RuntimePlugins to apply to the client.
     */
    default List<RuntimeClientPlugin> getClientPlugins() {
        return Collections.emptyList();
    }

    /**
     * Gets a list of protocol generators to register.
     *
     * @return Returns the list of protocol generators to register.
     */
    default List<ProtocolGenerator> getProtocolGenerators() {
        return Collections.emptyList();
    }

    /**
     * Adds additional client config interface fields.
     *
     * <p>Implementations of this method are expected to add fields to the
     * client dependency interface of a generated client. This interface
     * contains fields that are either statically generated from
     * a model or are dependent on the runtime that a client is running in.
     * Implementations are expected to write interface field names and
     * their type signatures, each followed by a semicolon (;). Any number
     * of fields can be added, and any {@link Symbol} or
     * {@link SymbolReference} objects that are written to the writer are
     * automatically imported, and any of their contained
     * {@link SymbolDependency} values are automatically added to the
     * generated {@code package.json} file.
     *
     * <p>For example, the following code adds two fields to a client:
     *
     * <pre>
     * {@code
     * public final class MyIntegration implements TypeScriptIntegration {
     *     public void addConfigInterfaceFields(
     *             TypeScriptSettings settings,
     *             Model model,
     *             SymbolProvider symbolProvider,
     *             TypeScriptWriter writer
     *     ) {
     *         writer.writeDocs("The docs for foo...");
     *         writer.write("foo?: string;"); // Note the trailing semicolon!
     *
     *         writer.writeDocs("The docs for bar...");
     *         writer.write("bar?: string;");
     *     }
     * }
     * }</pre>
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     * @param symbolProvider Symbol provider used for codegen.
     * @param writer TypeScript writer to write to.
     */
    default void addConfigInterfaceFields(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer
    ) {
        // pass
    }

    /**
     * Adds additional runtime-specific client config values.
     *
     * <p>Implementations of this method are expected to add values to
     * a runtime-specific configuration object that is used to provide
     * values for a client dependency interface. This method is invoked
     * for every supported {@link LanguageTarget}. Implementations are
     * expected to branch on the provided {@code LanguageTarget} and add
     * the appropriate default values and imports, each followed by a
     * (,). Any number of key-value pairs can be added, and any {@link Symbol}
     * or {@link SymbolReference} objects that are written to the writer are
     * automatically imported, and any of their contained
     * {@link SymbolDependency} values are automatically added to the
     * generated {@code package.json} file.
     *
     * <p>For example, the following code adds two values for both the
     * node and browser targets:
     *
     * <pre>
     * {@code
     * public final class MyIntegration implements TypeScriptIntegration {
     *
     *     private static final Logger LOGGER = Logger.getLogger(CodegenVisitor.class.getName());
     *
     *     public void addRuntimeConfigValues(
     *             TypeScriptSettings settings,
     *             Model model,
     *             SymbolProvider symbolProvider,
     *             TypeScriptWriter writer,
     *             LanguageTarget target
     *     ) {
     *         // This is a static value that is added to every generated
     *         // runtimeConfig file.
     *         writer.write("foo: 'some-static-value',"); // Note the trailing comma!
     *
     *         switch (target) {
     *             case NODE:
     *                 writer.write("bar: someNodeValue,");
     *                 break;
     *             case BROWSER:
     *                 writer.write("bar: someBrowserValue,");
     *                 break;
     *             default:
     *                 LOGGER.warn("Unknown target: " + target);
     *         }
     *     }
     * }
     * }</pre>
     *
     * @param settings Settings used to generate.
     * @param model Model to generate from.
     * @param symbolProvider Symbol provider used for codegen.
     * @param writer TypeScript writer to write to.
     * @param target The TypeScript language target.
     */
    default void addRuntimeConfigValues(
            TypeScriptSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TypeScriptWriter writer,
            LanguageTarget target
    ) {
        // pass
    }
}
