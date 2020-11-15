import java.io.File
import java.lang.Integer.toHexString

@ExperimentalUnsignedTypes
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

    fun emulateCycle() {
        // fetch opcode
        opcode = (memory[pc] shl 8) or (memory[pc + 1])

        // process opcode
        when (opcode and 0xF000) {
            0x0000 -> {
                when (opcode and 0x000F) {
                    0x0000 -> dispClear() // 0x00E0
                    0x000E -> returnFromSubroutine() // 0x00EE
                    else -> unknownOpcode()
                }
            }
            0x1000 -> jumpToAddress(opcode and 0x0FFF) // 0x1NNN
            0x2000 -> callSubroutine(opcode and 0x0FFF) // 0x2NNN
            0x3000 -> ifEqual(opcode and 0x0F00 shr 8, opcode and 0x00FF) // 0x3XNN
            0x4000 -> ifNotEqual(opcode and 0x0F00 shr 8, opcode and 0x00FF) // 0x4XNN
            0x5000 -> ifEqual(opcode and 0x0F00 shr 8, opcode and 0x00F0 shr 4) // 0x5XY0
            0x6000 -> setRegister(opcode and 0x0F00 shr 8, opcode and 0x00FF) // 0x6XNN
            0x7000 -> addToRegister(opcode and 0x0F00 shr 8, opcode and 0x00FF) // 0x7XNN
            0xA000 -> {
                I = opcode and 0x0FFF
                pc += 2
            }
            0xD000 -> draw(opcode and 0x0F00, opcode and 0x00F0, opcode and 0x000F) // 0xDXYN
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

    private fun addToRegister(regIdx: Int, a: Int) {
        V[regIdx] += a
        pc += 2
    }

    private fun setRegister(regIdx: Int, a: Int) {
        V[regIdx] = a
        pc += 2
    }

    private fun ifNotEqual(a: Int, b: Int) {
        // skip next instruction if a == b
        pc += if (a != b) 4 else 2
    }

    private fun ifEqual(a: Int, b: Int) {
        // skip next instruction if a == b
        pc += if (a == b) 4 else 2
    }

    private fun draw(vx: Int, vy: Int, n: Int) {

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
        for (i in gfx.indices) gfx[i] = true
        drawFlag = true
        pc += 2
    }

    fun setKeys() {
        println("setKeys: Not yet implemented")
    }

    private fun unknownOpcode() {
        println("Unknown opcode: ${toHexString(opcode).toUpperCase()}")
    }
}
