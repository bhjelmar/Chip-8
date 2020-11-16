import java.io.File
import java.lang.Integer.toHexString

@ExperimentalUnsignedTypes
@ExperimentalStdlibApi
class Chip8 {
    private val applicationOffset = 512

    var drawFlag: Boolean = false

    // current opcode
    // unsigned short opcode;
    private var opcode: Int = 0xFFFF

    // chip8 has 4k memory
    // unsigned char memory[4096];
    private var memory: IntArray = IntArray(4096)

    // 16 registers all 1 byte
    // unsigned char V[16];
    private var V: IntArray = IntArray(16)

    // unsigned short I;
    private var I: Int = 0

    // unsigned short pc;
    private var pc: Int = 0

    // systems memory map
    // 0x000-0x1FF - Chip 8 interpreter (contains font set in emu)
    // 0x050-0x0A0 - Used for the built in 4x5 pixel font set (0-F)
    // 0x200-0xFFF - Program ROM and work RAM

    // One instruction that draws sprite to the screen.
    // Drawing is done in XOR mode and if a pixel is turned off as a result of drawing, the VF register is set.
    // This is used for collision detection.

    // Graphics are black and white, 2048 pixels (64 x 32)
    private val screenWidth = 64
    private val screenHeight = 32
    private var gfx: BooleanArray = BooleanArray(screenWidth * screenHeight)

    // timer registers count down at 60 hz
    // system buzzer sounds when soundTimer reaches 0
    private var delayTimer: Int = Int.MAX_VALUE
    private var soundTimer: Int = Int.MAX_VALUE

    // Stack is used to remember the current location before we perform a jump.
    // So anytime we jump, store the program counter in the stack before proceeding.
    // Chip-8 has 16 levels of stack.
    private var stack: IntArray = IntArray(16)
    private var sp: Int = 0

    // Chip-8 keypad
    private var key: IntArray = IntArray(16)

    private val chip8Fontset: IntArray = intArrayOf(
            0xF0, 0x90, 0x90, 0x90, 0xF0, //0
            0x20, 0x60, 0x20, 0x20, 0x70, //1
            0xF0, 0x10, 0xF0, 0x80, 0xF0, //2
            0xF0, 0x10, 0xF0, 0x10, 0xF0, //3
            0x90, 0x90, 0xF0, 0x10, 0x10, //4
            0xF0, 0x80, 0xF0, 0x10, 0xF0, //5
            0xF0, 0x80, 0xF0, 0x90, 0xF0, //6
            0xF0, 0x10, 0x20, 0x40, 0x40, //7
            0xF0, 0x90, 0xF0, 0x90, 0xF0, //8
            0xF0, 0x90, 0xF0, 0x10, 0xF0, //9
            0xF0, 0x90, 0xF0, 0x90, 0x90, //A
            0xE0, 0x90, 0xE0, 0x90, 0xE0, //B
            0xF0, 0x80, 0x80, 0x80, 0xF0, //C
            0xE0, 0x90, 0x90, 0x90, 0xE0, //D
            0xF0, 0x80, 0xF0, 0x80, 0xF0, //E
            0xF0, 0x80, 0xF0, 0x80, 0x80  //F
    )

    init {
        pc = 0x200
        opcode = 0
        I = 0
        sp = 0

        // clear display
        for (i in 0 until 2048) gfx[i] = false
        // clear stack, registers, and key
        for (i in 0 until 16) stack[i] = 0
        for (i in 0 until 16) {
            V[i] = 0
            key[i] = 0
        }
        // clear memory
        for (i in 0 until 4096) memory[i] = 0
        // load font
        for (i in 0 until 80) memory[i] = chip8Fontset[i]

        // reset timers
        delayTimer = 0
        soundTimer = 0

        // clear screen once
        drawFlag = true
    }

    fun loadGame(fileName: String) {
        File(fileName).readBytes()
                .map { it.toUByte() }
                .forEachIndexed { i, byte -> memory[applicationOffset + i] = byte.toInt() }
    }

    fun debugRender() {
        print("--------------------------------------")
        for (x in 0 until screenWidth) {
            for (y in 0 until screenHeight) {
                if (gfx[(y * screenWidth) + x] == true) {
                    print("O")
                } else {
                    print(" ")
                }
            }
            println()
        }
        print("--------------------------------------")
    }

    fun emulateCycle() {
        // fetch opcode
        opcode = (memory[pc] shl 8) or (memory[pc + 1])

        // it's not 1978, let's make this a bit more readable at the cost of cpu cycles
        val nnn = opcode and 0x0FFF
        val nn = opcode and 0x00FF
        val n = opcode and 0x000F

        val x = opcode and 0x0F00 shr 8
        val vx = V[x]

        val y = opcode and 0x00F0 shr 4
        val vy = V[y]

        println(pc)

        // process opcode
        when (opcode and 0xF000) {
            0x0000 -> {
                when (opcode and 0x000F) {
                    0x0000 -> dispClear() // 0x00E0
                    0x000E -> returnFromSubroutine() // 0x00EE
                    else -> unknownOpcode()
                }
            }
            0x1000 -> jumpToAddress(nnn)   // 0x1NNN
            0x2000 -> callSubroutine(nnn)  // 0x2NNN
            0x3000 -> ifEqual(vx, nn)      // 0x3XNN
            0x4000 -> ifNotEqual(vx, nn)   // 0x4XNN
            0x5000 -> ifEqual(vx, vy)      // 0x5XY0
            0x6000 -> setRegister(x, nn)   // 0x6XNN
            0x7000 -> addToRegister(x, nn) // 0x7XNN
            0x8000 -> {
                when (opcode and 0x000F) {
                    0x0000 -> setRegister(x, vy)           // 0x8XY0
                    0x0001 -> setRegister(x, vx or vy)  // 0x8XY1
                    0x0002 -> setRegister(x, vx and vy) // 0x8XY2
                    0x0003 -> setRegister(x, vx xor vy) // 0x8XY3
                    0x0004 -> add(x, vx, vy)               // 0x8XY4
                    0x0005 -> subtract(x, vx, vy)          // 0x8XY5
                    0x0006 -> shiftRight(x, vx)            // 0x8XY6
                    0x0007 -> subtract(x, vy, vx)          // 0x8XY7 // TODO: revisit this, may be wrong...
                    0x000E -> shiftLeft(x, vx)             // 0x8XYE
                    else -> unknownOpcode()
                }
            }
            0x9000 -> ifNotEqual(vx, vy)               // 0x9XY0
            0xA000 -> setIndex(nnn)                    // 0xANNN
            0xB000 -> jumpToAddress(V[0] + nnn) // 0xANNN
            0xC000 -> rand(x, nn)                      // 0xANNN
            0xD000 -> draw(vx, vy, n)                  // 0xDXYN
            0xE000 -> {
                when (opcode and 0x00FF) {
                    0x009E -> unimplemented()
                    0x00A1 -> unimplemented()
                    else -> unknownOpcode()
                }
            }
            0xF00 -> {
                when (opcode and 0x00FF) {
                    0x0007 -> unimplemented()
                    0x000A -> unimplemented()
                    0x0015 -> unimplemented()
                    0x0018 -> unimplemented()
                    0x001E -> unimplemented()
                    0x0029 -> unimplemented()
                    0x0033 -> unimplemented()
                    0x0055 -> unimplemented()
                    0x0065 -> unimplemented()
                    else -> unknownOpcode()
                }
            }
            else -> unknownOpcode()
        }

        // Update timers
        if (delayTimer > 0) {
            delayTimer--
        }
        if (soundTimer > 0) {
            if (soundTimer == 1) {
                println("BEEP!")
            }
            soundTimer--
        }
    }

    // Sets VX to the result of a bitwise and operation on a random number (Typically: 0 to 255) and NN.
    private fun rand(regIdx: Int, x: Int) {
        val randNum = (0 until 255).random() and x
        setRegister(regIdx, randNum)
    }

    private fun setIndex(x: Int) {
        I = x
        pc += 2
    }

    private fun shiftLeft(regIdx: Int, x: Int) {
        V[0xF] = x.takeHighestOneBit()
        setRegister(regIdx, x shl 1)
    }

    private fun shiftRight(regIdx: Int, x: Int) {
        V[0xF] = x.takeLowestOneBit()
        setRegister(regIdx, x shr 1)
    }

    private fun subtract(regIdx: Int, x: Int, y: Int) {
        V[0xF] = if (y > x) 0 else 1
        setRegister(regIdx, x - y)

    }

    private fun add(regIdx: Int, x: Int, y: Int) {
        V[0xF] = if (y > x) 1 else 0
        setRegister(regIdx, x + y)
    }

    private fun addToRegister(regIdx: Int, x: Int) {
        V[regIdx] += x
        pc += 2
    }

    private fun setRegister(regIdx: Int, x: Int) {
        V[regIdx] = x
        pc += 2
    }

    private fun ifNotEqual(x: Int, y: Int) {
        // skip next instruction if x == y
        pc += if (x != y) 4 else 2
    }

    private fun ifEqual(x: Int, y: Int) {
        // skip next instruction if x == y
        pc += if (x == y) 4 else 2
    }

    private fun draw(vx: Int, vy: Int, height: Int) {
        V[0xF] = 0

        for (yline in 0..height) {
            var pixel = memory[I + yline]
            for (xline in 0..8) {
                if (pixel and (0x80 shr xline) != 0) {
                    if (gfx[(vx + xline + ((vy + yline) * 64))]) {
                        V[0xF] = 1
                    }
                    gfx[vx + xline + ((vy + yline) * 64)] = true
                }
            }
        }

        drawFlag = true
        pc += 2
    }

    private fun callSubroutine(address: Int) {
        stack[sp] = pc // place current pc on stack
        sp++ // increment stack pointer
        jumpToAddress(address) // move pc to NNN
        pc = address
    }

    private fun jumpToAddress(address: Int) {
        pc = address
    }

    private fun returnFromSubroutine() {
        sp-- // 16 levels of stack, decrease stack pointer to prevent overwrite
        pc = stack[sp] // put stored return address from the stack back onto the program counter
        pc += 2
    }

    private fun dispClear() {
        for (i in gfx.indices) gfx[i] = false
        drawFlag = true
        pc += 2
    }

    fun setKeys() {
        println("setKeys: Not yet implemented")
    }

    private fun unknownOpcode() {
        println("Unknown opcode: ${toHexString(opcode).toUpperCase()}")
    }

    private fun unimplemented() {
        println("Unimplemented opcode: ${toHexString(opcode).toUpperCase()}")
    }
}
