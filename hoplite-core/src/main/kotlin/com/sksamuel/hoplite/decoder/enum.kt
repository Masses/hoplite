package com.sksamuel.hoplite.decoder

import arrow.data.invalidNel
import arrow.data.valid
import com.sksamuel.hoplite.BooleanNode
import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DoubleNode
import com.sksamuel.hoplite.LongNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import kotlin.reflect.KClass
import kotlin.reflect.KType

@Suppress("UNCHECKED_CAST")
class EnumDecoder<T : Any> : Decoder<T> {

  override fun supports(type: KType): Boolean = type.classifier is KClass<*> && (type.classifier as KClass<*>).java.isEnum

  override fun decode(node: Node,
                      type: KType,
                      registry: DecoderRegistry,
                      path: String): ConfigResult<T> {

    val klass = type.classifier as KClass<*>

    fun decode(value: String): ConfigResult<T> {
      val t = klass.java.enumConstants.find { it.toString() == value }
      return if (t == null)
        ConfigFailure.InvalidEnumConstant(node, path, type, value).invalidNel()
      else
        (t as T).valid()
    }

    return when (node) {
      is StringNode -> decode(node.value)
      is BooleanNode -> decode(node.value.toString())
      is LongNode -> decode(node.value.toString())
      is DoubleNode -> decode(node.value.toString())
      else -> ConfigFailure.TypeConversionFailure(node, path, type).invalidNel()
    }
  }
}