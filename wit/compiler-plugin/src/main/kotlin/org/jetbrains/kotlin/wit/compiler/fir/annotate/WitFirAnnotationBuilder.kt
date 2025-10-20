package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeConstructor

internal class WitFirAnnotationBuilder(private val session: FirSession) {

    fun buildWorldAnnotation(metadata: WorldMetadata): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_WORLD_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("packageId")] = buildStringLiteral(metadata.packageId)
                mapping[Name.identifier("worldName")] = buildStringLiteral(metadata.runtimeWorld.name)
            }
        }
    }

    fun buildBindingAnnotation(bindingMetadata: BindingMetadata): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_BINDING_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val directionExpression =
            buildEnumEntryExpression(WIT_BINDING_DIRECTION_CLASS_ID, bindingMetadata.direction.name) ?: return null
        val kindExpression =
            buildEnumEntryExpression(
                WIT_BINDING_KIND_CLASS_ID,
                bindingMetadata.binding.bindingKind.name,
            ) ?: return null
        val binding = bindingMetadata.binding
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("direction")] = directionExpression
                mapping[Name.identifier("kind")] = kindExpression
                mapping[Name.identifier("bindingName")] = buildStringLiteral(binding.name)
                val runtimeTarget = binding.target
                if (runtimeTarget.isNotEmpty()) {
                    mapping[Name.identifier("runtimeTarget")] = buildStringLiteral(runtimeTarget)
                }
                bindingMetadata.interfaceName?.takeIf { it.isNotEmpty() }?.let { interfaceName ->
                    mapping[Name.identifier("interfaceName")] = buildStringLiteral(interfaceName)
                }
                bindingMetadata.resourceName?.takeIf { it.isNotEmpty() }?.let { resourceName ->
                    mapping[Name.identifier("resourceName")] = buildStringLiteral(resourceName)
                }
                if (binding.signature?.isAsync == true) {
                    mapping[Name.identifier("isAsync")] = buildBooleanLiteral(true)
                }
                if (binding.signature?.usesStreams == true) {
                    mapping[Name.identifier("usesStreams")] = buildBooleanLiteral(true)
                }
            }
        }
    }

    fun buildResourceAnnotation(resourceMetadata: ResourceBindingMetadata): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_RESOURCE_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val resource = resourceMetadata.resource
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("interfaceName")] =
                    buildStringLiteral(resourceMetadata.runtimeInterface.name)
                mapping[Name.identifier("resourceName")] = buildStringLiteral(resource.name)
                resource.ownHandleType?.takeIf { it.isNotEmpty() }?.let { own ->
                    mapping[Name.identifier("ownHandleType")] = buildStringLiteral(own)
                }
                resource.borrowHandleType?.takeIf { it.isNotEmpty() }?.let { borrow ->
                    mapping[Name.identifier("borrowHandleType")] = buildStringLiteral(borrow)
                }
            }
        }
    }

    fun buildConstructorAnnotation(
        metadata: WorldMetadata,
        runtimeConstructor: WitRuntimeConstructor,
    ): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_CONSTRUCTOR_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val bindingDirection =
            metadata.importBindings.values.firstOrNull { it.binding.name == runtimeConstructor.bindingName }?.direction
                ?: metadata.exportBindings.values.firstOrNull { it.binding.name == runtimeConstructor.bindingName }?.direction
                ?: return null
        val directionExpression =
            buildEnumEntryExpression(WIT_BINDING_DIRECTION_CLASS_ID, bindingDirection.name) ?: return null
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("bindingName")] =
                    buildStringLiteral(runtimeConstructor.bindingName)
                mapping[Name.identifier("direction")] = directionExpression
            }
        }
    }

    private fun buildEnumEntryExpression(classId: ClassId, entryName: String): FirExpression? {
        if (session.symbolProvider.getClassLikeSymbolByClassId(classId) == null) return null
        return buildEnumEntryDeserializedAccessExpression {
            enumClassId = classId
            enumEntryName = Name.identifier(entryName)
        }.toQualifiedPropertyAccessExpression(session)
    }
}
