import kotlin.experimental.and

@ExperimentalUnsignedTypes
fun main(args: Array<String>) {
    // Set up render system and register input callbacks
    setupGraphics()
    setupInput()

    // Initialize the Chip8 system and load the game into the memory
    val chip8 = Chip8()
    chip8.loadGame(args[0])

    while (true) {
        chip8.emulateCycle()

        if (chip8.drawFlag) {
            drawGraphics()
        }
        chip8.setKeys()

        // TODO: should run 60 times per second
        Thread.sleep(1000)
    }


}

fun drawGraphics() {
    println("drawGraphics: Not yet implemented")
}

fun setupGraphics() {
    println("setupGraphics: Not yet implemented")
}

fun setupInput() {
    println("setupInput: Not yet implemented")
}
