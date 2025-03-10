/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jacodb.impl.fs

import kotlinx.collections.immutable.toImmutableList
import org.jacodb.api.ClassSource
import org.jacodb.impl.storage.AnnotationValueKind
import org.jacodb.impl.types.AnnotationInfo
import org.jacodb.impl.types.AnnotationValue
import org.jacodb.impl.types.AnnotationValueList
import org.jacodb.impl.types.ClassInfo
import org.jacodb.impl.types.ClassRef
import org.jacodb.impl.types.EnumRef
import org.jacodb.impl.types.FieldInfo
import org.jacodb.impl.types.MethodInfo
import org.jacodb.impl.types.OuterClassRef
import org.jacodb.impl.types.ParameterInfo
import org.jacodb.impl.types.PrimitiveValue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeAnnotationNode

fun ClassNode.asClassInfo(bytecode: ByteArray) = ClassInfo(
    name = Type.getObjectType(name).className,
    signature = signature,
    access = access,

    outerClass = outerClassRef(),
    innerClasses = innerClasses.map {
        Type.getObjectType(it.name).className
    }.toImmutableList(),
    outerMethod = outerMethod,
    outerMethodDesc = outerMethodDesc,
    superClass = superName?.className,
    interfaces = interfaces.map { it.className }.toImmutableList(),
    methods = methods.map { it.asMethodInfo() }.toImmutableList(),
    fields = fields.map { it.asFieldInfo() }.toImmutableList(),
    annotations = visibleAnnotations.asAnnotationInfos(true) + invisibleAnnotations.asAnnotationInfos(false)
            + visibleTypeAnnotations.asTypeAnnotationInfos(true) + invisibleTypeAnnotations.asTypeAnnotationInfos(false),
    bytecode = bytecode
)

val String.className: String
    get() = Type.getObjectType(this).className

private fun ClassNode.outerClassRef(): OuterClassRef? {
    val innerRef = innerClasses.firstOrNull { it.name == name }

    val direct = outerClass?.className
    if (direct == null && innerRef != null && innerRef.outerName != null) {
        return OuterClassRef(innerRef.outerName.className, innerRef.innerName)
    }
    return direct?.let {
        OuterClassRef(it, innerRef?.innerName)
    }
}

private fun Any.toAnnotationValue(): AnnotationValue {
    return when (this) {
        is Type -> ClassRef(className)
        is AnnotationNode -> asAnnotationInfo(true)
        is List<*> -> AnnotationValueList(mapNotNull { it?.toAnnotationValue() })
        is Array<*> -> EnumRef((get(0) as String).className, get(1) as String)
        is Boolean -> PrimitiveValue(AnnotationValueKind.BOOLEAN, this)
        is Byte -> PrimitiveValue(AnnotationValueKind.BYTE, this)
        is Char -> PrimitiveValue(AnnotationValueKind.CHAR, this)
        is Short -> PrimitiveValue(AnnotationValueKind.SHORT, this)
        is Long -> PrimitiveValue(AnnotationValueKind.LONG, this)
        is Double -> PrimitiveValue(AnnotationValueKind.DOUBLE, this)
        is Float -> PrimitiveValue(AnnotationValueKind.FLOAT, this)
        is Int -> PrimitiveValue(AnnotationValueKind.INT, this)
        is String -> PrimitiveValue(AnnotationValueKind.STRING, this)
        else -> throw IllegalStateException("Unknown type: ${javaClass.name}")
    }
}

private fun AnnotationNode.asAnnotationInfo(visible: Boolean) = AnnotationInfo(
    className = Type.getType(desc).className,
    visible = visible,
    values = values?.chunked(2)?.map { (it[0] as String) to it[1].toAnnotationValue() }.orEmpty(),
    typeRef = null,
    typePath = null
)

private fun TypeAnnotationNode.asTypeAnnotationInfo(visible: Boolean) = AnnotationInfo(
    className = Type.getType(desc).className,
    visible = visible,
    values = values?.chunked(2)?.map { (it[0] as String) to it[1].toAnnotationValue() }.orEmpty(),
    typeRef = typeRef,
    typePath = typePath?.toString()
)

private fun List<AnnotationNode>?.asAnnotationInfos(visible: Boolean): List<AnnotationInfo> =
    orEmpty().map { it.asAnnotationInfo(visible) }.toImmutableList()

private fun List<TypeAnnotationNode>?.asTypeAnnotationInfos(visible: Boolean): List<AnnotationInfo> =
    orEmpty().map { it.asTypeAnnotationInfo(visible) }.toImmutableList()

private fun MethodNode.asMethodInfo(): MethodInfo {
    val params = Type.getArgumentTypes(desc).map { it.className }.toImmutableList()
    return MethodInfo(
        name = name,
        signature = signature,
        desc = desc,
        access = access,
        annotations = visibleAnnotations.asAnnotationInfos(true) + invisibleAnnotations.asAnnotationInfos(false)
                + visibleTypeAnnotations.asTypeAnnotationInfos(true) + invisibleTypeAnnotations.asTypeAnnotationInfos(false),
        exceptions = exceptions.map { it.className },
        parametersInfo = List(params.size) { index ->
            ParameterInfo(
                index = index,
                name = parameters?.get(index)?.name,
                access = parameters?.get(index)?.access ?: Opcodes.ACC_PUBLIC,
                type = params[index],
                annotations = visibleParameterAnnotations?.get(index)?.asAnnotationInfos(true).orEmpty()
                        + invisibleParameterAnnotations?.get(index)?.asAnnotationInfos(false).orEmpty()
            )
        }
    )
}

private fun FieldNode.asFieldInfo() = FieldInfo(
    name = name,
    signature = signature,
    access = access,
    type = Type.getObjectType(desc).className,
    annotations = visibleAnnotations.asAnnotationInfos(true) + invisibleAnnotations.asAnnotationInfos(false)
            + visibleTypeAnnotations.asTypeAnnotationInfos(true) + invisibleTypeAnnotations.asTypeAnnotationInfos(false),
)


val ClassSource.info: ClassInfo
    get() {
        return newClassNode(ClassReader.SKIP_CODE).asClassInfo(byteCode)
    }

val ClassSource.fullAsmNode: ClassNode
    get() {
        return newClassNode(ClassReader.EXPAND_FRAMES)
    }

//fun ClassSource.fullAsmNodeWithFrames(classpath: JcClasspath): ClassNode {
//    var classNode = fullAsmNode
//    classNode = when {
//        classNode.hasFrameInfo -> classNode
//        else -> classNode.computeFrames(classpath)
//    }
//    return classNode
//}

private fun ClassSource.newClassNode(level: Int): ClassNode {
    return ClassNode(Opcodes.ASM9).also {
        ClassReader(byteCode).accept(it, level)
    }
}
