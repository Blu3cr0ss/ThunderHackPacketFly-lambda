package idk.bluecross.tpf.util

class Timer {
    private var time = -1L

    fun passedS(s: Double): Boolean {
        return this.getMs(System.nanoTime() - this.time) >= (s * 1000.0).toLong()
    }

    fun passedM(m: Double): Boolean {
        return this.getMs(System.nanoTime() - this.time) >= (m * 1000.0 * 60.0).toLong()
    }

    fun passedDms(dms: Double): Boolean {
        return this.getMs(System.nanoTime() - this.time) >= (dms * 10.0).toLong()
    }

    fun passedDs(ds: Double): Boolean {
        return this.getMs(System.nanoTime() - this.time) >= (ds * 100.0).toLong()
    }

    fun passedMs(ms: Long): Boolean {
        return this.getMs(System.nanoTime() - this.time) >= ms
    }

    fun passedNS(ns: Long): Boolean {
        return System.nanoTime() - this.time >= ns
    }

    fun setMs(ms: Long) {
        this.time = System.nanoTime() - ms * 1000000L
    }

    val passedTimeMs: Long
        get() = this.getMs(System.nanoTime() - this.time)

    fun reset() {
        this.time = System.nanoTime()
    }

    fun getMs(time: Long): Long {
        return time / 1000000L
    }

    /*
    public final boolean passed( final long delay ) {
        return passed( delay, false );
    }

     */
    fun passed(delay: Long, reset: Boolean): Boolean {
        if (reset) this.reset()
        return System.currentTimeMillis() - this.time >= delay
    }

    val timeMs: Long
        get() = getMs(System.nanoTime() - this.time)

    fun getTime(): Long {
        return System.nanoTime() - this.time
    }

    fun adjust(by: Int) {
        time += by.toLong()
    }

    fun passed(delay: Long): Boolean {
        return this.getMs(System.nanoTime() - this.time) >= delay
    }
}