package labs.cerberus.ts.rest

import com.fasterxml.jackson.databind.ObjectMapper
import labs.cerberus.ts.service.*
import java.lang.reflect.Modifier
import java.util.UUID
import kotlin.reflect.KClass

enum class RequestMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class RestController(val endpoint: String, val pluginId: String = "")
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class RequestMapping(val method: RequestMethod, val endpoint: String, val isSecured: Boolean = true)
const val NO_DEFAULT = "\u0000"
@Target(AnnotationTarget.VALUE_PARAMETER) @Retention(AnnotationRetention.RUNTIME)
annotation class RequestParam(val name: String, val type: KClass<*> = String::class, val required: Boolean = true, val defaultValue: String = NO_DEFAULT)
@Target(AnnotationTarget.VALUE_PARAMETER) @Retention(AnnotationRetention.RUNTIME)
annotation class PathParam(val name: String, val type: KClass<*> = String::class)
@Target(AnnotationTarget.VALUE_PARAMETER) @Retention(AnnotationRetention.RUNTIME)
annotation class RequestBody(val required: Boolean = true)

/** Explicit HTTP errors survive controller reflection without exposing unexpected exception messages. */
class RestException(val status: Int, override val message: String) : RuntimeException(message) {
    init { require(status in 400..599) }
}
class ResponseEntity<T> @JvmOverloads constructor(val entity: T, var status: Int = 200, headers: Map<String, String> = emptyMap()) {
    private val values = headers.toMutableMap()
    val headers: Map<String, String> get() = values.toMap()
    fun addHeader(name: String, value: String) { values[name] = value }
    fun removeHeader(name: String) { values.remove(name) }
    class Builder<T>(private val entity: T) {
        private var status = 200; private val headers = linkedMapOf<String, String>()
        fun withStatus(value: Int) = apply { require(value in 100..599); status = value }
        fun withHeader(name: String, value: String) = apply { headers[name] = value }
        fun withHeaders(values: Map<String, String>) = apply { headers.putAll(values) }
        fun build() = ResponseEntity(entity, status, headers)
    }
}

/** Compiles parameter binders once, before registering any routes. */
internal object ControllerBindings {
    data class Route(val method: String, val path: String, val secured: Boolean, val handler: (RestRequest) -> RestResponse)
    fun routes(controller: Any, mapper: ObjectMapper, invocation: MethodInvocation): List<Route> {
        val declaration = controller.javaClass.getAnnotation(RestController::class.java) ?: error("Missing @RestController")
        return controller.javaClass.methods.filter { it.isAnnotationPresent(RequestMapping::class.java) }.map { method ->
            require(!Modifier.isStatic(method.modifiers) && !method.isBridge && method.trySetAccessible()) { "Controller method must be an accessible instance method" }
            invocation.validate(method)
            val annotation = method.getAnnotation(RequestMapping::class.java)
            val path = ("/" + declaration.endpoint.trim('/') + "/" + annotation.endpoint.trim('/')).replace(Regex("/+"), "/").let {
                if (it.length > 1) it.trimEnd('/') else it
            }
            val pathNames = path.split('/').mapNotNull { part -> when {
                part.startsWith(':') -> part.drop(1)
                part.startsWith('{') && part.endsWith('}') -> part.substring(1, part.length - 1)
                else -> null
            } }.toSet()
            require(method.parameters.count { it.isAnnotationPresent(RequestBody::class.java) } <= 1) { "Only one request body parameter is allowed" }
            val binders = method.parameters.map { parameter ->
                val query = parameter.getAnnotation(RequestParam::class.java)
                val segment = parameter.getAnnotation(PathParam::class.java)
                val body = parameter.getAnnotation(RequestBody::class.java)
                require(listOfNotNull(query, segment, body).size <= 1) { "Conflicting REST parameter annotations" }
                val binder: (RestRequest) -> Any? = when {
                    query != null -> {
                        require(query.name.isNotBlank())
                        val type = if (query.type == String::class) parameter.type else query.type.javaObjectType
                        require(box(parameter.type).isAssignableFrom(box(type))) { "Request parameter type does not match method" }
                        validateScalar(type)
                        require(query.required || !parameter.type.isPrimitive || query.defaultValue != NO_DEFAULT) { "Optional primitive parameter needs a default" }
                        val binding: (RestRequest) -> Any? = { request ->
                            val values = request.queryParameters[query.name].orEmpty()
                            if (values.size > 1) throw RestException(400, "Repeated query parameter: ${query.name}")
                            val value = values.singleOrNull() ?: query.defaultValue.takeUnless { it == NO_DEFAULT }
                            if (value == null && query.required) throw RestException(400, "Missing query parameter: ${query.name}")
                            value?.let { scalar(it, type, query.name) }
                        }
                        binding
                    }
                    segment != null -> {
                        require(segment.name in pathNames) { "Unknown path parameter: ${segment.name}" }
                        val type = if (segment.type == String::class) parameter.type else segment.type.javaObjectType
                        require(box(parameter.type).isAssignableFrom(box(type))); validateScalar(type)
                        val binding: (RestRequest) -> Any? = { request -> scalar(request.pathParameters.getValue(segment.name), type, segment.name) }
                        binding
                    }
                    body != null -> { request: RestRequest ->
                        if (request.body.isEmpty() && body.required) throw RestException(400, "Missing request body")
                        if (request.body.isEmpty() && !body.required) null else when(parameter.type) {
                            String::class.java -> request.body.toString(Charsets.UTF_8)
                            ByteArray::class.java -> request.body.copyOf()
                            else -> try {
                                val decoded = mapper.readValue<Any?>(request.body, mapper.typeFactory.constructType(parameter.parameterizedType))
                                if (decoded == null && body.required) throw RestException(400, "Missing request body")
                                decoded
                            }
                                catch (_: com.fasterxml.jackson.core.JacksonException) { throw RestException(400, "Invalid request body") }
                        }
                    }
                    parameter.type == RestRequest::class.java -> { request: RestRequest -> request }
                    else -> error("Controller parameter needs @RequestParam, @PathParam or @RequestBody")
                }
                binder
            }
            Route(annotation.method.name, path, annotation.isSecured) { request ->
                val args = binders.map { it(request) }.toTypedArray()
                val result = invocation.invoke(controller, method, args)
                when (result) {
                    is RestResponse -> result
                    is ResponseEntity<*> -> RestResponse(result.status, result.entity, result.headers)
                    null, Unit -> RestResponse(204, null)
                    else -> RestResponse(body = result)
                }
            }
        }.also { require(it.isNotEmpty()) { "Controller has no @RequestMapping methods" } }
    }
    private fun box(type: Class<*>): Class<*> = when(type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> type
    }
    private fun validateScalar(type: Class<*>) {
        require(box(type) in setOf(String::class.java, Int::class.javaObjectType, Long::class.javaObjectType, Short::class.javaObjectType,
            Byte::class.javaObjectType, Double::class.javaObjectType, Float::class.javaObjectType, Boolean::class.javaObjectType, UUID::class.java) || type.isEnum) { "Unsupported scalar REST parameter" }
    }
    private fun scalar(value: String, type: Class<*>, name: String): Any = try {
        when (box(type)) {
            String::class.java -> value
            Int::class.javaObjectType -> value.toInt()
            Long::class.javaObjectType -> value.toLong()
            Short::class.javaObjectType -> value.toShort()
            Byte::class.javaObjectType -> value.toByte()
            Double::class.javaObjectType -> value.toDouble().also { require(it.isFinite()) }
            Float::class.javaObjectType -> value.toFloat().also { require(it.isFinite()) }
            Boolean::class.javaObjectType -> value.lowercase().toBooleanStrict()
            UUID::class.java -> UUID.fromString(value)
            else -> type.enumConstants.single { (it as Enum<*>).name == value }
        }
    } catch (_: Exception) { throw RestException(400, "Invalid parameter: $name") }
}
