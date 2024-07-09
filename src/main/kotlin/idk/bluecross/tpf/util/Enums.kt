package idk.bluecross.tpf.util

enum class Limit {
    NONE, STRONG, STRICT
}

enum class Mode {
    UP, PRESERVE, DOWN, LIMITJITTER, BYPASS, OBSCURE
}

enum class Type {
    FACTOR, SETBACK, FAST, SLOW, DESYNC
}

enum class Phase {
    NONE, VANILLA, NCP
}

enum class AntiKick {
    NONE, NORMAL, LIMITED, STRICT
}