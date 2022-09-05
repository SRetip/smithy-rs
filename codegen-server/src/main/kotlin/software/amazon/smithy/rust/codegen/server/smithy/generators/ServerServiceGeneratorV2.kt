/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ResourceShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.traits.DocumentationTrait
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustReservedWords
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.docs
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.smithy.CoreCodegenContext
import software.amazon.smithy.rust.codegen.util.getTrait
import software.amazon.smithy.rust.codegen.util.orNull
import software.amazon.smithy.rust.codegen.util.toPascalCase
import software.amazon.smithy.rust.codegen.util.toSnakeCase

class ServerServiceGeneratorV2(
    coreCodegenContext: CoreCodegenContext,
    private val service: ServiceShape,
    private val protocol: ServerProtocol,
) {
    private val runtimeConfig = coreCodegenContext.runtimeConfig
    private val codegenScope =
        arrayOf(
            "Bytes" to CargoDependency.Bytes.asType(),
            "Http" to CargoDependency.Http.asType(),
            "HttpBody" to CargoDependency.HttpBody.asType(),
            "SmithyHttpServer" to
                ServerCargoDependency.SmithyHttpServer(runtimeConfig).asType(),
            "Tower" to CargoDependency.Tower.asType(),
        )
    private val model = coreCodegenContext.model
    private val symbolProvider = coreCodegenContext.symbolProvider

    private val serviceName = service.id.name
    private val builderName = "${serviceName}Builder"

    // Calculate all `operationShape`s contained within the `ServiceShape`.
    private val resourceOperationShapes = service
        .resources
        .mapNotNull { model.getShape(it).orNull() }
        .mapNotNull { it as? ResourceShape }
        .flatMap { it.allOperations }
        .mapNotNull { model.getShape(it).orNull() }
        .mapNotNull { it as? OperationShape }
    private val operationShapes = service.operations.mapNotNull { model.getShape(it).orNull() }.mapNotNull { it as? OperationShape }
    private val allOperationShapes = resourceOperationShapes + operationShapes

    // Returns the sequence of builder generics: `Op1`, ..., `OpN`.
    private fun builderGenerics(): Sequence<String> = sequence {
        for (index in 1..allOperationShapes.size) {
            yield("Op$index")
        }
    }

    // Returns the sequence of extension types: `Ext1`, ..., `ExtN`.
    private fun extensionTypes(): Sequence<String> = sequence {
        for (index in 1..allOperationShapes.size) {
            yield("Exts$index")
        }
    }

    // Returns the sequence of field names for the builder.
    private fun builderFieldNames(): Sequence<String> = sequence {
        for (operation in allOperationShapes) {
            val field = RustReservedWords.escapeIfNeeded(symbolProvider.toSymbol(operation).name.toSnakeCase())
            yield(field)
        }
    }

    // Returns the sequence of operation struct names.
    private fun operationStructNames(): Sequence<String> = sequence {
        for (operation in allOperationShapes) {
            yield(symbolProvider.toSymbol(operation).name.toPascalCase())
        }
    }

    // Returns a `Writable` block of "field: Type" for the builder.
    private fun builderFields(): Writable = writable {
        val zipped = builderFieldNames().zip(builderGenerics())
        for ((name, type) in zipped) {
            rust("$name: $type,")
        }
    }

    // Returns a `Writable` block containing all the `Handler` and `Operation` setters for the builder.
    private fun builderSetters(): Writable = writable {
        for ((index, pair) in builderFieldNames().zip(operationStructNames()).withIndex()) {
            val (fieldName, structName) = pair

            // The new generics for the handler setter, using `NewOp` where appropriate.
            val replacedGenericsService = writable {
                for ((innerIndex, item) in builderGenerics().withIndex()) {
                    if (innerIndex == index) {
                        rustTemplate(
                            """
                            #{SmithyHttpServer}::operation::Operation<#{SmithyHttpServer}::operation::IntoService<crate::operations::$structName, H>>
                            """,
                            *codegenScope,
                        )
                    } else {
                        rust("$item")
                    }
                    rust(", ")
                }
            }

            // The new generics for the operation setter, using `NewOp` where appropriate.
            val replacedGenerics = builderGenerics().withIndex().map { (innerIndex, item) ->
                if (innerIndex == index) {
                    "NewOp"
                } else {
                    item
                }
            }

            // The assignment of fields, using value where appropriate.
            val switchedFields = writable {
                for ((innerIndex, innerFieldName) in builderFieldNames().withIndex()) {
                    if (index == innerIndex) {
                        rust("$innerFieldName: value,")
                    } else {
                        rust("$innerFieldName: self.$innerFieldName,")
                    }
                }
            }

            rustTemplate(
                """
                /// Sets the [`$structName`](crate::operations::$structName) operation.
                ///
                /// This should be an [`Operation`](#{SmithyHttpServer}::operation::Operation) created from
                /// [`$structName`](crate::operations::$structName) using either
                /// [`OperationShape::from_handler`](#{SmithyHttpServer}::operation::OperationShapeExt::from_handler) or
                /// [`OperationShape::from_service`](#{SmithyHttpServer}::operation::OperationShapeExt::from_service).
                pub fn $fieldName<H, Exts>(self, value: H) -> $builderName<#{ReplacedGenericsService:W} ${extensionTypes().joinToString(",")}>
                where
                    H: #{SmithyHttpServer}::operation::Handler<crate::operations::$structName, Exts>
                {
                    use #{SmithyHttpServer}::operation::OperationShapeExt;
                    self.${fieldName}_operation(crate::operations::$structName::from_handler(value))
                }

                /// Sets the [`$structName`](crate::operations::$structName) operation.
                ///
                /// This should be an [`Operation`](#{SmithyHttpServer}::operation::Operation) created from
                /// [`$structName`](crate::operations::$structName) using either
                /// [`OperationShape::from_handler`](#{SmithyHttpServer}::operation::OperationShapeExt::from_handler) or
                /// [`OperationShape::from_service`](#{SmithyHttpServer}::operation::OperationShapeExt::from_service).
                pub fn ${fieldName}_operation<NewOp>(self, value: NewOp) -> $builderName<${(replacedGenerics + extensionTypes()).joinToString(",")}> {
                    $builderName {
                        #{SwitchedFields:W}
                        modifier: self.modifier,
                        _exts: std::marker::PhantomData
                    }
                }
                """,
                "SwitchedFields" to switchedFields,
                "ReplacedGenericsService" to replacedGenericsService,
                *codegenScope,
            )

            // Adds newline to between setters
            rust("")
        }
    }

    // / Returns the constraints required for the `build` method.
    private fun buildConstraints(): Writable = writable {
        for (tuple in allOperationShapes.asSequence().zip(builderGenerics()).zip(extensionTypes())) {
            val (first, exts) = tuple
            val (operation, type) = first
            // TODO(Relax): The `Error = Infallible` is an excess requirement to stay at parity with existing builder.
            rustTemplate(
                """
                $type: #{SmithyHttpServer}::operation::Upgradable<
                    #{Marker},
                    crate::operations::${symbolProvider.toSymbol(operation).name.toPascalCase()},
                    $exts,
                    B,
                    Modifier
                >,
                $type::Service: Clone + Send + 'static,
                <$type::Service as #{Tower}::Service<#{Http}::Request<B>>>::Future: Send + 'static,

                $type::Service: #{Tower}::Service<#{Http}::Request<B>, Error = std::convert::Infallible>,
                """,
                "Marker" to protocol.markerStruct(),
                *codegenScope,
            )
        }
    }

    // Returns a `Writable` containing the builder struct definition and its implementations.
    private fun builder(): Writable = writable {
        val generics = (builderGenerics() + extensionTypes()).joinToString(",")

        // Generate router construction block.
        val router = protocol
            .routerConstruction(
                service,
                builderFieldNames()
                    .map {
                        writable { rustTemplate("self.$it.upgrade(&self.modifier)") }
                    }
                    .asIterable(),
                model,
            )
        rustTemplate(
            """
            /// The service builder for [`$serviceName`].
            ///
            /// Constructed via [`$serviceName::builder`].
            pub struct $builderName<$generics, Modifier = #{SmithyHttpServer}::build_modifier::Identity> {
                #{Fields:W}
                modifier: Modifier,
                ##[allow(unused_parens)]
                _exts: std::marker::PhantomData<(${extensionTypes().joinToString(",")})>
            }

            impl<$generics> $builderName<$generics> {
                #{Setters:W}
            }

            impl<$generics, Modifier> $builderName<$generics, Modifier> {
                /// Constructs a [`$serviceName`] from the arguments provided to the builder.
                pub fn build<B>(self) -> $serviceName<#{SmithyHttpServer}::routing::Route<B>>
                where
                    #{BuildConstraints:W}
                {
                    let router = #{Router:W};
                    $serviceName {
                        router: #{SmithyHttpServer}::routing::routers::RoutingService::new(router),
                    }
                }
            }
            """,
            "Fields" to builderFields(),
            "Setters" to builderSetters(),
            "BuildConstraints" to buildConstraints(),
            "Router" to router,
            *codegenScope,
        )
    }

    // Returns a `Writable` comma delimited sequence of `OperationNotSet`.
    private fun notSetGenerics(): Writable = writable {
        for (index in 1..allOperationShapes.size) {
            rustTemplate("#{SmithyHttpServer}::operation::OperationNotSet,", *codegenScope)
        }
    }

    // Returns a `Writable` comma delimited sequence of `builder_field: OperationNotSet`.
    private fun notSetFields(): Writable = writable {
        for (fieldName in builderFieldNames()) {
            rustTemplate(
                "$fieldName: #{SmithyHttpServer}::operation::OperationNotSet,",
                *codegenScope,
            )
        }
    }

    // Returns a `Writable` containing the service struct definition and its implementations.
    private fun struct(): Writable = writable {
        // Generate struct documentation.
        val documentation = service.getTrait<DocumentationTrait>()?.value
        if (documentation != null) {
            docs(documentation.replace("#", "##"))
        }

        rustTemplate(
            """
            ##[derive(Clone)]
            pub struct $serviceName<S> {
                router: #{SmithyHttpServer}::routing::routers::RoutingService<#{Router}<S>, #{Protocol}>,
            }

            impl $serviceName<()> {
                /// Constructs a builder for [`$serviceName`].
                pub fn builder<${extensionTypes().joinToString(",")}>() -> $builderName<#{NotSetGenerics:W} ${extensionTypes().joinToString(",")}> {
                    $builderName {
                        #{NotSetFields:W}
                        modifier: #{SmithyHttpServer}::build_modifier::Identity,
                        _exts: std::marker::PhantomData
                    }
                }
            }

            impl<S> $serviceName<S> {
                /// Converts [`$serviceName`] into a [`MakeService`](tower::make::MakeService).
                pub fn into_make_service(self) -> #{SmithyHttpServer}::routing::IntoMakeService<Self> {
                    #{SmithyHttpServer}::routing::IntoMakeService::new(self)
                }

                /// Applies a layer uniformly to all routes.
                pub fn layer<L>(self, layer: &L) -> $serviceName<L::Service>
                where
                    L: #{Tower}::Layer<S>
                {
                    $serviceName {
                        router: self.router.map(|s| s.layer(layer))
                    }
                }
            }

            impl<B, RespB, S> #{Tower}::Service<#{Http}::Request<B>> for $serviceName<S>
            where
                S: #{Tower}::Service<#{Http}::Request<B>, Response = #{Http}::Response<RespB>> + Clone,
                RespB: #{HttpBody}::Body<Data = #{Bytes}::Bytes> + Send + 'static,
                RespB::Error: Into<#{Tower}::BoxError>
            {
                type Response = #{Http}::Response<#{SmithyHttpServer}::body::BoxBody>;
                type Error = S::Error;
                type Future = #{SmithyHttpServer}::routing::routers::RoutingFuture<S, B>;

                fn poll_ready(&mut self, cx: &mut std::task::Context) -> std::task::Poll<Result<(), Self::Error>> {
                    self.router.poll_ready(cx)
                }

                fn call(&mut self, request: #{Http}::Request<B>) -> Self::Future {
                    self.router.call(request)
                }
            }
            """,
            "NotSetGenerics" to notSetGenerics(),
            "NotSetFields" to notSetFields(),
            "Router" to protocol.routerType(),
            "Protocol" to protocol.markerStruct(),
            *codegenScope,
        )
    }

    fun render(writer: RustWriter) {
        writer.rustTemplate(
            """
            #{Builder:W}

            #{Struct:W}
            """,
            "Builder" to builder(),
            "Struct" to struct(),
        )
    }
}