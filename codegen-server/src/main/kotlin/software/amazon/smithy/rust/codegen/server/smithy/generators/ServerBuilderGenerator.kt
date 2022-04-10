/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.rustlang.Attribute
import software.amazon.smithy.rust.codegen.rustlang.RustType
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.conditionalBlock
import software.amazon.smithy.rust.codegen.rustlang.docs
import software.amazon.smithy.rust.codegen.rustlang.documentShape
import software.amazon.smithy.rust.codegen.rustlang.implInto
import software.amazon.smithy.rust.codegen.rustlang.render
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.rustlang.withBlock
import software.amazon.smithy.rust.codegen.server.smithy.ConstraintViolationSymbolProvider
import software.amazon.smithy.rust.codegen.server.smithy.ServerRuntimeType
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.expectRustMetadata
import software.amazon.smithy.rust.codegen.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.smithy.generators.builderSymbol
import software.amazon.smithy.rust.codegen.smithy.generators.targetNeedsValidation
import software.amazon.smithy.rust.codegen.smithy.isOptional
import software.amazon.smithy.rust.codegen.smithy.letIf
import software.amazon.smithy.rust.codegen.smithy.makeOptional
import software.amazon.smithy.rust.codegen.smithy.mapRustType
import software.amazon.smithy.rust.codegen.smithy.rustType
import software.amazon.smithy.rust.codegen.smithy.wrapValidated
import software.amazon.smithy.rust.codegen.util.toPascalCase
import software.amazon.smithy.rust.codegen.util.toSnakeCase

// TODO Document differences:
//     - This one takes in codegenContext.
//     - Unlike in `BuilderGenerator.kt`, we don't add helper methods to add items to vectors and hash maps.
//     - This builder is not `PartialEq`.
class ServerBuilderGenerator(
    private val codegenContext: CodegenContext,
    private val shape: StructureShape
) {
    private val model = codegenContext.model
    private val symbolProvider = codegenContext.symbolProvider
    private val constraintViolationSymbolProvider =
        ConstraintViolationSymbolProvider(codegenContext.symbolProvider, model, codegenContext.serviceShape)
    private val members: List<MemberShape> = shape.allMembers.values.toList()
    private val structureSymbol = symbolProvider.toSymbol(shape)
    private val moduleName = shape.builderSymbol(symbolProvider).namespace.split("::").last()

    fun render(writer: RustWriter) {
        writer.docs("See #D.", structureSymbol)
        writer.withModule(moduleName) {
            renderBuilder(this)
        }
    }

    private fun renderBuilder(writer: RustWriter) {
        if (StructureGenerator.serverHasFallibleBuilder(shape, model, symbolProvider)) {
            Attribute.Derives(setOf(RuntimeType.Debug, RuntimeType.PartialEq)).render(writer)
            // TODO(): `#[non_exhaustive] until we commit to making builders of builders public.
            Attribute.NonExhaustive.render(writer)
            writer.rustBlock("pub enum ValidationFailure") {
                validationFailures().forEach { renderValidationFailure(this, it) }
            }

            renderImplDisplayValidationFailure(writer)
            writer.rust("impl std::error::Error for ValidationFailure { }")

            // TODO This only needs to be generated for operation input shapes.
            renderImplFromValidationFailureForRequestRejection(writer)

            renderImplFromBuilderForValidated(writer)

            renderTryFromBuilderImpl(writer)
        } else {
            renderFromBuilderImpl(writer)
        }

        writer.docs("A builder for #D.", structureSymbol)
        // Matching derives to the main structure, - `PartialEq`, + `Default` since we are a builder and everything is optional.
        // TODO Manually implement `Default` so that we can add custom docs.
        val baseDerives = structureSymbol.expectRustMetadata().derives
        val derives = baseDerives.derives.intersect(setOf(RuntimeType.Debug, RuntimeType.Clone)) + RuntimeType.Default
        baseDerives.copy(derives = derives).render(writer)
        writer.rustBlock("pub struct Builder") {
            members.forEach { renderBuilderMember(this, it) }
        }

        writer.rustBlock("impl Builder") {
            for (member in members) {
                renderBuilderMemberFn(this, member)

                if (member.targetNeedsValidation(model, symbolProvider)) {
                    renderBuilderMemberSetterFn(this, member)
                }
            }
            renderBuildFn(this)
        }
    }

    // TODO This impl does not take into account sensitive trait.
    private fun renderImplDisplayValidationFailure(writer: RustWriter) {
        writer.rustBlock("impl std::fmt::Display for ValidationFailure") {
            rustBlock("fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result") {
                rustBlock("match self") {
                    validationFailures().forEach {
                        val arm = if (it.hasInner()) {
                            "ValidationFailure::${it.name()}(_)"
                        } else {
                            "ValidationFailure::${it.name()}"
                        }
                        rust("""$arm => write!(f, "${validationFailureErrorMessage(it)}"),""")
                    }
                }
            }
        }
    }

    private fun renderImplFromValidationFailureForRequestRejection(writer: RustWriter) {
        writer.rustTemplate(
            """
            impl From<ValidationFailure> for #{RequestRejection} {
                fn from(value: ValidationFailure) -> Self {
                    Self::BuildV2(value.into())
                }
            }
            """,
            "RequestRejection" to ServerRuntimeType.RequestRejection(codegenContext.runtimeConfig)
        )
    }

    private fun renderImplFromBuilderForValidated(writer: RustWriter) {
        writer.rust(
            """
            impl From<Builder> for #{T} {
                fn from(builder: Builder) -> Self {
                    Self::Unvalidated(builder)
                }
            }
            """,
            structureSymbol.wrapValidated()
        )
    }

    private fun renderBuildFn(implBlockWriter: RustWriter) {
        val fallibleBuilder = StructureGenerator.serverHasFallibleBuilder(shape, model, symbolProvider)
        val returnType = when (fallibleBuilder) {
            true -> "Result<${implBlockWriter.format(structureSymbol)}, ValidationFailure>"
            false -> implBlockWriter.format(structureSymbol)
        }
        // TODO Document when builder can fail.
        // TODO Document it returns the first error.
        implBlockWriter.docs("Consumes the builder and constructs a #D.", structureSymbol)
        implBlockWriter.rustBlock("pub fn build(self) -> $returnType") {
            conditionalBlock("Ok(", ")", conditional = fallibleBuilder) {
                coreBuilder(this)
            }
        }
    }

    fun renderConvenienceMethod(implBlock: RustWriter) {
        val builderSymbol = shape.builderSymbol(symbolProvider)
        implBlock.docs("Creates a new builder-style object to manufacture #D.", structureSymbol)
        implBlock.rustBlock("pub fn builder() -> #T", builderSymbol) {
            write("#T::default()", builderSymbol)
        }
    }

    // TODO(EventStream): [DX] Consider updating builders to take EventInputStream as Into<EventInputStream>
    private fun renderBuilderMember(writer: RustWriter, member: MemberShape) {
        val memberSymbol = builderMemberSymbol(member)
        val memberName = symbolProvider.toMemberName(member)
        // Builder members are crate-public to enable using them directly in serializers/deserializers.
        // During XML deserialization, `builder.<field>.take` is used to append to lists and maps.
        writer.write("pub(crate) $memberName: #T,", memberSymbol)
    }

    /**
     * Render a `foo` method for to set shape member `foo`. The caller must provide a value with the exact same type
     * as the shape member's type.
     */
    private fun renderBuilderMemberFn(
        writer: RustWriter,
        member: MemberShape,
    ) {
        val symbol = symbolProvider.toSymbol(member)
        val memberName = symbolProvider.toMemberName(member)

        writer.documentShape(member, model)
        writer.rustBlock("pub fn $memberName(mut self, input: ${symbol.rustType().render()}) -> Self") {
            rust("self.$memberName = ")
            conditionalBlock("Some(", ")", conditional = !symbol.isOptional()) {
                if (member.targetNeedsValidation(model, symbolProvider)) {
                    val validatedType = "${symbol.wrapValidated().rustType().namespace}::Validated::Validated"
                    if (symbol.isOptional()) {
                        write("input.map(|v| $validatedType(v))")
                    } else {
                        write("$validatedType(input)")
                    }
                } else {
                    write("input")
                }
            }
            rust(";")
            rust("self")
        }
    }

    /**
     * Render a `set_foo` method. This method is able to take in builders of structure shape types.
     */
    private fun renderBuilderMemberSetterFn(
        writer: RustWriter,
        member: MemberShape,
    ) {
        val builderMemberSymbol = builderMemberSymbol(member)
        val inputType = builderMemberSymbol.rustType().stripOuter<RustType.Option>().implInto().letIf(member.isOptional) { "Option<$it>" }
        val memberName = symbolProvider.toMemberName(member)

        writer.documentShape(member, model)
        // TODO: This method is only used by deserializers, so it will remain unused for shapes that are not (transitively)
        //     part of an operation input. We therefore `[allow(dead_code)]` here.
        Attribute.AllowDeadCode.render(writer)
        // TODO(): `pub(crate)` until we commit to making builders of builders public.
        // Setter names will never hit a reserved word and therefore never need escaping.
        writer.rustBlock("pub(crate) fn set_${memberName.toSnakeCase()}(mut self, input: $inputType) -> Self") {
            rust(
                """
                self.$memberName = ${
                    if (member.isOptional) {
                        "input.map(|v| v.into())"
                    } else {
                        "Some(input.into())"
                    }
                };
                self
                """
            )
        }
    }

    /**
     * The kinds of validation failures that can occur when building the builder.
     */
    enum class ValidationFailureKind {
        // A field is required but was not provided.
        MISSING_MEMBER,
        // An unconstrained type was provided for a field targeting a constrained shape, but it failed to convert into the constrained type.
        CONSTRAINED_SHAPE_FAILURE,
    }

    data class ValidationFailure(val forMember: MemberShape, val kind: ValidationFailureKind) {
        fun name() = when (kind) {
            ValidationFailureKind.MISSING_MEMBER -> "Missing${forMember.memberName.toPascalCase()}"
            ValidationFailureKind.CONSTRAINED_SHAPE_FAILURE -> "${forMember.memberName.toPascalCase()}ValidationFailure"
        }

        /**
         * Whether the validation failure is a Rust tuple struct with one element.
         */
        fun hasInner() = kind == ValidationFailureKind.CONSTRAINED_SHAPE_FAILURE
    }

    private fun renderValidationFailure(writer: RustWriter, validationFailure: ValidationFailure) {
        if (validationFailure.kind == ValidationFailureKind.CONSTRAINED_SHAPE_FAILURE) {
            // TODO(): `#[doc(hidden)]` until we commit to making builders of builders public.
            Attribute.DocHidden.render(writer)
        }

        // TODO Add Rust docs.

        when (validationFailure.kind) {
            ValidationFailureKind.MISSING_MEMBER -> writer.rust("${validationFailure.name()},")
            ValidationFailureKind.CONSTRAINED_SHAPE_FAILURE -> {
                val targetShape = model.expectShape(validationFailure.forMember.target)
                // Note we cannot express the inner validation failure as `<T as TryFrom<T>>::Error`, because `T` might
                // be `pub(crate)` and that would leak `T` in a public interface.
                writer.rust("${validationFailure.name()}(#T),", constraintViolationSymbolProvider.toSymbol(targetShape))
            }
        }
    }

    private fun validationFailureErrorMessage(validationFailure: ValidationFailure): String {
        val memberName = symbolProvider.toMemberName(validationFailure.forMember)
        // TODO $structureSymbol here is not quite right because it's printing the full namespace: crate:: in the context of the user will surely be different.
        return when (validationFailure.kind) {
            ValidationFailureKind.MISSING_MEMBER -> "`$memberName` was not specified but it is required when building `$structureSymbol`"
            // TODO Nest errors.
            ValidationFailureKind.CONSTRAINED_SHAPE_FAILURE -> "validation failure occurred building member `$memberName` when building `$structureSymbol`"
        }
    }

    private fun validationFailures() = members.flatMap { member ->
        listOfNotNull(
            builderMissingFieldForMember(member),
            builderValidationFailureForMember(member),
        )
    }

    /**
     * Returns the builder failure associated with the `member` field if its target requires validation.
     */
    private fun builderValidationFailureForMember(member: MemberShape) =
        if (member.targetNeedsValidation(model, symbolProvider)) {
            ValidationFailure(member, ValidationFailureKind.CONSTRAINED_SHAPE_FAILURE)
        } else {
            null
        }

    /**
     * Returns the builder failure associated with the `member` field if it is `required`.
     */
    private fun builderMissingFieldForMember(member: MemberShape) =
        // TODO: We go through the symbol provider because non-`required` blob streaming members are interpreted as `required`,
        //     so we can't use `member.isOptional`. See https://github.com/awslabs/smithy-rs/issues/1302.
        if (symbolProvider.toSymbol(member).isOptional()) {
            null
        } else {
            ValidationFailure(member, ValidationFailureKind.MISSING_MEMBER)
        }

    private fun renderTryFromBuilderImpl(writer: RustWriter) {
        // TODO `TryFrom` is in Rust 2021's prelude.
        writer.rustTemplate(
            """
            impl std::convert::TryFrom<Builder> for #{Structure} {
                type Error = ValidationFailure;
                
                fn try_from(builder: Builder) -> Result<Self, Self::Error> {
                    builder.build()
                }
            }
            """,
            "Structure" to structureSymbol,
        )
    }

    private fun renderFromBuilderImpl(writer: RustWriter) {
        writer.rustTemplate(
            """
            impl From<Builder> for #{Structure} {
                fn from(builder: Builder) -> Self {
                    builder.build()
                }
            }
            """,
            "Structure" to structureSymbol,
        )
    }

    /**
     * Returns the symbol for a builder's member.
     * All builder members are optional, but only some are `Option<T>`s where `T` needs to be validated.
     */
    private fun builderMemberSymbol(member: MemberShape): Symbol =
        symbolProvider.toSymbol(member)
            // Strip the `Option` in case the member is not `required`.
            .mapRustType { it.stripOuter<RustType.Option>() }
            // Wrap the symbol with the Cow-like `validation::Validated` type in case the target member shape needs validation.
            .letIf(member.targetNeedsValidation(model, symbolProvider)) { it.wrapValidated() }
            // Ensure we end up with an `Option`.
            .makeOptional()

    /**
     * Writes the code to instantiate the struct the builder builds.
     *
     * Builder member types are either:
     *     1. `Option<Validated<T>>`; or
     *     2. `Option<T>`.
     *
     * The structs they build have member types:
     *     a) `Option<T>`; or
     *     b) `T`.
     *
     * For each member, this function first unwraps case 1. into 2., and then converts into b) if necessary.
     */
    private fun coreBuilder(writer: RustWriter) {
        writer.rustBlock("#T", structureSymbol) {
            for (member in members) {
                val memberName = symbolProvider.toMemberName(member)

                withBlock("$memberName: self.$memberName", ",") {
                    // Write the modifier(s).
                    builderValidationFailureForMember(member)?.let {
                        // TODO Remove `TryInto` import when we switch to 2021 edition.
                        rustTemplate(
                            """
                            .map(|v| match v {
                                #{Validated}::Validated(x) => Ok(x),
                                #{Validated}::Unvalidated(x) => {
                                    use std::convert::TryInto;
                                    x.try_into()
                                }
                            })
                            .map(|v| v.map_err(|err| ValidationFailure::${it.name()}(err)))
                            .transpose()?
                            """,
                            "Validated" to RuntimeType.Validated()
                        )
                    }
                    builderMissingFieldForMember(member)?.let {
                        rust(".ok_or(ValidationFailure::${it.name()})?")
                    }
                }
            }
        }
    }
}
