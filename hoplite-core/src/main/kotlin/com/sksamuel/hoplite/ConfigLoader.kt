@file:Suppress("unused")

package com.sksamuel.hoplite

import arrow.core.valueOr
import com.sksamuel.hoplite.arrow.flatMap
import com.sksamuel.hoplite.arrow.sequence
import com.sksamuel.hoplite.decoder.Decoder
import com.sksamuel.hoplite.decoder.DecoderRegistry
import com.sksamuel.hoplite.decoder.defaultDecoderRegistry
import com.sksamuel.hoplite.parsers.Parser
import com.sksamuel.hoplite.parsers.ParserRegistry
import com.sksamuel.hoplite.parsers.defaultParserRegistry
import com.sksamuel.hoplite.preprocessor.Preprocessor
import com.sksamuel.hoplite.preprocessor.defaultPreprocessors
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

class ConfigException(msg: String) : java.lang.RuntimeException(msg)

class ConfigLoader(private val decoderRegistry: DecoderRegistry,
                   private val propertySources: List<PropertySource>,
                   private val parserRegistry: ParserRegistry,
                   private val preprocessors: List<Preprocessor>,
                   private val paramMappers: List<ParameterMapper>) {

  companion object {
    operator fun invoke(): ConfigLoader {
      val decoders = defaultDecoderRegistry()
      val parsers = defaultParserRegistry()
      val sources = defaultPropertySources(parsers)
      val preprocessors = defaultPreprocessors()
      val mappers = defaultParamMappers()
      return ConfigLoader(decoders, sources, parsers, preprocessors, mappers)
    }
  }

  fun withPreprocessor(preprocessor: Preprocessor) = ConfigLoader(
    decoderRegistry,
    propertySources,
    parserRegistry,
    preprocessors + preprocessor,
    paramMappers)

  fun withDecoder(decoder: Decoder<*>) = ConfigLoader(
    decoderRegistry.register(decoder),
    propertySources,
    parserRegistry,
    preprocessors,
    paramMappers)

  fun withFileExtensionMapping(ext: String, parser: Parser) = ConfigLoader(
    decoderRegistry,
    propertySources,
    parserRegistry.register(ext, parser),
    preprocessors,
    paramMappers)

  fun withParameterMapper(mapper: ParameterMapper) = ConfigLoader(
    decoderRegistry,
    propertySources,
    parserRegistry,
    preprocessors,
    paramMappers + mapper)

  fun withPropertySource(source: PropertySource) = ConfigLoader(
    decoderRegistry,
    propertySources + source,
    parserRegistry,
    preprocessors,
    paramMappers)

  /**
   * Attempts to load config from the specified resources on the class path and returns
   * an instance of <A> if the values can be appropriately converted.
   *
   * This function implements fallback, such that the first resource is scanned first, and the second
   * resource is scanned if the first does not contain a given path, and so on.
   */
  inline fun <reified A : Any> loadConfigOrThrow(vararg resources: String): A = loadConfigOrThrow(resources.toList())

  inline fun <reified A : Any> loadConfigOrThrow(resources: List<String>): A = loadConfig<A>(resources).returnOrThrow()

  inline fun <reified A : Any> loadConfigOrThrow(): A = loadConfig(A::class, emptyList()).returnOrThrow()

  /**
   * Attempts to load config from the specified resources on the class path and returns
   * a [ConfigResult] with either the errors during load, or the successfully created instance A.
   *
   * This function implements fallback, such that the first resource is scanned first, and the second
   * resource is scanned if the first does not contain a given path, and so on.
   */
  inline fun <reified A : Any> loadConfig(vararg resources: String): ConfigResult<A> = loadConfig(resources.toList())

  @JvmName("loadConfigFromResources")
  inline fun <reified A : Any> loadConfig(resources: List<String>): ConfigResult<A> {
    require(A::class.isData) { "Can only decode into data classes [was ${A::class}]" }
    return FileSource.fromClasspathResources(resources.toList()).flatMap { loadConfig(A::class, it) }
  }

  fun loadNodeOrThrow(resources: List<String>): Node =
    FileSource.fromClasspathResources(resources.toList()).flatMap { loadNode(it) }.returnOrThrow()

  /**
   * Attempts to load config from the specified resources on the class path and returns
   * an instance of <A> if the values can be appropriately converted.
   *
   * This function implements fallback, such that the first resource is scanned first, and the second
   * resource is scanned if the first does not contain a given path, and so on.
   */
  inline fun <reified A : Any> loadConfigOrThrow(vararg paths: Path): A = loadConfigOrThrow(paths.toList())

  @JvmName("loadConfigOrThrowFromPaths")
  inline fun <reified A : Any> loadConfigOrThrow(paths: List<Path>): A = loadConfig<A>(paths).returnOrThrow()

  @JvmName("loadNodeOrThrowFromPaths")
  fun loadNodeOrThrow(paths: List<Path>): Node =
    FileSource.fromPaths(paths.toList()).flatMap { loadNode(it) }.returnOrThrow()

  /**
   * Attempts to load config from the specified Paths and returns
   * a [ConfigResult] with either the errors during load, or the successfully created instance A.
   *
   * This function implements fallback, such that the first resource is scanned first, and the second
   * resource is scanned if the first does not contain a given path, and so on.
   */
  inline fun <reified A : Any> loadConfig(vararg paths: Path): ConfigResult<A> = loadConfig(paths.toList())

  @JvmName("loadConfigFromPaths")
  inline fun <reified A : Any> loadConfig(paths: List<Path>): ConfigResult<A> {
    require(A::class.isData) { "Can only decode into data classes [was ${A::class}]" }
    return FileSource.fromPaths(paths.toList()).flatMap { loadConfig(A::class, it) }
  }

  fun <A : Any> ConfigResult<A>.returnOrThrow(): A = this.valueOr {
    val err = "Error loading config because:\n\n" + it.description().prependIndent(Constants.indent)
    throw ConfigException(err)
  }

  fun <A : Any> loadConfig(klass: KClass<A>, inputs: List<FileSource>): ConfigResult<A> {
    fun Node.decode() = decoderRegistry.decoder(klass).flatMap { decoder ->
      decoder.decode(this, klass.createType(), DecoderContext(decoderRegistry, paramMappers))
    }
    return loadNode(inputs).flatMap { it.decode() }
  }

  private fun loadNode(files: List<FileSource>): ConfigResult<Node> {

    fun Node.preprocess() = preprocessors.fold(this) { node, preprocessor -> node.transform(preprocessor::process) }
    fun List<Node>.preprocessAll() = this.map { it.preprocess() }

    val srcs = propertySources + files.map { ConfigFilePropertySource(it, parserRegistry) }
    return srcs.map { it.node() }.sequence()
      .map { it.preprocessAll() }
      .map { it.reduce { acc, b -> acc.fallback(b) } }
      .leftMap { ConfigFailure.MultipleFailures(it) }
  }
}

