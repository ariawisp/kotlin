import kotlin.random.Random

fun main() {
    val randomValue = Random.nextLong()
    val randomBytes = Random.nextBytes(16)
    println("wasm-wasi random value: $randomValue")
    println("wasm-wasi random bytes: ${randomBytes.joinToString(prefix = "[", postfix = "]") { byte ->
        val unsigned = byte.toUByte().toString(16).padStart(2, '0')
        "0x$unsigned"
    }}")
}
